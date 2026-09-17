package id.keyboardku.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Gyro "air mouse": integrates calibrated angular velocity into pointer motion, the way gyro aiming
 * is done in JoyShockMapper / Steam Input (GyroWiki), not by differencing an absolute orientation.
 *
 * Pipeline per gyro sample (deg/s): bias -> gravity (complementary filter) -> stillness detection ->
 * optional auto-bias -> player-space yaw + local pitch -> tiered smoothing -> tightening -> mild
 * acceleration -> px/deg -> sink.move(). Pure Kotlin; unit-testable with synthetic samples.
 *
 * Android device axes: X right, Y up (top edge), Z out of the screen. Rotating the phone to point
 * left is a positive rotation about the world "up" axis; tilting the top edge down is a negative
 * rotation about X.
 */
class AirMouseEngine(private val sink: InputSink) {
    // ---- tunables ----
    var degreesPerScreen = 40f          // rotation needed to cross the host screen (20..90)
    var hostScreenWidthPx = 1920f
    var tighteningDegPerSec = 5f        // 0 = off
    var smoothingDegPerSec = 5f         // threshold t2; t1 = t2/2; 0 = off
    var accelMax = 1.6f                 // 1.0 = off, 1.3 low, 1.6 standard
    var autoBias = true
    var clutch = false                  // motion is only emitted while true
        set(v) { if (v && !field) resetSmoothing(); field = v }
    var invertX = false
    var invertY = false

    /** Manual bias in deg/s (from calibration), subtracted before everything else. */
    var biasX = 0f
    var biasY = 0f
    var biasZ = 0f

    /** Milliseconds the device has been continuously still (for sensor sleep). */
    var stillMillis = 0L
        private set

    companion object {
        const val RAD_TO_DEG = 57.29578f
        const val GRAVITY_BLEND = 0.02f
        const val STILL_WINDOW_NS = 500_000_000L
        const val STILL_GYRO_RANGE = 1.5f       // deg/s (max-min inside the window)
        const val STILL_ACCEL_RANGE = 0.5f      // m/s^2
        const val AUTO_BIAS_AFTER_MS = 2000L
        const val AUTO_BIAS_EASE_S = 3f
        const val YAW_RELAX = 2.0f              // 60 degrees of local-space freedom (JSM default)
        const val ACCEL_THRESHOLD = 75f         // deg/s where accelMax is reached
        const val SMOOTH_SAMPLES = 25           // 0.125 s at 200 Hz
    }

    // gravity direction in device frame (unit vector), initialised to "flat on a table"
    private var gx = 0f
    private var gy = 0f
    private var gz = 1f
    private var haveAccel = false
    private var ax = 0f
    private var ay = 0f
    private var az = 9.81f

    private var lastGyroNs = 0L

    // stillness window (max/min per axis, reset every STILL_WINDOW_NS)
    private var winStartNs = 0L
    private val gMin = FloatArray(3) { Float.MAX_VALUE }
    private val gMax = FloatArray(3) { -Float.MAX_VALUE }
    private val gSum = FloatArray(3)
    private var gCount = 0
    private val aMin = FloatArray(3) { Float.MAX_VALUE }
    private val aMax = FloatArray(3) { -Float.MAX_VALUE }
    private var stillSinceNs = -1L

    // auto bias (slow correction while still)
    private var autoBiasX = 0f
    private var autoBiasY = 0f
    private var autoBiasZ = 0f

    // tiered smoothing ring
    private val ringX = FloatArray(SMOOTH_SAMPLES)
    private val ringY = FloatArray(SMOOTH_SAMPLES)
    private var ringPos = 0
    private var ringSumX = 0f
    private var ringSumY = 0f

    // manual calibration
    private var calibrating = false
    private val calSamples = FloatArray(3 * 200)
    private var calCount = 0
    var calibrationListener: ((done: Boolean, progress: Int) -> Unit)? = null

    fun onAccel(x: Float, y: Float, z: Float) {
        ax = x; ay = y; az = z
        haveAccel = true
        aMin[0] = min(aMin[0], x); aMax[0] = max(aMax[0], x)
        aMin[1] = min(aMin[1], y); aMax[1] = max(aMax[1], y)
        aMin[2] = min(aMin[2], z); aMax[2] = max(aMax[2], z)
    }

    /** Gyro sample in rad/s with the sensor timestamp in ns. */
    fun onGyro(rx: Float, ry: Float, rz: Float, tNs: Long) {
        var dt = if (lastGyroNs == 0L) 0.005f else (tNs - lastGyroNs) * 1e-9f
        lastGyroNs = tNs
        if (dt <= 0f || dt > 0.02f) dt = 0.005f

        val wx = rx * RAD_TO_DEG - biasX - autoBiasX
        val wy = ry * RAD_TO_DEG - biasY - autoBiasY
        val wz = rz * RAD_TO_DEG - biasZ - autoBiasZ

        if (calibrating) collectCalibration(rx * RAD_TO_DEG, ry * RAD_TO_DEG, rz * RAD_TO_DEG)

        updateGravity(wx, wy, wz, dt)
        updateStillness(wx, wy, wz, tNs, dt)

        if (!clutch) return

        // ---- player space: yaw about world-up, pitch about local X ----
        val worldYaw = wy * gy + wz * gz
        val localMag = hypot(wy, wz)
        val yawV = sign(worldYaw) * min(abs(worldYaw) * YAW_RELAX, localMag)
        val pitchV = -wx
        var vx = -yawV
        var vy = pitchV

        // ---- tiered smoothing (only slow motion is averaged) ----
        if (smoothingDegPerSec > 0f) {
            val t2 = smoothingDegPerSec
            val t1 = t2 * 0.5f
            val mag = hypot(vx, vy)
            val direct = ((mag - t1) / (t2 - t1)).coerceIn(0f, 1f)
            val sx = vx * (1f - direct)
            val sy = vy * (1f - direct)
            ringSumX += sx - ringX[ringPos]; ringX[ringPos] = sx
            ringSumY += sy - ringY[ringPos]; ringY[ringPos] = sy
            ringPos = (ringPos + 1) % SMOOTH_SAMPLES
            vx = vx * direct + ringSumX / SMOOTH_SAMPLES
            vy = vy * direct + ringSumY / SMOOTH_SAMPLES
        }

        // ---- tightening instead of a hard cutoff ----
        val mag = hypot(vx, vy)
        if (tighteningDegPerSec > 0f && mag < tighteningDegPerSec && mag > 0f) {
            val f = mag / tighteningDegPerSec
            vx *= f; vy *= f
        }

        // ---- mild two-point acceleration ----
        val sens = 1f + (accelMax - 1f) * (mag / ACCEL_THRESHOLD).coerceIn(0f, 1f)
        val pxPerDeg = hostScreenWidthPx / degreesPerScreen
        var dx = vx * sens * pxPerDeg * dt
        var dy = vy * sens * pxPerDeg * dt
        if (invertX) dx = -dx
        if (invertY) dy = -dy
        if (dx != 0f || dy != 0f) sink.move(dx, dy)
    }

    private fun updateGravity(wx: Float, wy: Float, wz: Float, dt: Float) {
        // rotate the gravity estimate by -omega*dt (world vector seen from a rotating frame)
        val rx = wx / RAD_TO_DEG * dt
        val ry = wy / RAD_TO_DEG * dt
        val rz = wz / RAD_TO_DEG * dt
        val nx = gx - (ry * gz - rz * gy)
        val ny = gy - (rz * gx - rx * gz)
        val nz = gz - (rx * gy - ry * gx)
        gx = nx; gy = ny; gz = nz
        if (haveAccel) {
            val n = sqrt(ax * ax + ay * ay + az * az)
            if (n > 1e-3f) {
                gx += (ax / n - gx) * GRAVITY_BLEND
                gy += (ay / n - gy) * GRAVITY_BLEND
                gz += (az / n - gz) * GRAVITY_BLEND
            }
        }
        val len = sqrt(gx * gx + gy * gy + gz * gz)
        if (len > 1e-6f) { gx /= len; gy /= len; gz /= len }
    }

    private fun updateStillness(wx: Float, wy: Float, wz: Float, tNs: Long, dt: Float) {
        gMin[0] = min(gMin[0], wx); gMax[0] = max(gMax[0], wx)
        gMin[1] = min(gMin[1], wy); gMax[1] = max(gMax[1], wy)
        gMin[2] = min(gMin[2], wz); gMax[2] = max(gMax[2], wz)
        gSum[0] += wx; gSum[1] += wy; gSum[2] += wz
        gCount++
        if (winStartNs == 0L) winStartNs = tNs
        if (tNs - winStartNs >= STILL_WINDOW_NS) {
            val gyroStill = (gMax[0] - gMin[0]) < STILL_GYRO_RANGE && (gMax[1] - gMin[1]) < STILL_GYRO_RANGE && (gMax[2] - gMin[2]) < STILL_GYRO_RANGE
            val accelStill = !haveAccel || ((aMax[0] - aMin[0]) < STILL_ACCEL_RANGE && (aMax[1] - aMin[1]) < STILL_ACCEL_RANGE && (aMax[2] - aMin[2]) < STILL_ACCEL_RANGE)
            if (gyroStill && accelStill) {
                if (stillSinceNs < 0) stillSinceNs = winStartNs
                stillMillis = (tNs - stillSinceNs) / 1_000_000L
                if (autoBias && stillMillis >= AUTO_BIAS_AFTER_MS && gCount > 0) {
                    // residual mean of the window is the remaining bias; ease it in over AUTO_BIAS_EASE_S
                    val f = ((tNs - winStartNs) * 1e-9f / AUTO_BIAS_EASE_S).coerceIn(0f, 1f)
                    autoBiasX += gSum[0] / gCount * f
                    autoBiasY += gSum[1] / gCount * f
                    autoBiasZ += gSum[2] / gCount * f
                }
            } else {
                stillSinceNs = -1
                stillMillis = 0
            }
            winStartNs = tNs
            for (i in 0..2) { gMin[i] = Float.MAX_VALUE; gMax[i] = -Float.MAX_VALUE; gSum[i] = 0f; aMin[i] = Float.MAX_VALUE; aMax[i] = -Float.MAX_VALUE }
            gCount = 0
        }
        @Suppress("UNUSED_VARIABLE") val unused = dt
    }

    private fun resetSmoothing() {
        ringX.fill(0f); ringY.fill(0f); ringSumX = 0f; ringSumY = 0f; ringPos = 0
    }

    // ---- manual calibration: hold still ~1 s, median of 200 raw samples becomes the bias ----

    fun startCalibration() {
        calibrating = true
        calCount = 0
        autoBiasX = 0f; autoBiasY = 0f; autoBiasZ = 0f
    }

    private fun collectCalibration(x: Float, y: Float, z: Float) {
        if (calCount < 200) {
            calSamples[calCount * 3] = x
            calSamples[calCount * 3 + 1] = y
            calSamples[calCount * 3 + 2] = z
            calCount++
            if (calCount % 20 == 0) calibrationListener?.invoke(false, calCount / 2)
        }
        if (calCount >= 200) {
            calibrating = false
            biasX = median(0); biasY = median(1); biasZ = median(2)
            calibrationListener?.invoke(true, 100)
        }
    }

    private fun median(axis: Int): Float {
        val tmp = FloatArray(calCount) { calSamples[it * 3 + axis] }
        tmp.sort()
        return tmp[tmp.size / 2]
    }

    val isCalibrating: Boolean get() = calibrating

    /** Reset gravity estimate and timing (call when the sensor is (re)registered). */
    fun resetTiming() {
        lastGyroNs = 0L
        winStartNs = 0L
        stillSinceNs = -1L
        stillMillis = 0L
        resetSmoothing()
    }
}
