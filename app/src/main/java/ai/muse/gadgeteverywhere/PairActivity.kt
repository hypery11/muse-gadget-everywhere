package ai.muse.gadgeteverywhere

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import com.chaquo.python.Python
import java.io.File

/** Pairing screen: opens a 10-minute BLE setup window for the Muse app. */
class PairActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var tokenValue: TextView
    private lateinit var tokenDot: View
    private lateinit var continueButton: Button
    private var transport: BleTransport? = null
    @Volatile private var pairing = false
    @Volatile private var closed = false

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

        content.addView(Ui.bodyText(this, 15f).apply { text = getString(R.string.pair_intro) })
        if (File(filesDir, "musegadget/pairing.json").isFile) {
            content.addView(Ui.bodyText(this, 15f).apply { text = getString(R.string.pair_already) })
            content.addView(controlsButton())
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.pair_step_token)))
        content.addView(Ui.bodyText(this, 15f).apply { text = getString(R.string.pair_token_help) })
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
        val input = Ui.field(this, getString(R.string.pair_token_input)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        tokenCard.addView(input)
        tokenCard.addView(Ui.secondaryButton(this, getString(R.string.pair_save_token)).apply {
            setOnClickListener {
                val token = input.text.toString()
                isEnabled = false
                Thread {
                    try {
                        Startup.ensurePython(applicationContext)
                        Python.getInstance().getModule("androidtv.pairing").callAttr("save_sdk_token", filesDir.absolutePath, token)
                        runOnUiThread { input.text.clear(); updateTokenRow(); status.text = getString(R.string.pair_token_saved_local) }
                    } catch (_: Exception) { setStatus(getString(R.string.pair_invalid_token)) }
                    finally { runOnUiThread { isEnabled = true } }
                }.start()
            }
        })
        updateTokenRow()

        content.addView(Ui.sectionTitle(this, getString(R.string.pair_step_phone)))
        content.addView(Ui.bodyText(this, 15f).apply { text = getString(R.string.pair_phone_help) })
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
            setOnClickListener { if (ensurePermissions()) startPairing() }
        }
        cancelButton = Ui.secondaryButton(this, getString(R.string.pair_cancel)).apply {
            setOnClickListener { finish() }
        }
        content.addView(Ui.actions(this, startButton, cancelButton))
        continueButton = controlsButton().apply { visibility = View.GONE }
        content.addView(continueButton)

        setContentView(scroll)
        startButton.requestFocus()
    }

    /** Refresh the token row from the import file. Cheap; call on show
     *  and after any import attempt (users stage the file between taps). */
    private fun updateTokenRow() {
        val staged = try {
            val dir = getExternalFilesDir("import")
            File(filesDir, "musegadget/sdk_token").isFile || (dir != null && File(dir, "muse_token.txt").let { it.isFile && it.length() in 1..4096 })
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

    private fun controlsButton(): Button = Ui.primaryButton(this, getString(R.string.pair_start_controls)).apply {
        setOnClickListener {
            GadgetService.start(this@PairActivity)
            startActivity(android.content.Intent(this@PairActivity, ControlActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    private fun ensurePermissions(): Boolean {
        val missing = Startup.missingBlePermissions(this)
        if (missing.isNotEmpty()) {
            startButton.isEnabled = false
            requestPermissions(missing.toTypedArray(), 1)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        startButton.isEnabled = true
        if (granted) startPairing()
        else status.text = getString(R.string.pair_retry_permissions)
    }

    private fun setStatus(text: String) {
        runOnUiThread { if (!closed) status.text = text }
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

            val module = py.getModule("androidtv.pairing")
            val importFile = getExternalFilesDir("import")?.let { File(it, "muse_token.txt") }
            if (importFile?.isFile == true) {
                require(importFile.length() <= 4096) { "SDK token import is too large" }
                module.callAttr("save_sdk_token", filesDir, importFile.readText())
                check(importFile.delete()) { "Could not remove imported token" }
            }
            val sdkToken = module.callAttr("get_sdk_token", filesDir)?.toJava(String::class.java)
                ?: throw IllegalStateException("Save an SDK token before opening setup")
            runOnUiThread { updateTokenRow() }
            if (closed) return

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
            if (closed) return
            transport.open(this, bleName)
            if (closed) { transport.shutdown(); return }
            val recordName = transport.adapterName(this)
            setStatus(getString(R.string.pair_setup_open, bleName, recordName))
            val paired = py.getModule("androidtv.pairing")
                .callAttr("run_pairing", transport, filesDir, sdkToken, 600)
                .toJava(Boolean::class.java)
            if (paired) runOnUiThread { if (!closed) continueButton.visibility = View.VISIBLE }
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
            runOnUiThread { if (!closed) startButton.visibility = View.VISIBLE }
        }
    }

    override fun onDestroy() {
        closed = true
        try {
            transport?.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
