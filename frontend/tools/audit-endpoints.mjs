/**
 * Every backend endpoint should be reachable from the console.
 *
 *   node tools/audit-endpoints.mjs
 *
 * Reads the Spring controllers for their mappings and the console's sources for
 * the paths it calls, and prints each endpoint nothing in the console calls.
 * Silence is a pass. Paths are matched segment by segment; a {variable} in the
 * mapping matches anything, and so does a ${expression} in the console. A
 * template that starts from a local constant (`${base}/keys`, where
 * `const base = \`/api/admin/...\``) is resolved within its file first.
 *
 * Endpoints that are deliberately not the console's — called by other programs
 * only — are listed in NOT_FOR_THE_CONSOLE with the reason.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const JAVA = join(ROOT, "backend", "src", "main", "java");
const SRC = join(ROOT, "frontend", "src");

/** "METHOD path" → why the console does not call it. */
const NOT_FOR_THE_CONSOLE = {};

function walk(dir, ext, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, ext, out);
    else if (ext.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}

/* ---- backend mappings ---------------------------------------------- */

const VERB = { GetMapping: "GET", PostMapping: "POST", PutMapping: "PUT", DeleteMapping: "DELETE", PatchMapping: "PATCH" };

function pathsOf(args) {
  if (args == null) return [""];
  const named = args.match(/\b(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")/);
  const lead = args.match(/^\s*(\{[^}]*\}|"[^"]*")/);
  const src = named ? named[1] : lead ? lead[1] : "";
  const ps = [...src.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
  return ps.length ? ps : [""];
}

const endpoints = [];
for (const file of walk(JAVA, [".java"])) {
  const src = readFileSync(file, "utf8");
  if (!/@RestController|@Controller\b/.test(src)) continue;
  const decl = src.match(/^\s*(?:public\s+)?(?:final\s+)?(?:class|record)\s+\w+/m);
  const head = decl ? src.slice(0, decl.index) : "";
  const body = decl ? src.slice(decl.index) : src;
  const base = head.match(/@RequestMapping\s*\(([^)]*)\)/);
  const bases = base ? pathsOf(base[1]) : [""];
  for (const m of body.matchAll(/@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*(\(((?:[^()]|\([^()]*\))*)\))?/g)) {
    const args = m[3];
    const verbs = m[1] === "RequestMapping" ? [...(args ?? "").matchAll(/RequestMethod\.(\w+)/g)].map((x) => x[1]) : [VERB[m[1]]];
    for (const b of bases)
      for (const p of args && args.includes('"') ? pathsOf(args) : [""])
        for (const v of verbs.length ? verbs : ["ANY"])
          endpoints.push({ verb: v, path: b.replace(/\/$/, "") + (p ? "/" + p.replace(/^\//, "") : ""), file: file.split("/").pop() });
  }
}

/* ---- console calls ------------------------------------------------- */

const calls = [];
for (const file of walk(SRC, [".ts", ".tsx"])) {
  let src = readFileSync(file, "utf8");
  // Resolve `const name = \`...\`` / "..." used as a prefix elsewhere in the file.
  for (const c of src.matchAll(/const\s+(\w+)\s*=\s*(`[^`\n]*`|"[^"\n]*")/g)) {
    const value = c[2].slice(1, -1);
    if (value.startsWith("/api/") || value.startsWith("/v1/")) src = src.split("${" + c[1] + "}").join(value);
  }
  for (const m of src.matchAll(/`([^`\n]*?\/(?:api|v1)\/[^`\n]*)`|"(\/(?:api|v1)\/[^"\n]*)"|'(\/(?:api|v1)\/[^'\n]*)'/g)) {
    const lit = (m[1] ?? m[2] ?? m[3]).replace(/\$\{[^}]*\}/g, "{}").split("?")[0].split("#")[0];
    const concat = /^\s*\+/.test(src.slice(m.index + m[0].length, m.index + m[0].length + 8));
    const at = lit.search(/\/(api|v1)\//);
    calls.push({ path: lit.slice(at), concat });
  }
}

const segs = (p) => p.replace(/^\/|\/$/g, "").split("/");
function covers(ep, call) {
  const a = segs(ep);
  let b = segs(call.path);
  const same = (x, y) =>
    x === y || x.startsWith("{") || y === "{}" || (y.includes("{}") && new RegExp("^" + y.split("{}").map((s) => s.replace(/[.*+?^$()|[\]\\]/g, "\\$&")).join(".*") + "$").test(x));
  if (call.concat) {
    if (b[b.length - 1] === "") b = b.slice(0, -1);
    return b.length <= a.length && b.every((y, i) => same(a[i], y));
  }
  return a.length === b.length && a.every((x, i) => same(x, b[i]));
}

let missing = 0;
for (const ep of endpoints) {
  const key = `${ep.verb} ${ep.path}`;
  if (NOT_FOR_THE_CONSOLE[key]) continue;
  if (!calls.some((c) => covers(ep.path, c))) {
    missing++;
    console.log(`${key}\t${ep.file}`);
  }
}
console.log(missing === 0 ? `endpoints: all ${endpoints.length} reachable from the console` : `endpoints: ${missing} of ${endpoints.length} not reachable`);
process.exit(missing === 0 ? 0 : 1);
