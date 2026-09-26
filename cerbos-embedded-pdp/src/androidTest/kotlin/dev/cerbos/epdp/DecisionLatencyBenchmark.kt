package dev.cerbos.epdp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures how long a decision takes to come back to the main thread, which is what a UI waits on.
 *
 * Results go to logcat:
 * ```
 * ./gradlew :cerbos-embedded-pdp:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.cerbos.epdp.DecisionLatencyBenchmark
 * adb logcat -d -s DecisionLatency
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DecisionLatencyBenchmark {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ruleId = arguments.getString("CERBOS_RULE_ID") ?: "AVGB9RP6HFBL"

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
                        actions = listOf("read", "create", "update", "delete", "publish"),
                    )
                ),
            includeMetadata = true,
        )

    @Test
    fun checkResourcesLatency() = runBlocking {
        val cache = PolicyBundleCache(File(context.cacheDir, "bench-${UUID.randomUUID()}"))
        val pdp =
            CerbosEmbeddedPDP(
                context,
                CerbosEmbeddedPDP.Configuration(ruleId = ruleId, updateInterval = null),
                cache,
            )
        try {
            pdp.start()
            check(pdp.isReady) { "PDP did not start: ${pdp.status}" }
            withContext(Dispatchers.Main) {
                val first = System.nanoTime()
                pdp.checkResources(request)
                Log.i(
                    "DecisionLatency",
                    "first check: %.2f ms".format((System.nanoTime() - first) / 1e6),
                )
                repeat(100) { pdp.checkResources(request) }
                report("idle main thread", measure(pdp))
                // A busy UI: every frame spends 10 ms of main-thread work.
                val handler = Handler(Looper.getMainLooper())
                var busy = true
                val frame =
                    object : Runnable {
                        override fun run() {
                            val end = SystemClock.uptimeMillis() + 10
                            while (SystemClock.uptimeMillis() < end) Unit
                            if (busy) handler.postDelayed(this, 16)
                        }
                    }
                handler.post(frame)
                report("busy main thread", measure(pdp))
                busy = false
            }
        } finally {
            pdp.close()
            cache.removeAll()
        }
    }

    private suspend fun measure(pdp: CerbosEmbeddedPDP, iterations: Int = 300): DoubleArray {
        val samples = DoubleArray(iterations)
        for (i in 0 until iterations) {
            val started = System.nanoTime()
            pdp.checkResources(request)
            samples[i] = (System.nanoTime() - started) / 1e6
        }
        samples.sort()
        return samples
    }

    private fun report(label: String, sorted: DoubleArray) {
        fun p(q: Double) = "%.2f".format(sorted[((sorted.size - 1) * q).toInt()])
        Log.i("DecisionLatency", "$label: p50 ${p(0.5)} ms, p90 ${p(0.9)} ms, p99 ${p(0.99)} ms")
    }
}
