package id.keyboardku.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import id.keyboardku.Log
import id.keyboardku.Prefs
import id.keyboardku.R
import id.keyboardku.input.ReportScheduler
import id.keyboardku.transport.Transport
import id.keyboardku.transport.TransportManager
import id.keyboardku.transport.TransportState
import id.keyboardku.ui.MainActivity

/**
 * Foreground service (type connectedDevice, no timeout) that owns the transports and the scheduler.
 * It must be in the foreground *before* BluetoothHidDevice.registerApp (importance <= VISIBLE),
 * and it keeps the HID registration alive while the screen is off.
 */
class ConnectionService : Service() {
    inner class LocalBinder : Binder() {
        val service: ConnectionService get() = this@ConnectionService
    }

    private val binder = LocalBinder()
    lateinit var prefs: Prefs
        private set
    lateinit var scheduler: ReportScheduler
        private set
    lateinit var manager: TransportManager
        private set
    private val main = Handler(Looper.getMainLooper())
    private var connectionStarted = false

    private val timer = object : ReportScheduler.Timer {
        override fun schedule(r: Runnable, delayMs: Long) { main.removeCallbacks(r); main.postDelayed(r, delayMs) }
        override fun cancel(r: Runnable) { main.removeCallbacks(r) }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        scheduler = ReportScheduler(timer = timer)
        manager = TransportManager(this, prefs, main, scheduler)
        manager.uiListener = object : Transport.Listener {
            override fun onStateChanged(t: Transport, state: TransportState, detail: String?) {
                updateNotification(t, state, detail)
                uiListener?.onStateChanged(t, state, detail)
            }
            override fun onRtt(t: Transport, rttMs: Float) { uiListener?.onRtt(t, rttMs) }
            override fun onPairingCodeNeeded(t: Transport, serverName: String) { uiListener?.onPairingCodeNeeded(t, serverName) }
            override fun onServerChoice(t: Transport, names: List<String>) { uiListener?.onServerChoice(t, names) }
        }
        createChannel()
    }

    /** The Activity's listener (set while bound). */
    var uiListener: Transport.Listener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopConnection()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(buildNotification("Menyiapkan...", null))
        if (!connectionStarted) {
            connectionStarted = true
            manager.start()
        }
        return START_STICKY
    }

    fun stopConnection() {
        if (!connectionStarted) return
        connectionStarted = false
        manager.stop()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopConnection()
        super.onDestroy()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String, sub: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(if (sub != null) "$text - $sub" else text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    private fun updateNotification(t: Transport, state: TransportState, detail: String?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, buildNotification("${t.name}: ${stateLabel(state)}", detail))
        } catch (e: Exception) {
            Log.w("notify failed: $e")
        }
    }

    companion object {
        const val CHANNEL = "connection"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "id.keyboardku.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stateLabel(s: TransportState): String = when (s) {
            TransportState.IDLE -> "nonaktif"
            TransportState.PROBING -> "memeriksa"
            TransportState.DISCOVERABLE -> "siap pairing"
            TransportState.PAIRING -> "pairing"
            TransportState.CONNECTING -> "menghubungkan"
            TransportState.CONNECTED -> "terhubung"
            TransportState.RECONNECTING -> "menyambung ulang"
            TransportState.UNSUPPORTED -> "tidak didukung"
            TransportState.ERROR -> "error"
        }
    }
}
