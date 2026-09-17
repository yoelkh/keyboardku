package id.keyboardku

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** All persisted settings. Plain SharedPreferences (MODE_PRIVATE); no AndroidX. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("keyboardku", Context.MODE_PRIVATE)

    // ---- transport ----
    /** AUTO, BT, WIFI */
    var transportMode: String
        get() = sp.getString("transportMode", "AUTO") ?: "AUTO"
        set(v) = sp.edit().putString("transportMode", v).apply()
    var hidUnsupported: Boolean
        get() = sp.getBoolean("hidUnsupported", false)
        set(v) = sp.edit().putBoolean("hidUnsupported", v).apply()
    var btDeviceAddress: String?
        get() = sp.getString("btDeviceAddress", null)
        set(v) = sp.edit().putString("btDeviceAddress", v).apply()
    var manualServerIp: String
        get() = sp.getString("manualServerIp", "") ?: ""
        set(v) = sp.edit().putString("manualServerIp", v).apply()
    var preferredServerName: String?
        get() = sp.getString("preferredServerName", null)
        set(v) = sp.edit().putString("preferredServerName", v).apply()

    /** 8 random bytes identifying this phone to servers (generated once). */
    val peerId: ByteArray
        get() {
            val hex = sp.getString("peerId", null)
            if (hex != null && hex.length == 16) return hexToBytes(hex)
            val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
            sp.edit().putString("peerId", bytesToHex(b)).apply()
            return b
        }

    fun pskFor(serverName: String): ByteArray? {
        val hex = sp.getString("psk:$serverName", null) ?: return null
        return if (hex.length == 64) hexToBytes(hex) else null
    }
    fun setPsk(serverName: String, psk: ByteArray) = sp.edit().putString("psk:$serverName", bytesToHex(psk)).apply()
    fun clearPsk(serverName: String) = sp.edit().remove("psk:$serverName").apply()

    // ---- trackpad ----
    /** 0.5 .. 3.0 */
    var pointerSpeed: Float
        get() = sp.getFloat("pointerSpeed", 1.0f)
        set(v) = sp.edit().putFloat("pointerSpeed", v).apply()
    /** 0 .. 1: scales the acceleration slope */
    var pointerAccel: Float
        get() = sp.getFloat("pointerAccel", 0.6f)
        set(v) = sp.edit().putFloat("pointerAccel", v).apply()
    var scrollSpeed: Float
        get() = sp.getFloat("scrollSpeed", 1.0f)
        set(v) = sp.edit().putFloat("scrollSpeed", v).apply()
    var naturalScroll: Boolean
        get() = sp.getBoolean("naturalScroll", false)
        set(v) = sp.edit().putBoolean("naturalScroll", v).apply()
    var tapToClick: Boolean
        get() = sp.getBoolean("tapToClick", true)
        set(v) = sp.edit().putBoolean("tapToClick", v).apply()

    // ---- air mouse ----
    var airMouseEnabled: Boolean
        get() = sp.getBoolean("airMouse", false)
        set(v) = sp.edit().putBoolean("airMouse", v).apply()
    /** degrees of rotation to cross the host screen: 20 .. 90 */
    var airDegreesPerScreen: Float
        get() = sp.getFloat("airDegPerScreen", 40f)
        set(v) = sp.edit().putFloat("airDegPerScreen", v).apply()
    /** tightening threshold in deg/s: 0 .. 10 */
    var airSteadiness: Float
        get() = sp.getFloat("airSteadiness", 5f)
        set(v) = sp.edit().putFloat("airSteadiness", v).apply()
    /** smoothing threshold in deg/s: 0 (off) .. 10 */
    var airSmoothing: Float
        get() = sp.getFloat("airSmoothing", 5f)
        set(v) = sp.edit().putFloat("airSmoothing", v).apply()
    /** 0 off, 1 low (1.3x), 2 standard (1.6x) */
    var airAccel: Int
        get() = sp.getInt("airAccel", 2)
        set(v) = sp.edit().putInt("airAccel", v).apply()
    var airAlwaysOn: Boolean
        get() = sp.getBoolean("airAlwaysOn", false)
        set(v) = sp.edit().putBoolean("airAlwaysOn", v).apply()
    var airAutoBias: Boolean
        get() = sp.getBoolean("airAutoBias", true)
        set(v) = sp.edit().putBoolean("airAutoBias", v).apply()
    var airBiasX: Float
        get() = sp.getFloat("airBiasX", 0f)
        set(v) = sp.edit().putFloat("airBiasX", v).apply()
    var airBiasY: Float
        get() = sp.getFloat("airBiasY", 0f)
        set(v) = sp.edit().putFloat("airBiasY", v).apply()
    var airBiasZ: Float
        get() = sp.getFloat("airBiasZ", 0f)
        set(v) = sp.edit().putFloat("airBiasZ", v).apply()
    /** host screen width in px as reported by the last WELCOME (0 = unknown) */
    var hostScreenWidth: Int
        get() = sp.getInt("hostScreenW", 0)
        set(v) = sp.edit().putInt("hostScreenW", v).apply()

    // ---- power / misc ----
    /** seconds of no input before the screen is dimmed; 0 = never */
    var dimAfterSeconds: Int
        get() = sp.getInt("dimAfter", 20)
        set(v) = sp.edit().putInt("dimAfter", v).apply()
    var pocketMode: Boolean
        get() = sp.getBoolean("pocketMode", false)
        set(v) = sp.edit().putBoolean("pocketMode", v).apply()
    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit().putBoolean("onboardingDone", v).apply()
    var onboardingOsVersion: String
        get() = sp.getString("onboardingOs", "") ?: ""
        set(v) = sp.edit().putString("onboardingOs", v).apply()

    companion object {
        fun bytesToHex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) sb.append(String.format("%02x", x.toInt() and 0xFF))
            return sb.toString()
        }
        fun hexToBytes(s: String): ByteArray {
            val out = ByteArray(s.length / 2)
            for (i in out.indices) out[i] = s.substring(2 * i, 2 * i + 2).toInt(16).toByte()
            return out
        }
    }
}
