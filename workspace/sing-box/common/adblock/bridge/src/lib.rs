use adblock::lists::{FilterSet, ParseOptions};
use adblock::resources::resource_assembler::assemble_web_accessible_resources;
use adblock::resources::{MimeType, PermissionMask, Resource, ResourceType};
use adblock::{request::Request, Engine};
use base64::{engine::general_purpose::STANDARD as BASE64_STANDARD, Engine as _};
use std::collections::{HashMap, HashSet};
use std::ffi::{c_char, CStr, CString};
use std::fs;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::ptr;

#[repr(C)]
pub struct RuleSetInput {
    pub rules: *const *const c_char,
    pub rules_len: usize,
    pub format: *const c_char,
    pub permissions: u8,
}

#[repr(C)]
pub struct CheckResult {
    pub matched: u8,
    pub error: *mut c_char,
}

#[repr(C)]
pub struct DetailedCheckResult {
    pub matched: u8,
    pub important: u8,
    pub redirect: *mut c_char,
    pub rewritten_url: *mut c_char,
    pub exception: *mut c_char,
    pub filter: *mut c_char,
    pub error: *mut c_char,
}

#[repr(C)]
pub struct StringArray {
    pub values: *mut *mut c_char,
    pub len: usize,
}

#[repr(C)]
pub struct StringArrayResult {
    pub array: StringArray,
    pub error: *mut c_char,
}

#[repr(C)]
pub struct CosmeticResourcesResult {
    pub hide_selectors: StringArray,
    pub procedural_actions: StringArray,
    pub exceptions: StringArray,
    pub injected_script: *mut c_char,
    pub generichide: u8,
    pub error: *mut c_char,
}

#[repr(C)]
pub struct StringResult {
    pub value: *mut c_char,
    pub error: *mut c_char,
}

fn error_string(message: impl Into<String>) -> *mut c_char {
    CString::new(message.into())
        .unwrap_or_else(|_| CString::new("adblock bridge error").unwrap())
        .into_raw()
}

fn ok_string(value: impl Into<String>) -> StringResult {
    match CString::new(value.into()) {
        Ok(value) => StringResult {
            value: value.into_raw(),
            error: ptr::null_mut(),
        },
        Err(_) => StringResult {
            value: ptr::null_mut(),
            error: error_string("result contains null byte"),
        },
    }
}

fn optional_string(value: Option<String>) -> *mut c_char {
    let Some(value) = value else {
        return ptr::null_mut();
    };
    if value.is_empty() {
        return ptr::null_mut();
    }
    match CString::new(value) {
        Ok(value) => value.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

fn filter_debug_string(value: adblock::sourcemap::FilterRuleDebugInfo) -> String {
    value.raw_line.unwrap_or_else(|| "NetworkFilter".to_owned())
}

fn request_method_name(method: u8) -> &'static str {
    match method {
        0 => "",
        1 => "connect",
        2 => "delete",
        3 => "get",
        4 => "head",
        5 => "options",
        6 => "patch",
        7 => "post",
        8 => "put",
        _ => "other",
    }
}

fn string_value(value: impl Into<String>) -> *mut c_char {
    match CString::new(value.into()) {
        Ok(value) => value.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

fn string_array(values: impl IntoIterator<Item = String>) -> StringArray {
    let mut values = values.into_iter().map(string_value).collect::<Vec<_>>();
    values.shrink_to_fit();
    let result = StringArray {
        values: values.as_mut_ptr(),
        len: values.len(),
    };
    std::mem::forget(values);
    result
}

unsafe fn cstr_array_to_vec(
    values: *const *const c_char,
    len: usize,
    name: &str,
) -> Result<Vec<String>, String> {
    if len == 0 {
        return Ok(Vec::new());
    }
    if values.is_null() {
        return Err(format!("{name} is null"));
    }
    std::slice::from_raw_parts(values, len)
        .iter()
        .map(|value| cstr_to_str(*value, name).map(ToOwned::to_owned))
        .collect()
}

unsafe fn cstr_to_str<'a>(value: *const c_char, name: &str) -> Result<&'a str, String> {
    if value.is_null() {
        return Err(format!("{name} is null"));
    }
    CStr::from_ptr(value)
        .to_str()
        .map_err(|error| format!("{name}: {error}"))
}

unsafe fn nullable_cstr_to_str<'a>(value: *const c_char, name: &str) -> Result<&'a str, String> {
    if value.is_null() {
        return Ok("");
    }
    CStr::from_ptr(value)
        .to_str()
        .map_err(|error| format!("{name}: {error}"))
}

fn resolve_ublock_src(path: &str) -> PathBuf {
    let path = Path::new(path);
    if path.join("js").join("redirect-resources.js").is_file() {
        return path.to_path_buf();
    }
    path.join("src")
}

fn assemble_adblock_resources(path: &str) -> Result<Vec<Resource>, String> {
    if path.is_empty() {
        return Ok(Vec::new());
    }
    let mut resources = read_adblock_resources_json(path)?.unwrap_or_default();
    let src_dir = resolve_ublock_src(path);
    let root_dir = if src_dir.file_name().and_then(|name| name.to_str()) == Some("src") {
        src_dir.parent().unwrap_or(&src_dir).to_path_buf()
    } else {
        src_dir.clone()
    };
    let web_accessible_resource_dir = src_dir.join("web_accessible_resources");
    let redirect_resources_path = src_dir.join("js").join("redirect-resources.js");
    if !web_accessible_resource_dir.is_dir() || !redirect_resources_path.is_file() {
        for candidate in generated_resource_candidates(&root_dir, &src_dir) {
            resources.extend(read_resource_json(&candidate)?);
        }
        if !resources.is_empty() {
            return Ok(resources);
        }
        if !web_accessible_resource_dir.is_dir() {
            return Err(format!(
                "uBlock web_accessible_resources not found: {}",
                web_accessible_resource_dir.display()
            ));
        }
        return Err(format!(
            "uBlock redirect-resources.js not found: {}",
            redirect_resources_path.display()
        ));
    }
    resources.extend(assemble_web_accessible_resources(
        &web_accessible_resource_dir,
        &redirect_resources_path,
    ));
    for candidate in generated_resource_candidates(&root_dir, &src_dir) {
        resources.extend(read_resource_json(&candidate)?);
    }
    if !resources
        .iter()
        .any(|resource| resource.name == "json-prune.js")
    {
        resources.extend(assemble_current_ublock_scriptlets(
            &src_dir.join("js").join("resources"),
        )?);
    }
    Ok(resources)
}

fn read_adblock_resources_json(path: &str) -> Result<Option<Vec<Resource>>, String> {
    let root = Path::new(path);
    let candidates = [
        root.join("dist").join("resources.json"),
        root.join("resources.json"),
    ];
    for candidate in candidates {
        if candidate.is_file() {
            return read_resource_json(&candidate).map(Some);
        }
    }
    Ok(None)
}

fn generated_resource_candidates(root_dir: &Path, src_dir: &Path) -> Vec<PathBuf> {
    [
        root_dir.join("resources").join("resources.json"),
        root_dir.join("resources").join("ubo-scriptlets.json"),
        root_dir.join("generated").join("resources.json"),
        root_dir.join("generated").join("ubo-scriptlets.json"),
        src_dir.join("resources").join("resources.json"),
        src_dir.join("resources").join("ubo-scriptlets.json"),
    ]
    .into_iter()
    .collect()
}

fn read_resource_json(path: &Path) -> Result<Vec<Resource>, String> {
    if !path.is_file() {
        return Ok(Vec::new());
    }
    let content =
        fs::read_to_string(path).map_err(|error| format!("read {}: {error}", path.display()))?;
    serde_json::from_str(&content).map_err(|error| format!("parse {}: {error}", path.display()))
}

#[derive(Default)]
struct ScriptletDetails {
    name: String,
    aliases: Vec<String>,
    dependencies: Vec<String>,
    requires_trust: bool,
}

fn assemble_current_ublock_scriptlets(resources_dir: &Path) -> Result<Vec<Resource>, String> {
    if !resources_dir.is_dir() {
        return Ok(Vec::new());
    }
    let mut function_sources = HashMap::<String, String>::new();
    let mut scriptlets = Vec::<(String, ScriptletDetails)>::new();
    for entry in fs::read_dir(resources_dir).map_err(|error| error.to_string())? {
        let entry = entry.map_err(|error| error.to_string())?;
        let path = entry.path();
        if path.extension().and_then(|extension| extension.to_str()) != Some("js") {
            continue;
        }
        let content = fs::read_to_string(&path)
            .map_err(|error| format!("read {}: {error}", path.display()))?;
        collect_function_sources(&content, &mut function_sources);
        collect_registered_scriptlets(&content, &mut scriptlets);
    }
    let mut resources = Vec::with_capacity(scriptlets.len());
    let mut added = HashSet::new();
    let function_resource_names = scriptlets
        .iter()
        .map(|(function_name, details)| (function_name.clone(), details.name.clone()))
        .collect::<HashMap<_, _>>();
    for (function_name, details) in scriptlets {
        if details.name.is_empty() || !added.insert(details.name.clone()) {
            continue;
        }
        let Some(function_source) = function_sources.get(&function_name) else {
            continue;
        };
        resources.push(Resource {
            name: with_js_extension(&details.name),
            aliases: details
                .aliases
                .into_iter()
                .map(|alias| with_js_extension(&alias))
                .collect(),
            kind: ResourceType::Mime(MimeType::ApplicationJavascript),
            content: BASE64_STANDARD.encode(function_source),
            dependencies: details
                .dependencies
                .into_iter()
                .map(|dependency| {
                    function_resource_names
                        .get(&dependency)
                        .cloned()
                        .unwrap_or(dependency)
                })
                .map(|dependency| with_js_extension(&dependency))
                .collect(),
            permission: if details.requires_trust {
                PermissionMask::from_bits(u8::MAX)
            } else {
                PermissionMask::default()
            },
        });
    }
    Ok(resources)
}

fn with_js_extension(name: &str) -> String {
    if name.ends_with(".js") || name.ends_with(".fn") {
        name.to_owned()
    } else {
        format!("{name}.js")
    }
}

fn collect_function_sources(content: &str, output: &mut HashMap<String, String>) {
    let mut offset = 0;
    while let Some(index) = content[offset..].find("function ") {
        let start = offset + index;
        let name_start = start + "function ".len();
        let Some(paren_offset) = content[name_start..].find('(') else {
            break;
        };
        let name_end = name_start + paren_offset;
        let name = content[name_start..name_end].trim();
        if name.is_empty()
            || !name
                .chars()
                .all(|ch| ch == '_' || ch == '$' || ch.is_ascii_alphanumeric())
        {
            offset = name_end;
            continue;
        }
        let paren_start = name_start + paren_offset;
        let Some(paren_end) = find_balanced_end(content, paren_start, '(', ')') else {
            break;
        };
        let Some(body_start_offset) = content[paren_end..].find('{') else {
            break;
        };
        let body_start = paren_end + body_start_offset;
        let Some(end) = find_balanced_end(content, body_start, '{', '}') else {
            break;
        };
        output
            .entry(name.to_owned())
            .or_insert_with(|| content[start..end].to_owned());
        offset = end;
    }
}

fn collect_registered_scriptlets(content: &str, output: &mut Vec<(String, ScriptletDetails)>) {
    collect_push_scriptlets(content, "builtinScriptlets.push", output);
    collect_register_scriptlets(content, output);
}

fn collect_push_scriptlets(
    content: &str,
    marker: &str,
    output: &mut Vec<(String, ScriptletDetails)>,
) {
    let mut offset = 0;
    while let Some(index) = content[offset..].find(marker) {
        let marker_start = offset + index;
        let Some(paren_index) = content[marker_start..].find('(') else {
            break;
        };
        let paren_start = marker_start + paren_index;
        let Some(paren_end) = find_balanced_end(content, paren_start, '(', ')') else {
            break;
        };
        let args = &content[paren_start + 1..paren_end - 1];
        if let Some(details) = parse_scriptlet_details(args) {
            if let Some(function_name) = details_function_name(args) {
                output.push((function_name, details));
            }
        }
        offset = paren_end;
    }
}

fn collect_register_scriptlets(content: &str, output: &mut Vec<(String, ScriptletDetails)>) {
    let mut offset = 0;
    while let Some(index) = content[offset..].find("registerScriptlet(") {
        let marker_start = offset + index;
        let paren_start = marker_start + "registerScriptlet".len();
        let Some(paren_end) = find_balanced_end(content, paren_start, '(', ')') else {
            break;
        };
        let args = &content[paren_start + 1..paren_end - 1];
        let mut parts = args.splitn(2, ',');
        let function_name = parts.next().unwrap_or_default().trim();
        let details_text = parts.next().unwrap_or_default();
        if let Some(details) = parse_scriptlet_details(details_text) {
            output.push((function_name.to_owned(), details));
        }
        offset = paren_end;
    }
}

fn details_function_name(text: &str) -> Option<String> {
    let value = field_value(text, "fn")?.trim_start();
    let end = value
        .find(|ch: char| !(ch == '_' || ch == '$' || ch.is_ascii_alphanumeric()))
        .unwrap_or(value.len());
    if end == 0 {
        return None;
    }
    Some(value[..end].to_owned())
}

fn parse_scriptlet_details(text: &str) -> Option<ScriptletDetails> {
    Some(ScriptletDetails {
        name: string_field(text, "name")?,
        aliases: string_array_field(text, "aliases"),
        dependencies: string_array_field(text, "dependencies"),
        requires_trust: bool_field(text, "requiresTrust"),
    })
}

fn string_field(text: &str, field: &str) -> Option<String> {
    let value = field_value(text, field)?;
    parse_quoted_string(value.trim()).map(|(value, _)| value)
}

fn bool_field(text: &str, field: &str) -> bool {
    field_value(text, field)
        .map(|value| value.trim().starts_with("true"))
        .unwrap_or(false)
}

fn string_array_field(text: &str, field: &str) -> Vec<String> {
    let Some(value) = field_value(text, field) else {
        return Vec::new();
    };
    let value = value.trim();
    if !value.starts_with('[') {
        return Vec::new();
    }
    let Some(end) = find_balanced_end(value, 0, '[', ']') else {
        return Vec::new();
    };
    let mut result = Vec::new();
    let mut offset = 1;
    while offset + 1 < end {
        let rest = value[offset..end - 1].trim_start();
        let skipped = value[offset..end - 1].len() - rest.len();
        offset += skipped;
        if rest.starts_with('"') || rest.starts_with('\'') || rest.starts_with('`') {
            if let Some((item, consumed)) = parse_quoted_string(rest) {
                result.push(item);
                offset += consumed;
                continue;
            }
        }
        let consumed = rest
            .find(',')
            .unwrap_or_else(|| rest.find(']').unwrap_or(rest.len()));
        let item = rest[..consumed].trim();
        if !item.is_empty() {
            result.push(item.to_owned());
        }
        offset += consumed;
        if value[offset..].starts_with(',') {
            offset += 1;
        }
    }
    result
}

fn field_value<'a>(text: &'a str, field: &str) -> Option<&'a str> {
    let mut offset = 0;
    let pattern = format!("{field}:");
    while let Some(index) = text[offset..].find(&pattern) {
        let start = offset + index;
        if start > 0 {
            let previous = text.as_bytes()[start - 1];
            if previous == b'_' || previous.is_ascii_alphanumeric() {
                offset = start + pattern.len();
                continue;
            }
        }
        return Some(&text[start + pattern.len()..]);
    }
    None
}

fn parse_quoted_string(text: &str) -> Option<(String, usize)> {
    let quote = text.as_bytes().first().copied()?;
    if quote != b'\'' && quote != b'"' && quote != b'`' {
        return None;
    }
    let mut escaped = false;
    let mut result = String::new();
    for (index, ch) in text.char_indices().skip(1) {
        if escaped {
            result.push(ch);
            escaped = false;
            continue;
        }
        if ch == '\\' {
            escaped = true;
            continue;
        }
        if ch as u8 == quote {
            return Some((result, index + ch.len_utf8()));
        }
        result.push(ch);
    }
    None
}

fn find_balanced_end(text: &str, start: usize, open: char, close: char) -> Option<usize> {
    let mut depth = 0usize;
    let mut string_quote = None::<char>;
    let mut escaped = false;
    let mut in_line_comment = false;
    let mut in_block_comment = false;
    for (index, ch) in text.char_indices().skip_while(|(index, _)| *index < start) {
        if in_line_comment {
            if ch == '\n' {
                in_line_comment = false;
            }
            continue;
        }
        if in_block_comment {
            if ch == '/' && text[..index].ends_with('*') {
                in_block_comment = false;
            }
            continue;
        }
        if let Some(quote) = string_quote {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == quote {
                string_quote = None;
            }
            continue;
        }
        if ch == '/' && text[index..].starts_with("//") {
            in_line_comment = true;
            continue;
        }
        if ch == '/' && text[index..].starts_with("/*") {
            in_block_comment = true;
            continue;
        }
        if ch == '\'' || ch == '"' || ch == '`' {
            string_quote = Some(ch);
            continue;
        }
        if ch == open {
            depth += 1;
        } else if ch == close {
            depth = depth.checked_sub(1)?;
            if depth == 0 {
                return Some(index + ch.len_utf8());
            }
        }
    }
    None
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_new(
    rule_sets: *const RuleSetInput,
    rule_sets_len: usize,
    adblock_resources: *const c_char,
    error: *mut *mut c_char,
) -> usize {
    if !error.is_null() {
        *error = ptr::null_mut();
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut filter_set = FilterSet::new(false);
        if rule_sets_len > 0 {
            if rule_sets.is_null() {
                return Err("rule_sets is null".to_owned());
            }
            let rule_sets_slice = std::slice::from_raw_parts(rule_sets, rule_sets_len);
            for rule_set in rule_sets_slice {
                if rule_set.rules_len == 0 {
                    continue;
                }
                if rule_set.rules.is_null() {
                    return Err("rule_set.rules is null".to_owned());
                }
                let format = nullable_cstr_to_str(rule_set.format, "rule_set.format")?;
                let opts = ParseOptions {
                    format: match format {
                        "" | "standard" => adblock::lists::FilterFormat::Standard,
                        "hosts" => adblock::lists::FilterFormat::Hosts,
                        other => return Err(format!("unsupported rule_set.format: {other}")),
                    },
                    permissions: PermissionMask::from_bits(rule_set.permissions),
                    ..ParseOptions::default()
                };
                let rules_slice = std::slice::from_raw_parts(rule_set.rules, rule_set.rules_len);
                let mut parsed_rules = Vec::with_capacity(rule_set.rules_len);
                for rule in rules_slice {
                    parsed_rules.push(cstr_to_str(*rule, "rule")?.to_owned());
                }
                filter_set.add_filter_list(parsed_rules.join("\n"), opts);
            }
        }
        let mut engine = Engine::new_with_filter_set(filter_set);
        let adblock_resources = nullable_cstr_to_str(adblock_resources, "adblock_resources")?;
        let resources = assemble_adblock_resources(adblock_resources)?;
        if !resources.is_empty() {
            engine.use_resources(resources);
        }
        Ok(Box::into_raw(Box::new(engine)) as usize)
    }));
    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => {
            if !error.is_null() {
                *error = error_string(message);
            }
            0
        }
        Err(_) => {
            if !error.is_null() {
                *error = error_string("adblock engine creation panicked");
            }
            0
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_url_cosmetic_resources(
    engine: usize,
    url: *const c_char,
) -> CosmeticResourcesResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let url = cstr_to_str(url, "url")?;
        let engine = &*(engine as *mut Engine);
        Ok(engine.url_cosmetic_resources(url))
    }));
    match result {
        Ok(Ok(value)) => CosmeticResourcesResult {
            hide_selectors: string_array(value.hide_selectors),
            procedural_actions: string_array(value.procedural_actions),
            exceptions: string_array(value.exceptions),
            injected_script: string_value(value.injected_script),
            generichide: u8::from(value.generichide),
            error: ptr::null_mut(),
        },
        Ok(Err(message)) => CosmeticResourcesResult {
            hide_selectors: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            procedural_actions: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            exceptions: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            injected_script: ptr::null_mut(),
            generichide: 0,
            error: error_string(message),
        },
        Err(_) => CosmeticResourcesResult {
            hide_selectors: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            procedural_actions: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            exceptions: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            injected_script: ptr::null_mut(),
            generichide: 0,
            error: error_string("adblock cosmetic resource query panicked"),
        },
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_check_detailed(
    engine: usize,
    url: *const c_char,
    source_url: *const c_char,
    request_type: *const c_char,
    method: u8,
) -> DetailedCheckResult {
    check_detailed(engine, url, source_url, request_type, method, true)
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_check_detailed_no_filter(
    engine: usize,
    url: *const c_char,
    source_url: *const c_char,
    request_type: *const c_char,
    method: u8,
) -> DetailedCheckResult {
    check_detailed(engine, url, source_url, request_type, method, false)
}

unsafe fn check_detailed(
    engine: usize,
    url: *const c_char,
    source_url: *const c_char,
    request_type: *const c_char,
    method: u8,
    include_filter: bool,
) -> DetailedCheckResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let url = cstr_to_str(url, "url")?;
        let source_url = cstr_to_str(source_url, "source_url")?;
        let request_type = cstr_to_str(request_type, "request_type")?;
        let request = Request::new(url, source_url, request_type, request_method_name(method))
            .map_err(|error| error.to_string())?;
        let engine = &*(engine as *mut Engine);
        Ok(engine.check_network_request(&request))
    }));
    match result {
        Ok(Ok(value)) => DetailedCheckResult {
            matched: u8::from(value.should_block()),
            important: u8::from(value.important),
            redirect: optional_string(value.redirect),
            rewritten_url: optional_string(value.rewritten_url),
            exception: optional_string(value.exception.map(filter_debug_string)),
            filter: if include_filter {
                optional_string(value.filter.map(filter_debug_string))
            } else {
                ptr::null_mut()
            },
            error: ptr::null_mut(),
        },
        Ok(Err(message)) => DetailedCheckResult {
            matched: 0,
            important: 0,
            redirect: ptr::null_mut(),
            rewritten_url: ptr::null_mut(),
            exception: ptr::null_mut(),
            filter: ptr::null_mut(),
            error: error_string(message),
        },
        Err(_) => DetailedCheckResult {
            matched: 0,
            important: 0,
            redirect: ptr::null_mut(),
            rewritten_url: ptr::null_mut(),
            exception: ptr::null_mut(),
            filter: ptr::null_mut(),
            error: error_string("adblock detailed check panicked"),
        },
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_check_exception(
    engine: usize,
    url: *const c_char,
    source_url: *const c_char,
    request_type: *const c_char,
    method: u8,
) -> CheckResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let url = cstr_to_str(url, "url")?;
        let source_url = cstr_to_str(source_url, "source_url")?;
        let request_type = cstr_to_str(request_type, "request_type")?;
        let request = Request::new(url, source_url, request_type, request_method_name(method))
            .map_err(|error| error.to_string())?;
        let engine = &*(engine as *mut Engine);
        Ok(engine
            .check_network_request_subset(&request, false, true)
            .exception
            .is_some())
    }));
    match result {
        Ok(Ok(matched)) => CheckResult {
            matched: u8::from(matched),
            error: ptr::null_mut(),
        },
        Ok(Err(message)) => CheckResult {
            matched: 0,
            error: error_string(message),
        },
        Err(_) => CheckResult {
            matched: 0,
            error: error_string("adblock exception check panicked"),
        },
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_csp_directives(
    engine: usize,
    url: *const c_char,
    source_url: *const c_char,
    request_type: *const c_char,
    method: u8,
) -> StringResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let url = cstr_to_str(url, "url")?;
        let source_url = cstr_to_str(source_url, "source_url")?;
        let request_type = cstr_to_str(request_type, "request_type")?;
        let request = Request::new(url, source_url, request_type, request_method_name(method))
            .map_err(|error| error.to_string())?;
        let engine = &*(engine as *mut Engine);
        Ok(engine.get_csp_directives(&request).unwrap_or_default())
    }));
    match result {
        Ok(Ok(value)) => ok_string(value),
        Ok(Err(message)) => StringResult {
            value: ptr::null_mut(),
            error: error_string(message),
        },
        Err(_) => StringResult {
            value: ptr::null_mut(),
            error: error_string("adblock CSP query panicked"),
        },
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_hidden_class_id_selectors(
    engine: usize,
    classes: *const *const c_char,
    classes_len: usize,
    ids: *const *const c_char,
    ids_len: usize,
    exceptions: *const *const c_char,
    exceptions_len: usize,
) -> StringArrayResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let classes = cstr_array_to_vec(classes, classes_len, "classes")?;
        let ids = cstr_array_to_vec(ids, ids_len, "ids")?;
        let exceptions: HashSet<String> =
            cstr_array_to_vec(exceptions, exceptions_len, "exceptions")?
                .into_iter()
                .collect();
        let engine = &*(engine as *mut Engine);
        Ok(engine.hidden_class_id_selectors(&classes, &ids, &exceptions))
    }));
    match result {
        Ok(Ok(value)) => StringArrayResult {
            array: string_array(value),
            error: ptr::null_mut(),
        },
        Ok(Err(message)) => StringArrayResult {
            array: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            error: error_string(message),
        },
        Err(_) => StringArrayResult {
            array: StringArray {
                values: ptr::null_mut(),
                len: 0,
            },
            error: error_string("adblock hidden selector query panicked"),
        },
    }
}

unsafe fn tags_from_c(tags: *const *const c_char, tags_len: usize) -> Result<Vec<String>, String> {
    cstr_array_to_vec(tags, tags_len, "tags")
}

fn tag_result(result: Result<bool, String>) -> CheckResult {
    match result {
        Ok(matched) => CheckResult {
            matched: u8::from(matched),
            error: ptr::null_mut(),
        },
        Err(message) => CheckResult {
            matched: 0,
            error: error_string(message),
        },
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_use_tags(
    engine: usize,
    tags: *const *const c_char,
    tags_len: usize,
) -> CheckResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let tags = tags_from_c(tags, tags_len)?;
        let tag_refs = tags.iter().map(|tag| tag.as_str()).collect::<Vec<_>>();
        let engine = &mut *(engine as *mut Engine);
        engine.use_tags(&tag_refs);
        Ok(false)
    }));
    match result {
        Ok(value) => tag_result(value),
        Err(_) => tag_result(Err("adblock use_tags panicked".to_owned())),
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_enable_tags(
    engine: usize,
    tags: *const *const c_char,
    tags_len: usize,
) -> CheckResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let tags = tags_from_c(tags, tags_len)?;
        let tag_refs = tags.iter().map(|tag| tag.as_str()).collect::<Vec<_>>();
        let engine = &mut *(engine as *mut Engine);
        engine.enable_tags(&tag_refs);
        Ok(false)
    }));
    match result {
        Ok(value) => tag_result(value),
        Err(_) => tag_result(Err("adblock enable_tags panicked".to_owned())),
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_disable_tags(
    engine: usize,
    tags: *const *const c_char,
    tags_len: usize,
) -> CheckResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let tags = tags_from_c(tags, tags_len)?;
        let tag_refs = tags.iter().map(|tag| tag.as_str()).collect::<Vec<_>>();
        let engine = &mut *(engine as *mut Engine);
        engine.disable_tags(&tag_refs);
        Ok(false)
    }));
    match result {
        Ok(value) => tag_result(value),
        Err(_) => tag_result(Err("adblock disable_tags panicked".to_owned())),
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_tag_exists(
    engine: usize,
    tag: *const c_char,
) -> CheckResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let tag = cstr_to_str(tag, "tag")?;
        let engine = &*(engine as *mut Engine);
        Ok(engine.tag_exists(tag))
    }));
    match result {
        Ok(value) => tag_result(value),
        Err(_) => tag_result(Err("adblock tag_exists panicked".to_owned())),
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_check(
    engine: usize,
    url: *const c_char,
    source_url: *const c_char,
    request_type: *const c_char,
    method: u8,
) -> CheckResult {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if engine == 0 {
            return Err("engine is null".to_owned());
        }
        let url = cstr_to_str(url, "url")?;
        let source_url = cstr_to_str(source_url, "source_url")?;
        let request_type = cstr_to_str(request_type, "request_type")?;
        let request = Request::new(url, source_url, request_type, request_method_name(method))
            .map_err(|error| error.to_string())?;
        let engine = &*(engine as *mut Engine);
        Ok(engine.check_network_request(&request).should_block())
    }));
    match result {
        Ok(Ok(matched)) => CheckResult {
            matched: u8::from(matched),
            error: ptr::null_mut(),
        },
        Ok(Err(message)) => CheckResult {
            matched: 0,
            error: error_string(message),
        },
        Err(_) => CheckResult {
            matched: 0,
            error: error_string("adblock check panicked"),
        },
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_string_array_free(array: StringArray) {
    if array.values.is_null() {
        return;
    }
    let values = Vec::from_raw_parts(array.values, array.len, array.len);
    for value in values {
        if !value.is_null() {
            let _ = CString::from_raw(value);
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_cosmetic_resources_free(result: CosmeticResourcesResult) {
    sing_box_adblock_string_array_free(result.hide_selectors);
    sing_box_adblock_string_array_free(result.procedural_actions);
    sing_box_adblock_string_array_free(result.exceptions);
    if !result.injected_script.is_null() {
        let _ = CString::from_raw(result.injected_script);
    }
    if !result.error.is_null() {
        let _ = CString::from_raw(result.error);
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_engine_free(engine: usize) {
    if engine != 0 {
        drop(Box::from_raw(engine as *mut Engine));
    }
}

#[no_mangle]
pub unsafe extern "C" fn sing_box_adblock_string_free(value: *mut c_char) {
    if !value.is_null() {
        drop(CString::from_raw(value));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collect_function_sources_handles_default_object_parameter() {
        let mut sources = HashMap::new();
        collect_function_sources(
            r#"
export function validateConstantFn(trusted, raw, extraArgs = {}) {
    return { trusted, raw, extraArgs };
}

function setConstant(...args) {
    setConstantFn(false, ...args);
}
"#,
            &mut sources,
        );

        let validate_constant = sources
            .get("validateConstantFn")
            .expect("validateConstantFn source");
        assert!(validate_constant.contains("return { trusted, raw, extraArgs };"));
        assert!(validate_constant.ends_with("\n}"));
        assert!(!validate_constant.ends_with("extraArgs = {}"));
        assert_eq!(
            sources.get("setConstant").map(String::as_str),
            Some("function setConstant(...args) {\n    setConstantFn(false, ...args);\n}")
        );
    }
}
