package ai.muse.gadgettv

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager

/** Typed result across the Java/Python boundary (never "ok:…"/"error:…" strings). */
data class TvResult(val ok: Boolean, val message: String)

/**
 * TV hardware access for the Python layer. Passed into
 * `androidtv.service.main` so `tv.*` commands can launch apps and learn the
 * dongle's own LAN address (for Cast-to-self) without new permissions:
 * starting exported activities and reading the Wi-Fi address need none.
 */
class TvControl(private val context: Context) {

    /**
     * Launch [target]: an app package (`com.google.android.youtube.tv`),
     * a URL (`https://…`, `intent://…`), or an `#Intent;…` URI.
     */
    fun launch(target: String): TvResult {
        // Android 10+ blocks background activity starts from a service and
        // fails SILENTLY (no exception). canDrawOverlays is the exemption we
        // use; without it, say so instead of reporting a fake ok. The gate
        // applies to foreground callers too by design: one uniform
        // prerequisite, granted once at setup (see README TV setup step 5).
        if (!android.provider.Settings.canDrawOverlays(context)) {
            return TvResult(
                false,
                "overlay permission missing; allow it once with: " +
                    "adb shell appops set ai.muse.gadgettv SYSTEM_ALERT_WINDOW allow",
            )
        }
        return try {
            val intent = if (isPackageName(target)) {
                launchIntentForPackage(target)
                    ?: return TvResult(false, "no launch intent for package $target")
            } else {
                Intent.parseUri(target, Intent.URI_INTENT_SCHEME)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            TvResult(true, "launched $target")
        } catch (e: Exception) {
            TvResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** "Google Chromecast / android 12 (sdk 31)" for device.health. Never fails. */
    fun deviceModel(): TvResult {
        return TvResult(
            true,
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / " +
                "android ${android.os.Build.VERSION.RELEASE} " +
                "(sdk ${android.os.Build.VERSION.SDK_INT})",
        )
    }

    /** Seconds since boot for device.health (apps can't read /proc/uptime). */
    fun uptimeSeconds(): TvResult {
        return TvResult(true, (android.os.SystemClock.elapsedRealtime() / 1000).toString())
    }

    /** The dongle's own Wi-Fi IPv4 address, for Cast-to-self. */
    @Suppress("DEPRECATION")
    fun deviceIp(): TvResult {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wifi.connectionInfo.ipAddress
            if (raw == 0) return TvResult(false, "no Wi-Fi address")
            val dotted = listOf(0, 8, 16, 24).joinToString(".") { shift ->
                ((raw shr shift) and 0xFF).toString()
            }
            TvResult(true, dotted)
        } catch (e: Exception) {
            TvResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun launchIntentForPackage(pkg: String): Intent? {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        // TV apps declare LEANBACK_LAUNCHER, which getLaunchIntentForPackage
        // never matches. Resolve one explicitly.
        val query = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(pkg)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(query, 0).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
    }

    private fun isPackageName(target: String): Boolean {
        if (target.contains("://") || target.contains(";") || target.contains("/")) return false
        return target.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))
    }
}
