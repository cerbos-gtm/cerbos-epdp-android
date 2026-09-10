package dev.cerbos.epdp

import android.util.Log

internal object CerbosLog {
    private const val TAG = "CerbosEmbeddedPDP"

    fun debug(message: String) {
        Log.d(TAG, message)
    }

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
