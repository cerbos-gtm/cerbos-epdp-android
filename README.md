# Cerbos embedded PDP on Android

[![CI](https://github.com/cerbos-gtm/cerbos-epdp-android/actions/workflows/ci.yml/badge.svg)](https://github.com/cerbos-gtm/cerbos-epdp-android/actions/workflows/ci.yml)

Run [Cerbos](https://www.cerbos.dev) authorization checks on the device. Policies come from a
[Cerbos Hub embedded PDP rule](https://docs.cerbos.dev/cerbos-hub/deployments-epdp-rules), stay up
to date in the background, and are cached so the app also starts offline. A decision takes about a
millisecond and never touches the network.

```
cerbos-embedded-pdp/   Android library: dev.cerbos.epdp.CerbosEmbeddedPDP
app/                   Jetpack Compose demo app
CerbosBridge/          Builds the JavaScript bridge and the engine assets (only needed to upgrade Cerbos)
```

The iOS version is [cerbos-epdp-ios](https://github.com/cerbos-gtm/cerbos-epdp-ios).

## Run the demo

You need Android Studio (or JDK 17+ and the Android SDK with platform 37) and a device or emulator
running Android 8.0 or later.

```sh
./gradlew :app:installDebug
```

The app starts with a public demo rule. Pick a role, toggle ownership and tap **Check access** to
see decisions for each action. To use your own rule, tap the settings icon, enter the rule ID (plus
client credentials if the rule requires them) and tap **Apply**.

## Use it in your app

1. Add the `cerbos-embedded-pdp` module to your project and depend on it:

   ```kotlin
   implementation(project(":cerbos-embedded-pdp"))
   ```

2. Keep the 20 MB engine uncompressed in the APK so it can be streamed into the compiler:

   ```kotlin
   android { androidResources { noCompress += "wasm" } }
   ```

3. Create one PDP, start it, and check:

   ```kotlin
   import dev.cerbos.epdp.*

   val pdp = CerbosEmbeddedPDP(context, CerbosEmbeddedPDP.Configuration(ruleId = "AVGB9RP6HFBL"))
   pdp.start()

   val response = pdp.checkResources(
       CheckResourcesRequest(
           principal = Principal(id = "alice", roles = listOf("USER")),
           resources = listOf(
               ResourceCheck(
                   resource = Resource(kind = "document", id = "1", attributes = attributesOf("ownerId" to "alice")),
                   actions = listOf("read", "update"),
               ),
           ),
       ),
   )
   response.isAllowed(kind = "document", id = "1", action = "update")
   ```

Tips:

- **Share one instance** (for example from a `ViewModel` or your `Application`) and call `close()`
  when you're done. Each instance runs its own copy of the engine.
- **Batch checks.** Put every resource a screen needs into one `checkResources` call.
- **Watch `pdp.state`**, a `StateFlow` with the status, the active bundle and recent logs. In Compose
  use `collectAsStateWithLifecycle()`.
- **Call `checkHealth()` on resume.** Android can kill the engine's process in the background; this
  rebuilds it before your next check.
- `start()` doesn't throw: failures show up as `Status.Failed` in `state`. Checks throw
  `CerbosException`, for example `NotReady` before start.

`isAllowed`, `checkResource` and `planResources` are also available. See `Configuration` for update
intervals, scopes, credentials, timeouts, JWT decoding and decision logging.

## How it works

The Cerbos engine is Go compiled to WebAssembly and needs a JavaScript host. The library runs it in a
hidden `WebView` that is never attached to the screen:

```
Your code ─▶ CerbosEmbeddedPDP ─ evaluateJavascript ─▶ hidden WebView
                  ▲                                     ├─ bridge.js (@cerbos/embedded-client)
                  └──── @JavascriptInterface ◀──────────┤  server.wasm (the engine)
                                                        └─▶ Cerbos Hub (policy bundle downloads)
```

- The page, `bridge.js` and `server.wasm` are app assets. Nothing is loaded from a CDN.
- Each bundle downloaded from Hub is saved to the app's no-backup files directory. If the first
  download on a later start fails because of the network or a Hub outage, the saved bundle is used.
  A 4xx from Hub (disabled rule, revoked credentials) is never masked by the cache.
- Results are decoded off the main thread, so a busy UI doesn't slow them down. A warm-up check runs
  during `start()`, so your first decision doesn't pay the engine's warm-up cost.
- If Android kills the web view's renderer, the PDP rebuilds it and retries the interrupted check
  once.

## Security

- Client credentials shipped in an app can be extracted. Prefer public rules that only expose the
  policies the app needs, or fetch bundles through your own backend, as the
  [Hub docs recommend](https://docs.cerbos.dev/cerbos-hub/deployments-epdp-rules#_browser_applications).
- `hubBaseUrl` must be `https` (`http` only for `localhost`).
- The web view can only load its bundled page, runs under a strict Content Security Policy, has file,
  content and DOM storage access disabled, and denies all permission requests.
- In debuggable builds the library turns on `WebView.setWebContentsDebuggingEnabled`, so you can
  inspect the engine from `chrome://inspect`. That setting applies to every WebView in the app.
- The demo stores the client secret encrypted with an Android Keystore key.

## Development

### Tests

```sh
./gradlew ktfmtCheck testDebugUnitTest                  # formatting and JVM unit tests
./gradlew :cerbos-embedded-pdp:connectedDebugAndroidTest  # integration test on a device (needs network)
cd CerbosBridge && npm ci && npm test                    # the bridge against the real engine and Hub
```

Set `CERBOS_RULE_ID` (or `-Pandroid.testInstrumentationRunnerArguments.CERBOS_RULE_ID=...`) to test
against another public rule. CI runs all three.

### Measuring decision latency

```sh
./gradlew :cerbos-embedded-pdp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cerbos.epdp.DecisionLatencyBenchmark
adb logcat -d -s DecisionLatency
cd CerbosBridge && node test/bench.mjs                   # the JavaScript side on its own
```

### Upgrading Cerbos

```sh
cd CerbosBridge
npm install @cerbos/embedded-client@latest @cerbos/embedded-server@latest  # and the other @cerbos packages
npm run typecheck && npm run build && npm test
```

`npm run build` checks `server.wasm` against its published checksum and writes the assets into
`cerbos-embedded-pdp/src/main/assets/cerbos-epdp`. Commit them: the Android build doesn't need Node.
