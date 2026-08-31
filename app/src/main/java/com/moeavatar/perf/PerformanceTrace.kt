package com.moeavatar.perf

import android.util.Log

/** Central switch for developer-only performance traces. */
object PerformanceTrace {
    @Volatile var enabled: Boolean = false

    fun i(stage: String, message: String) {
        if (enabled) Log.i(TAG, "stage=$stage $message")
    }

    private const val TAG = "MoeAvatar.Perf"
}
