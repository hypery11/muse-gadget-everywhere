package ai.muse.gadgeteverywhere

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python

/** Capability probe. Answers, on-device:
 *  1. Is BLE advertising (peripheral mode) supported? (gating for native pairing)
 *  2. Does upstream musegadget + cryptography import under Chaquopy?
 *  3. Are the two runtime prerequisites granted (overlay for tv.launch,
 *     battery exemption so OEMs don't kill the service)?
 */
class ProbeActivity : Activity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
        val serviceButton = android.widget.Button(this).apply {
            text = getString(R.string.probe_start_service)
            setOnClickListener { GadgetService.start(this@ProbeActivity) }
        }
        val pairButton = android.widget.Button(this).apply {
            text = getString(R.string.probe_pair)
            setOnClickListener {
                startActivity(android.content.Intent(this@ProbeActivity, PairActivity::class.java))
            }
        }
        val resetButton = android.widget.Button(this).apply {
            text = getString(R.string.probe_reset)
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
        val demoButton = android.widget.Button(this).apply {
            text = getString(R.string.probe_demo)
            setOnClickListener {
                Thread {
                    try {
                        val out = Python.getInstance()
                            .getModule("androidtv.probe")
                            .callAttr("probe_demo", TvControl(this@ProbeActivity))
                            .toString()
                        runOnUiThread {
                            output.text = getString(R.string.probe_demo_append, output.text, out)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            output.text = getString(R.string.probe_demo_failed, output.text, e.message ?: e.javaClass.simpleName)
                        }
                    }
                }.start()
            }
        }
        val overlayButton = android.widget.Button(this).apply {
            text = getString(R.string.probe_grant_overlay)
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
        val batteryButton = android.widget.Button(this).apply {
            text = getString(R.string.probe_battery)
            setOnClickListener {
                // OEM task killers murder background services; the exemption
                // list is the user's call, we just deep-link to it.
                openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(serviceButton)
            addView(pairButton)
            addView(resetButton)
            addView(demoButton)
        }
        val setupButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(overlayButton)
            addView(batteryButton)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Android 15 edge-to-edge draws under the status bar; consume
            // the inset as padding on every API level instead.
            fitsSystemWindows = true
            // Four wide buttons overflow narrow phones; scroll instead of
            // wrapping mid-label ("DEM\nO\nTV.*").
            addView(
                android.widget.HorizontalScrollView(this@ProbeActivity).apply {
                    addView(buttons)
                },
            )
            addView(setupButtons)
            addView(
                ScrollView(this@ProbeActivity).apply { addView(output) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(layout)

        val missing = Startup.missingBlePermissions(this) +
            Startup.missingPermissions(this, Startup.NOTIFICATION_PERMISSIONS)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        runProbe()
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

    private fun runProbe() {
        val lines = mutableListOf(
            "model=${Build.MANUFACTURER} ${Build.MODEL}",
            "android=${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})",
        )
        try {
            val adapter: BluetoothAdapter? =
                (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            lines += "bluetooth_enabled=${adapter?.isEnabled}"
            lines += "ble_advertising_supported=${adapter?.isMultipleAdvertisementSupported}"
            // The flag above is advisory; a null advertiser is the real veto
            // (emulators, peripheral-less boxes). Pairing refuses on null.
            lines += "ble_peripheral_ready=${adapter?.bluetoothLeAdvertiser != null}"
            lines += "ble_offloaded_filtering=${adapter?.isOffloadedFilteringSupported}"
        } catch (e: SecurityException) {
            lines += "bluetooth=PERMISSION_DENIED (${e.message ?: e.javaClass.simpleName})"
        }
        try {
            lines += "overlay_granted=${Settings.canDrawOverlays(this)}"
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            lines += "battery_unrestricted=${power.isIgnoringBatteryOptimizations(packageName)}"
        } catch (e: Exception) {
            lines += "prereqs=UNKNOWN (${e.javaClass.simpleName})"
        }
        output.text = getString(R.string.probe_starting_python, lines.joinToString("\n"))

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
                output.text = getString(R.string.probe_output, lines.joinToString("\n"), pyLine)
            }
        }.start()
    }
}
