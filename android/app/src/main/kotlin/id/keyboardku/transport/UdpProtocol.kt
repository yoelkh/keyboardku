package id.keyboardku.transport

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire constants and byte helpers, mirroring server/src/protocol.rs and docs/PROTOCOL.md. */
object UdpProtocol {
    const val MAGIC: Int = 0xA8
    const val PORT = 47800
    const val VERSION = 2

    const val HDR_LEN = 8
    const val PAYLOAD_LEN = 8
    const val TAG_LEN = 16
    const val DATA_LEN = 32
    const val DISCOVER_LEN = 16
    const val OFFER_LEN = 52
    const val HELLO_LEN = 48
    const val REJECT_LEN = 16

    const val T_MOUSE = 0x01
    const val T_SCROLL = 0x02
    const val T_BUTTONS = 0x03
    const val T_KEYS = 0x04
    const val T_UNICODE = 0x05
    const val T_CONSUMER = 0x06
    const val T_HEARTBEAT = 0x07
    const val T_RELEASE_ALL = 0x08
    const val T_HELLO = 0x10
    const val T_WELCOME = 0x11
    const val T_REJECT = 0x12
    const val T_PAIR = 0x13
    const val T_DISCOVER = 0x20
    const val T_OFFER = 0x21
    const val T_PONG = 0x22

    const val REJ_UNKNOWN_PEER = 1
    const val REJ_BAD_MAC = 2
    const val REJ_RATE_LIMITED = 3
    const val REJ_VERSION = 4
    const val REJ_PAIRING_DISABLED = 5

    const val OFFER_FLAG_PAIRING_ALLOWED = 1
    const val OFFER_FLAG_NO_CODE = 2

    fun writeHeader(b: ByteArray, type: Int, session: Int, seq: Int) {
        b[0] = MAGIC.toByte()
        b[1] = type.toByte()
        b[2] = (session and 0xFF).toByte()
        b[3] = ((session ushr 8) and 0xFF).toByte()
        wrU32(b, 4, seq)
    }

    fun headerType(b: ByteArray): Int = b[1].toInt() and 0xFF
    fun headerSession(b: ByteArray): Int = (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8)
    fun headerSeq(b: ByteArray): Int = rdU32(b, 4)
    fun isMagic(b: ByteArray, n: Int): Boolean = n >= HDR_LEN && (b[0].toInt() and 0xFF) == MAGIC

    fun wrU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    fun wrU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
    fun rdU16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    fun rdU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}

/** HKDF/HMAC pairing primitives and the per-packet AEAD, mirroring server/src/crypto.rs. */
object Crypto {
    private const val HMAC = "HmacSHA256"

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun hmac16(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac(key, *parts).copyOf(16)

    /** RFC 5869 HKDF-SHA256, single 32-byte block. */
    fun hkdf32(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        return hmac(prk, info, byteArrayOf(1)).copyOf(32)
    }

    private val INFO_PAIR = "keyboardku-pair".toByteArray()
    private val INFO_PSK = "keyboardku-psk".toByteArray()
    private val INFO_C2S = "keyboardku-c2s".toByteArray()
    private val INFO_S2C = "keyboardku-s2c".toByteArray()

    fun pairK0(code: ByteArray, nonceS: ByteArray, nonceC: ByteArray): ByteArray = hkdf32(code, nonceS + nonceC, INFO_PAIR)
    fun pskFromK0(k0: ByteArray): ByteArray = hkdf32(k0, ByteArray(0), INFO_PSK)
    fun helloMac(psk: ByteArray, nonceS: ByteArray, nonceC: ByteArray, peerId: ByteArray): ByteArray =
        hmac16(psk, "hello".toByteArray(), nonceS, nonceC, peerId)
    fun pairMac(k0: ByteArray, nonceS: ByteArray, nonceC: ByteArray, peerId: ByteArray): ByteArray =
        hmac16(k0, "pair".toByteArray(), nonceS, nonceC, peerId)

    fun sessionKeys(psk: ByteArray, nonceS: ByteArray, nonceC: ByteArray): Aead {
        val salt = nonceS + nonceC
        return Aead(hkdf32(psk, salt, INFO_C2S), hkdf32(psk, salt, INFO_S2C))
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }
}

/**
 * ChaCha20-Poly1305 for 32-byte data packets. One instance per session; not thread-safe
 * (the phone->server direction is used from the main thread only, server->phone from the rx thread),
 * so two separate Cipher objects are kept.
 */
class Aead(keyC2S: ByteArray, keyS2C: ByteArray) {
    companion object {
        /** Android/Conscrypt names it "ChaCha20/Poly1305/NoPadding", the JDK (unit tests) "ChaCha20-Poly1305". */
        fun newCipher(): Cipher = try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (e: java.security.NoSuchAlgorithmException) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }
    }

    private val kc = SecretKeySpec(keyC2S, "ChaCha20")
    private val ks = SecretKeySpec(keyS2C, "ChaCha20")
    private val encC2S: Cipher = newCipher()
    private val decS2C: Cipher = newCipher()
    private val nonceC2S = ByteArray(12)
    private val nonceS2C = ByteArray(12)
    private val tmpEnc = ByteArray(24)
    private val tmpDec = ByteArray(24)

    private fun nonce(n: ByteArray, session: Int, seq: Int) {
        n[0] = (session and 0xFF).toByte(); n[1] = ((session ushr 8) and 0xFF).toByte(); n[2] = 0; n[3] = 0
        UdpProtocol.wrU32(n, 4, seq)
        n[8] = 0; n[9] = 0; n[10] = 0; n[11] = 0
    }

    /** Encrypt in place: header in [0..8), plaintext payload in [8..16) -> ciphertext+tag in [8..32). */
    fun seal(pkt: ByteArray, session: Int, seq: Int) {
        nonce(nonceC2S, session, seq)
        encC2S.init(Cipher.ENCRYPT_MODE, kc, IvParameterSpec(nonceC2S))
        encC2S.updateAAD(pkt, 0, UdpProtocol.HDR_LEN)
        encC2S.doFinal(pkt, UdpProtocol.HDR_LEN, UdpProtocol.PAYLOAD_LEN, tmpEnc, 0)
        System.arraycopy(tmpEnc, 0, pkt, UdpProtocol.HDR_LEN, 24)
    }

    /** Decrypt a server->phone packet in place; payload lands in [8..16). Returns false on a bad tag. */
    fun open(pkt: ByteArray, session: Int, seq: Int): Boolean {
        return try {
            nonce(nonceS2C, session, seq)
            decS2C.init(Cipher.DECRYPT_MODE, ks, IvParameterSpec(nonceS2C))
            decS2C.updateAAD(pkt, 0, UdpProtocol.HDR_LEN)
            val n = decS2C.doFinal(pkt, UdpProtocol.HDR_LEN, 24, tmpDec, 0)
            if (n != UdpProtocol.PAYLOAD_LEN) return false
            System.arraycopy(tmpDec, 0, pkt, UdpProtocol.HDR_LEN, UdpProtocol.PAYLOAD_LEN)
            true
        } catch (e: Exception) {
            false
        }
    }
}
