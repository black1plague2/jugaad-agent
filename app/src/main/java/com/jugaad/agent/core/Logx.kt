package com.jugaad.agent.core

import android.util.Log

/**
 * Namespaced logging. The `JUGAAD` tag prefix makes the demo's
 * `adb logcat -s JUGAAD:* ExecuTorch:* Qnn:*` filter trivial.
 */
object Logx {
    private const val TAG = "JUGAAD"
    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
