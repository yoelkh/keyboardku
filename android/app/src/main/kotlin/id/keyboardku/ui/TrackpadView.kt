package id.keyboardku.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import id.keyboardku.input.InputSink
import id.keyboardku.input.TrackpadGestureEngine

/**
 * The touch surface. Requests unbuffered dispatch on every finger-down so samples arrive at the
 * digitizer rate (no vsync batching, no 5 ms resampling) and consumes historical samples when a
 * batch does arrive. Never invalidates during a gesture.
 */
class TrackpadView(context: Context, sink: InputSink) : View(context) {
    val engine: TrackpadGestureEngine

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF333333.toInt()
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    var hintText: String = ""
        set(v) { field = v; invalidate() }

    /** When the screen is dimmed the first touch only wakes it. */
    var swallowNextGesture = false
    var onAnyTouch: (() -> Unit)? = null

    init {
        val dm = resources.displayMetrics
        val xdpi = if (dm.xdpi > 60f) dm.xdpi else dm.densityDpi.toFloat()
        val ydpi = if (dm.ydpi > 60f) dm.ydpi else dm.densityDpi.toFloat()
        engine = TrackpadGestureEngine(sink, xdpi / 25.4f, ydpi / 25.4f, ViewConfiguration.get(context).scaledTouchSlop.toFloat())
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = false
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(InputDevice.SOURCE_TOUCHSCREEN)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        onAnyTouch?.invoke()
        val action = e.actionMasked
        if (swallowNextGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowNextGesture = false
            return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                engine.onDown(e.getPointerId(0), e.x, e.y, e.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                engine.onDown(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                val h = e.historySize
                val pc = e.pointerCount
                for (k in 0 until h) {
                    val t = e.getHistoricalEventTime(k)
                    for (i in 0 until pc) engine.onMove(e.getPointerId(i), e.getHistoricalX(i, k), e.getHistoricalY(i, k), t)
                }
                for (i in 0 until pc) engine.onMove(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                engine.onUp(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_UP -> engine.onUp(e.getPointerId(0), e.x, e.y, e.eventTime)
            MotionEvent.ACTION_CANCEL -> engine.onCancel()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(4f, 4f, w - 4f, h - 4f, 24f, 24f, border)
        if (hintText.isNotEmpty()) c.drawText(hintText, w / 2f, h / 2f, hint)
    }
}
