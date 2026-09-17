package id.keyboardku.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import id.keyboardku.input.InputSink
import id.keyboardku.input.KeyboardState
import id.keyboardku.input.Keys

/**
 * Scrollable row of special keys. Normal keys: press on touch-down, release on touch-up (so holding
 * an arrow auto-repeats on the host). Modifier keys: tap = one-shot, long-press = lock.
 * Media keys: consumer usage held while touched.
 */
class SpecialKeysBar(context: Context, private val keyboard: KeyboardState, private val sink: InputSink) : HorizontalScrollView(context) {
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val modButtons = HashMap<Int, TextView>()
    private val dp = resources.displayMetrics.density

    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((24 * dp).toInt())
        clipToPadding = false
        setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (s in Keys.SPECIALS) row.addView(makeKey(s))
        keyboard.listener = { refreshMods() }
    }

    private fun bg(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 8 * dp
        setColor(color)
    }

    private fun makeKey(s: Keys.Special): TextView {
        val tv = TextView(context)
        tv.text = s.label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.background = bg(0xFF1F1F1F.toInt())
        tv.minWidth = (44 * dp).toInt()
        tv.setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins((3 * dp).toInt(), (4 * dp).toInt(), (3 * dp).toInt(), (4 * dp).toInt())
        tv.layoutParams = lp
        tv.isHapticFeedbackEnabled = true

        when {
            s.mod != 0 -> {
                modButtons[s.mod] = tv
                tv.setOnClickListener { keyboard.toggleOneShot(s.mod) }
                tv.setOnLongClickListener { keyboard.toggleLock(s.mod); true }
            }
            s.consumer != 0 -> tv.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.background = bg(0xFF1E88E5.toInt()); sink.consumer(s.consumer) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.background = bg(0xFF1F1F1F.toInt()); sink.consumer(0) }
                }
                true
            }
            else -> tv.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.background = bg(0xFF1E88E5.toInt()); keyboard.press(s.usage, s.mods) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.background = bg(0xFF1F1F1F.toInt()); keyboard.release(s.usage) }
                }
                true
            }
        }
        return tv
    }

    fun refreshMods() {
        for ((mod, tv) in modButtons) {
            tv.background = when {
                keyboard.isLocked(mod) -> bg(0xFFE53935.toInt())
                keyboard.isOneShot(mod) -> bg(0xFF1E88E5.toInt())
                else -> bg(0xFF1F1F1F.toInt())
            }
        }
    }
}
