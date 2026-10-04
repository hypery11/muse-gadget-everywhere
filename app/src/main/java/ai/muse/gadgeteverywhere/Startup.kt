package ai.muse.gadgeteverywhere

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** App-startup prerequisites shared by every entry point. */
object Startup {
    /** Boot the Chaquopy runtime once; safe to call from any thread. */
    @Synchronized
    fun ensurePython(context: Context) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
    }

    /** BLE permissions the pairing window needs (Android 12+ runtime model). */
    val BLE_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            emptyArray()
        }

    /** Subset of [BLE_PERMISSIONS] the user hasn't granted yet. */
    fun missingBlePermissions(activity: Activity): List<String> =
        missingPermissions(activity, BLE_PERMISSIONS)

    /** Notification runtime permission (Android 13+). The foreground
     *  service's own notification is exempt, but the service-ERROR alert
     *  (`showError`, plain `notify`) needs this grant to be seen. */
    val NOTIFICATION_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** Subset of [perms] the user hasn't granted yet (23+ API). */
    fun missingPermissions(activity: Activity, perms: Array<String>): List<String> =
        perms.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
}
