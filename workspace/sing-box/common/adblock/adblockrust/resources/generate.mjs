#!/usr/bin/env node

import { pathToFileURL } from "node:url";
import fs from "node:fs/promises";
import path from "node:path";

const [, , resourcesRootArg, outputRootArg] = process.argv;

if (!resourcesRootArg || !outputRootArg) {
  console.error("usage: generate.mjs <ublock-root-or-src> <output-root>");
  process.exit(2);
}

const resourcesRoot = path.resolve(resourcesRootArg);
const outputRoot = path.resolve(outputRootArg);
const outputDir = path.join(outputRoot, "resources");
const srcDir = path.basename(resourcesRoot) === "src" ? resourcesRoot : path.join(resourcesRoot, "src");
const scriptletsPath = path.join(srcDir, "js", "resources", "scriptlets.js");
const redirectResourcesPath = path.join(srcDir, "js", "redirect-resources.js");
const webAccessibleResourcesDir = path.join(srcDir, "web_accessible_resources");

const wrapScriptletArgFormat = (fnString, dependencyPrelude) => `{
const args = ["{{1}}", "{{2}}", "{{3}}", "{{4}}", "{{5}}", "{{6}}", "{{7}}", "{{8}}", "{{9}}"];
let last_arg_index = 0;
for (const arg_index in args) {
    if (args[arg_index] === '{{' + (Number(arg_index) + 1) + '}}') {
        break;
    }
    last_arg_index += 1;
}
${dependencyPrelude}
(${fnString})(...args.slice(0, last_arg_index))
}`;

const withJsExtension = name => name.endsWith(".js") || name.endsWith(".fn") ? name : `${name}.js`;

const dependencyClosure = (scriptlet, byName) => {
  const seen = new Set();
  const ordered = [];
  const visit = name => {
    const canonical = withJsExtension(name);
    if (seen.has(canonical)) {
      return;
    }
    seen.add(canonical);
    const dependency = byName.get(canonical);
    if (!dependency) {
      return;
    }
    for (const child of dependency.dependencies || []) {
      visit(child);
    }
    ordered.push(dependency);
  };
  for (const dependency of scriptlet.dependencies || []) {
    visit(dependency);
  }
  return ordered;
};

const buildScriptletResources = scriptlets => {
  const byName = new Map();
  for (const scriptlet of scriptlets) {
    byName.set(withJsExtension(scriptlet.name), scriptlet);
  }
  return scriptlets
    .filter(scriptlet => !scriptlet.name.endsWith(".fn"))
    .map(scriptlet => {
      if (typeof scriptlet.fn !== "function") {
        return null;
      }
      let dependencyPrelude = "";
      for (const dependency of dependencyClosure(scriptlet, byName)) {
        if (typeof dependency.fn === "function") {
          dependencyPrelude += `${dependency.fn.toString()}\n`;
        }
      }
      const wrapped = wrapScriptletArgFormat(scriptlet.fn.toString(), dependencyPrelude);
      return {
        name: withJsExtension(scriptlet.name),
        aliases: (scriptlet.aliases || []).map(withJsExtension),
        kind: { mime: "application/javascript" },
        content: Buffer.from(wrapped, "utf8").toString("base64"),
        dependencies: [],
        permission: scriptlet.requiresTrust ? 255 : 0,
      };
    })
    .filter(Boolean);
};

const main = async () => {
  await fs.access(scriptletsPath);
  const { builtinScriptlets } = await import(pathToFileURL(scriptletsPath).href);
  if (!Array.isArray(builtinScriptlets)) {
    throw new Error("scriptlets module did not export builtinScriptlets");
  }
  const resources = buildScriptletResources(builtinScriptlets);
  if (resources.length === 0) {
    throw new Error("generated zero scriptlet resources");
  }
  await fs.mkdir(outputDir, { recursive: true });
  await fs.writeFile(
    path.join(outputDir, "ubo-scriptlets.json"),
    `${JSON.stringify(resources, null, 2)}\n`,
  );

  const redirectResourcesModule = await import(pathToFileURL(redirectResourcesPath).href);
  const redirectResources = redirectResourcesModule.default;
  if (!(redirectResources instanceof Map) || redirectResources.size === 0) {
    throw new Error("redirect-resources module did not export a non-empty Map");
  }
  const outputSrcDir = path.join(outputRoot, "src");
  const outputRedirectResourcesPath = path.join(outputSrcDir, "js", "redirect-resources.js");
  const outputWebAccessibleResourcesDir = path.join(outputSrcDir, "web_accessible_resources");
  await fs.mkdir(path.dirname(outputRedirectResourcesPath), { recursive: true });
  await fs.mkdir(outputWebAccessibleResourcesDir, { recursive: true });
  await fs.copyFile(redirectResourcesPath, outputRedirectResourcesPath);
  for (const name of redirectResources.keys()) {
    if (typeof name !== "string" || name === "" || path.basename(name) !== name) {
      throw new Error(`invalid web-accessible resource name: ${name}`);
    }
    await fs.copyFile(
      path.join(webAccessibleResourcesDir, name),
      path.join(outputWebAccessibleResourcesDir, name),
    );
  }
  console.log(`Generated ${resources.length} scriptlets and ${redirectResources.size} web-accessible resources`);
};

main().catch(error => {
  console.error(error instanceof Error ? error.stack || error.message : String(error));
  process.exit(1);
});
