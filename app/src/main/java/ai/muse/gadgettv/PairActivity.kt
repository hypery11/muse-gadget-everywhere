package ai.muse.gadgettv

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python
import java.io.File

/** Pairing screen: opens a 10-minute BLE setup window for the Muse app. */
class PairActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private var transport: BleTransport? = null
    @Volatile private var pairing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 20f
            setPadding(48, 48, 48, 48)
            text = "Ready to pair."
        }
        startButton = Button(this).apply {
            text = "Open setup (10 min)"
            setOnClickListener { startPairing() }
        }
        cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(startButton)
            addView(cancelButton)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(buttons)
                addView(ScrollView(this@PairActivity).apply { addView(status) })
            },
        )
        startButton.requestFocus()
        ensurePermissions()
    }

    private fun ensurePermissions() {
        val missing = Startup.missingBlePermissions(this)
        if (missing.isNotEmpty()) {
            startButton.isEnabled = false
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        startButton.isEnabled = granted
        if (!granted) status.text = "Bluetooth permission denied.\n\nAllow nearby-devices access, then reopen this screen."
    }

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    private fun startPairing() {
        if (pairing) return
        pairing = true
        startButton.visibility = View.GONE
        setStatus("Starting…")
        Thread({ runPairing() }, "pairing").start()
    }

    private fun runPairing() {
        try {
            Startup.ensurePython(this)
            val py = Python.getInstance()
            val filesDir = filesDir.absolutePath

            // SDK token: adb push it to the import dir, no permission needed:
            // /sdcard/Android/data/ai.muse.gadgettv/files/import/muse_token.txt
            var sdkToken: String? = null
            val importFile = File(getExternalFilesDir("import"), "muse_token.txt")
            if (importFile.exists()) {
                val token = importFile.readText().trim()
                if (token.isNotEmpty()) {
                    try {
                        py.getModule("androidtv.pairing")
                            .callAttr("save_sdk_token", filesDir, token)
                    } catch (e: Exception) {
                        setStatus("Bad SDK token in:\n${importFile.absolutePath}\n\n${e.message}\n\nFix the file, then reopen this screen.")
                        pairing = false
                        runOnUiThread { startButton.visibility = View.VISIBLE }
                        return
                    }
                    sdkToken = token
                    setStatus("SDK token saved.\n\nStarting…")
                }
            }
            if (sdkToken == null) {
                setStatus("No SDK token found.\n\nPush one to:\n${importFile.absolutePath}\n\nThen reopen this screen.")
                pairing = false
                runOnUiThread { startButton.visibility = View.VISIBLE }
                return
            }

            val bleName = py.getModule("androidtv.pairing")
                .callAttr("get_ble_name", filesDir)
                .toString()
            val transport = BleTransport(this).also { this.transport = it }
            transport.open(bleName)
            val recordName = transport.adapterName()
            setStatus(
                "Setup open as:\n\n$bleName\n\n" +
                    "On-air Bluetooth name: $recordName\n" +
                    "(apps can't rename it; if the Muse app can't find us, " +
                    "rename this Chromecast to $bleName in Settings > System > About > Device name.)\n\n" +
                    "In the Muse app: Settings > Devices > Add Device.\n\n" +
                    "Waiting (10 min window)…",
            )
            val paired = py.getModule("androidtv.pairing")
                .callAttr("run_pairing", transport, filesDir, sdkToken, 600)
                .toJava(Boolean::class.java)
            setStatus(if (paired) "Paired!\n\nStart the service from the main screen." else "Window closed without pairing.")
        } catch (e: Exception) {
            setStatus("Pairing failed:\n${e.javaClass.simpleName}: ${e.message}")
        } finally {
            pairing = false
            try {
                transport?.shutdown()
            } catch (_: Exception) {
            }
            transport = null
        }
    }

    override fun onDestroy() {
        try {
            transport?.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
