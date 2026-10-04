package ai.muse.gadgeteverywhere

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.chaquo.python.Python

/** Capability probe. Answers, on-device:
 *  1. Is BLE advertising (peripheral mode) supported? (gating for native pairing)
 *  2. Does upstream musegadget + cryptography import under Chaquopy?
 *  3. Are the two runtime prerequisites granted (overlay for tv.launch,
 *     battery exemption so OEMs don't kill the service)?
 */
class ProbeActivity : Activity() {

    private lateinit var pyBlock: TextView
    private lateinit var deviceCard: LinearLayout
    private lateinit var btCard: LinearLayout
    private lateinit var prereqCard: LinearLayout
    private lateinit var firstAction: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, content) = Ui.screenFrame(this)

        content.addView(header())
        content.addView(actionsRow())
        content.addView(setupRow())
        deviceCard = section(content, getString(R.string.probe_section_device))
        btCard = section(content, getString(R.string.probe_section_bluetooth))
        prereqCard = section(content, getString(R.string.probe_section_prereqs))
        val pyCard = section(content, getString(R.string.probe_section_python))
        pyBlock = Ui.monoBlock(this)
        pyCard.addView(pyBlock)
        content.addView(footer())

        setContentView(scroll)

        // TV d-pad starts somewhere visible; touch users never notice.
        // Posted: requestFocus before first attach doesn't always stick.
        firstAction.post { firstAction.requestFocus() }

        val missing = Startup.missingBlePermissions(this) +
            Startup.missingPermissions(this, Startup.NOTIFICATION_PERMISSIONS)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        runProbe()
    }

    /** Two-tone brand title + version stamp. */
    private fun header(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.run { this@ProbeActivity.dp(18) })
        }
        val title = SpannableString(
            "${getString(R.string.brand_title_a)} ${getString(R.string.brand_title_b)}",
        )
        val split = getString(R.string.brand_title_a).length
        title.setSpan(
            ForegroundColorSpan(Ui.run { brand(R.color.ink) }),
            0, split, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        title.setSpan(
            ForegroundColorSpan(Ui.run { brand(R.color.accent) }),
            split + 1, title.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        col.addView(
            TextView(this).apply {
                text = title
                textSize = 30f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
        )
        col.addView(
            TextView(this).apply {
                text = getString(R.string.probe_version, Ui.appVersion(this@ProbeActivity))
                textSize = 14f
                setTextColor(Ui.run { brand(R.color.ink_faint) })
            },
        )
        return col
    }

    private fun cardMargin(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = Ui.run { this@ProbeActivity.dp(10) }
        }

    private fun section(parent: LinearLayout, title: String): LinearLayout {
        val card = Ui.card(this).apply { layoutParams = cardMargin() }
        card.addView(Ui.sectionTitle(this, title))
        parent.addView(card)
        return card
    }

    private fun actionsRow(): View {
        val serviceButton = Ui.primaryButton(this, getString(R.string.probe_start_service)).apply {
            setOnClickListener { GadgetService.start(this@ProbeActivity) }
        }
        val pairButton = Ui.primaryButton(this, getString(R.string.probe_pair)).apply {
            setOnClickListener {
                startActivity(Intent(this@ProbeActivity, PairActivity::class.java))
            }
        }
        val resetButton = Ui.secondaryButton(this, getString(R.string.probe_reset)).apply {
            setOnClickListener {
                GadgetService.stop(this@ProbeActivity)
                try {
                    Startup.ensurePython(this@ProbeActivity)
                    Python.getInstance().getModule("androidtv.pairing")
                        .callAttr("reset_pairing", filesDir.absolutePath)
                    android.widget.Toast.makeText(
                        this@ProbeActivity, getString(R.string.probe_reset_done),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@ProbeActivity,
                        getString(R.string.probe_reset_failed, e.message ?: e.javaClass.simpleName),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        val demoButton = Ui.secondaryButton(this, getString(R.string.probe_demo)).apply {
            setOnClickListener {
                Thread {
                    try {
                        val out = Python.getInstance()
                            .getModule("androidtv.probe")
                            .callAttr("probe_demo", TvControl(this@ProbeActivity))
                            .toString()
                        runOnUiThread {
                            pyBlock.text = getString(R.string.probe_demo_append, pyBlock.text, out)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            pyBlock.text = getString(
                                R.string.probe_demo_failed, pyBlock.text,
                                e.message ?: e.javaClass.simpleName,
                            )
                        }
                    }
                }.start()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val gap = Ui.run { this@ProbeActivity.dp(10) }
            listOf(serviceButton, pairButton, resetButton, demoButton).forEach {
                it.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = gap }
                addView(it)
            }
        }
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        // Centered content that overflows (narrow phones) would otherwise
        // open mid-strip with the first buttons cut off; pin to the start.
        // No-op when everything fits.
        scroller.post { scroller.scrollTo(0, 0) }
        firstAction = serviceButton
        return scroller
    }

    private fun setupRow(): View {
        val overlayButton = Ui.secondaryButton(this, getString(R.string.probe_grant_overlay)).apply {
            setOnClickListener {
                // tv.launch needs SYSTEM_ALERT_WINDOW (background-start
                // exemption). No permission needed to open our own page.
                openSettings(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
        val batteryButton = Ui.secondaryButton(this, getString(R.string.probe_battery)).apply {
            setOnClickListener {
                // OEM task killers murder background services; the exemption
                // list is the user's call, we just deep-link to it.
                openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = cardMargin()
            val gap = Ui.run { this@ProbeActivity.dp(10) }
            listOf(overlayButton, batteryButton).forEach {
                it.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = gap }
                addView(it)
            }
        }
    }

    private fun footer(): View {
        return TextView(this).apply {
            text = packageName
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.run { brand(R.color.ink_faint) })
            layoutParams = cardMargin()
        }
    }

    /**
     * Open a system settings page that may not exist on this build
     * (TVs often lack the battery-exemption list). Never crash: say so.
     */
    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                this, getString(R.string.probe_settings_missing),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runProbe()
    }

    /** Green dot = good, red = blocking, amber = needs attention, none = info. */
    private fun toneForGood(ok: Boolean?): Int? = when (ok) {
        true -> Ui.run { brand(R.color.ok) }
        false -> Ui.run { brand(R.color.bad) }
        null -> null
    }

    private fun toneForAttention(granted: Boolean?): Int? = when (granted) {
        true -> Ui.run { brand(R.color.ok) }
        false -> Ui.run { brand(R.color.warn) }
        null -> null
    }

    private fun runProbe() {
        // Device card (static info, no dots).
        deviceCard.removeViews(1, deviceCard.childCount - 1)
        deviceCard.addView(
            Ui.statusRow(
                this, "model", "${Build.MANUFACTURER} ${Build.MODEL}",
            ).first,
        )
        deviceCard.addView(
            Ui.statusRow(
                this, "android",
                "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
            ).first,
        )

        // Bluetooth card.
        btCard.removeViews(1, btCard.childCount - 1)
        try {
            val adapter: BluetoothAdapter? =
                (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            btCard.addView(
                Ui.statusRow(
                    this, "enabled", adapter?.isEnabled.toString(),
                    toneForGood(adapter?.isEnabled),
                ).first,
            )
            btCard.addView(
                Ui.statusRow(
                    this, "advertising",
                    adapter?.isMultipleAdvertisementSupported.toString(),
                    toneForGood(adapter?.isMultipleAdvertisementSupported),
                ).first,
            )
            // The flag above is advisory; a null advertiser is the real veto
            // (emulators, peripheral-less boxes). Pairing refuses on null.
            val peripheral = adapter?.bluetoothLeAdvertiser != null
            btCard.addView(
                Ui.statusRow(
                    this, "peripheral ready", peripheral.toString(),
                    toneForGood(if (adapter == null) null else peripheral),
                ).first,
            )
            btCard.addView(
                Ui.statusRow(
                    this, "offloaded filtering",
                    adapter?.isOffloadedFilteringSupported.toString(),
                ).first,
            )
        } catch (e: SecurityException) {
            btCard.addView(
                Ui.statusRow(
                    this, "bluetooth", "PERMISSION_DENIED",
                    Ui.run { brand(R.color.bad) },
                ).first,
            )
        }

        // Prerequisites card.
        prereqCard.removeViews(1, prereqCard.childCount - 1)
        try {
            val overlay = Settings.canDrawOverlays(this)
            prereqCard.addView(
                Ui.statusRow(
                    this, "overlay granted", overlay.toString(),
                    toneForAttention(overlay),
                ).first,
            )
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val battery = power.isIgnoringBatteryOptimizations(packageName)
            prereqCard.addView(
                Ui.statusRow(
                    this, "battery unrestricted", battery.toString(),
                    toneForAttention(battery),
                ).first,
            )
        } catch (e: Exception) {
            prereqCard.addView(
                Ui.statusRow(
                    this, "prerequisites", "UNKNOWN",
                    Ui.run { brand(R.color.bad) },
                ).first,
            )
        }

        pyBlock.text = getString(R.string.probe_python_waiting)
        Thread {
            val pyLine = try {
                Startup.ensurePython(this)
                val py = Python.getInstance()
                val result = py.getModule("androidtv.probe").callAttr("probe").toString()
                val tv = py.getModule("androidtv.probe")
                    .callAttr("probe_tv", TvControl(this)).toString()
                "python: $result\ntv-bridge: $tv"
            } catch (e: Exception) {
                "python: FAILED ${e.javaClass.simpleName}: ${e.message ?: getString(R.string.err_no_detail)}"
            }
            runOnUiThread {
                pyBlock.text = pyLine
            }
        }.start()
    }
}
