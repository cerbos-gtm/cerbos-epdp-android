package dev.cerbos.epdp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the whole stack on a device or emulator: the hidden web view, the request interceptor
 * streaming `server.wasm` into Chromium, the JavaScript bridge, a real policy bundle from Cerbos
 * Hub, decisions, and the offline replay of the cached bundle.
 *
 * Requires network access to `api.cerbos.cloud`. Override the rule with the `CERBOS_RULE_ID`
 * instrumentation argument (`-Pandroid.testInstrumentationRunnerArguments.CERBOS_RULE_ID=...`).
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedPDPIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ruleId =
        InstrumentationRegistry.getArguments().getString("CERBOS_RULE_ID") ?: "AVGB9RP6HFBL"

    private fun temporaryDirectory(): File =
        File(context.cacheDir, "cerbos-epdp-tests-${UUID.randomUUID()}")

    private val request =
        CheckResourcesRequest(
            principal =
                Principal(
                    id = "alice@example.com",
                    roles = listOf("USER"),
                    attributes = attributesOf("tier" to "PREMIUM"),
                ),
            resources =
                listOf(
                    ResourceCheck(
                        resource =
                            Resource(
                                "resource",
                                "1",
                                attributesOf("ownerId" to "alice@example.com"),
                            ),
                        actions = listOf("read", "update"),
                    ),
                    ResourceCheck(
                        resource =
                            Resource(
                                "resource",
                                "2",
                                attributesOf("ownerId" to "someone-else@example.com"),
                            ),
                        actions = listOf("read"),
                    ),
                ),
            includeMetadata = true,
        )

    @Test
    fun onlineStartThenOfflineReplay() = runBlocking {
        withTimeout(5.minutes) {
            val cacheDirectory = temporaryDirectory()
            try {
                val cache = PolicyBundleCache(cacheDirectory)
                val configuration =
                    CerbosEmbeddedPDP.Configuration(ruleId = ruleId, updateInterval = null)

                // 1. Online start: WASM compiled by Chromium, bundle downloaded from Cerbos Hub.
                val online = CerbosEmbeddedPDP(context, configuration, cache)
                online.start()
                assertEquals(
                    "online start failed: ${online.state.value.status}; logs: ${online.state.value.logs.map { it.message }}",
                    CerbosEmbeddedPDP.Status.Ready,
                    online.state.value.status,
                )
                val onlineBundle = checkNotNull(online.state.value.bundle)
                assertEquals(BundleSource.NETWORK, onlineBundle.source)
                assertTrue(onlineBundle.bundleId.isNotEmpty())
                assertTrue(online.state.value.server?.version?.isNotEmpty() == true)

                // 2. Decisions are evaluated locally and come back well-formed.
                val response = online.checkResources(request)
                assertEquals(2, response.results.size)
                assertEquals(listOf("1", "2"), response.results.map { it.resource.id })
                assertEquals(setOf("read", "update"), response.results[0].actions.keys)
                assertNotNull("includeMetadata was requested", response.results[0].metadata)

                val allowed =
                    online.isAllowed(
                        principal = request.principal,
                        resource = request.resources[0].resource,
                        action = "read",
                    )
                assertEquals(response.results[0].isAllowed("read"), allowed)

                val plan =
                    online.planResources(
                        PlanResourcesRequest(
                            principal = request.principal,
                            resource = PlanResource(kind = "resource"),
                            action = "read",
                        )
                    )
                assertTrue(plan.kind in PlanKind.entries)

                // 2b. Reconfiguring reuses the compiled engine (fast) and the health check passes.
                val reconfigureStart = TimeSource.Monotonic.markNow()
                online.reconfigure(configuration.copy(scopes = emptyList()))
                assertEquals(
                    "reconfigure failed: ${online.state.value.status}",
                    CerbosEmbeddedPDP.Status.Ready,
                    online.state.value.status,
                )
                assertEquals(BundleSource.NETWORK, online.state.value.bundle?.source)
                assertTrue(reconfigureStart.elapsedNow() < 60.seconds)
                assertTrue(online.checkHealth())

                // 3. The bundle was persisted for offline starts (the cache write is asynchronous).
                val onlineKey = CerbosEmbeddedPDP.offlineCacheKey(online.configuration)
                var cached = cache.load(onlineKey)
                repeat(50) {
                    if (cached == null) {
                        delay(200)
                        cached = cache.load(onlineKey)
                    }
                }
                val cachedEntry =
                    checkNotNull(cached) { "policy bundle was not written to the offline cache" }
                assertEquals(onlineBundle.bundleId, cachedEntry.entry.bundleId)
                online.stop()
                online.close()

                // 4. Offline start: Hub is unreachable, the cached bundle is replayed.
                val offlineConfiguration = configuration.copy(hubBaseUrl = "http://127.0.0.1:9")
                val offline = CerbosEmbeddedPDP(context, offlineConfiguration, cache)
                // The cache key includes the Hub URL; seed the entry for the unreachable one.
                cache.save(
                    key = CerbosEmbeddedPDP.offlineCacheKey(offline.configuration),
                    body = cachedEntry.body,
                    bundleId = cachedEntry.entry.bundleId,
                    ruleRevision = cachedEntry.entry.ruleRevision,
                )
                offline.start()
                assertEquals(
                    "offline start failed: ${offline.state.value.status}; logs: ${offline.state.value.logs.map { it.message }}",
                    CerbosEmbeddedPDP.Status.Ready,
                    offline.state.value.status,
                )
                assertEquals(BundleSource.CACHE, offline.state.value.bundle?.source)
                assertEquals(onlineBundle.bundleId, offline.state.value.bundle?.bundleId)
                val offlineResponse = offline.checkResources(request)
                assertEquals(
                    response.results.map { it.actions },
                    offlineResponse.results.map { it.actions },
                )
                offline.stop()
                offline.close()

                // 5. Offline start without a cached bundle fails cleanly instead of hanging, and is
                //    classified as a policy-source problem (Hub unreachable), not a bridge bug.
                val uncached =
                    CerbosEmbeddedPDP(
                        context,
                        offlineConfiguration,
                        PolicyBundleCache(temporaryDirectory()),
                    )
                uncached.start()
                val status = uncached.state.value.status
                assertTrue(
                    "expected the uncached offline start to fail, got $status",
                    status is CerbosEmbeddedPDP.Status.Failed,
                )
                val failure = (status as CerbosEmbeddedPDP.Status.Failed).error
                assertFalse(failure.message.isNullOrEmpty())
                assertTrue(
                    "expected PolicySource, got $failure",
                    failure is CerbosException.PolicySource,
                )
                assertThrows(CerbosException.NotReady::class.java) {
                    runBlocking { uncached.checkResources(request) }
                }
                uncached.close()

                // 6. A reconfigure while a start is still loading supersedes it: the PDP ends up
                //    ready under the new configuration, not the old one.
                val superseded = CerbosEmbeddedPDP(context, offlineConfiguration, cache)
                val firstStart = launch { superseded.start() }
                withTimeout(10.seconds) {
                    superseded.state.first { it.status == CerbosEmbeddedPDP.Status.Loading }
                }
                superseded.reconfigure(configuration)
                firstStart.join()
                assertEquals(
                    "reconfigure during loading failed: ${superseded.state.value.status}; logs: ${superseded.state.value.logs.map { it.message }}",
                    CerbosEmbeddedPDP.Status.Ready,
                    superseded.state.value.status,
                )
                assertEquals(configuration, superseded.configuration)
                assertEquals(BundleSource.NETWORK, superseded.state.value.bundle?.source)
                assertEquals(2, superseded.checkResources(request).results.size)

                // 7. close() from inside a coroutine still tears the web view down.
                superseded.close()
                withTimeout(10.seconds) {
                    superseded.state.first { it.status == CerbosEmbeddedPDP.Status.Idle }
                }

                // 8. A non-HTTPS Hub URL is rejected up front with a configuration error.
                val insecure =
                    CerbosEmbeddedPDP(
                        context,
                        configuration.copy(hubBaseUrl = "http://10.0.2.2:3592"),
                    )
                insecure.start()
                assertTrue(
                    "expected InvalidRequest, got ${insecure.state.value.status}",
                    (insecure.state.value.status as? CerbosEmbeddedPDP.Status.Failed)?.error
                        is CerbosException.InvalidRequest,
                )
                insecure.close()
            } finally {
                cacheDirectory.deleteRecursively()
            }
        }
    }
}
