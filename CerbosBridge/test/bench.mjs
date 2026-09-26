// Measures the bridge-side cost of a decision: JSON in, envelope out, against the real engine.
// Usage: npm run build && node test/bench.mjs
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = path.dirname(fileURLToPath(import.meta.url));
const webDir = path.resolve(here, "..", "..", "cerbos-embedded-pdp", "src", "main", "assets", "cerbos-epdp");
const ruleId = process.env.CERBOS_RULE_ID ?? "AVGB9RP6HFBL";
const wasmBytes = await readFile(path.join(webDir, "server.wasm"));
const realFetch = globalThis.fetch.bind(globalThis);
globalThis.fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  if (url.endsWith("server.wasm")) return new Response(wasmBytes, { headers: { "content-type": "application/wasm" } });
  return realFetch(input, init);
};
globalThis.window = globalThis;
const pending = new Map();
globalThis.cerbosHost = { postMessage: () => {}, postResult: (id, json) => pending.get(id)?.(json) };
vm.runInThisContext(await readFile(path.join(webDir, "bridge.js"), "utf8"));
const bridge = globalThis.CerbosBridge;
let n = 0;
const invoke = (method, params) =>
  new Promise((resolve) => {
    const id = `c${n++}`;
    pending.set(id, (json) => resolve(JSON.parse(json)));
    bridge.invoke(id, method, params === undefined ? undefined : JSON.stringify(params));
  });

const init = await invoke("init", { ruleId, updateIntervalSeconds: 0, emitDecisions: process.env.DECISIONS === "1" });
if (!init.ok) throw new Error(JSON.stringify(init.error));
const request = {
  principal: { id: "alice@example.com", roles: ["USER"], attr: { tier: "PREMIUM" } },
  resources: [{ resource: { kind: "resource", id: "1", attr: { ownerId: "alice@example.com" } }, actions: ["read", "create", "update", "delete", "publish"] }],
  includeMetadata: true,
};
const iterations = Number(process.env.N ?? 500);
let t0 = performance.now();
await invoke("checkResources", request);
console.log(`first checkResources: ${(performance.now() - t0).toFixed(2)} ms`);
for (let i = 0; i < 50; i++) await invoke("checkResources", request);
const samples = [];
for (let i = 0; i < iterations; i++) {
  const t = performance.now();
  const env = await invoke("checkResources", request);
  samples.push(performance.now() - t);
  if (!env.ok) throw new Error(JSON.stringify(env.error));
}
samples.sort((a, b) => a - b);
const p = (q) => samples[Math.floor(q * (samples.length - 1))].toFixed(3);
console.log(`checkResources x${iterations}: p50 ${p(0.5)} ms  p90 ${p(0.9)} ms  p99 ${p(0.99)} ms`);
await invoke("stop");
process.exit(0);
