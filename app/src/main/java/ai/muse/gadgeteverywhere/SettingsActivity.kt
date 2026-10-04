package ai.muse.gadgeteverywhere

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import org.json.JSONObject

class SettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val config = AppSettings.read(this)
        val (scroll, content) = Ui.screenFrame(this)
        content.addView(Ui.twoToneTitle(this, getString(R.string.settings_title), "", 28f))
        content.addView(Ui.bodyText(this).apply { text = getString(R.string.settings_help) })
        val textFields = mutableMapOf<String, EditText>()
        val toggles = mutableMapOf<String, Switch>()
        var group = content
        fun section(label: Int, help: Int) {
            val panel = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(0, 8, 0, 20)
                addView(Ui.bodyText(this@SettingsActivity, 15f).apply { text = getString(help) })
            }
            content.addView(Ui.secondaryButton(this, getString(label)).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
                setOnClickListener {
                    panel.visibility = if (panel.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
                    isSelected = panel.visibility == android.view.View.VISIBLE
                }
            })
            content.addView(panel)
            group = panel
        }
        fun field(key: String, label: Int, secret: Boolean = false) {
            val labelView = Ui.bodyText(this, 14f).apply { text = getString(label); setPadding(0, 20, 0, 0) }
            group.addView(labelView)
            val edit = Ui.field(this, getString(label)).apply {
                id = android.view.View.generateViewId()
                labelView.labelFor = id
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
                if (key == "mqtt_port") inputType = InputType.TYPE_CLASS_NUMBER
                setText(config.optString(key))
                setTextColor(getColor(R.color.ink))
                setHintTextColor(getColor(R.color.ink_dim))
                maxLines = 1
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            textFields[key] = edit
            group.addView(edit)
        }
        // This app uses a platform theme; SwitchCompat lacks required text/style attributes.
        @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
        fun toggle(key: String, label: Int) {
            val control = Switch(this).apply {
                text = getString(label)
                setTextColor(getColor(R.color.ink))
                isChecked = config.optBoolean(key, false)
                setPadding(0, 18, 0, 18)
            }
            toggles[key] = control
            group.addView(control)
        }
        section(R.string.settings_home, R.string.settings_home_help)
        field("ha_url", R.string.ha_url)
        field("ha_token", R.string.ha_token, true)
        field("ha_entities", R.string.ha_entities)
        toggle("ha_events", R.string.ha_events)
        toggle("ha_actions", R.string.ha_actions)
        section(R.string.settings_mqtt, R.string.settings_mqtt_help)
        field("mqtt_host", R.string.mqtt_host)
        field("mqtt_port", R.string.mqtt_port)
        field("mqtt_prefix", R.string.mqtt_prefix)
        field("mqtt_user", R.string.mqtt_user)
        field("mqtt_password", R.string.mqtt_password, true)
        toggle("mqtt_tls", R.string.mqtt_tls)
        toggle("mqtt_publish", R.string.mqtt_publish)
        section(R.string.settings_privacy, R.string.settings_privacy_help)
        toggle("notify_muse", R.string.notify_muse)
        toggle("developer", R.string.developer_mode)
        content.addView(Ui.primaryButton(this, getString(R.string.save_settings)).apply {
            setOnClickListener {
                val save = {
                    val result = JSONObject()
                    textFields.forEach { (key, view) ->
                        val value = view.text.toString().trim()
                        if (value.isNotEmpty()) result.put(key, value)
                    }
                    toggles.forEach { (key, view) -> result.put(key, view.isChecked) }
                    val port = result.optString("mqtt_port")
                    if (port.isNotEmpty() && (port.toIntOrNull() ?: 0) !in 1..65535) {
                        Toast.makeText(this@SettingsActivity, R.string.invalid_port, Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            AppSettings.write(this@SettingsActivity, result)
                            GadgetService.stop(this@SettingsActivity)
                            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_LONG).show()
                            finish()
                        } catch (_: Exception) {
                            Toast.makeText(this@SettingsActivity, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (toggles.getValue("developer").isChecked && !config.optBoolean("developer")) {
                    AlertDialog.Builder(this@SettingsActivity).setTitle(R.string.developer_mode)
                        .setMessage(R.string.developer_warning)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.enable_developer) { _, _ -> save() }.show()
                } else save()
            }
        })
        setContentView(scroll)
    }
}
