package id.keyboardku.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.PowerManager
import android.view.WindowManager
import id.keyboardku.Log

/**
 * Dims the window after N seconds without input (the display is the biggest battery consumer) and
 * restores it on the next touch, which is swallowed. Optional pocket mode turns the screen off with
 * the proximity sensor (PROXIMITY_SCREEN_OFF_WAKE_LOCK), the same mechanism dialer apps use.
 */
class IdleDimmer(private val activity: Activity, private val main: Handler) {
    var dimAfterMs: Long = 20_000
    var onDimmed: (() -> Unit)? = null
    var onWoken: (() -> Unit)? = null
    var dimmed = false
        private set

    private val dimRunnable = Runnable { dim() }
    private var proximityLock: PowerManager.WakeLock? = null

    fun activity() {
        if (dimmed) wake()
        main.removeCallbacks(dimRunnable)
        if (dimAfterMs > 0) main.postDelayed(dimRunnable, dimAfterMs)
    }

    fun pause() {
        main.removeCallbacks(dimRunnable)
        if (dimmed) wake()
        setPocketMode(false)
    }

    private fun dim() {
        if (dimmed) return
        dimmed = true
        val lp = activity.window.attributes
        lp.screenBrightness = 0.02f
        activity.window.attributes = lp
        onDimmed?.invoke()
    }

    private fun wake() {
        dimmed = false
        val lp = activity.window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp
        onWoken?.invoke()
    }

    fun setPocketMode(enabled: Boolean) {
        if (enabled) {
            if (proximityLock != null) return
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "KeyboardKu:pocket").also {
                try { it.acquire() } catch (e: Exception) { Log.w("proximity lock failed: $e") }
            }
        } else {
            proximityLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
            proximityLock = null
        }
    }
}
