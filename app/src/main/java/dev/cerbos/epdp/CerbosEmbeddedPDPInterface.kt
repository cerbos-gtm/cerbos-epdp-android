package dev.cerbos.epdp
import android.webkit.JavascriptInterface


class CerbosEmbeddedPDPInterface(
    private val dispatcher: (String, String) -> Unit,
    private val onDecision: (String) -> Unit,
    private val pdpReadyHandler: () -> Unit,
    private val sdkLoadedHandler: () -> Unit
) {
    @JavascriptInterface
    fun postResponse(id: String, result: String) {
        dispatcher(id, result)
    }

    @JavascriptInterface
    fun onDecision(log: String) {
        onDecision(log)
    }

    @JavascriptInterface
    fun notifyPDPReady() {
        pdpReadyHandler()
    }

    @JavascriptInterface
    fun notifySDKLoaded() {
        sdkLoadedHandler()
    }
}