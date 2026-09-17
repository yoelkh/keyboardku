package id.keyboardku.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Two-line status strip: transport / state / RTT on the left, action buttons on the right. */
class StatusBar(context: Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val title = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val sub = TextView(context).apply { setTextColor(0xFF9E9E9E.toInt()); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }

    var onKeyboard: (() -> Unit)? = null
    var onAirMouse: (() -> Unit)? = null
    var onAirMouseLong: (() -> Unit)? = null
    var onAction: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null

    private val btnKeyboard = makeButton("⌨") { onKeyboard?.invoke() }
    private val btnAir = makeButton("↗") { onAirMouse?.invoke() }.also { it.setOnLongClickListener { onAirMouseLong?.invoke(); true } }
    private val btnAction = makeButton("Pair") { onAction?.invoke() }
    private val btnSettings = makeButton("⚙") { onSettings?.invoke() }

    private var rtt = -1f
    private var unsent = 0
    private var stateText = ""
    private var detailText: String? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(title)
        texts.addView(sub)
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(btnKeyboard)
        buttons.addView(btnAir)
        buttons.addView(btnAction)
        buttons.addView(btnSettings)
        addView(buttons, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setStatus("KeyboardKu", "", null)
    }

    private fun makeButton(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(context)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        tv.gravity = Gravity.CENTER
        tv.background = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(0xFF1F1F1F.toInt()) }
        tv.minWidth = (40 * dp).toInt()
        tv.setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        return tv
    }

    fun setStatus(transport: String, state: String, detail: String?) {
        stateText = if (state.isEmpty()) transport else "$transport · $state"
        detailText = detail
        render()
    }

    fun setRtt(ms: Float) { rtt = ms; render() }
    fun setUnsent(n: Int) { unsent = n; render() }
    fun setActionLabel(label: String) { btnAction.text = label }
    fun setAirMouseActive(active: Boolean) {
        btnAir.background = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(if (active) 0xFF1E88E5.toInt() else 0xFF1F1F1F.toInt()) }
    }
    fun setConnected(connected: Boolean) {
        title.setTextColor(if (connected) 0xFF66BB6A.toInt() else Color.WHITE)

    }

    private fun render() {
        title.text = stateText
        val parts = ArrayList<String>(3)
        detailText?.let { if (it.isNotEmpty()) parts.add(it) }
        if (rtt >= 0) parts.add("RTT ${"%.0f".format(rtt)} ms")
        if (unsent > 0) parts.add("$unsent karakter tidak terkirim (BT)")
        sub.text = parts.joinToString(" · ")
        sub.visibility = if (parts.isEmpty()) GONE else VISIBLE
    }
}
