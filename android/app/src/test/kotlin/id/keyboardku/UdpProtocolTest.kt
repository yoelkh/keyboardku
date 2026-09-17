package id.keyboardku

import id.keyboardku.transport.Crypto
import id.keyboardku.transport.UdpProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the vectors printed by `testclient --vectors` (docs/vectors.txt). */
class UdpProtocolTest {
    private fun hex(s: String) = Prefs.hexToBytes(s)
    private fun hex(b: ByteArray) = Prefs.bytesToHex(b)

    private val code = "123456".toByteArray()
    private val nonceS = hex("1111111111111111")
    private val nonceC = hex("2222222222222222")
    private val peerId = hex("3333333333333333")

    @Test
    fun pairingDerivationMatchesServer() {
        val k0 = Crypto.pairK0(code, nonceS, nonceC)
        assertEquals("0db48b88fe7949702ace81a607a798d63b05da33b9d904559c0f7ee829ceb388", hex(k0))
        val psk = Crypto.pskFromK0(k0)
        assertEquals("624562b765c7131dba8ae6e3491bae910624c4f9b7c0a3198639426824b007ae", hex(psk))
        assertEquals("b1147ce9c0769745389e759b56124adb", hex(Crypto.pairMac(k0, nonceS, nonceC, peerId)))
        assertEquals("8aac1a3cf8ddfd29c1cfa4c273cc18ec", hex(Crypto.helloMac(psk, nonceS, nonceC, peerId)))
    }

    @Test
    fun sealMatchesServerAndOpenWorks() {
        val psk = hex("624562b765c7131dba8ae6e3491bae910624c4f9b7c0a3198639426824b007ae")
        val aead = Crypto.sessionKeys(psk, nonceS, nonceC)
        val pkt = ByteArray(UdpProtocol.DATA_LEN)
        UdpProtocol.writeHeader(pkt, UdpProtocol.T_MOUSE, 0x0102, 7)
        UdpProtocol.wrU32(pkt, 8, -5)
        UdpProtocol.wrU32(pkt, 12, 9)
        aead.seal(pkt, 0x0102, 7)
        assertEquals("a8010201070000002d24cb31548322f191cf64b38126e3d73a8b95000b7677b5", hex(pkt))

        val pong = hex("a82202010300000002e58440b51061843c51b77226e3e82b7ce883581b3ffd5e")
        assertEquals(UdpProtocol.T_PONG, UdpProtocol.headerType(pong))
        assertEquals(0x0102, UdpProtocol.headerSession(pong))
        assertEquals(3, UdpProtocol.headerSeq(pong))
        assertTrue(aead.open(pong, 0x0102, 3))
        assertEquals(1000, UdpProtocol.rdU32(pong, 8))
        assertEquals(2000, UdpProtocol.rdU32(pong, 12))

        val tampered = hex("a82202010300000002e58440b51061843c51b77226e3e82b7ce883581b3ffd5f")
        assertFalse(aead.open(tampered, 0x0102, 3))
        val wrongSeq = hex("a82202010300000002e58440b51061843c51b77226e3e82b7ce883581b3ffd5e")
        assertFalse(aead.open(wrongSeq, 0x0102, 4))
    }

    @Test
    fun byteHelpersRoundTrip() {
        val b = ByteArray(8)
        UdpProtocol.wrU32(b, 0, -123456)
        UdpProtocol.wrU16(b, 4, 0xBEEF)
        assertEquals(-123456, UdpProtocol.rdU32(b, 0))
        assertEquals(0xBEEF, UdpProtocol.rdU16(b, 4))
        assertArrayEquals(hex("c01dfeff"), b.copyOfRange(0, 4))
    }
}
