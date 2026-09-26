/**
 * Android entry point, loaded by `index.html` in the hidden WebView.
 *
 * Kotlin calls `CerbosBridge.invoke(id, method, paramsJSON)` with `evaluateJavascript`. The result
 * goes back through `cerbosHost.postResult(id, envelopeJSON)` and events through
 * `cerbosHost.postMessage(json)`; `cerbosHost` is the injected `@JavascriptInterface` object.
 */
import type { JWT } from "@cerbos/core";

import type { BridgeEvent } from "./core.js";
import { CerbosBridgeCore, EngineError } from "./core.js";

interface AndroidHost {
  postMessage(json: string): void;
  postResult(id: string, envelopeJSON: string): void;
}

interface AndroidWindow {
  cerbosHost?: AndroidHost;
  CerbosBridge?: {
    invoke(id: string, method: string, paramsJSON: string | null | undefined): void;
    resolveCallback(id: string, ok: boolean, payload: string): boolean;
  };
}

const androidWindow = globalThis as unknown as AndroidWindow;
const host = androidWindow.cerbosHost;

function emit(event: BridgeEvent): void {
  try {
    host?.postMessage(JSON.stringify(event));
  } catch {
    // The host is gone.
  }
}

// JWT decoding asks the host with a `jwtDecode` event; it answers with `resolveCallback`.
const callbackTimeoutMs = 30_000;
let callbackCounter = 0;
const pendingCallbacks = new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>();

function requestFromHost(event: Extract<BridgeEvent, { id: string }>): Promise<unknown> {
  return new Promise((resolve, reject) => {
    pendingCallbacks.set(event.id, { resolve, reject });
    setTimeout(() => {
      if (pendingCallbacks.delete(event.id)) {
        reject(new Error("Timed out waiting for the native host"));
      }
    }, callbackTimeoutMs);
    emit(event);
  });
}

// Compile the 20 MB engine once per page and reuse it for every `init`.
let compiledModule: Promise<WebAssembly.Module> | undefined;

function loadEngineModule(): Promise<WebAssembly.Module> {
  if (typeof WebAssembly === "undefined" || typeof WebAssembly.compileStreaming !== "function") {
    return Promise.reject(new EngineError("WebAssembly streaming compilation is not available in this web view"));
  }
  compiledModule ??= (async () => {
    // Served from the APK as `application/wasm`, so Chromium compiles it while streaming.
    let response: Response;
    try {
      response = await fetch("server.wasm");
    } catch (error) {
      throw new EngineError(`Failed to load server.wasm: ${String(error)}`);
    }
    if (!response.ok) {
      throw new EngineError(`Failed to load server.wasm: HTTP ${response.status}`);
    }
    try {
      return await WebAssembly.compileStreaming(response);
    } catch (error) {
      throw new EngineError(`Failed to compile server.wasm: ${String(error)}`);
    }
  })();
  compiledModule.catch(() => {
    compiledModule = undefined;
  });
  return compiledModule;
}

const core = new CerbosBridgeCore({
  emit,
  loadWasm: loadEngineModule,
  decodeJWTPayload: async (jwt: JWT) => {
    const id = `jwt-${Date.now()}-${callbackCounter++}`;
    const payload = await requestFromHost({ type: "jwtDecode", id, token: jwt.token, keySetId: jwt.keySetId ?? "" });
    if (typeof payload !== "object" || payload === null) {
      throw new Error("Native decodeJWTPayload returned an invalid payload");
    }
    return payload as Record<string, never>;
  },
});

androidWindow.CerbosBridge = {
  invoke: (id, method, paramsJSON) => {
    void core.invokeJSON(method, paramsJSON).then((envelope) => {
      try {
        host?.postResult(id, envelope);
      } catch {
        // The host is gone.
      }
    });
  },
  resolveCallback: (id, ok, payload) => {
    const pending = pendingCallbacks.get(id);
    if (!pending) {
      return false;
    }
    pendingCallbacks.delete(id);
    if (ok) {
      try {
        pending.resolve(JSON.parse(payload));
      } catch (error) {
        pending.reject(new Error(`Invalid callback payload: ${String(error)}`));
      }
    } else {
      pending.reject(new Error(payload || "Native callback failed"));
    }
    return true;
  },
};

// Forward uncaught errors and console warnings to the native logs.
if (typeof globalThis.addEventListener === "function") {
  globalThis.addEventListener("error", (event) => {
    emit({ type: "log", level: "error", message: `Uncaught error: ${event.message}` });
  });
  globalThis.addEventListener("unhandledrejection", (event) => {
    emit({ type: "log", level: "error", message: `Unhandled rejection: ${String(event.reason)}` });
  });
}
for (const level of ["warn", "error"] as const) {
  const original = console[level].bind(console);
  console[level] = (...args: unknown[]) => {
    original(...args);
    emit({ type: "log", level, message: args.map((arg) => (arg instanceof Error ? arg.message : String(arg))).join(" ") });
  };
}

emit({ type: "bridgeReady" });
