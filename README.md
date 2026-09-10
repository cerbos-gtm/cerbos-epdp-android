# Cerbos embedded PDP on Android

[![CI](https://github.com/cerbos-gtm/cerbos-epdp-android/actions/workflows/ci.yml/badge.svg)](https://github.com/cerbos-gtm/cerbos-epdp-android/actions/workflows/ci.yml)

A Jetpack Compose demo that runs a [Cerbos](https://www.cerbos.dev) embedded policy decision point
(ePDP) on the device: policies are downloaded from Cerbos Hub, evaluated locally by the Cerbos
WebAssembly engine, kept up to date in the background, and cached for offline starts.

This is the Android counterpart of [cerbos-epdp-ios](https://github.com/cerbos-gtm/cerbos-epdp-ios).
The two share the bridge core (`core.ts`, `hub-bundle-cache.ts`) and the wire protocol; only the
host entry point (`bridge.ts`) differs.

```
app/                   Jetpack Compose demo app (Kotlin, Material 3)
cerbos-embedded-pdp/   Android library: the ePDP engine wrapper (`dev.cerbos.epdp.CerbosEmbeddedPDP`)
CerbosBridge/          Node project that builds the JavaScript bridge and stages the WASM assets
```

## How it works

The Cerbos ePDP has two parts (see the
[Hub docs](https://docs.cerbos.dev/cerbos-hub/deployments-epdp-rules)):

- `@cerbos/embedded-server` ships `server.wasm`, the policy evaluation engine (Go compiled to
  WebAssembly, about 20 MB). It contains no policies.
- `@cerbos/embedded-client` downloads a policy bundle for an *ePDP rule* from Cerbos Hub, loads it
  into the engine, and polls Hub for updates.

The engine is built for a JavaScript host (`GOOS=js`) and the client needs a browser host
(`fetch`, `WebAssembly.compileStreaming`), so on Android both run inside a hidden `WebView`, where
Chromium's V8 gives WebAssembly a JIT. The web view is an implementation detail; the app only ever
talks to `CerbosEmbeddedPDP`.

```
Compose ─▶ CerbosEmbeddedPDP (StateFlow<State>, main-thread confined)
              │  evaluateJavascript(JSON literals)        ▲ @JavascriptInterface (JSON events + results)
              ▼                                           │
           hidden WebView ── https://appassets.androidplatform.net/cerbos-epdp/  (intercepted, app assets)
              ├─ index.html + bridge.js  (@cerbos/embedded-client bundled with esbuild)
              └─ server.wasm            (streamed into WebAssembly.compileStreaming)
                        │
                        └─ Cerbos Hub: BundleService/GetBundle (rule ID, optional credentials)
```

### Wrapper responsibilities

- **No CDN, no network for the runtime.** The bridge script and `server.wasm` are app assets,
  served to the page by `shouldInterceptRequest` with the right MIME type. The WASM is streamed
  straight from the APK into the compiler, never base64-encoded or copied through a string.
- **Offline cold start.** The SDK keeps policies in memory only. The bridge observes the Hub
  `GetBundle` responses, hands each bundle to Kotlin for persistence (`PolicyBundleCache`, in the
  app's no-backup files directory), and replays the last one if the initial download fails. Later
  update checks always go to Hub.
- **Typed, structured bridge.** Requests are `@Serializable` data classes mirroring `@cerbos/core`;
  results, gRPC status codes and validation errors come back as `CerbosException` / `BridgeError`.
  Nothing is string-interpolated into JavaScript: every argument is passed as a JSON literal.
- **Lifecycle handling.** Idempotent `start()`, readiness with timeout, automatic rebuild when the
  WebView renderer process is killed (`onRenderProcessGone`), `reconfigure()` for rule or
  credential changes without recompiling the engine, `stop()`/`restart()` for a full rebuild, and
  `close()` to release everything.
- **JWT verification stays native.** Pass `AuxData.jwt` and supply `Configuration.jwtDecoder`; the
  bridge asks the app to verify and decode the token, the engine only sees the claims.
- **Diagnostics.** Bridge logs, bundle metadata (ID, rule revision, source) and update outcomes are
  observable; in debuggable builds the web view is inspectable from `chrome://inspect`.

## Using `CerbosEmbeddedPDP`

```kotlin
import dev.cerbos.epdp.*
import kotlin.time.Duration.Companion.seconds

val configuration = CerbosEmbeddedPDP.Configuration(
    ruleId = "AVGB9RP6HFBL",
    credentials = CerbosEmbeddedPDP.HubCredentials(clientId, clientSecret), // if the rule requires it
    updateInterval = 60.seconds,
    jwtDecoder = { jwt -> MyAuth.verify(jwt.token) },
)

val pdp = CerbosEmbeddedPDP(context, configuration)
pdp.start()

val response = pdp.checkResources(
    CheckResourcesRequest(
        principal = Principal(id = "alice", roles = listOf("USER"), attributes = attributesOf("tier" to "PREMIUM")),
        resources = listOf(
            ResourceCheck(
                resource = Resource(kind = "resource", id = "1", attributes = attributesOf("ownerId" to "alice")),
                actions = listOf("read", "update"),
            )
        ),
    )
)
response.isAllowed(kind = "resource", id = "1", action = "update")
```

`start()`, `reconfigure()`, `checkHealth()` and the check methods are `suspend` functions that can
be called from any dispatcher. `start()` reports failure through `state.status` rather than by
throwing; checks against a PDP that is not `Ready` throw `CerbosException.NotReady`.
`checkResource`, `isAllowed` and `planResources` are also available. Batch the resources a screen
needs into one `checkResources` call: each call is one round trip to the renderer process.

Observable state: `pdp.state` is a `StateFlow<State>` with `status`, `bundle`, `pendingBundle`,
`server`, `lastPolicyUpdate` and `logs` (collect it with `collectAsStateWithLifecycle()` in
Compose). The same values, plus `isReady` and `configuration`, are available as plain properties on
the instance.

## Building

Requirements: JDK 17 or later and the Android SDK with platform 37 (Android Studio installs it on
first sync). The library supports Android 8.0 (API 26) and later and needs a system WebView with
WebAssembly streaming compilation (the bridge is built for Chrome 80 and later). Node is only
needed to upgrade the Cerbos SDK.

Open the project in Android Studio and run the `app` configuration, or:

```sh
./gradlew :app:installDebug
```

The demo starts with a public demo rule. To use your own rule, open **Cerbos Hub settings** from
the toolbar, enter the rule ID (and client credentials for authenticated rules) and tap **Apply**.
The secret is stored in `SharedPreferences` only as AES-GCM ciphertext; the key lives in the
Android Keystore.

### Upgrading the Cerbos SDK / engine

```sh
cd CerbosBridge
npm install                       # bump @cerbos/* versions in package.json first
npm run typecheck && npm run build  # regenerates cerbos-embedded-pdp/src/main/assets/cerbos-epdp
npm test                          # runs the built bridge in Node against the real WASM and Hub
```

`build.mjs` verifies `server.wasm` against the checksum published in `@cerbos/embedded-server`
and writes `manifest.json` with the versions in use. The generated assets are committed, and marked
as generated in `.gitattributes`, so the Gradle project builds without Node.

### Tests

- `./gradlew testDebugUnitTest`: request/response coding, bridge events, bundle cache, cache keys,
  Hub URL validation, and the demo's request builder (plain JVM, no device needed).
- `cd CerbosBridge && npm test`: end-to-end bridge smoke test: online init, checks, plan, JWT
  callback, structured errors, offline start from the cached bundle, stalled connections, captive
  portals, Hub outages, revoked credentials and superseded initialisations.
- `./gradlew :cerbos-embedded-pdp:connectedDebugAndroidTest`: integration test
  (`EmbeddedPDPIntegrationTest`) on a device or emulator. It starts a real `CerbosEmbeddedPDP`
  against the public demo rule, runs checks and a plan, reconfigures, verifies the bundle was
  cached, restarts with an unreachable Hub URL and asserts it comes up from the cache, then covers
  a reconfigure during loading, `close()` from a coroutine and a rejected Hub URL. Needs network
  access. Pass `-Pandroid.testInstrumentationRunnerArguments.CERBOS_RULE_ID=...` to use another
  public rule (`CERBOS_RULE_ID` in the environment for `npm test`).
- CI (`.github/workflows/ci.yml`) runs all of the above on pushes to `main`, on every pull request,
  and on demand: the bridge on Node 24 (and checks that the committed assets, including
  `manifest.json`, match the build), format/lint/unit tests/APKs on JDK 17, and the integration
  test on an API 35 emulator. Actions are pinned to commit SHAs; Dependabot keeps the npm packages,
  Gradle dependencies and workflow actions current. Formatting is enforced with
  [ktfmt](https://github.com/facebook/ktfmt) (`./gradlew ktfmtCheck`, fix with `ktfmtFormat`).

## Runtime behaviour

- **Offline cache policy.** The last bundle is replayed only when the first download fails for a
  reason that is not the client's fault: no network, DNS failure, a stalled connection (aborted
  after `initialLoadTimeout`, 20 s by default), a Hub 5xx/408/429, or a captive portal answering
  with HTML. A 4xx (disabled rule, unknown rule, bad or revoked credentials) fails `start()`
  instead, so revoking access takes effect on the next cold start.
- **Timeouts.** `start()` completes with `state.status` set to
  `Status.Failed(CerbosException.Timeout)` after `startTimeout` (120 s), and each check throws
  `CerbosException.Timeout` after `requestTimeout` (30 s). `initialLoadTimeout` is clamped to a
  minimum of 1 s. Two fixed timeouts also apply: the bridge page must load within 30 s, and a
  `jwtDecoder` must answer within 30 s or that check fails. Evaluation takes milliseconds; these
  only fire on a stuck engine or a hung network.
- **Deferred activation.** With `activateOnLoad = false`, `bundle` stays on the active bundle and a
  downloaded update appears in `pendingBundle` until `activatePendingBundle()`.
- **Web view hardening.** The page runs under a Content Security Policy that allows only the
  bundled script, WebAssembly compilation, and connections over HTTPS or to `localhost`. The
  WebView has file, content and DOM-storage access disabled, blocks navigation away from the
  bundled page, denies permission requests and never allows mixed content. The engine module is
  compiled once per page so `reconfigure()` and policy reloads never recompile it. In debuggable
  builds the library enables `WebView.setWebContentsDebuggingEnabled(true)`, which is
  process-global and makes every WebView in the app inspectable.
- **Renderer recovery.** A lost renderer process is rebuilt automatically (at most
  `maximumRestarts` per `restartWindow`; the budget resets on `start()` and `reconfigure()`), and a
  check interrupted by that loss is retried once. Calling `checkHealth()` when the app returns to
  the foreground (as the demo does) rebuilds a dead page before the first real check. A
  `reconfigure()` during a start supersedes it, so the new configuration is the one that ends up
  running. Failures are classified as `Engine` (WebAssembly missing or broken), `PolicySource`
  (Hub rejected the request, or is unreachable and there is no cached bundle) or `Initialisation`
  (anything else).
- **Background timers.** The WebView is never attached to a window, so Chromium treats the page as
  hidden and throttles its timers (intensive wake-up throttling). Update polling still happens,
  but not on an exact cadence; the SDK's loader has no "check now". `restart()` forces a fresh
  download at the cost of recompiling the WebAssembly module.
- **One instance per rule.** Each `CerbosEmbeddedPDP` owns a web view and compiles the 20 MB
  engine. Share one instance (for example from a `ViewModel` or your application class) rather
  than creating one per screen, and call `close()` when you are done with it.
- **Cache keys.** The offline cache is keyed by Hub URL (trailing slashes ignored), rule ID and
  scopes; changing any of them starts from an empty cache.
- **Hub URL scheme.** `hubBaseUrl` must use `https`; `http` is accepted only for `localhost` and
  `127.0.0.1`. Other values are rejected at `start()` with `CerbosException.InvalidRequest`
  (reported through `state.status`), before any network access. The page's Content Security Policy
  enforces the same restriction, and Android blocks cleartext traffic unless the app's network
  security configuration allows it for those hosts.
- **APK size.** The engine adds about 20 MB. The demo marks `.wasm` as `noCompress` so the WebView
  can stream it straight from the APK; do the same in your app.
- **CI depends on the public demo rule** and on Hub being reachable from the runners.

## Security notes

- Client credentials in a shipped app can be extracted; prefer public rules with policy filtering
  (resources, actions, roles, scopes) or a backend-for-frontend, as the
  [Hub docs recommend](https://docs.cerbos.dev/cerbos-hub/deployments-epdp-rules#_browser_applications).
- The `@JavascriptInterface` object is only reachable from the bundled page: the WebView never
  loads any other origin, and every request to the private origin is answered from app assets.
- The page never navigates away from its own origin, has DOM storage disabled and HTTP caching
  turned off.
