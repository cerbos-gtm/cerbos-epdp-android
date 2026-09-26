import type {
  CheckResourceRequest,
  CheckResourcesRequest,
  DecisionLogEntry,
  IsAllowedRequest,
  JWT,
  PlanResourcesRequest,
  ValidationError,
  Value,
} from "@cerbos/core";
import { NotOK, Status, ValidationFailed } from "@cerbos/core";
import type {
  DecodedJWTPayload,
  Options as EmbeddedOptions,
  PolicyLoaderOptions,
} from "@cerbos/embedded-client";
import { Embedded, PolicyLoader, SchemaEnforcement } from "@cerbos/embedded-client";
import { metadata as serverMetadata } from "@cerbos/embedded-server";

import type { BundleInfo } from "./hub-bundle-cache.js";
import { HubBundleCache } from "./hub-bundle-cache.js";

export type BridgeStatus = "idle" | "loading" | "ready" | "failed";

export type ErrorKind = "engine" | "policySource" | "bridge";

export interface SerializedError {
  name: string;
  message: string;
  /**
   * Init failures only: `engine` (WebAssembly unavailable or broken), `policySource` (Hub rejected
   * the request, or was unreachable with no cached bundle) or `bridge` (anything else).
   */
  kind?: ErrorKind;
  /** gRPC status code for `NotOK` errors. */
  code?: number;
  details?: string;
  validationErrors?: ValidationError[];
  stack?: string;
}

/** Events sent to the native host. */
export type BridgeEvent =
  | { type: "bridgeReady" }
  | { type: "status"; status: BridgeStatus; error?: SerializedError }
  | { type: "bundles"; active?: BundleInfo; pending?: BundleInfo }
  | { type: "policyUpdate"; ok: boolean; error?: SerializedError; bundle?: BundleInfo; pending?: BundleInfo }
  | { type: "decision"; entry: unknown }
  | { type: "validationErrors"; errors: ValidationError[] }
  | { type: "bundleCache"; key: string; body: string; bundleId: string; ruleRevision: string }
  | { type: "log"; level: "debug" | "info" | "warn" | "error"; message: string }
  | { type: "jwtDecode"; id: string; token: string; keySetId: string };

/** Thrown when the engine's WebAssembly module cannot be obtained or compiled. */
export class EngineError extends Error {
  public override readonly name = "EngineError";
}

/** Thrown when the web view has no WebAssembly support (for example iOS Lockdown Mode). */
export class WebAssemblyUnavailableError extends Error {
  public override readonly name = "WebAssemblyUnavailable";

  public constructor() {
    super("WebAssembly is not available in this web view (is Lockdown Mode enabled?)");
  }
}

export interface BridgeHost {
  emit(event: BridgeEvent): void;
  /** Provides the engine's WebAssembly module. */
  loadWasm(): Promise<WebAssembly.Module | Response | ArrayBuffer | ArrayBufferView<ArrayBuffer>>;
  /** Verifies and decodes a JWT natively (used when `jwtDecoding` is set). */
  decodeJWTPayload?: (jwt: JWT) => Promise<DecodedJWTPayload>;
}

export interface InitParams {
  ruleId: string;
  scopes?: string[];
  hub?: {
    baseUrl?: string;
    clientId?: string;
    clientSecret?: string;
  };
  /** Seconds between update checks. `0` disables polling. Minimum 10. Default 60. */
  updateIntervalSeconds?: number;
  activateOnLoad?: boolean;
  /** Seconds before a stalled first download falls back to the cache. Default 20. */
  initialLoadTimeoutSeconds?: number;
  options?: {
    defaultPolicyVersion?: string;
    defaultScope?: string;
    globals?: Record<string, Value>;
    lenientScopeSearch?: boolean;
    schemaEnforcement?: "none" | "warn" | "reject";
    strictEvaluation?: boolean;
    userAgent?: string;
    headers?: Record<string, string>;
    /** `throw` rejects checks with validation errors; `report` emits them as events. */
    onValidationError?: "throw" | "report";
  };
  emitDecisions?: boolean;
  jwtDecoding?: boolean;
  /** The saved `GetBundle` response, used if the first download fails. */
  cachedBundle?: { key: string; body: string } | null;
}

export interface InitResult {
  status: BridgeStatus;
  /** The bundle decisions are evaluated against. */
  bundle: BundleInfo | undefined;
  /** A downloaded bundle waiting for `activate` (only when `activateOnLoad` is false). */
  pending: BundleInfo | undefined;
  server: { version: string; commit: string; builtAt: string };
}

export interface StatusResult {
  status: BridgeStatus;
  bundle: BundleInfo | undefined;
  pending: BundleInfo | undefined;
  error: SerializedError | undefined;
}

type Envelope = { ok: true; result: unknown } | { ok: false; error: SerializedError };

export class CerbosBridgeCore {
  private client: Embedded | undefined;
  private loader: PolicyLoader | undefined;
  private status: BridgeStatus = "idle";
  private lastError: SerializedError | undefined;
  private readonly bundleCache: HubBundleCache;
  /** Bumped per `init` so a superseded init leaves the newer client alone. */
  private generation = 0;
  private initialised = false;
  private activateOnLoad = true;
  /** Suppresses decision and validation events for the warm-up check. */
  private warmingUp = false;
  private activeBundle: BundleInfo | undefined;
  private pendingBundle: BundleInfo | undefined;

  public constructor(private readonly host: BridgeHost) {
    this.bundleCache = new HubBundleCache({
      persist: (entry) => host.emit({ type: "bundleCache", ...entry }),
      log: (level, message) => host.emit({ type: "log", level, message }),
    });
    this.bundleCache.onBundle = (bundle) => this.handleDownloadedBundle(bundle);
  }

  /** Called for each bundle from Hub or the cache. */
  private handleDownloadedBundle(bundle: BundleInfo): void {
    if (!this.initialised || this.activateOnLoad) {
      // The loader always activates the first bundle.
      this.activeBundle = bundle;
      this.pendingBundle = undefined;
    } else {
      this.pendingBundle = bundle;
    }
    this.emitBundles();
  }

  private emitBundles(): void {
    this.host.emit({
      type: "bundles",
      ...(this.activeBundle ? { active: this.activeBundle } : {}),
      ...(this.pendingBundle ? { pending: this.pendingBundle } : {}),
    });
  }

  /** JSON in, JSON envelope out. */
  public async invokeJSON(method: string, paramsJSON: string | null | undefined): Promise<string> {
    let envelope: Envelope;
    try {
      const params: unknown = paramsJSON ? JSON.parse(paramsJSON) : undefined;
      envelope = { ok: true, result: await this.invoke(method, params) };
    } catch (error) {
      envelope = { ok: false, error: serializeError(error) };
    }
    return JSON.stringify(envelope);
  }

  public async invoke(method: string, params: unknown): Promise<unknown> {
    switch (method) {
      case "init":
        return await this.init(params as InitParams);
      case "status":
        return this.statusResult();
      case "checkResources":
        return await this.checkResources(params as CheckResourcesRequest);
      case "checkResource":
        return await this.checkResource(params as CheckResourceRequest);
      case "isAllowed":
        return await this.requireClient().isAllowed(params as IsAllowedRequest);
      case "planResources":
        return toPlain(await this.requireClient().planResources(params as PlanResourcesRequest));
      case "serverInfo":
        return await this.requireClient().serverInfo();
      case "activate":
        this.loader?.activate();
        if (this.pendingBundle) {
          this.activeBundle = this.pendingBundle;
          this.pendingBundle = undefined;
          this.emitBundles();
        }
        return true;
      case "stop":
        this.stop();
        return true;
      default:
        throw new Error(`Unknown bridge method "${method}"`);
    }
  }

  private async init(params: InitParams): Promise<InitResult> {
    if (!params || typeof params.ruleId !== "string" || params.ruleId.trim() === "") {
      throw new Error("init requires a ruleId");
    }

    const generation = ++this.generation;
    this.stop();
    this.setStatus("loading");

    if (typeof WebAssembly === "undefined" || typeof WebAssembly.instantiate !== "function") {
      const error = new WebAssemblyUnavailableError();
      this.lastError = serializeError(error);
      this.setStatus("failed", this.lastError);
      throw error;
    }
    this.activateOnLoad = params.activateOnLoad ?? true;

    const { ruleId, scopes = [], hub = {}, options = {} } = params;
    const cacheKey = params.cachedBundle?.key ?? bundleCacheKey(hub.baseUrl, ruleId, scopes);
    this.bundleCache.configure(cacheKey, params.cachedBundle?.body, (params.initialLoadTimeoutSeconds ?? 20) * 1000);

    const loaderOptions: PolicyLoaderOptions = {
      ruleId: ruleId.trim(),
      scopes,
      onUpdate: (error) => {
        this.host.emit({
          type: "policyUpdate",
          ok: error === undefined,
          ...(error ? { error: serializeError(error) } : {}),
          ...(this.activeBundle ? { bundle: this.activeBundle } : {}),
          ...(this.pendingBundle ? { pending: this.pendingBundle } : {}),
        });
      },
    };
    if (typeof params.updateIntervalSeconds === "number") {
      loaderOptions.interval = params.updateIntervalSeconds;
    }
    if (typeof params.activateOnLoad === "boolean") {
      loaderOptions.activateOnLoad = params.activateOnLoad;
    }
    if (hub.baseUrl) {
      loaderOptions.baseUrl = hub.baseUrl;
    }
    if (hub.clientId && hub.clientSecret) {
      loaderOptions.credentials = { clientId: hub.clientId, clientSecret: hub.clientSecret };
    }
    if (options.userAgent) {
      loaderOptions.userAgent = options.userAgent;
    }

    const loader = new PolicyLoader(loaderOptions);

    const embeddedOptions: EmbeddedOptions = {
      policies: loader,
      wasm: this.host.loadWasm(),
    };
    if (options.defaultPolicyVersion) embeddedOptions.defaultPolicyVersion = options.defaultPolicyVersion;
    if (options.defaultScope) embeddedOptions.defaultScope = options.defaultScope;
    if (options.globals) embeddedOptions.globals = options.globals;
    if (typeof options.lenientScopeSearch === "boolean") embeddedOptions.lenientScopeSearch = options.lenientScopeSearch;
    if (typeof options.strictEvaluation === "boolean") embeddedOptions.strictEvaluation = options.strictEvaluation;
    if (options.schemaEnforcement) embeddedOptions.schemaEnforcement = schemaEnforcement(options.schemaEnforcement);
    if (options.userAgent) embeddedOptions.userAgent = options.userAgent;
    if (options.headers) embeddedOptions.headers = options.headers;
    if (options.onValidationError === "throw") {
      embeddedOptions.onValidationError = "throw";
    } else if (options.onValidationError === "report") {
      embeddedOptions.onValidationError = (errors) => {
        if (!this.warmingUp) this.host.emit({ type: "validationErrors", errors });
      };
    }
    if (params.emitDecisions) {
      embeddedOptions.onDecision = (entry: DecisionLogEntry) => {
        if (!this.warmingUp) this.host.emit({ type: "decision", entry: toPlain(entry) });
      };
    }
    if (params.jwtDecoding) {
      const decode = this.host.decodeJWTPayload;
      if (!decode) {
        throw new Error("jwtDecoding was requested but the host does not provide decodeJWTPayload");
      }
      embeddedOptions.decodeJWTPayload = decode;
    }

    const client = new Embedded(embeddedOptions);
    this.client = client;
    this.loader = loader;

    try {
      // Resolves once the engine is instantiated and the first bundle is loaded.
      await client.serverInfo();
    } catch (error) {
      if (generation !== this.generation) {
        // A newer init took over; leave its client alone.
        loader.stop();
        throw error;
      }
      this.lastError = serializeError(error);
      this.lastError.kind = classifyInitError(error);
      this.setStatus("failed", this.lastError);
      this.stop();
      throw new InitError(error, this.lastError.kind);
    }

    await this.warmUp(client);

    if (generation !== this.generation) {
      loader.stop();
      throw new Error("Initialisation was superseded by a newer init call");
    }

    this.initialised = true;
    this.lastError = undefined;
    this.setStatus("ready");

    return {
      status: this.status,
      bundle: this.activeBundle,
      pending: this.pendingBundle,
      server: {
        version: serverMetadata.cerbosVersion,
        commit: serverMetadata.cerbosCommitHash,
        builtAt: timestampToISO(serverMetadata.builtAt),
      },
    };
  }

  /** The first evaluation is slow while V8 compiles the hot paths; pay for it here, not on the app's first check. */
  private async warmUp(client: Embedded): Promise<void> {
    this.warmingUp = true;
    try {
      await client.checkResources({
        principal: { id: "warm-up", roles: ["warm-up"] },
        resources: [{ resource: { kind: "warm-up", id: "warm-up" }, actions: ["warm-up"] }],
      });
    } catch {
      // Only the side effect matters.
    } finally {
      this.warmingUp = false;
    }
  }

  private async checkResources(request: CheckResourcesRequest): Promise<unknown> {
    const response = await this.requireClient().checkResources(request);
    return {
      requestId: response.requestId,
      cerbosCallId: response.cerbosCallId,
      results: response.results.map((result) => ({
        resource: result.resource,
        actions: result.actions,
        allowedActions: result.allowedActions(),
        validationErrors: result.validationErrors,
        metadata: result.metadata,
        outputs: result.outputs,
      })),
    };
  }

  private async checkResource(request: CheckResourceRequest): Promise<unknown> {
    const result = await this.requireClient().checkResource(request);
    return {
      resource: result.resource,
      actions: result.actions,
      allowedActions: result.allowedActions(),
      validationErrors: result.validationErrors,
      metadata: result.metadata,
      outputs: result.outputs,
    };
  }

  private stop(): void {
    this.loader?.stop();
    this.loader = undefined;
    this.client = undefined;
    this.initialised = false;
    this.activeBundle = undefined;
    this.pendingBundle = undefined;
  }

  private requireClient(): Embedded {
    if (!this.client) {
      throw new Error("Cerbos embedded client is not initialised. Call init first.");
    }
    return this.client;
  }

  private statusResult(): StatusResult {
    return { status: this.status, bundle: this.activeBundle, pending: this.pendingBundle, error: this.lastError };
  }

  private setStatus(status: BridgeStatus, error?: SerializedError): void {
    this.status = status;
    this.host.emit({ type: "status", status, ...(error ? { error } : {}) });
  }
}

export function bundleCacheKey(baseUrl: string | undefined, ruleId: string, scopes: string[]): string {
  // Keep in sync with `CerbosEmbeddedPDP.offlineCacheKey` on the native side.
  const normalisedBaseUrl = (baseUrl ?? "https://api.cerbos.cloud").replace(/\/+$/, "");
  return [normalisedBaseUrl, ruleId.trim(), [...scopes].sort().join(",")].join("|");
}

function schemaEnforcement(value: "none" | "warn" | "reject"): SchemaEnforcement {
  switch (value) {
    case "warn":
      return SchemaEnforcement.WARN;
    case "reject":
      return SchemaEnforcement.REJECT;
    default:
      return SchemaEnforcement.NONE;
  }
}

/** Carries the classification of a failed `init` into the envelope. */
class InitError extends Error {
  public constructor(
    public override readonly cause: unknown,
    public readonly kind: ErrorKind,
  ) {
    super(cause instanceof Error ? cause.message : String(cause));
    this.name = cause instanceof Error ? cause.name : "Error";
  }
}

export function classifyInitError(error: unknown): ErrorKind {
  // The client wraps most failures in NotOK(UNKNOWN, ..., { cause }), so check the whole chain.
  const chain = causeChain(error);
  if (chain.some(isEngineError)) {
    return "engine";
  }
  const notOK = chain.find((item): item is NotOK => item instanceof NotOK);
  if (notOK) {
    const root = chain[chain.length - 1];
    // Network failures: a Connect error, an aborted or timed-out request, or fetch's TypeError.
    const rootIsTransport =
      root instanceof NotOK ||
      root instanceof TypeError ||
      (root instanceof Error && (root.name === "ConnectError" || root.name === "AbortError" || root.name === "TimeoutError"));
    return notOK.code === Status.UNKNOWN && !rootIsTransport ? "bridge" : "policySource";
  }
  return "bridge";
}

function causeChain(error: unknown): unknown[] {
  const chain: unknown[] = [];
  let current = error;
  while (current !== undefined && current !== null && chain.length < 10 && !chain.includes(current)) {
    chain.push(current);
    current = (current as { cause?: unknown }).cause;
  }
  return chain;
}

function isEngineError(error: unknown): boolean {
  if (error instanceof WebAssemblyUnavailableError || error instanceof EngineError) {
    return true;
  }
  return (
    typeof WebAssembly !== "undefined"
    && (error instanceof WebAssembly.CompileError || error instanceof WebAssembly.LinkError || error instanceof WebAssembly.RuntimeError)
  );
}

export function serializeError(error: unknown): SerializedError {
  if (error instanceof InitError) {
    const serialized = serializeError(error.cause);
    serialized.kind = error.kind;
    return serialized;
  }
  if (error instanceof NotOK) {
    return { name: "NotOK", message: error.message, code: error.code, details: error.details };
  }
  if (error instanceof ValidationFailed) {
    return { name: "ValidationFailed", message: error.message, validationErrors: error.validationErrors };
  }
  if (error instanceof Error) {
    const serialized: SerializedError = { name: error.name, message: error.message };
    if (error.stack) serialized.stack = error.stack;
    const code = (error as { code?: unknown }).code;
    if (typeof code === "number") serialized.code = code;
    return serialized;
  }
  return { name: "Error", message: String(error) };
}

/** Makes a value JSON-safe (drops prototypes, converts BigInts). */
function toPlain<T>(value: T): unknown {
  return JSON.parse(JSON.stringify(value, (_key, item: unknown) => (typeof item === "bigint" ? item.toString() : item)));
}

function timestampToISO(timestamp: { seconds: bigint; nanos: number }): string {
  return new Date(Number(timestamp.seconds) * 1000 + Math.floor(timestamp.nanos / 1e6)).toISOString();
}
