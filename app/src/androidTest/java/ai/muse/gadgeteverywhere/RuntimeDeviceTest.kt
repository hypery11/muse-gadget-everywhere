package ai.muse.gadgeteverywhere

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs the packaged Python, native libraries, player and service on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun command(name: String, params: JSONObject = JSONObject()): JSONObject = JSONObject(
        Python.getInstance().getModule("androidtv.service").callAttr("invoke", name, params.toString()).toString(),
    )
    private fun good(name: String, params: JSONObject = JSONObject()): JSONObject {
        val result = command(name, params)
        assertTrue("$name: $result", result.getBoolean("ok"))
        return result.getJSONObject("payload")
    }
    private fun eventually(timeout: Long = 15000, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (test()) return
            Thread.sleep(150)
        }
        assertTrue("condition was not observed within ${timeout}ms", test())
    }
    private fun openScreen(screenClass: Class<out android.app.Activity>): android.app.Activity {
        // NEW_TASK can bring an existing root activity forward without creating
        // one. startActivitySync waits for creation and times out in that case.
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, screenClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        instrumentation.waitForIdleSync()
        eventually { GadgetApplication.visibleActivity.get()?.let { screenClass.isInstance(it) && !it.isFinishing } == true }
        return requireNotNull(GadgetApplication.visibleActivity.get())
    }
    private fun start() {
        Startup.ensurePython(context)
        // Regression tests never rotate or revoke a real account's tokens.
        // Cloud verification is a separate opt-in run after local tests pass.
        if (InstrumentationRegistry.getArguments().getString("cloud") != "true") {
            Python.getInstance().getModule("builtins").callAttr("exec",
                "from musegadget.service import Service\nasync def _local_test_run(self):\n    await self._stop.wait()\nService.run = _local_test_run", Python.getInstance().getModule("builtins").callAttr("dict"))
        }
        openScreen(ControlActivity::class.java)
        instrumentation.waitForIdleSync()
        eventually { GadgetApplication.visibleActivity.get() != null }
        instrumentation.runOnMainSync { GadgetService.start(context) }
        eventually(30000) { Python.isStarted() && command("device.status").optBoolean("ok") }
    }

    @Test fun packagedRuntimeMediaScreenVisionAndLifecycle() {
        org.junit.Assume.assumeTrue("cloud tests run separately", InstrumentationRegistry.getArguments().getString("cloud") != "true")
        start()
        val caps = good("device.capabilities")
        assertEquals("android", caps.getString("platform"))
        assertTrue(caps.getJSONArray("commands").length() >= 30)
        assertFalse(caps.getBoolean("developer_mode"))
        assertFalse(command("system.run", JSONObject().put("command", "id")).getBoolean("ok"))
        assertFalse(command("file.read", JSONObject().put("path", "../musegadget/pairing.json")).getBoolean("ok"))
        assertTrue(good("apps.list").getJSONArray("apps").length() > 0)
        good("sensors.read")
        good("screen.show", JSONObject().put("title", "Muse verification").put("text", "Chromecast / Android native runtime").put("ttl_s", 30))
        eventually { good("screen.status").optString("state") == "rendered" }
        good("screen.clear")
        openScreen(ControlActivity::class.java)

        // A real decodable offline audio stream verifies ExoPlayer and packaged codecs.
        val workspace = File(context.filesDir, "workspace/validation").apply { mkdirs() }
        val rate = 16000
        val size = rate * 2 * 8
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
        wave.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wave.put("data".toByteArray()).putInt(size)
        repeat(size / 2) { wave.putShort(0) }
        File(workspace, "silence.wav").writeBytes(wave.array())
        good("media.play", JSONObject().put("url", "validation/silence.wav").put("queue", JSONArray().put("validation/silence.wav")))
        eventually { good("media.status").optString("state") == "playing" }
        good("media.control", JSONObject().put("action", "pause"))
        eventually { good("media.status").optString("state") == "paused" }
        good("media.control", JSONObject().put("action", "seek").put("value", 2))
        assertTrue(good("media.status").getDouble("position_s") >= 1.9)
        good("media.control", JSONObject().put("action", "next"))
        assertEquals(1, good("media.status").getInt("index"))
        good("media.control", JSONObject().put("action", "stop"))
        assertEquals("idle", good("media.status").getString("state"))

        // Bundled OCR must recognize pixels without a network model download.
        val bitmap = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("MUSE 2026", 60f, 190f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 120f })
        File(workspace, "ocr.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val vision = good("vision.analyze", JSONObject().put("path", "validation/ocr.png"))
        assertTrue(vision.getString("text"), vision.getString("text").contains("2026"))
        assertEquals("on_device", vision.getString("processing"))
        instrumentation.context.assets.open("validation-qr.png").use { input -> File(workspace, "qr.png").outputStream().use { input.copyTo(it) } }
        val barcodes = good("vision.analyze", JSONObject().put("path", "validation/qr.png").put("mode", "barcode")).getJSONArray("barcodes")
        assertTrue(barcodes.toString(), (0 until barcodes.length()).any { barcodes.getJSONObject(it).optString("value") == "MUSE-VALIDATION-2026" })
        if (!caps.getBoolean("camera")) assertFalse(command("camera.capture").getBoolean("ok"))
        if (!caps.getBoolean("microphone") || !caps.getBoolean("speech_recognizer")) assertFalse(command("voice.listen").getBoolean("ok"))

        val scene = JSONObject().put("id", "validation_scene").put("actions", JSONArray().put(JSONObject().put("command", "screen.show").put("params", JSONObject().put("text", "Scene verified"))))
        good("automation.put", scene)
        assertTrue(good("automation.run", JSONObject().put("id", "validation_scene")).getBoolean("completed"))
        good("automation.delete", JSONObject().put("id", "validation_scene"))
        good("events.emit", JSONObject().put("type", "validation.complete").put("notify", false))
        assertTrue(good("events.list").getJSONArray("events").length() > 0)
        good("screen.clear")
        val screens = mutableListOf<Class<out android.app.Activity>>(ControlActivity::class.java, SettingsActivity::class.java)
        if (Startup.missingBlePermissions(context).isEmpty()) screens.add(PairActivity::class.java)
        for (screenClass in screens) {
            val screen = openScreen(screenClass)
            instrumentation.waitForIdleSync()
            if (screenClass == ControlActivity::class.java) {
                instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                    File(workspace, "controls.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
            }
            instrumentation.runOnMainSync { screen.finish() }
        }
        val report = JSONObject().put("capabilities", caps).put("vision", vision).put("status", good("device.status"))
        File(workspace, "device-report.json").writeText(report.toString(2))

        // Exercise the rapid stop/start race, preserving all credentials.
        repeat(3) {
            val previous = DeviceBridge.current
            instrumentation.runOnMainSync { GadgetService.stop(context); GadgetService.start(context) }
            eventually(30000) { DeviceBridge.current != null && DeviceBridge.current !== previous && command("device.capabilities").optBoolean("ok") }
        }
        good("device.capabilities")
        val castHost = InstrumentationRegistry.getArguments().getString("castHost")
        if (castHost != null) {
            val discovery = good("cast.discover")
            assertTrue("Cast discovery must find a receiver", discovery.getJSONArray("devices").length() > 0)
            val castResults = JSONArray()
            fun cast(action: String, extra: JSONObject = JSONObject()): JSONObject {
                val result = good("tv.cast", extra.put("host", castHost).put("action", action))
                castResults.put(result)
                return result
            }
            val initial = cast("status")
            cast("play_url", JSONObject().put("url", "https://www.w3schools.com/html/mov_bbb.mp4"))
            assertEquals("PAUSED", cast("pause").getString("player"))
            assertEquals("PLAYING", cast("play").getString("player"))
            cast("volume", JSONObject().put("value", initial.getDouble("volume")))
            assertEquals("IDLE", cast("stop").getString("player"))
            good("speech.say", JSONObject().put("text", "Muse 測試完成").put("language", "zh-TW"))
            eventually(20000) { good("speech.status").optString("state") == "completed" }
            if (InstrumentationRegistry.getArguments().getString("cloud") == "true")
                eventually(30000) { good("device.status").getString("state") == "connected" }
            File(workspace, "cast-report.json").writeText(JSONObject().put("discovery", discovery)
                .put("cast", castResults).put("speech", good("speech.status")).put("connection", good("device.status").getString("state")).toString(2))
        }
    }

    @Test fun controlsKeepDraftsAndValidateBeforeDispatch() {
        val activity = openScreen(ControlActivity::class.java)
        fun find(view: android.view.View, match: (android.view.View) -> Boolean): android.view.View? {
            if (match(view)) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), match)?.let { return it }
            return null
        }
        fun nav(index: Int) = requireNotNull(find(activity.window.decorView) { it.tag == "nav_$index" })
        fun field(label: Int) = requireNotNull(find(activity.window.decorView) {
            it is android.widget.EditText && it.contentDescription == context.getString(label)
        }) as android.widget.EditText
        instrumentation.runOnMainSync {
            nav(1).performClick()
            field(R.string.control_message).setText("Keep this draft across tabs")
            nav(2).performClick()
            field(R.string.control_media_url).setText("validation/silence.wav")
            nav(1).performClick()
            assertEquals("Keep this draft across tabs", field(R.string.control_message).text.toString())
            nav(2).performClick()
            assertEquals("validation/silence.wav", field(R.string.control_media_url).text.toString())
            nav(3).performClick()
            field(R.string.scene_name).setText("../invalid")
            requireNotNull(find(activity.window.decorView) { it is android.widget.Button && it.text == context.getString(R.string.control_scene) }).performClick()
            assertNotNull("Invalid names must stay in the form with an error", field(R.string.scene_name).error)
            for (index in 0..4) {
                nav(index).performClick()
                assertTrue("Current destination must remain selected", nav(index).isSelected)
            }
            nav(0).performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun cloudRegistrationWhenExplicitlyRequested() {
        org.junit.Assume.assumeTrue("cloud verification is opt-in", InstrumentationRegistry.getArguments().getString("cloud") == "true")
        start()
        eventually(45000) { good("device.status").getString("state") == "connected" }
        good("device.capabilities")
        // Wait for cooperative shutdown before the runner terminates the process.
        instrumentation.runOnMainSync { GadgetService.stop(context) }
        eventually(30000) { !command("device.status").optBoolean("ok") }
    }

    @Test fun cameraCaptureOnExplicitEmulatorFixture() {
        org.junit.Assume.assumeTrue("requires an explicitly selected emulator camera fixture", InstrumentationRegistry.getArguments().getString("cameraFixture") == "true")
        org.junit.Assume.assumeTrue("grant CAMERA to the emulator before starting instrumentation", context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        start()
        val directory = File(context.filesDir, "workspace/camera")
        val before = directory.list()?.toSet() ?: emptySet()
        val activity = openScreen(CameraActivity::class.java)
        fun findButton(view: android.view.View): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == context.getString(R.string.camera_take)) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findButton(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            eventually(15000) {
                instrumentation.runOnMainSync { findButton(activity.window.decorView)?.performClick() }
                directory.list()?.any { it !in before && File(directory, it).length() > 1000 } == true
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun bleAdvertisingLifecycleWhenSupported() {
        org.junit.Assume.assumeTrue("BLE permissions not granted", Startup.missingBlePermissions(context).isEmpty())
        val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        org.junit.Assume.assumeTrue("BLE advertising hardware unavailable", manager?.adapter?.isEnabled == true && manager.adapter.bluetoothLeAdvertiser != null)
        repeat(2) {
            val transport = BleTransport()
            try { transport.open(context, "Muse validation"); assertTrue(transport.is_open()) }
            finally { transport.shutdown() }
            assertFalse(transport.is_open())
        }
    }
}
