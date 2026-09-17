package id.keyboardku.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import id.keyboardku.Log
import id.keyboardku.input.AirMouseEngine
import kotlin.math.abs

/**
 * Feeds the [AirMouseEngine] with gyroscope (200 Hz, no batching) and accelerometer (50 Hz) samples,
 * delivered on the main looper so the whole input pipeline stays single-threaded.
 * Puts the gyro to sleep after 30 s of stillness and wakes it on the first accelerometer movement.
 */
class MotionSensorSource(context: Context, private val engine: AirMouseEngine) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())

    val available: Boolean get() = gyro != null
    /** When false the gyro is kept awake even if the phone is still (e.g. finger held on the pad). */
    var allowSleep: () -> Boolean = { !engine.clutch }

    private var enabled = false
    private var gyroOn = false
    private var lastAx = 0f
    private var lastAy = 0f
    private var lastAz = 0f

    companion object {
        const val GYRO_PERIOD_US = 5000       // 200 Hz (the cap without HIGH_SAMPLING_RATE_SENSORS)
        const val ACCEL_PERIOD_US = 20000     // 50 Hz
        const val ACCEL_IDLE_PERIOD_US = 50000
        const val SLEEP_AFTER_MS = 30_000L
        const val WAKE_DELTA = 0.3f           // m/s^2
    }

    fun start() {
        if (enabled || gyro == null) return
        enabled = true
        registerAccel(ACCEL_PERIOD_US)
        registerGyro()
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        sm.unregisterListener(this)
        gyroOn = false
    }

    private fun registerGyro() {
        if (gyroOn || gyro == null) return
        engine.resetTiming()
        val period = maxOf(GYRO_PERIOD_US, gyro.minDelay)
        val ok = try {
            sm.registerListener(this, gyro, period, 0, handler)
        } catch (e: SecurityException) {
            // debuggable builds throw instead of capping
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, 0, handler)
        }
        gyroOn = ok
        Log.d("gyro registered=$ok period=${period}us")
    }

    private fun registerAccel(periodUs: Int) {
        if (accel == null) return
        sm.unregisterListener(this, accel)
        sm.registerListener(this, accel, periodUs, 0, handler)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                engine.onGyro(e.values[0], e.values[1], e.values[2], e.timestamp)
                if (gyroOn && engine.stillMillis >= SLEEP_AFTER_MS && !engine.isCalibrating && allowSleep()) {
                    Log.d("gyro sleeping after ${engine.stillMillis} ms still")
                    sm.unregisterListener(this, e.sensor)
                    gyroOn = false
                    registerAccel(ACCEL_IDLE_PERIOD_US)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                engine.onAccel(x, y, z)
                if (!gyroOn && enabled) {
                    if (abs(x - lastAx) > WAKE_DELTA || abs(y - lastAy) > WAKE_DELTA || abs(z - lastAz) > WAKE_DELTA) {
                        registerAccel(ACCEL_PERIOD_US)
                        registerGyro()
                    }
                }
                lastAx = x; lastAy = y; lastAz = z
            }
        }
    }

    /** Force the gyro back on (e.g. when the user touches the clutch). */
    fun wake() {
        if (enabled && !gyroOn) {
            registerAccel(ACCEL_PERIOD_US)
            registerGyro()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
