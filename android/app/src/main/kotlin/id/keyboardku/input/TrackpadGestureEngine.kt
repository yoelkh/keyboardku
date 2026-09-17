package id.keyboardku.input

import kotlin.math.hypot

/**
 * Pure-Kotlin trackpad gesture recogniser + pointer acceleration.
 *
 * Gestures: 1 finger move; tap = left click; tap-then-touch-and-move = drag (button held);
 * 2 fingers move = scroll; 2-finger tap = right click; 3-finger tap = middle click.
 * Acceleration follows libinput's adaptive profile: measured in mm/s, precision gain below a low
 * threshold, 1:1 mid band, linear ramp above. Fractional output is carried by the sink.
 */
class TrackpadGestureEngine(
    private val sink: InputSink,
    private val pxPerMmX: Float,
    private val pxPerMmY: Float,
    private val touchSlopPx: Float,
) {
    // ---- tunables (set from Prefs) ----
    var pointerSpeed = 1.0f      // 0.5 .. 3
    var pointerAccel = 0.6f      // 0 .. 1
    var scrollSpeed = 1.0f
    var naturalScroll = false
    var tapToClick = true
    /** When true the engine only reports clutch/click/scroll and never moves the pointer (air-mouse mode). */
    var airMouseMode = false
    var clutchListener: ((Boolean) -> Unit)? = null
    var buttonsListener: ((Int) -> Unit)? = null

    companion object {
        const val TAP_MAX_MS = 180L
        const val DRAG_WINDOW_MS = 250L
        const val BASE_PX_PER_MM = 9f      // host pixels per finger millimetre at gain 1
        const val V_LOW = 25f              // mm/s: below this the gain drops to PRECISION_GAIN
        const val V_HIGH = 250f            // mm/s: gain reaches its maximum
        const val PRECISION_GAIN = 0.35f
        const val MAX_GAIN = 3.0f
        const val SCROLL_UNITS_PER_MM = 30f // 1/120 units per mm: 4 mm per notch
    }

    private enum class S { IDLE, ONE_DOWN, MOVING, TWO_DOWN, SCROLLING, THREE_DOWN, DRAGGING, MULTI_DEAD }

    private var s = S.IDLE
    private var buttons = 0
    private var pointerCount = 0

    // primary finger tracking
    private var p0Id = -1
    private var p0X = 0f
    private var p0Y = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var downT = 0L
    private var movedPx = 0f
    private var lastTapUpT = -1L
    private var lastTapButton = 0

    // secondary finger (scroll centroid)
    private var p1Id = -1
    private var cX = 0f
    private var cY = 0f

    // velocity estimate (mm/s, EMA)
    private var vEst = 0f

    private fun buttonsChanged() {
        sink.buttons(buttons)
        buttonsListener?.invoke(buttons)
    }

    fun onDown(pointerId: Int, x: Float, y: Float, t: Long) {
        pointerCount++
        when (s) {
            S.IDLE -> {
                p0Id = pointerId; p0X = x; p0Y = y; lastX = x; lastY = y; lastT = t; downT = t; movedPx = 0f; vEst = 0f
                // tap-then-touch within the drag window -> hold the button from now on
                if (tapToClick && lastTapUpT >= 0 && t - lastTapUpT <= DRAG_WINDOW_MS && lastTapButton == Keys.BTN_LEFT) {
                    buttons = buttons or Keys.BTN_LEFT
                    buttonsChanged()
                    s = S.DRAGGING
                } else {
                    s = S.ONE_DOWN
                }
                lastTapUpT = -1
                if (airMouseMode) clutchListener?.invoke(true)
            }
            S.ONE_DOWN, S.MOVING -> {
                p1Id = pointerId
                p1X = x; p1Y = y
                cX = (lastX + x) / 2f; cY = (lastY + y) / 2f
                s = if (s == S.MOVING && movedPx > touchSlopPx * 3) S.SCROLLING else S.TWO_DOWN
            }
            S.TWO_DOWN, S.SCROLLING -> s = S.THREE_DOWN
            S.DRAGGING -> { /* second finger during drag: ignore */ }
            S.THREE_DOWN, S.MULTI_DEAD -> s = S.MULTI_DEAD
        }
    }

    fun onMove(pointerId: Int, x: Float, y: Float, t: Long) {
        when (s) {
            S.ONE_DOWN, S.MOVING, S.DRAGGING -> {
                if (pointerId != p0Id) return
                val dx = x - lastX
                val dy = y - lastY
                lastX = x; lastY = y
                movedPx += hypot(dx, dy)
                if (s == S.ONE_DOWN) {
                    if (movedPx > touchSlopPx) s = S.MOVING else { lastT = t; return }
                }
                emitMove(dx, dy, t)
            }
            S.TWO_DOWN, S.SCROLLING -> {
                // centroid-based scrolling: track whichever pointer moved, average both
                val prevCX = cX; val prevCY = cY
                if (pointerId == p0Id) { lastX = x; lastY = y }
                else if (pointerId == p1Id) { p1X = x; p1Y = y }
                else return
                if (p1X.isNaN()) return
                cX = (lastX + p1X) / 2f; cY = (lastY + p1Y) / 2f
                val dx = cX - prevCX; val dy = cY - prevCY
                movedPx += hypot(dx, dy)
                if (s == S.TWO_DOWN) {
                    if (movedPx > touchSlopPx) s = S.SCROLLING else return
                }
                val mmY = dy / pxPerMmY
                val mmX = dx / pxPerMmX
                val sign = if (naturalScroll) 1f else -1f
                sink.scroll(sign * mmY * SCROLL_UNITS_PER_MM * scrollSpeed, -sign * mmX * SCROLL_UNITS_PER_MM * scrollSpeed)
            }
            else -> {}
        }
    }

    private var p1X = Float.NaN
    private var p1Y = Float.NaN

    fun onUp(pointerId: Int, x: Float, y: Float, t: Long) {
        pointerCount = (pointerCount - 1).coerceAtLeast(0)
        when (s) {
            S.ONE_DOWN -> {
                if (pointerId == p0Id) {
                    if (tapToClick && t - downT <= TAP_MAX_MS) click(Keys.BTN_LEFT, t)
                    finish()
                }
            }
            S.MOVING -> if (pointerId == p0Id) finish()
            S.DRAGGING -> if (pointerId == p0Id) {
                buttons = buttons and Keys.BTN_LEFT.inv()
                buttonsChanged()
                finish()
            }
            S.TWO_DOWN -> {
                if (tapToClick && t - downT <= TAP_MAX_MS + 60) click(Keys.BTN_RIGHT, t)
                s = S.MULTI_DEAD
                if (pointerCount == 0) finish()
            }
            S.SCROLLING -> { s = S.MULTI_DEAD; if (pointerCount == 0) finish() }
            S.THREE_DOWN -> {
                if (tapToClick && t - downT <= TAP_MAX_MS + 100) click(Keys.BTN_MIDDLE, t)
                s = S.MULTI_DEAD
                if (pointerCount == 0) finish()
            }
            S.MULTI_DEAD -> if (pointerCount == 0) finish()
            S.IDLE -> {}
        }
    }

    fun onCancel() {
        pointerCount = 0
        if (buttons != 0) { buttons = 0; buttonsChanged() }
        finish()
    }

    private fun finish() {
        s = S.IDLE
        pointerCount = 0
        p0Id = -1; p1Id = -1
        p1X = Float.NaN; p1Y = Float.NaN
        if (airMouseMode) clutchListener?.invoke(false)
    }

    private fun click(button: Int, t: Long) {
        buttons = buttons or button
        buttonsChanged()
        buttons = buttons and button.inv()
        buttonsChanged()
        lastTapUpT = t
        lastTapButton = button
    }

    /** Physical L/R buttons under the pad: hold = drag with the other hand. */
    fun physicalButton(button: Int, down: Boolean) {
        buttons = if (down) buttons or button else buttons and button.inv()
        buttonsChanged()
    }

    private fun emitMove(dxPx: Float, dyPx: Float, t: Long) {
        if (airMouseMode) { lastT = t; return }
        val mmX = dxPx / pxPerMmX
        val mmY = dyPx / pxPerMmY
        val dtMs = (t - lastT).coerceAtLeast(1L)
        lastT = t
        val dist = hypot(mmX, mmY)
        val v = dist / dtMs * 1000f              // mm/s for this sample
        vEst += (v - vEst) * 0.5f                 // light smoothing across samples
        val g = gain(vEst)
        val k = BASE_PX_PER_MM * pointerSpeed * g
        sink.move(mmX * k, mmY * k)
    }

    /** libinput-like adaptive gain as a function of finger speed (mm/s). */
    fun gain(v: Float): Float = when {
        v < V_LOW -> PRECISION_GAIN + (1f - PRECISION_GAIN) * (v / V_LOW)
        v < V_HIGH -> 1f + (MAX_GAIN - 1f) * pointerAccel * ((v - V_LOW) / (V_HIGH - V_LOW))
        else -> 1f + (MAX_GAIN - 1f) * pointerAccel
    }

    val isDragging: Boolean get() = buttons != 0
    val currentButtons: Int get() = buttons
}
