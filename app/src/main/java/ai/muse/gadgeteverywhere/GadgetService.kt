package ai.muse.gadgeteverywhere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.chaquo.python.Python
import java.lang.ref.WeakReference

/** One in-process runtime with observable connection state and a graceful stop. */
class GadgetService : Service() {
    companion object {
        private const val CHANNEL = "gadget"
        private const val NOTIFICATION_ID = 1
        private var instance = WeakReference<GadgetService>(null)
        @Volatile private var runner: Thread? = null
        @Volatile var lastState = "stopped"
            private set

        fun start(context: Context): Boolean = try {
            val intent = Intent(context, GadgetService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            true
        } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.service_start_failed, Toast.LENGTH_LONG).show()
            false
        }
        fun stop(context: Context) { context.stopService(Intent(context, GadgetService::class.java)) }
        fun connectionChanged(state: String) { instance.get()?.setState(state) }
        fun playbackActive(active: Boolean, source: String = "media") {
            Handler(Looper.getMainLooper()).post {
                instance.get()?.let {
                    if (active) it.playing.add(source) else it.playing.remove(source)
                    it.foreground()
                }
            }
        }
    }
    private var bridge: DeviceBridge? = null
    private var state = "starting"
    private val playing = mutableSetOf<String>()
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        lastState = "starting"
        foreground()
        if (!destroyed && runner?.isAlive != true) bridge = DeviceBridge(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (destroyed) return START_NOT_STICKY
        if (bridge == null) {
            setState("stopping")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!destroyed) {
                    if (runner?.isAlive == true) onStartCommand(intent, flags, startId)
                    else { bridge = DeviceBridge(this); onStartCommand(intent, flags, startId) }
                }
            }, 250)
            return START_STICKY
        }
        if (runner?.isAlive != true) {
            runner = Thread({ runLoop(startId) }, "musegadget").apply { start() }
        } else if (DeviceBridge.current !== bridge) {
            setState("stopping")
            stopSelf(startId)
        }
        return START_STICKY
    }
    private fun runLoop(startId: Int) {
        try {
            Startup.ensurePython(applicationContext)
            if (destroyed) return
            val module = Python.getInstance().getModule("androidtv.service")
            module.callAttr("prepare_start")
            if (destroyed) { module.callAttr("stop"); return }
            module.callAttr("main", filesDir.absolutePath, cacheDir.absolutePath,
                "${Build.MANUFACTURER} ${Build.MODEL}", bridge)
        } catch (e: Exception) {
            state = "error"
            lastState = "error"
            // Stack traces can embed command parameters. Report the class only.
            Log.e("GadgetService", "Runtime stopped: ${e.javaClass.simpleName}")
            Handler(Looper.getMainLooper()).post { setState("error") }
        } finally {
            stopSelf(startId)
        }
    }
    private fun setState(value: String) {
        if (state == value || destroyed) return
        state = value
        lastState = value
        foreground()
    }
    private fun foreground() {
        if (destroyed) return
        try {
            val note = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    if (playing.isEmpty()) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(NOTIFICATION_ID, note, type)
            } else startForeground(NOTIFICATION_ID, note)
        } catch (_: RuntimeException) {
            destroyed = true
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }
    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val title = getString(R.string.notif_channel)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW))
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val label = when (state) {
            "connected" -> R.string.notif_connected
            "unpaired" -> R.string.service_unpaired
            "reconnecting" -> R.string.service_reconnecting
            "error" -> R.string.service_error
            "stopped", "stopping" -> R.string.service_stopped
            else -> R.string.service_connecting
        }
        val pending = PendingIntent.getActivity(this, 0, Intent(this, ControlActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder.setContentTitle(title).setContentText(getString(label)).setContentIntent(pending)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
    }
    override fun onDestroy() {
        destroyed = true
        if (state != "error") lastState = "stopped"
        try { if (bridge != null && Python.isStarted()) Python.getInstance().getModule("androidtv.service").callAttr("stop") } catch (_: Exception) { }
        bridge?.close()
        bridge = null
        if (instance.get() === this) instance.clear()
        super.onDestroy()
    }
}
