package id.keyboardku

import id.keyboardku.input.AirMouseEngine
import id.keyboardku.input.InputSink
import id.keyboardku.input.Keys
import id.keyboardku.input.ReportScheduler
import id.keyboardku.input.TextDiff
import id.keyboardku.input.TrackpadGestureEngine
import id.keyboardku.transport.Transport
import id.keyboardku.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Records everything a sink receives. */
class RecordingSink : InputSink {
    var dx = 0f; var dy = 0f
    var sv = 0f; var sh = 0f
    val buttons = ArrayList<Int>()
    val keyStates = ArrayList<Pair<Int, List<Int>>>()
    val texts = ArrayList<String>()
    var consumer = 0
    var released = 0
    override fun move(dx: Float, dy: Float) { this.dx += dx; this.dy += dy }
    override fun scroll(v120: Float, h120: Float) { sv += v120; sh += h120 }
    override fun buttons(mask: Int) { buttons.add(mask) }
    override fun keyState(mods: Int, keys: ByteArray) { keyStates.add(mods to keys.map { it.toInt() and 0xFF }) }
    override fun text(cs: CharSequence) { texts.add(cs.toString()) }
    override fun consumer(usage: Int) { consumer = usage }
    override fun releaseAll() { released++ }
}

class TrackpadGestureEngineTest {
    private val sink = RecordingSink()
    private val engine = TrackpadGestureEngine(sink, pxPerMmX = 16f, pxPerMmY = 16f, touchSlopPx = 8f).apply { pointerAccel = 0f }

    @Test
    fun tapIsLeftClick() {
        engine.onDown(0, 100f, 100f, 1000)
        engine.onUp(0, 101f, 100f, 1080)
        assertEquals(listOf(Keys.BTN_LEFT, 0), sink.buttons)
        assertEquals(0f, sink.dx, 0.001f)
    }

    @Test
    fun slowMoveHasPrecisionGainAndDirection() {
        engine.onDown(0, 100f, 100f, 0)
        var t = 0L
        for (i in 1..40) { t += 5; engine.onMove(0, 100f + i * 1f, 100f, t) } // 1 px / 5 ms = 12.5 mm/s
        assertTrue("moved right", sink.dx > 0f)
        assertEquals(0f, sink.dy, 0.001f)
        // 40 px = 2.5 mm of finger travel; precision gain < 1 so output < 2.5 mm * 9 px/mm = 22.5 px
        assertTrue("precision gain applied: ${sink.dx}", sink.dx < 22.5f)
        engine.onUp(0, 140f, 100f, t)
        assertTrue("no click after a move", sink.buttons.isEmpty())
    }

    @Test
    fun twoFingerTapIsRightClickAndDragScrolls() {
        engine.onDown(0, 100f, 100f, 0)
        engine.onDown(1, 140f, 100f, 5)
        engine.onUp(1, 140f, 100f, 90)
        engine.onUp(0, 100f, 100f, 95)
        assertEquals(listOf(Keys.BTN_RIGHT, 0), sink.buttons)

        sink.buttons.clear()
        engine.onDown(0, 100f, 100f, 1000)
        engine.onDown(1, 140f, 100f, 1005)
        for (i in 1..20) { engine.onMove(0, 100f, 100f + i * 4f, 1005L + i * 8); engine.onMove(1, 140f, 100f + i * 4f, 1005L + i * 8) }
        engine.onUp(1, 140f, 180f, 1300)
        engine.onUp(0, 100f, 180f, 1305)
        assertTrue("fingers down -> wheel negative (classic scroll): ${sink.sv}", sink.sv < 0f)
        assertTrue(sink.buttons.isEmpty())
    }

    @Test
    fun tapThenTouchDrags() {
        engine.onDown(0, 100f, 100f, 0)
        engine.onUp(0, 100f, 100f, 50)
        engine.onDown(0, 100f, 100f, 200)
        engine.onMove(0, 130f, 100f, 240)
        assertEquals(listOf(Keys.BTN_LEFT, 0, Keys.BTN_LEFT), sink.buttons)
        engine.onUp(0, 130f, 100f, 300)
        assertEquals(0, sink.buttons.last())
    }

    @Test
    fun gainCurveIsMonotonic() {
        engine.pointerAccel = 1f
        var prev = 0f
        for (v in 0..400 step 10) {
            val g = engine.gain(v.toFloat())
            assertTrue(g >= prev)
            prev = g
        }
        assertEquals(TrackpadGestureEngine.PRECISION_GAIN, engine.gain(0f), 0.001f)
        assertEquals(1f, engine.gain(TrackpadGestureEngine.V_LOW), 0.001f)
        assertEquals(TrackpadGestureEngine.MAX_GAIN, engine.gain(1000f), 0.001f)
    }
}

class AirMouseEngineTest {
    private val sink = RecordingSink()
    private val engine = AirMouseEngine(sink).apply {
        hostScreenWidthPx = 1920f
        degreesPerScreen = 40f
        smoothingDegPerSec = 0f
        tighteningDegPerSec = 0f
        accelMax = 1f
        autoBias = false
        clutch = true
    }

    private fun feed(degPerSec: Triple<Float, Float, Float>, seconds: Float) {
        val n = (seconds * 200).toInt()
        val r = 1f / AirMouseEngine.RAD_TO_DEG
        var t = 1_000_000_000L
        engine.onAccel(0f, 0f, 9.81f) // flat on a table, screen up
        for (i in 0 until n) {
            t += 5_000_000L
            engine.onGyro(degPerSec.first * r, degPerSec.second * r, degPerSec.third * r, t)
        }
    }

    @Test
    fun yawRotationCrossesScreen() {
        // 40 deg/s about world-up (device Z when flat) for 1 s = 40 degrees = one screen width, to the left
        feed(Triple(0f, 0f, 40f), 1f)
        assertTrue("cursor moved left: ${sink.dx}", sink.dx < 0f)
        assertEquals(1920f, abs(sink.dx), 1920f * 0.03f)
        assertEquals(0f, sink.dy, 1f)
    }

    @Test
    fun pitchMovesVertically() {
        // tilting the top edge down = negative rotation about X -> cursor moves down (positive dy)
        feed(Triple(-20f, 0f, 0f), 0.5f)
        assertTrue("cursor moved down: ${sink.dy}", sink.dy > 0f)
        assertEquals(480f, sink.dy, 480f * 0.03f)
    }

    @Test
    fun stillDeviceProducesNoMotionAndReportsStillness() {
        feed(Triple(0f, 0f, 0f), 3f)
        assertEquals(0f, sink.dx, 0.001f)
        assertTrue("still for ~3 s: ${engine.stillMillis}", engine.stillMillis >= 2000)
    }

    @Test
    fun tighteningReducesSlowMotion() {
        engine.tighteningDegPerSec = 5f
        feed(Triple(0f, 0f, 2.5f), 1f)  // 2.5 deg/s < 5 -> scaled by 0.5
        val expected = 2.5f * (1920f / 40f) * 1f * 0.5f
        assertEquals(expected, abs(sink.dx), expected * 0.05f)
    }

    @Test
    fun clutchGatesMotion() {
        engine.clutch = false
        feed(Triple(0f, 0f, 40f), 0.5f)
        assertEquals(0f, sink.dx, 0.001f)
    }
}

class TextDiffTest {
    private val out = ArrayList<Pair<Int, String>>()
    private val diff = TextDiff()
    private fun apply(buffer: String, cursor: Int = buffer.length) = diff.update(buffer, cursor) { b, t -> out.add(b to t.toString()) }

    @Test
    fun composingWordThenAutocorrect() {
        apply("w"); apply("wr"); apply("wro"); apply("wrod")
        assertEquals(listOf(0 to "w", 0 to "r", 0 to "o", 0 to "d"), out)
        out.clear()
        apply("word ")           // autocorrect replaced the composing word: common prefix "w"
        assertEquals(listOf(3 to "ord "), out)
    }

    @Test
    fun backspaceAndSurrogates() {
        apply("ab😀")  // "ab😀"
        out.clear()
        apply("ab")              // emoji deleted: one backspace (code point), not two
        assertEquals(listOf(1 to ""), out)
        apply("abc")
        assertEquals(listOf(1 to "", 0 to "c"), out)
    }

    @Test
    fun cursorSubsetOnly() {
        apply("hello", cursor = 3)
        assertEquals(listOf(0 to "hel"), out)
    }
}

class ReportSchedulerTest {
    private class FakeTransport(var failMoves: Int = 0) : Transport {
        override val name = "fake"
        override val state = TransportState.CONNECTED
        override val minMoveIntervalNanos = 6_000_000L
        override val supportsUnicode = false
        override var listener: Transport.Listener? = null
        val moves = ArrayList<Pair<Int, Int>>()
        val events = ArrayList<String>()
        override fun start() {}
        override fun stop() {}
        override fun sendMouse(dx: Int, dy: Int): Boolean {
            if (failMoves > 0) { failMoves--; return false }
            moves.add(dx to dy); events.add("m"); return true
        }
        override fun sendScroll(v120: Int, h120: Int) = true
        override fun sendButtons(mask: Int) { events.add("b$mask") }
        override fun sendKeys(mods: Int, keys: ByteArray) { events.add("k${mods}:${keys[0]}") }
        override fun sendText(cs: CharSequence, start: Int, end: Int) = end - start
        override fun sendConsumer(usage: Int) {}
        override fun releaseAll() {}
        override fun setActive(active: Boolean) {}
    }

    private class FakeTimer : ReportScheduler.Timer {
        val pending = LinkedHashMap<Runnable, Long>()
        override fun schedule(r: Runnable, delayMs: Long) { pending[r] = delayMs }
        override fun cancel(r: Runnable) { pending.remove(r) }
        fun fireAll() { val rs = pending.keys.toList(); pending.clear(); rs.forEach { it.run() } }
    }

    private var now = 0L
    private val timer = FakeTimer()
    private val t = FakeTransport()
    private val s = ReportScheduler({ now }, timer).apply { transport = t }

    @Test
    fun coalescesWithinIntervalAndKeepsFraction() {
        s.move(0.2f, 0f)     // rounds to 0 -> nothing sent, fraction kept
        s.move(0.2f, 0f)
        assertTrue(t.moves.isEmpty())
        s.move(0.2f, 0f)     // 0.6 -> rounds to 1, keeps -0.4
        assertEquals(listOf(1 to 0), t.moves)
        now += 1_000_000L
        s.move(3f, 2f)       // within 6 ms: scheduled, not sent
        assertEquals(1, t.moves.size)
        assertTrue(timer.pending.size >= 1) // flush timer (+ idle timer)
        now += 6_000_000L
        timer.fireAll()
        assertEquals(listOf(1 to 0, 3 to 2), t.moves)
    }

    @Test
    fun discreteEventsFlushPendingMotionFirst() {
        s.move(5f, 0f)
        now += 1_000_000L
        s.move(4f, 0f)       // pending
        s.buttons(1)         // must flush the 4 px first, then the button
        assertEquals(listOf("m", "m", "b1"), t.events)
    }

    @Test
    fun failedSendKeepsDeltaForNextFlush() {
        t.failMoves = 1
        s.move(5f, 0f)       // fails -> delta retained, retry scheduled
        assertTrue(t.moves.isEmpty())
        now += 6_000_000L
        s.move(2f, 0f)
        assertEquals(listOf(7 to 0), t.moves)
    }

    @Test
    fun asciiTextBecomesKeyTaps() {
        s.text("Hi")
        assertEquals(listOf("k2:11", "k0:0", "k0:12", "k0:0"), t.events) // H = Shift+0x0B, i = 0x0C
        assertEquals(0, s.unsentChars)
        s.text("é")
        assertEquals(1, s.unsentChars)
    }
}
