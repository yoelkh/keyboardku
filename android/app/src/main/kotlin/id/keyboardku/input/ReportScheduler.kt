package id.keyboardku.input

import id.keyboardku.transport.Transport
import kotlin.math.roundToInt

/**
 * Main-thread accumulator + throttle-with-trailing-flush in front of a [Transport].
 *
 * Motion arriving faster than the transport's minimum interval is coalesced into one report;
 * the last piece of a gesture is always flushed (trailing timer). Discrete events (buttons, keys,
 * text) flush pending motion first so ordering is preserved exactly. Fractional remainders are carried.
 *
 * `Clock` and `Timer` are abstracted so the class is unit-testable without Android.
 */
class ReportScheduler(
    private val clock: () -> Long = System::nanoTime,
    private val timer: Timer,
) : InputSink {

    private companion object { const val NEVER = Long.MIN_VALUE / 2 }

    interface Timer {
        /** Run [r] after [delayMs] on the main thread; a second call replaces the pending one. */
        fun schedule(r: Runnable, delayMs: Long)
        fun cancel(r: Runnable)
    }

    var transport: Transport? = null
        set(value) {
            field = value
            accX = 0f; accY = 0f; accV = 0f; accH = 0f
            lastSendNs = NEVER
        }

    /** Chars that the current transport could not type (BT HID + non-ASCII). */
    var unsentChars = 0
        private set
    var onUnsentChanged: (() -> Unit)? = null

    private var accX = 0f
    private var accY = 0f
    private var accV = 0f
    private var accH = 0f
    private var lastSendNs = NEVER
    private var scheduled = false
    private val flushRunnable = Runnable { scheduled = false; flush() }

    private var activeUntilNs = 0L
    private val idleRunnable = Runnable { transport?.setActive(false) }

    override fun move(dx: Float, dy: Float) {
        accX += dx
        accY += dy
        touchActivity()
        requestFlush()
    }

    override fun scroll(v120: Float, h120: Float) {
        accV += v120
        accH += h120
        touchActivity()
        requestFlush()
    }

    private fun touchActivity() {
        val now = clock()
        if (now > activeUntilNs) transport?.setActive(true)
        activeUntilNs = now + 3_000_000_000L
        timer.schedule(idleRunnable, 3_000)
    }

    private fun requestFlush() {
        val t = transport ?: return
        val now = clock()
        val interval = t.minMoveIntervalNanos
        if (now - lastSendNs >= interval) {
            flush()
        } else if (!scheduled) {
            scheduled = true
            val delayMs = ((lastSendNs + interval - now) / 1_000_000L).coerceAtLeast(1L)
            timer.schedule(flushRunnable, delayMs)
        }
    }

    /** Send whatever motion is pending. Returns true if nothing remains. */
    fun flush(): Boolean {
        val t = transport ?: run { accX = 0f; accY = 0f; accV = 0f; accH = 0f; return true }
        var pending = false
        val ix = accX.roundToInt()
        val iy = accY.roundToInt()
        if (ix != 0 || iy != 0) {
            if (t.sendMouse(ix, iy)) {
                accX -= ix
                accY -= iy
                lastSendNs = clock()
            } else {
                pending = true
            }
        }
        val iv = accV.roundToInt()
        val ih = accH.roundToInt()
        if (iv != 0 || ih != 0) {
            if (t.sendScroll(iv, ih)) {
                accV -= iv
                accH -= ih
            } else {
                pending = true
            }
        }
        if (pending && !scheduled) {
            scheduled = true
            timer.schedule(flushRunnable, (t.minMoveIntervalNanos / 1_000_000L).coerceAtLeast(1L))
        }
        return !pending
    }

    override fun buttons(mask: Int) {
        touchActivity()
        flush()
        transport?.sendButtons(mask)
    }

    override fun keyState(mods: Int, keys: ByteArray) {
        touchActivity()
        flush()
        transport?.sendKeys(mods, keys)
    }

    override fun text(cs: CharSequence) {
        val t = transport ?: return
        touchActivity()
        flush()
        var i = 0
        val n = cs.length
        while (i < n) {
            val c = cs[i]
            val usage = HidKeymap.usageFor(c)
            if (usage != 0) {
                tapTemp(t, usage, HidKeymap.modsFor(c))
                i++
            } else {
                // run of non-ASCII chars -> transport (unicode on WiFi; unsupported on BT)
                var j = i + 1
                while (j < n && HidKeymap.usageFor(cs[j]) == 0) j++
                val unsent = t.sendText(cs, i, j)
                if (unsent > 0) {
                    unsentChars += unsent
                    onUnsentChanged?.invoke()
                }
                i = j
            }
        }
    }

    private val tmpKeys = ByteArray(6)
    private val zeroKeys = ByteArray(6)

    private fun tapTemp(t: Transport, usage: Int, mods: Int) {
        tmpKeys.fill(0)
        tmpKeys[0] = usage.toByte()
        t.sendKeys(mods or externalMods, tmpKeys)
        t.sendKeys(externalMods, zeroKeys)
    }

    /** Modifiers latched by the key bar, applied to typed text as well (e.g. Ctrl held + typing 'c'). */
    var externalMods = 0

    override fun consumer(usage: Int) {
        touchActivity()
        transport?.sendConsumer(usage)
    }

    override fun releaseAll() {
        accX = 0f; accY = 0f; accV = 0f; accH = 0f
        timer.cancel(flushRunnable)
        scheduled = false
        transport?.releaseAll()
    }

    fun resetUnsent() {
        unsentChars = 0
        onUnsentChanged?.invoke()
    }
}
