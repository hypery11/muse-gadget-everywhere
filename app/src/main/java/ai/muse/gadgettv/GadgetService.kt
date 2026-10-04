package ai.muse.gadgettv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python

/** Foreground service hosting the musegadget run loop. */
class GadgetService : Service() {

    companion object {
        private const val TAG = "GadgetService"
        private const val CHANNEL = "gadget"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, GadgetService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GadgetService::class.java))
        }
    }

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Muse Gadget", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Muse Gadget")
            .setContentText("Connected to your Muse")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            Thread({ runLoop() }, "musegadget").start()
        }
        return START_STICKY
    }

    private fun runLoop() {
        try {
            Startup.ensurePython(this)
            val displayName = "${Build.MANUFACTURER} ${Build.MODEL}"
            Python.getInstance()
                .getModule("androidtv.service")
                .callAttr(
                    "main", filesDir.absolutePath, cacheDir.absolutePath,
                    displayName, TvControl(this),
                )
        } catch (e: Exception) {
            Log.e(TAG, "service loop failed", e)
            showError("Muse Gadget stopped: ${e.javaClass.simpleName}")
        } finally {
            running = false
            stopSelf()
        }
    }

    private fun showError(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setContentTitle("Muse Gadget")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .build(),
        )
    }

    override fun onDestroy() {
        // The asyncio loop has no remote stop hook. This service runs in
        // :gadget, so killing our process cannot touch the UI process.
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
