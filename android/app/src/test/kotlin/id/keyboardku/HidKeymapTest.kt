package id.keyboardku

import id.keyboardku.input.HidKeymap
import id.keyboardku.input.InputSink
import id.keyboardku.input.KeyboardState
import id.keyboardku.input.Keys
import id.keyboardku.transport.HidDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidKeymapTest {
    @Test
    fun usLayoutBasics() {
        assertEquals(0x04, HidKeymap.usageFor('a'))
        assertEquals(0x04, HidKeymap.usageFor('A'))
        assertEquals(Keys.MOD_LSHIFT, HidKeymap.modsFor('A'))
        assertEquals(0, HidKeymap.modsFor('a'))
        assertEquals(0x1E, HidKeymap.usageFor('1'))
        assertEquals(0x1E, HidKeymap.usageFor('!'))
        assertEquals(Keys.MOD_LSHIFT, HidKeymap.modsFor('!'))
        assertEquals(0x27, HidKeymap.usageFor('0'))
        assertEquals(Keys.SPACE, HidKeymap.usageFor(' '))
        assertEquals(Keys.ENTER, HidKeymap.usageFor('\n'))
        assertEquals(0x38, HidKeymap.usageFor('?'))
        assertEquals(0, HidKeymap.usageFor('é'))
        assertEquals(0, HidKeymap.usageFor('\uD83D'))
    }

    @Test
    fun descriptorIsWellFormed() {
        val d = HidDescriptor.DESCRIPTOR
        // three application collections, each closed
        var opens = 0; var closes = 0
        var i = 0
        while (i < d.size) {
            val b = d[i].toInt() and 0xFF
            val size = when (b and 0x03) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            if (b == 0xA1) opens++
            if (b == 0xC0) closes++
            i += 1 + size
        }
        assertEquals(i, d.size)          // item boundaries line up exactly
        assertEquals(4, opens)           // 3 application + 1 physical
        assertEquals(4, closes)
        assertTrue(d.size < 200)
    }

    @Test
    fun keyboardStateEmitsFullReports() {
        val states = ArrayList<Pair<Int, List<Int>>>()
        val sink = object : InputSink {
            override fun move(dx: Float, dy: Float) {}
            override fun scroll(v120: Float, h120: Float) {}
            override fun buttons(mask: Int) {}
            override fun keyState(mods: Int, keys: ByteArray) { states.add(mods to keys.map { it.toInt() }) }
            override fun text(cs: CharSequence) {}
            override fun consumer(usage: Int) {}
            override fun releaseAll() {}
        }
        val k = KeyboardState(sink)
        k.toggleOneShot(Keys.MOD_LCTRL)             // one-shot Ctrl: reported held immediately
        assertEquals(Keys.MOD_LCTRL to listOf(0, 0, 0, 0, 0, 0), states.last())
        k.tap(0x06)                                  // Ctrl+C
        assertEquals(Keys.MOD_LCTRL to listOf(6, 0, 0, 0, 0, 0), states[states.size - 2])
        assertEquals(0 to listOf(0, 0, 0, 0, 0, 0), states.last())   // one-shot cleared on release
        k.toggleLock(Keys.MOD_LSHIFT)
        k.press(0x04); k.press(0x05)
        assertEquals(Keys.MOD_LSHIFT to listOf(4, 5, 0, 0, 0, 0), states.last())
        k.release(0x04)
        assertEquals(Keys.MOD_LSHIFT to listOf(0, 5, 0, 0, 0, 0), states.last())
        k.releaseAll()
        assertEquals(0 to listOf(0, 0, 0, 0, 0, 0), states.last())
    }
}
