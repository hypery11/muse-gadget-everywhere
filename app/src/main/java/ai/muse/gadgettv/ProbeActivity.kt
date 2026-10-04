package ai.muse.gadgettv

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python

/** Build 0.1: capability probe. Answers, on-device:
 *  1. Is BLE advertising (peripheral mode) supported? (gating for native pairing)
 *  2. Does upstream musegadget + cryptography import under Chaquopy?
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
            text = "Start service"
            setOnClickListener { GadgetService.start(this@ProbeActivity) }
        }
        val pairButton = android.widget.Button(this).apply {
            text = "Pair"
            setOnClickListener {
                startActivity(android.content.Intent(this@ProbeActivity, PairActivity::class.java))
            }
        }
        val resetButton = android.widget.Button(this).apply {
            text = "Reset pairing"
            setOnClickListener {
                GadgetService.stop(this@ProbeActivity)
                try {
                    Startup.ensurePython(this@ProbeActivity)
                    Python.getInstance().getModule("androidtv.pairing")
                        .callAttr("reset_pairing", filesDir.absolutePath)
                    android.widget.Toast.makeText(
                        this@ProbeActivity, "Pairing cleared. Pair again to reconnect.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@ProbeActivity, "Reset failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        val demoButton = android.widget.Button(this).apply {
            text = "Demo tv.*"
            setOnClickListener {
                Thread {
                    try {
                        val out = Python.getInstance()
                            .getModule("androidtv.probe")
                            .callAttr("probe_demo", TvControl(this@ProbeActivity))
                            .toString()
                        runOnUiThread { output.text = "${output.text}\n\ndemo: $out" }
                    } catch (e: Exception) {
                        runOnUiThread { output.text = "${output.text}\n\ndemo FAILED: ${e.message}" }
                    }
                }.start()
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(buttons)
            addView(
                ScrollView(this@ProbeActivity).apply { addView(output) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(layout)

        val missing = Startup.missingBlePermissions(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        runProbe()
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
            lines += "ble_offloaded_filtering=${adapter?.isOffloadedFilteringSupported}"
        } catch (e: SecurityException) {
            lines += "bluetooth=PERMISSION_DENIED (${e.message})"
        }
        output.text = lines.joinToString("\n") + "\n\nstarting python…"

        Thread {
            val pyLine = try {
                Startup.ensurePython(this)
                val py = Python.getInstance()
                val result = py.getModule("androidtv.probe").callAttr("probe").toString()
                val tv = py.getModule("androidtv.probe")
                    .callAttr("probe_tv", TvControl(this)).toString()
                "python: $result\ntv-bridge: $tv"
            } catch (e: Exception) {
                "python: FAILED ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { output.text = lines.joinToString("\n") + "\n\n$pyLine" }
        }.start()
    }
}
