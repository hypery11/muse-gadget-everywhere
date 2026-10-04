package ai.muse.gadgeteverywhere

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import ai.muse.gadgeteverywhere.Ui.dp

/** Task navigation adapts to the available window; every action uses the shared runtime. */
class ControlActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var connection: TextView
    private val navigation = mutableListOf<Button>()
    private var page = 0
    private var message = ""
    private var mediaUrl = ""
    private var sceneName = ""
    private var sceneInterval = ""
    private var messageField: EditText? = null
    private var mediaField: EditText? = null
    private var nameField: EditText? = null
    private var intervalField: EditText? = null
    private var selectedCast: String? = null
    private var selectedCastName = ""
    private var castLabel: TextView? = null
    private var homeState: TextView? = null
    private var homeHelp: TextView? = null
    private var serviceButton: Button? = null
    private var resultView: TextView? = null
    private var details: TextView? = null
    private var lastResult: JSONObject? = null
    private var busy = false
    private val commandButtons = mutableListOf<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateConnection(); handler.postDelayed(this, 1000) }
    }

    // API 24 is the minimum; platform drawable loading/tint APIs are available.
    @android.annotation.SuppressLint("UseCompatLoadingForDrawables")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = state?.getInt("page") ?: 0
        message = state?.getString("message") ?: getString(R.string.demo_message)
        mediaUrl = state?.getString("url") ?: ""
        sceneName = state?.getString("sceneName") ?: ""
        sceneInterval = state?.getString("sceneInterval") ?: ""
        selectedCast = state?.getString("cast")
        selectedCastName = state?.getString("castName") ?: ""
        val wide = resources.configuration.screenWidthDp >= 840
        val root = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(if (wide) 40 else 16), dp(if (wide) 24 else 8), dp(if (wide) 40 else 16), dp(if (wide) 24 else 8))
        }
        val sidebar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = Ui.twoToneTitle(this, "Muse Gadget", if (wide) "\nEverywhere" else "", if (wide) 24f else 20f)
        connection = Ui.bodyText(this, 13f).apply {
            setTextColor(getColor(R.color.ink_dim))
            setPadding(0, dp(6), 0, dp(16))
        }
        sidebar.addView(brand)
        sidebar.addView(connection)
        val nav = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (!wide) setPadding(0, dp(8), 0, 0)
        }
        val labels = intArrayOf(R.string.nav_home, R.string.nav_display, R.string.nav_media, R.string.nav_scenes, R.string.nav_device)
        val icons = intArrayOf(R.drawable.ic_home, R.drawable.ic_display, R.drawable.ic_media, R.drawable.ic_scenes, R.drawable.ic_device)
        labels.forEachIndexed { index, label ->
            val button = Ui.secondaryButton(this, getString(label)).apply {
                tag = "nav_$index"
                textSize = if (wide) 16f else 11f
                minHeight = dp(if (wide) 52 else 64)
                setPadding(dp(if (wide) 14 else 2), dp(6), dp(if (wide) 12 else 2), dp(6))
                gravity = if (wide) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
                setBackgroundResource(R.drawable.nav_states)
                val icon = getDrawable(icons[index])!!.mutate().apply { setBounds(0, 0, dp(22), dp(22)) }
                if (wide) setCompoundDrawablesRelative(icon, null, null, null)
                else setCompoundDrawablesRelative(null, icon, null, null)
                compoundDrawablePadding = dp(if (wide) 12 else 4)
                layoutParams = LinearLayout.LayoutParams(if (wide) -1 else 0, -2, if (wide) 0f else 1f).apply {
                    if (wide) topMargin = dp(8)
                }
                setOnClickListener { selectPage(index) }
            }
            navigation.add(button); nav.addView(button)
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        if (wide) {
            sidebar.addView(nav)
            sidebar.addView(Ui.bodyText(this, 12f).apply {
                text = getString(R.string.probe_version, Ui.appVersion(this@ControlActivity))
                setTextColor(getColor(R.color.ink_dim)); setPadding(0, dp(20), 0, 0)
            })
            // A short landscape or enlarged text can scroll the rail without losing destinations.
            root.addView(ScrollView(this).apply { addView(sidebar) }, LinearLayout.LayoutParams(dp(180), -1).apply { marginEnd = dp(32) })
            root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            root.addView(sidebar)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(nav)
        }
        val insetFrame = android.widget.FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.bg))
            addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(insetFrame)
        selectPage(page)
        if (resources.configuration.navigation == android.content.res.Configuration.NAVIGATION_DPAD) navigation[page].requestFocus()
    }

    private fun rememberFields() {
        messageField?.let { message = it.text.toString() }
        mediaField?.let { mediaUrl = it.text.toString() }
        nameField?.let { sceneName = it.text.toString() }
        intervalField?.let { sceneInterval = it.text.toString() }
    }
    @android.annotation.SuppressLint("UseCompatTextViewDrawableApis")
    private fun selectPage(index: Int) {
        rememberFields()
        page = index.coerceIn(0, 4)
        messageField = null; mediaField = null; nameField = null; intervalField = null
        homeState = null; homeHelp = null; serviceButton = null; castLabel = null; resultView = null; details = null
        commandButtons.clear(); content.removeAllViews()
        navigation.forEachIndexed { i, button ->
            button.isSelected = i == page
            val color = getColor(if (i == page) R.color.accent else R.color.ink_dim)
            button.setTextColor(color); button.compoundDrawableTintList = ColorStateList.valueOf(color)
        }
        when (page) { 0 -> home(); 1 -> display(); 2 -> media(); 3 -> scenes(); 4 -> device() }
        if (page != 0) resultArea()
        updateConnection()
        scroll.post { scroll.scrollTo(0, 0) }
    }
    private fun heading(title: Int, help: Int) {
        content.addView(Ui.twoToneTitle(this, getString(title), "", 28f).apply { Ui.run { accessibilityHeadingCompat() } })
        content.addView(Ui.bodyText(this, 15f).apply {
            text = getString(help); setTextColor(getColor(R.color.ink_dim)); setPadding(0, dp(6), 0, dp(12))
        })
    }
    private fun text(label: Int): TextView = Ui.bodyText(this, 15f).apply { setText(label); setTextColor(getColor(R.color.ink_dim)) }
    private fun action(label: Int, primary: Boolean = false, command: Boolean = true, block: () -> Unit): Button {
        val button = if (primary) Ui.primaryButton(this, getString(label)) else Ui.secondaryButton(this, getString(label))
        button.setOnClickListener { block() }
        if (command) { commandButtons.add(button); button.isEnabled = !busy }
        return button
    }
    private fun field(label: Int, value: String, multiline: Boolean = false): EditText {
        val edit = Ui.field(this, getString(label), multiline).apply { setText(value) }
        content.addView(Ui.bodyText(this, 14f).apply { setText(label); labelFor = View.generateViewId().also { edit.id = it }; setPadding(0, dp(12), 0, 0) })
        content.addView(edit)
        return edit
    }
    private fun home() {
        heading(R.string.home_title, R.string.home_intro)
        val statusPanel = Ui.card(this)
        homeState = Ui.bodyText(this, 22f).also { statusPanel.addView(it) }
        homeHelp = text(R.string.home_stopped_help).also { it.setPadding(0, dp(6), 0, dp(8)); statusPanel.addView(it) }
        serviceButton = action(R.string.probe_start_service, true, false) {
            if (GadgetService.lastState in listOf("stopped", "error")) {
                val missing = Startup.missingPermissions(this, Startup.NOTIFICATION_PERMISSIONS)
                if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
                GadgetService.start(this)
            } else GadgetService.stop(this)
            updateConnection()
        }
        statusPanel.addView(Ui.actions(this, serviceButton!!, action(R.string.home_pair, command = false) { startActivity(Intent(this, PairActivity::class.java)) }))
        content.addView(statusPanel)
        content.addView(Ui.sectionTitle(this, getString(R.string.home_try)))
        content.addView(text(R.string.home_try_help))
        content.addView(action(R.string.home_try_card, command = false) { selectPage(1) })
        content.addView(Ui.sectionTitle(this, getString(R.string.home_cloud)))
        content.addView(text(R.string.home_cloud_help))
    }
    private fun display() {
        heading(R.string.control_display, R.string.display_help)
        messageField = field(R.string.control_message, message, true)
        content.addView(Ui.actions(this,
            action(R.string.control_show, true) { withMessage { run("screen.show", JSONObject().put("title", getString(R.string.app_name)).put("text", it)) } },
            action(R.string.control_speak) { withMessage { run("speech.say", JSONObject().put("text", it)) } }))
        content.addView(Ui.actions(this, action(R.string.clear_card) { run("screen.clear") }, action(R.string.control_silence) { run("speech.stop") }))
        content.addView(Ui.sectionTitle(this, getString(R.string.check_activity)))
        content.addView(Ui.actions(this, action(R.string.screen_status) { run("screen.status") }, action(R.string.speech_status) { run("speech.status") }))
    }
    private fun withMessage(block: (String) -> Unit) {
        rememberFields()
        if (message.isBlank()) { messageField?.error = getString(R.string.message_required); messageField?.requestFocus() }
        else block(message)
    }
    private fun withUrl(block: (String) -> Unit) {
        rememberFields()
        if (mediaUrl.isBlank()) { mediaField?.error = getString(R.string.url_required); mediaField?.requestFocus() }
        else block(mediaUrl.trim())
    }
    private fun media() {
        heading(R.string.control_media, R.string.media_help)
        mediaField = field(R.string.control_media_url, mediaUrl).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        content.addView(action(R.string.control_play, true) { withUrl { run("media.play", JSONObject().put("url", it)) } })
        content.addView(Ui.actions(this, action(R.string.control_pause) { run("media.control", JSONObject().put("action", "pause")) }, action(R.string.control_resume) { run("media.control", JSONObject().put("action", "resume")) }, action(R.string.control_stop) { run("media.control", JSONObject().put("action", "stop")) }))
        content.addView(Ui.actions(this, action(R.string.media_status) { run("media.status") }, action(R.string.control_cache) { cacheMedia() }))
        content.addView(Ui.sectionTitle(this, getString(R.string.cast_heading)))
        castLabel = text(R.string.cast_empty).apply { if (selectedCast != null) text = getString(R.string.cast_selected, selectedCastName) }.also { content.addView(it) }
        content.addView(Ui.actions(this, action(R.string.control_cast) { discoverCast() }, action(R.string.control_cast_play) {
            if (selectedCast == null) feedback(getString(R.string.cast_empty))
            else withUrl { run("tv.cast", JSONObject().put("device", selectedCast).put("action", "play_url").put("url", it)) }
        }))
    }
    private fun scenes() {
        heading(R.string.nav_scenes, R.string.scene_help)
        messageField = field(R.string.control_message, message, true)
        nameField = field(R.string.scene_name, sceneName)
        intervalField = field(R.string.scene_interval, sceneInterval).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        content.addView(action(R.string.control_scene, true) {
            withMessage {
                val seconds = sceneInterval.toIntOrNull()
                when {
                    !sceneName.matches(Regex("[A-Za-z0-9_-]{1,64}")) -> { nameField?.error = getString(R.string.scene_invalid_name); nameField?.requestFocus() }
                    sceneInterval.isNotBlank() && (seconds == null || seconds !in 60..604800) -> { intervalField?.error = getString(R.string.scene_invalid_interval); intervalField?.requestFocus() }
                    else -> {
                        val actions = JSONArray().put(JSONObject().put("command", "screen.show").put("params", JSONObject().put("text", message)))
                        val params = JSONObject().put("id", sceneName).put("actions", actions)
                        if (seconds != null) params.put("interval_s", seconds)
                        run("automation.put", params)
                    }
                }
            }
        })
        content.addView(action(R.string.control_scenes) { listScenes() })
        content.addView(action(R.string.control_events) { run("events.list") })
    }
    private fun device() {
        heading(R.string.nav_device, R.string.device_help)
        content.addView(Ui.actions(this, action(R.string.settings_title, true, false) { startActivity(Intent(this, SettingsActivity::class.java)) }, action(R.string.diagnostics, command = false) { startActivity(Intent(this, ProbeActivity::class.java)) }))
        content.addView(Ui.sectionTitle(this, getString(R.string.device_tools)))
        content.addView(Ui.actions(this, action(R.string.voice_title) { run("voice.listen") }, action(R.string.camera_title) { run("camera.capture") }))
        content.addView(Ui.actions(this, action(R.string.control_sensors) { run("sensors.read") }, action(R.string.control_apps) { listApps() }))
        content.addView(Ui.sectionTitle(this, getString(R.string.device_inspect)))
        content.addView(Ui.actions(this, action(R.string.control_capabilities) { run("device.capabilities") }, action(R.string.control_status) { run("device.status") }))
        content.addView(action(R.string.control_home) { run("home.states") })
    }
    private fun resultArea() {
        content.addView(Ui.sectionTitle(this, getString(R.string.result_title)))
        resultView = text(R.string.result_empty).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }.also { content.addView(it) }
        val expand = action(R.string.control_details, command = false) {
            details?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        content.addView(expand)
        details = Ui.monoBlock(this).apply { visibility = View.GONE; setTextIsSelectable(true); setPadding(0, dp(12), 0, 0) }.also { content.addView(it) }
        lastResult?.let { show(it) }
        if (busy) feedback(getString(R.string.control_working))
    }
    private fun updateConnection() {
        val state = DeviceBridge.current?.connection?.optString("state") ?: GadgetService.lastState
        val label = when (state) {
            "connected" -> R.string.notif_connected
            "unpaired" -> R.string.service_unpaired
            "reconnecting" -> R.string.service_reconnecting
            "stopped", "stopping" -> R.string.service_stopped
            "error" -> R.string.service_error
            else -> R.string.service_connecting
        }
        fun update(view: TextView?, text: String) { if (view != null && view.text.toString() != text) view.text = text }
        update(connection, getString(label)); update(homeState, getString(label))
        val color = getColor(when(state) { "connected" -> R.color.ok; "error" -> R.color.bad; else -> R.color.ink_dim })
        connection.setTextColor(color)
        update(homeHelp, getString(when(state) { "connected" -> R.string.home_connected_help; "unpaired" -> R.string.home_unpaired_help; "stopped", "error", "stopping" -> R.string.home_stopped_help; else -> R.string.home_connecting_help }))
        update(serviceButton, getString(if (GadgetService.lastState in listOf("stopped", "error")) R.string.probe_start_service else R.string.stop_service))
    }
    private fun feedback(message: String) { resultView?.text = message }
    private fun run(command: String, params: JSONObject = JSONObject(), after: ((JSONObject) -> Unit)? = null) {
        if (busy) return
        busy = true; commandButtons.forEach { it.isEnabled = false }; feedback(getString(R.string.control_working))
        LocalClient.execute(this, command, params) { result ->
            busy = false; commandButtons.forEach { it.isEnabled = true }; show(result); after?.invoke(result)
        }
    }
    private fun show(result: JSONObject) {
        lastResult = result
        details?.text = result.toString(2).take(16000)
        val payload = result.optJSONObject("payload")
        val summary = when {
            !result.optBoolean("ok") -> getString(R.string.result_failed, result.optString("error", getString(R.string.err_no_detail)))
            payload?.has("state") == true -> getString(R.string.result_state, payload.optString("state"))
            payload?.has("job_id") == true -> getString(R.string.result_accepted)
            else -> getString(R.string.result_received)
        }
        resultView?.text = summary
        resultView?.setTextColor(getColor(if (result.optBoolean("ok")) R.color.ink_dim else R.color.bad))
    }
    private fun discoverCast() = run("cast.discover") { result ->
        val devices = result.optJSONObject("payload")?.optJSONArray("devices") ?: return@run
        if (devices.length() == 0) { feedback(getString(R.string.cast_not_found)); return@run }
        val names = (0 until devices.length()).map { devices.getJSONObject(it).getString("name") }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.control_cast_select).setItems(names) { _, which ->
            selectedCast = devices.getJSONObject(which).getString("id"); selectedCastName = names[which]
            castLabel?.text = getString(R.string.cast_selected, selectedCastName)
            run("tv.cast", JSONObject().put("device", selectedCast).put("action", "status"))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun listApps() = run("apps.list") { result ->
        val apps = result.optJSONObject("payload")?.optJSONArray("apps") ?: return@run
        if (apps.length() == 0) { feedback(getString(R.string.apps_empty)); return@run }
        val names = (0 until apps.length()).map { apps.getJSONObject(it).getString("name") }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.control_apps).setItems(names) { _, which ->
            run("tv.launch", JSONObject().put("target", apps.getJSONObject(which).getString("package")))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun listScenes() = run("automation.list") { result ->
        val scenes = result.optJSONObject("payload")?.optJSONArray("automations") ?: return@run
        if (scenes.length() == 0) { feedback(getString(R.string.scenes_empty)); return@run }
        val ids = (0 until scenes.length()).map { scenes.getJSONObject(it).getString("id") }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.control_scenes).setItems(ids) { _, which ->
            AlertDialog.Builder(this).setTitle(ids[which])
                .setPositiveButton(R.string.run_scene) { _, _ -> run("automation.run", JSONObject().put("id", ids[which])) }
                .setNeutralButton(R.string.delete_scene) { _, _ ->
                    AlertDialog.Builder(this).setTitle(R.string.delete_scene).setMessage(getString(R.string.scene_delete_confirm, ids[which]))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.delete_scene) { _, _ -> run("automation.delete", JSONObject().put("id", ids[which])) }.show()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun cacheMedia() = withUrl { url ->
        val path = Ui.field(this, getString(R.string.cache_filename))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.control_cache).setView(path)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (path.text.isBlank()) path.error = getString(R.string.path_required)
            else { run("media.cache", JSONObject().put("url", url).put("path", path.text.toString())); dialog.dismiss() }
        } }; dialog.show()
    }
    override fun onSaveInstanceState(out: Bundle) {
        rememberFields(); out.putInt("page", page); out.putString("message", message); out.putString("url", mediaUrl)
        out.putString("sceneName", sceneName); out.putString("sceneInterval", sceneInterval)
        out.putString("cast", selectedCast); out.putString("castName", selectedCastName)
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
