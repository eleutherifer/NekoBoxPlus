//go:build with_adblock

package adblock

var singBoxAdblockRunner = `
(function(){
if (self.__` + runBlockHash() + `Run) { return; }
const applied = new WeakMap();
const styledNodes = new Set();
let scheduled = false;
const query = (selector, root = document) => {
  try {
    if (root !== document && /^[+~]/.test(selector)) {
      const parent = root.parentElement;
      if (!parent) { return []; }
      let pos = 1, node = root;
      while ((node = node.previousElementSibling)) { pos++; }
      return Array.from(parent.querySelectorAll(":scope > :nth-child(" + pos + ")" + selector));
    }
    if (root !== document && /^\s*>/.test(selector)) {
      return Array.from(root.querySelectorAll(":scope " + selector));
    }
    return Array.from(root.querySelectorAll(selector));
  } catch { return []; }
};
const escapeRegex = text => String(text).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const regexFromString = (raw, exact) => {
  raw = String(raw == null ? "" : raw);
  if (raw.charCodeAt(0) === 0x2F) {
    const end = raw.lastIndexOf("/");
    if (end > 0) {
      try { return new RegExp(raw.slice(1, end), raw.slice(end + 1)); } catch {}
    }
  }
  return new RegExp(exact ? "^" + escapeRegex(raw) + "$" : escapeRegex(raw));
};
const textMatches = (text, pattern) => {
  try { return regexFromString(pattern, false).test(text); } catch { return String(text).includes(String(pattern)); }
};
const splitNameValue = arg => {
  const match = /^([\w-]+)\s*:\s*(.+)$/.exec(arg);
  return match ? [match[1], match[2]] : ["", ""];
};
const cssMatches = (element, arg, pseudo) => {
  const parts = splitNameValue(arg);
  if (!parts[0]) { return false; }
  const style = getComputedStyle(element, pseudo || null);
  const value = (style.getPropertyValue(parts[0]) || style[parts[0]] || "").trim();
  return regexFromString(parts[1], true).test(value);
};
const attrMatches = (element, arg) => {
  const index = String(arg || "").indexOf("=");
  const attrPattern = index >= 0 ? arg.slice(0, index) : arg;
  const valuePattern = index >= 0 ? arg.slice(index + 1) : "";
  const attrRe = regexFromString(attrPattern, true);
  const valueRe = valuePattern === "" ? null : regexFromString(valuePattern, true);
  for (const name of element.getAttributeNames ? element.getAttributeNames() : []) {
    if (!attrRe.test(name)) { continue; }
    if (valueRe === null || valueRe.test(element.getAttribute(name) || "")) { return true; }
  }
  return false;
};
const propMatches = (element, arg) => {
  const index = String(arg || "").indexOf("=");
  const path = index >= 0 ? arg.slice(0, index) : arg;
  const valuePattern = index >= 0 ? arg.slice(index + 1) : "";
  let value = element;
  for (const prop of String(path).split(".")) {
    if (value == null) { return false; }
    value = value[prop];
  }
  if (valuePattern === "") { return value !== undefined; }
  return regexFromString(valuePattern, true).test(value);
};
const nestedMatches = (node, arg, expected) => {
  let matched = false;
  if (typeof arg === "string") {
    matched = query(arg, node).length !== 0;
  } else if (arg && typeof arg === "object") {
    matched = evalFilter(arg, node).length !== 0;
  }
  return matched === expected;
};
const spath = (selector, node) => query(selector, node);
const shadow = (selector, node) => {
  const root = node.openOrClosedShadowRoot || node.shadowRoot || null;
  return root ? query(selector, root) : [];
};
const others = nodes => {
  const keep = new Set(nodes);
  const discard = new Set();
  const nonVisual = { br: true, head: true, link: true, meta: true, script: true, style: true, title: true };
  for (let node of nodes) {
    while (node && node !== document.body && node !== document.head) {
      keep.add(node);
      discard.delete(node);
      for (let sibling = node.previousElementSibling; sibling; sibling = sibling.previousElementSibling) {
        if (!nonVisual[sibling.localName] && !keep.has(sibling)) { discard.add(sibling); }
      }
      for (let sibling = node.nextElementSibling; sibling; sibling = sibling.nextElementSibling) {
        if (!nonVisual[sibling.localName] && !keep.has(sibling)) { discard.add(sibling); }
      }
      node = node.parentElement;
    }
  }
  return Array.from(discard);
};
const evalFilter = (filter, root) => {
  let nodes = [root || document];
  for (const op of filter.selector || []) {
    switch (op.type) {
    case "css-selector":
      nodes = nodes.flatMap(node => query(op.arg, node === document ? document : node));
      break;
    case "has":
    case "if":
      nodes = nodes.filter(node => nestedMatches(node, op.arg, true));
      break;
    case "not":
    case "if-not":
      nodes = nodes.filter(node => nestedMatches(node, op.arg, false));
      break;
    case "has-text":
      nodes = nodes.filter(node => textMatches(node.textContent || "", op.arg));
      break;
    case "matches-attr":
      nodes = nodes.filter(node => attrMatches(node, op.arg));
      break;
    case "matches-css":
      nodes = nodes.filter(node => cssMatches(node, op.arg));
      break;
    case "matches-css-before":
      nodes = nodes.filter(node => cssMatches(node, op.arg, "::before"));
      break;
    case "matches-css-after":
      nodes = nodes.filter(node => cssMatches(node, op.arg, "::after"));
      break;
    case "matches-media":
      try {
        nodes = self.matchMedia && self.matchMedia(op.arg).matches ? nodes : [];
      } catch {
        nodes = [];
      }
      break;
    case "matches-path":
      if (!textMatches(location.pathname + location.search, op.arg)) { nodes = []; }
      break;
    case "matches-prop":
      nodes = nodes.filter(node => propMatches(node, op.arg));
      break;
    case "min-text-length":
      nodes = nodes.filter(node => (node.textContent || "").length >= Number(op.arg || 0));
      break;
    case "others":
      nodes = others(nodes);
      break;
    case "shadow":
      nodes = nodes.flatMap(node => shadow(op.arg, node));
      break;
    case "spath":
      nodes = nodes.flatMap(node => spath(op.arg, node));
      break;
    case "upward":
      nodes = nodes.map(node => /^\d+$/.test(String(op.arg)) ? ascend(node, Number(op.arg)) : (node.parentElement && node.parentElement.closest(op.arg))).filter(Boolean);
      break;
    case "watch-attr":
      break;
    case "xpath":
      nodes = nodes.flatMap(node => xpath(op.arg, node));
      break;
    }
  }
  return nodes;
};
const ascend = (node, count) => {
  while (node && count-- > 0) { node = node.parentElement; }
  return node;
};
const xpath = (expr, node) => {
  try {
    const result = document.evaluate(expr, node, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
    return Array.from({ length: result.snapshotLength }, (_, i) => result.snapshotItem(i)).filter(Boolean);
  } catch { return []; }
};
const markSeen = (seen, node, token) => {
  let tokens = seen.get(node);
  if (!tokens) {
    tokens = new Set();
    seen.set(node, tokens);
  }
  tokens.add(token);
};
const stickyHidden = new WeakSet();
const hideSticky = node => {
  if (!node || !node.style || stickyHidden.has(node)) { return; }
  stickyHidden.add(node);
  const enforce = () => {
    if (node.style.getPropertyValue("display") !== "none" || node.style.getPropertyPriority("display") !== "important") {
      node.style.setProperty("display", "none", "important");
    }
  };
  enforce();
  new MutationObserver(enforce).observe(node, { attributes: true, attributeFilter: ["style"] });
};
const addCleanup = (node, token, cleanup) => {
  let tokens = applied.get(node);
  if (!tokens) {
    tokens = new Map();
    applied.set(node, tokens);
  }
  if (!tokens.has(token)) {
    tokens.set(token, cleanup);
    styledNodes.add(node);
  }
};
const actionType = action => Array.isArray(action) ? action[0] : action && action.type;
const actionArg = action => Array.isArray(action) ? action[1] : action && action.arg;
const applyAction = (node, action, token, seen) => {
  if (!node) { return; }
  const type = actionType(action) || "";
  const arg = actionArg(action) || "";
  if (type === "remove") {
    node.remove();
    node.textContent = "";
    return;
  }
  if (type === "remove-attr") {
    const re = regexFromString(arg, true);
    for (const name of node.getAttributeNames ? node.getAttributeNames() : []) {
      if (re.test(name)) { node.removeAttribute(name); }
    }
    return;
  }
  if (type === "remove-class") {
    const re = regexFromString(arg, true);
    for (const name of Array.from(node.classList || [])) {
      if (re.test(name)) { node.classList.remove(name); }
    }
    return;
  }
  if (type === "hide-sticky") {
    hideSticky(node);
    return;
  }
  markSeen(seen, node, token);
  if (type === "style") {
    const className = "__` + runBlockID() + `-style-" + token;
    node.classList.add(className);
    addCleanup(node, token, () => node.classList.remove(className));
  } else {
    node.classList.add("__` + runBlockID() + `-hide");
    addCleanup(node, token, () => node.classList.remove("__` + runBlockID() + `-hide"));
  }
};
const cleanupStale = seen => {
  for (const node of Array.from(styledNodes)) {
    const tokens = applied.get(node);
    if (!tokens) {
      styledNodes.delete(node);
      continue;
    }
    const live = seen.get(node);
    for (const [token, cleanup] of Array.from(tokens.entries())) {
      if (live && live.has(token)) { continue; }
      cleanup();
      tokens.delete(token);
    }
    if (tokens.size === 0) { styledNodes.delete(node); }
  }
};
self.__` + runBlockHash() + `Run = payload => {
  const filters = payload.procedural || [];
  const stickyFilters = filters.filter(filter => actionType(filter.action) === "hide-sticky");
  const runSticky = () => {
    for (const filter of stickyFilters) {
      for (const node of evalFilter(filter)) { applyAction(node, filter.action, 0, new WeakMap()); }
    }
  };
  const run = () => {
    scheduled = false;
    const seen = new WeakMap();
    for (let i = 0; i < filters.length; i++) {
      const filter = filters[i];
      for (const node of evalFilter(filter)) { applyAction(node, filter.action, i, seen); }
    }
    cleanupStale(seen);
  };
  const schedule = () => {
    if (scheduled) { return; }
    scheduled = true;
    if (self.requestAnimationFrame) {
      self.requestAnimationFrame(run);
    } else {
      self.setTimeout(run, 1);
    }
  };
  run();
  if (!self.__` + runBlockHash() + `Observer) {
    self.__` + runBlockHash() + `Observer = new MutationObserver(() => {
      // Anti-circumvention snippets must see transient DOM states before page
      // observers randomize the attributes which caused the XPath match.
      runSticky();
      schedule();
    });
    self.__` + runBlockHash() + `Observer.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true });
  }
};
self.__` + runBlockHash() + `Dynamic = payload => {
  const token = payload && payload.token;
  const endpoint = payload && payload.endpoint;
  if (!token || !endpoint || self.__` + runBlockHash() + `DynamicObserver) { return; }
  const seenClasses = new Set();
  const seenIds = new Set();
  const pendingClasses = new Set();
  const pendingIds = new Set();
  const receivedClasses = new Set();
  const receivedIds = new Set();
  const appliedStyleHashes = new Set();
  let dynamicScheduled = false;
  let dynamicStyle = null;
  const styleHash = async css => {
    if (self.crypto && self.crypto.subtle && self.TextEncoder) {
      try {
        const data = new TextEncoder().encode(css);
        const digest = await self.crypto.subtle.digest("SHA-256", data);
        return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
      } catch {}
    }
    return css;
  };
  const ensureStyle = () => {
    if (dynamicStyle && dynamicStyle.isConnected) { return dynamicStyle; }
    dynamicStyle = document.querySelector("style[data-` + runBlockID() + `]");
    if (dynamicStyle) { return dynamicStyle; }
    dynamicStyle = document.createElement("style");
    dynamicStyle.setAttribute("data-` + runBlockID() + `-dynamic", "");
    (document.head || document.documentElement || document).appendChild(dynamicStyle);
    return dynamicStyle;
  };
  const collectNode = node => {
    if (!node || node.nodeType !== 1) { return; }
    if (node.id && !seenIds.has(node.id)) {
      seenIds.add(node.id);
      pendingIds.add(node.id);
    }
    if (node.classList) {
      for (const className of node.classList) {
        if (className && !seenClasses.has(className)) {
          seenClasses.add(className);
          pendingClasses.add(className);
        }
      }
    }
  };
  const collectTree = node => {
    collectNode(node);
    if (node && node.querySelectorAll) {
      for (const child of node.querySelectorAll("[class],[id]")) { collectNode(child); }
    }
  };
  const flushDynamic = async () => {
    dynamicScheduled = false;
    const classes = Array.from(pendingClasses).filter(className => !receivedClasses.has(className));
    const ids = Array.from(pendingIds).filter(id => !receivedIds.has(id));
    pendingClasses.clear();
    pendingIds.clear();
    if (classes.length === 0 && ids.length === 0) { return; }
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token, classes, ids })
      });
      if (!response.ok || response.status === 204) { return; }
      const result = await response.json();
      if (!result || !result.css) { return; }
      for (const className of classes) { receivedClasses.add(className); }
      for (const id of ids) { receivedIds.add(id); }
      const hash = await styleHash(result.css);
      if (appliedStyleHashes.has(hash)) { return; }
      appliedStyleHashes.add(hash);
      ensureStyle().appendChild(document.createTextNode(result.css));
    } catch {}
  };
  const scheduleDynamic = () => {
    if (dynamicScheduled) { return; }
    dynamicScheduled = true;
    if (self.requestAnimationFrame) {
      self.requestAnimationFrame(flushDynamic);
    } else {
      self.setTimeout(flushDynamic, 1);
    }
  };
  collectTree(document.documentElement || document);
  scheduleDynamic();
  self.__` + runBlockHash() + `DynamicObserver = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === "childList") {
        for (const node of record.addedNodes) { collectTree(node); }
      } else if (record.type === "attributes") {
        collectNode(record.target);
      }
    }
    scheduleDynamic();
  });
  self.__` + runBlockHash() + `DynamicObserver.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true, attributeFilter: ["class", "id"] });
};
})();`
