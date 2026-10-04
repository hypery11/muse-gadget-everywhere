package ai.muse.gadgeteverywhere

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.chaquo.python.Python
import java.io.File

/** Pairing screen: opens a 10-minute BLE setup window for the Muse app. */
class PairActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var tokenValue: TextView
    private lateinit var tokenDot: View
    private var transport: BleTransport? = null
    @Volatile private var pairing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, content) = Ui.screenFrame(this)

        content.addView(
            Ui.twoToneTitle(
                this,
                getString(R.string.pair_title_a),
                getString(R.string.pair_title_b),
                28f,
            ).apply {
                setPadding(0, 0, 0, Ui.run { this@PairActivity.dp(16) })
            },
        )

        // Token state card: users see BEFORE tapping whether the import
        // file is staged, instead of learning it from an error.
        val tokenCard = Ui.card(this)
        val (tokenRow, valueView) = Ui.statusRow(
            this, getString(R.string.pair_token_label),
            getString(R.string.pair_token_missing),
            Ui.run { brand(R.color.warn) },
        )
        tokenDot = tokenRow.getChildAt(0)
        tokenValue = valueView
        tokenCard.addView(tokenRow)
        content.addView(tokenCard)
        updateTokenRow()

        val statusCard = Ui.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.run { this@PairActivity.dp(12) } }
        }
        status = Ui.bodyText(this).apply {
            text = getString(R.string.pair_ready)
        }
        statusCard.addView(status)
        content.addView(statusCard)

        startButton = Ui.primaryButton(this, getString(R.string.pair_open_setup)).apply {
            setOnClickListener { startPairing() }
        }
        cancelButton = Ui.secondaryButton(this, getString(R.string.pair_cancel)).apply {
            setOnClickListener { finish() }
        }
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.run { this@PairActivity.dp(18) } }
                val gap = Ui.run { this@PairActivity.dp(10) }
                listOf(startButton, cancelButton).forEach {
                    it.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = gap }
                    addView(it)
                }
            },
        )

        setContentView(scroll)
        startButton.requestFocus()
        ensurePermissions()
    }

    /** Refresh the token row from the import file. Cheap; call on show
     *  and after any import attempt (users stage the file between taps). */
    private fun updateTokenRow() {
        val staged = try {
            val dir = getExternalFilesDir("import")
            dir != null && File(dir, "muse_token.txt").let { it.exists() && it.readText().isNotBlank() }
        } catch (_: Exception) {
            false
        }
        tokenValue.text = getString(
            if (staged) R.string.pair_token_staged else R.string.pair_token_missing,
        )
        tokenDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(
                Ui.run {
                    brand(if (staged) R.color.ok else R.color.warn)
                },
            )
        }
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
        if (!granted) status.text = getString(R.string.pair_perm_denied)
    }

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    /** Non-null when BLE peripheral mode can't work; the text says why. */
    private fun peripheralBlocker(): String? {
        return try {
            val manager = getSystemService(android.bluetooth.BluetoothManager::class.java)
            val adapter = manager?.adapter
                ?: return getString(R.string.pair_no_adapter)
            if (!adapter.isEnabled) return getString(R.string.pair_bt_off)
            // Null advertiser is the only veto. isMultipleAdvertisementSupported
            // means >1 SIMULTANEOUS sets; a single-ad chipset runs our one
            // advertisement fine, so it must not refuse (matches Probe's
            // ble_peripheral_ready and BleTransport.open's own check).
            if (adapter.bluetoothLeAdvertiser == null) {
                return getString(R.string.pair_no_peripheral)
            }
            null
        } catch (e: SecurityException) {
            getString(R.string.pair_perm_revoked, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun startPairing() {
        if (pairing) return
        pairing = true
        startButton.visibility = View.GONE
        setStatus(getString(R.string.pair_starting))
        Thread({ runPairing() }, "pairing").start()
    }

    private fun runPairing() {
        try {
            Startup.ensurePython(this)
            val py = Python.getInstance()
            val filesDir = filesDir.absolutePath

            // SDK token: adb push it to the import dir, no permission needed:
            // /sdcard/Android/data/ai.muse.gadgeteverywhere/files/import/muse_token.txt
            var sdkToken: String? = null
            val importDir = getExternalFilesDir("import")
            if (importDir == null) {
                setStatus(getString(R.string.pair_no_storage))
                pairing = false
                runOnUiThread { startButton.visibility = View.VISIBLE }
                return
            }
            val importFile = File(importDir, "muse_token.txt")
            if (importFile.exists()) {
                val token = importFile.readText().trim()
                if (token.isNotEmpty()) {
                    try {
                        py.getModule("androidtv.pairing")
                            .callAttr("save_sdk_token", filesDir, token)
                    } catch (e: Exception) {
                        setStatus(getString(R.string.pair_bad_token, importFile.absolutePath, e.message ?: e.javaClass.simpleName))
                        pairing = false
                        runOnUiThread { startButton.visibility = View.VISIBLE }
                        return
                    }
                    sdkToken = token
                    setStatus(getString(R.string.pair_token_saved))
                }
            }
            runOnUiThread { updateTokenRow() }
            if (sdkToken == null) {
                setStatus(getString(R.string.pair_no_token, importFile.absolutePath))
                pairing = false
                runOnUiThread { startButton.visibility = View.VISIBLE }
                return
            }

            val bleName = py.getModule("androidtv.pairing")
                .callAttr("get_ble_name", filesDir)
                .toString()
            // Fail fast with guidance on peripheral-less hardware
            // (emulators, some boxes): transport.open would throw anyway,
            // but this says WHY instead of an exception class name.
            val peripheralError = peripheralBlocker()
            if (peripheralError != null) {
                setStatus(getString(R.string.pair_cant_pair, peripheralError))
                pairing = false
                runOnUiThread { startButton.visibility = View.VISIBLE }
                return
            }
            val transport = BleTransport().also { this.transport = it }
            transport.open(this, bleName)
            val recordName = transport.adapterName(this)
            setStatus(getString(R.string.pair_setup_open, bleName, recordName))
            val paired = py.getModule("androidtv.pairing")
                .callAttr("run_pairing", transport, filesDir, sdkToken, 600)
                .toJava(Boolean::class.java)
            setStatus(
                if (paired) getString(R.string.pair_paired)
                else getString(R.string.pair_window_closed),
            )
        } catch (e: Exception) {
            setStatus(getString(R.string.pair_failed, e.javaClass.simpleName, e.message ?: getString(R.string.err_no_detail)))
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
