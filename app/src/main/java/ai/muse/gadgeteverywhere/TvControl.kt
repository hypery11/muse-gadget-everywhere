package ai.muse.gadgeteverywhere

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager

/** Typed result across the Java/Python boundary (never "ok:…"/"error:…" strings). */
data class TvResult(val ok: Boolean, val message: String)

/**
 * Device hardware access for the Python layer. Passed into
 * `androidtv.service.main` so `tv.*` commands can launch apps and learn this
 * device's own LAN address (for Cast-to-self) without new permissions:
 * starting exported activities and reading interface addresses need none.
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
        if (GadgetApplication.visibleActivity.get() == null && !android.provider.Settings.canDrawOverlays(context)) {
            return TvResult(
                false,
                "overlay permission missing; on the Probe screen tap Grant " +
                    "overlay, or run once: adb shell appops set " +
                    "ai.muse.gadgeteverywhere SYSTEM_ALERT_WINDOW allow",
            )
        }
        return try {
            val intent = if (isPackageName(target)) {
                launchIntentForPackage(target)
                    ?: return TvResult(false, "no launch intent for package $target")
            } else {
                val uri = android.net.Uri.parse(target)
                require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "target must be an app package or HTTP(S) URL" }
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            TvResult(true, "dispatched $target")
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

    /** This device's own LAN IPv4 address, for Cast-to-self. */
    @Suppress("DEPRECATION")
    fun deviceIp(): TvResult {
        // Fast path: Wi-Fi address, no permission needed.
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wifi.connectionInfo.ipAddress
            if (raw != 0) {
                val dotted = listOf(0, 8, 16, 24).joinToString(".") { shift ->
                    ((raw shr shift) and 0xFF).toString()
                }
                return TvResult(true, dotted)
            }
        } catch (e: Exception) {
            // Fall through to interface enumeration.
        }
        // Slow path: Ethernet-only boxes, USB tethering, VPN-less oddballs.
        // NetworkInterface needs no permission either.
        return try {
            val addrs = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .toList()
            if (addrs.isEmpty()) TvResult(false, "no LAN IPv4 address")
            else TvResult(true, addrs.first().hostAddress ?: "no LAN IPv4 address")
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
