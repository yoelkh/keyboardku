package id.keyboardku

import android.util.Log as ALog

/** Tiny logging wrapper with a single tag so `adb logcat KbKu:V *:S` shows everything. */
object Log {
    const val TAG = "KbKu"

    @JvmStatic fun d(msg: String) { ALog.d(TAG, msg) }
    @JvmStatic fun i(msg: String) { ALog.i(TAG, msg) }
    @JvmStatic fun w(msg: String) { ALog.w(TAG, msg) }
    @JvmStatic fun e(msg: String, t: Throwable? = null) { if (t != null) ALog.e(TAG, msg, t) else ALog.e(TAG, msg) }
}
