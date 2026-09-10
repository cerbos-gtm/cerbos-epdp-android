// Runs the built Android bridge bundle inside Node against the real server.wasm and Cerbos Hub.
// Usage: npm run build && CERBOS_RULE_ID=<public rule id> npm test
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = path.dirname(fileURLToPath(import.meta.url));
const webDir = path.resolve(here, "..", "..", "cerbos-embedded-pdp", "src", "main", "assets", "cerbos-epdp");
// The default is a public (unauthenticated) demo rule in the Cerbos Hub demo organisation.
const ruleId = process.env.CERBOS_RULE_ID ?? "AVGB9RP6HFBL";

// --- Fake Android host -------------------------------------------------------------------------
const events = [];
const wasmBytes = await readFile(path.join(webDir, "server.wasm"));
const realFetch = globalThis.fetch.bind(globalThis);
// How the fake network answers GetBundle: "online" | "offline" | "hang" | "forbidden" | "portal" | "outage"
let hubMode = "online";
// Whether server.wasm can be fetched; counts how often the page asked for it.
let wasmAvailable = true;
let wasmFetches = 0;
// Number of GetBundle requests currently parked in "hang" mode.
let hangingRequests = 0;

globalThis.fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  if (url === "server.wasm" || url.endsWith("/server.wasm")) {
    wasmFetches++;
    if (!wasmAvailable) {
      return new Response("missing", { status: 404, headers: { "content-type": "text/plain" } });
    }
    return new Response(wasmBytes, { status: 200, headers: { "content-type": "application/wasm" } });
  }
  if (url.includes("/GetBundle")) {
    switch (hubMode) {
      case "offline":
        throw new TypeError("Simulated offline: Load failed");
      case "hang":
        // Stalled connection: only the caller's abort signal can end this.
        hangingRequests++;
        return new Promise((_, reject) => {
          init?.signal?.addEventListener("abort", () => {
            hangingRequests--;
            reject(init.signal.reason ?? new Error("aborted"));
          });
        });
      case "forbidden":
        return new Response(JSON.stringify({ code: "permission_denied", message: "rule disabled" }), { status: 403, headers: { "content-type": "application/json" } });
      case "outage":
        return new Response("bad gateway", { status: 502, headers: { "content-type": "text/plain" } });
      case "portal":
        return new Response("<html><body>Please log in to the Wi-Fi</body></html>", { status: 200, headers: { "content-type": "text/html" } });
      default:
        break;
    }
  }
  return realFetch(input, init);
};

globalThis.window = globalThis;
// Results of `invoke` calls come back through `postResult`, keyed by the call ID the host chose.
const pendingResults = new Map();
globalThis.cerbosHost = {
  postMessage: (message) => {
    const event = JSON.parse(message);
    events.push(event);
    if (event.type === "jwtDecode") {
      // Answer asynchronously, like the native host would.
      setTimeout(() => globalThis.CerbosBridge.resolveCallback(event.id, true, JSON.stringify({ sub: "decoded-" + event.token })), 0);
    }
  },
  postResult: (id, envelopeJSON) => {
    const pending = pendingResults.get(id);
    pendingResults.delete(id);
    pending?.(envelopeJSON);
  },
};

vm.runInThisContext(await readFile(path.join(webDir, "bridge.js"), "utf8"), { filename: "bridge.js" });
const bridge = globalThis.CerbosBridge;
assert.ok(bridge, "CerbosBridge global not defined");
assert.equal(events.at(-1)?.type, "bridgeReady");

let callCounter = 0;
async function invoke(method, params) {
  const id = `call-${callCounter++}`;
  const envelopeJSON = await new Promise((resolve) => {
    pendingResults.set(id, resolve);
    bridge.invoke(id, method, params === undefined ? undefined : JSON.stringify(params));
  });
  const envelope = JSON.parse(envelopeJSON);
  if (!envelope.ok) {
    const error = new Error(`${method} failed: ${envelope.error.message}`);
    error.serialized = envelope.error;
    throw error;
  }
  return envelope.result;
}

const request = {
  principal: { id: "user@example.com", roles: ["USER"], attr: { tier: "PREMIUM" } },
  resources: [
    { resource: { kind: "resource", id: "1", attr: { ownerId: "user@example.com" } }, actions: ["read", "update"] },
    { resource: { kind: "resource", id: "2", attr: { ownerId: "someone-else@example.com" } }, actions: ["read", "update"] },
  ],
  includeMetadata: true,
};

// --- 0. An engine failure is classified as such and does not poison the module cache ----------
wasmAvailable = false;
await assert.rejects(invoke("init", { ruleId, updateIntervalSeconds: 0 }), (error) => {
  assert.equal(error.serialized.kind, "engine", `expected an engine error, got ${JSON.stringify(error.serialized)}`);
  return true;
});
wasmAvailable = true;

// --- 1. Online initialisation -------------------------------------------------------------------
let started = performance.now();
const init = await invoke("init", { ruleId, updateIntervalSeconds: 0, emitDecisions: true, jwtDecoding: true });
console.log(`init (online): ${Math.round(performance.now() - started)} ms`, JSON.stringify(init));
assert.equal(init.status, "ready");
assert.ok(init.bundle?.bundleId, "bundle id missing");
assert.equal(init.bundle.source, "network");
const cacheEvent = events.find((event) => event.type === "bundleCache");
assert.ok(cacheEvent, "bundleCache event not emitted");
assert.equal(cacheEvent.bundleId, init.bundle.bundleId);

const response = await invoke("checkResources", request);
assert.equal(response.results.length, 2);
assert.ok(Array.isArray(response.results[0].allowedActions));
console.log("checkResources:", JSON.stringify(response.results.map((r) => [r.resource.id, r.actions])));
assert.ok(events.some((event) => event.type === "decision"), "decision event not emitted");

const single = await invoke("checkResource", { principal: request.principal, resource: request.resources[0].resource, actions: ["read"] });
assert.ok("actions" in single);
const allowed = await invoke("isAllowed", { principal: request.principal, resource: request.resources[0].resource, action: "read" });
assert.equal(typeof allowed, "boolean");
const plan = await invoke("planResources", { principal: request.principal, resource: { kind: "resource" }, action: "read" });
assert.ok(typeof plan.kind === "string");
const info = await invoke("serverInfo");
assert.ok(info.version);

// JWT decoding goes through the native callback handler.
const withJWT = await invoke("checkResource", {
  principal: request.principal,
  resource: request.resources[0].resource,
  actions: ["read"],
  auxData: { jwt: { token: "abc" } },
});
assert.ok("actions" in withJWT);
assert.ok(events.some((event) => event.type === "jwtDecode"), "jwtDecode event not emitted");

// Errors come back as structured envelopes.
await assert.rejects(invoke("checkResources", { principal: { id: "" }, resources: [] }), (error) => {
  assert.ok(error.serialized.name, "error name missing");
  return true;
});

// --- 2. Offline cold start using the cached bundle --------------------------------------------
hubMode = "offline";
const cachedBundle = { key: cacheEvent.key, body: cacheEvent.body };
started = performance.now();
const offline = await invoke("init", { ruleId, updateIntervalSeconds: 0, cachedBundle });
console.log(`init (offline, cached bundle): ${Math.round(performance.now() - started)} ms`, JSON.stringify(offline.bundle));
assert.equal(offline.status, "ready");
assert.equal(offline.bundle.source, "cache");
assert.equal(offline.bundle.bundleId, init.bundle.bundleId);
const offlineResponse = await invoke("checkResources", request);
assert.deepEqual(offlineResponse.results.map((r) => r.actions), response.results.map((r) => r.actions));

// --- 3. Offline cold start without a cache fails cleanly --------------------------------------
await assert.rejects(invoke("init", { ruleId, updateIntervalSeconds: 0 }), (error) => {
  // An unreachable Hub is a policy-source problem, not a bridge bug.
  assert.equal(error.serialized.kind, "policySource", `expected a policySource error, got ${JSON.stringify(error.serialized)}`);
  return true;
});
const status = await invoke("status");
assert.equal(status.status, "failed");
assert.equal(status.error?.kind, "policySource");
assert.ok(status.error?.message);

// --- 4. A stalled connection is aborted and the cache is replayed -------------------------------
hubMode = "hang";
started = performance.now();
const stalled = await invoke("init", { ruleId, updateIntervalSeconds: 0, initialLoadTimeoutSeconds: 1, cachedBundle });
const stalledMs = performance.now() - started;
console.log(`init (stalled connection, cached bundle): ${Math.round(stalledMs)} ms`);
assert.equal(stalled.bundle.source, "cache");
assert.ok(stalledMs < 15_000, "initial-load timeout did not fire");

// --- 5. A captive portal (HTTP 200 with HTML) is not mistaken for a bundle ---------------------
hubMode = "portal";
const portal = await invoke("init", { ruleId, updateIntervalSeconds: 0, cachedBundle });
assert.equal(portal.bundle.source, "cache");

// --- 6. A Hub outage (5xx) replays the cache ---------------------------------------------------
hubMode = "outage";
const outage = await invoke("init", { ruleId, updateIntervalSeconds: 0, cachedBundle });
assert.equal(outage.bundle.source, "cache");

// --- 7. A client error (disabled rule, revoked credentials) is never masked by the cache -------
hubMode = "forbidden";
await assert.rejects(invoke("init", { ruleId, updateIntervalSeconds: 0, cachedBundle }), (error) => {
  assert.equal(error.serialized.code, 7, "expected gRPC PERMISSION_DENIED");
  assert.equal(error.serialized.kind, "policySource");
  return true;
});
assert.equal((await invoke("status")).status, "failed");

// --- 8. A superseded init must not tear down its successor -------------------------------------
hubMode = "online";
const [superseded, winner] = await Promise.allSettled([
  invoke("init", { ruleId, updateIntervalSeconds: 0 }),
  invoke("init", { ruleId, updateIntervalSeconds: 0 }),
]);
assert.equal(superseded.status, "rejected");
assert.equal(winner.status, "fulfilled");
assert.equal(winner.value.status, "ready");
assert.equal((await invoke("status")).status, "ready");
assert.equal((await invoke("checkResources", request)).results.length, 2);

// --- 8b. A superseded init must not consume its successor's offline fallback -------------------
// Init A stalls on its first download. Init B (another rule, offline, with a cached bundle) takes
// over; stopping A's loader aborts A's request. A's abort must neither replay B's cache nor mark
// the initial load as done, otherwise B's own offline fallback is gone and B fails.
hubMode = "hang";
const stalledInit = invoke("init", { ruleId: "AVGB9RP6HFBM", updateIntervalSeconds: 0, initialLoadTimeoutSeconds: 30 });
while (hangingRequests === 0) {
  await new Promise((resolve) => setTimeout(resolve, 10));
}
hubMode = "offline";
const eventsBefore = events.length;
const [abandoned, takeover] = await Promise.allSettled([stalledInit, invoke("init", { ruleId, updateIntervalSeconds: 0, cachedBundle })]);
assert.equal(abandoned.status, "rejected");
assert.equal(takeover.status, "fulfilled", `takeover init failed: ${takeover.reason}`);
assert.equal(takeover.value.status, "ready");
assert.equal(takeover.value.bundle.source, "cache");
assert.equal(takeover.value.bundle.bundleId, init.bundle.bundleId);
const takeoverBundles = events.slice(eventsBefore).filter((event) => event.type === "bundles" && event.active);
assert.equal(takeoverBundles.length, 1, `expected exactly one active-bundle event from the takeover, got ${JSON.stringify(takeoverBundles)}`);
assert.equal(takeoverBundles[0].active.source, "cache");
assert.equal((await invoke("status")).status, "ready");
assert.equal((await invoke("checkResources", request)).results.length, 2);

// --- 9. The engine module was compiled once and reused by every init ------------------------------
assert.equal(wasmFetches, 2, `server.wasm fetched ${wasmFetches} times (one failed probe, one compile)`);

await invoke("stop");
console.log("smoke test passed");
process.exit(0);
