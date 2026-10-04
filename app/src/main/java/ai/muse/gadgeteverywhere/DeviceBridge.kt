package ai.muse.gadgeteverywhere

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Native capability adapter. Python calls on workers; Android objects live on main. */
class DeviceBridge(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val tv = TvControl(app)
    private val events = ConcurrentLinkedQueue<JSONObject>()
    val media = NativeMedia(app) { type, data -> emit(type, data) }
    private val sensors = SensorHub(app) { type, data -> emit(type, data) }
    @Volatile var connection = JSONObject().put("state", "starting")
        private set
    var screen = JSONObject().put("state", "hidden")
    var screenActivity: java.lang.ref.WeakReference<DisplayActivity> = java.lang.ref.WeakReference(null)
    @Volatile private var closed = false

    init { current = this }

    companion object {
        // The bridge holds only application context and is cleared on service stop.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile var current: DeviceBridge? = null
            private set

        fun workspaceFile(context: Context, value: String): File {
            require(value.isNotBlank() && !value.contains('\u0000')) { "workspace path is required" }
            val root = File(context.filesDir, "workspace").canonicalFile.apply { mkdirs() }
            val source = File(value).let { if (it.isAbsolute) it else File(root, value) }
            val result = source.canonicalFile
            require(result.path.startsWith(root.path + File.separator)) { "path is outside the workspace" }
            require(result.relativeTo(root).invariantSeparatorsPath.split('/').none { it.startsWith('.') }) { "reserved workspace path" }
            return result
        }

        fun resourceUri(context: Context, value: String): Uri {
            if (value.startsWith("http://") || value.startsWith("https://")) {
                val uri = Uri.parse(value)
                require(!uri.host.isNullOrEmpty() && uri.userInfo == null && value.length <= 4096) { "invalid media URL" }
                return uri
            }
            require(!value.contains("://")) { "only HTTP(S) or workspace files are supported" }
            val file = workspaceFile(context, value)
            require(file.isFile) { "workspace file does not exist" }
            return Uri.fromFile(file)
        }
    }

    fun settings(): String = AppSettings.read(app).toString()
    fun deviceModel(): TvResult = tv.deviceModel()
    fun uptimeSeconds(): TvResult = tv.uptimeSeconds()
    fun deviceIp(): TvResult = tv.deviceIp()
    fun launch(target: String): TvResult = onMain { tv.launch(target) }

    fun updateState(json: String) {
        if (closed) return
        connection = JSONObject(json)
        main.post { GadgetService.connectionChanged(connection.optString("state", "starting")) }
    }

    fun emit(type: String, data: JSONObject, notify: Boolean = false) {
        if (!closed && events.size < 100) events.add(JSONObject().put("type", type).put("data", data).apply { if (notify) put("notify", true) })
    }
    fun drainEvents(): String {
        val result = JSONArray()
        while (true) result.put(events.poll() ?: break)
        return result.toString()
    }

    private fun <T> onMain(action: () -> T): T {
        check(!closed) { "native runtime stopped" }
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask { check(!closed) { "native runtime stopped" }; action() }
        main.post(task)
        return try { task.get(8, TimeUnit.SECONDS) } catch (e: Exception) {
            task.cancel(false)
            throw e
        }
    }

    fun canPresent(): Boolean = GadgetApplication.visibleActivity.get() != null || Settings.canDrawOverlays(app)

    private fun open(activity: Class<out Activity>, request: JSONObject? = null) {
        require(canPresent()) { "Open the app or grant overlay access to show this screen" }
        app.startActivity(Intent(app, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { request?.let { putExtra("request", it.toString()) } })
    }

    fun invoke(command: String, paramsJson: String): String = try {
        val params = JSONObject(paramsJson)
        val payload = when (command) {
            "cast.discover" -> CastDiscovery.discover(app)
            "vision.analyze" -> vision(params)
            "sensors.read" -> {
                onMain { sensors.sample() }
                Thread.sleep(350)
                onMain { sensors.snapshot() }
            }
            else -> onMain { dispatch(command, params) }
        }
        JSONObject().put("ok", true).put("payload", payload).toString()
    } catch (exc: Exception) {
        val cause = exc.cause ?: exc
        val detail = if (cause is IllegalArgumentException || cause is IllegalStateException) cause.message else cause.javaClass.simpleName
        JSONObject().put("ok", false).put("error", detail ?: "native command failed").toString()
    }

    private fun dispatch(command: String, params: JSONObject): JSONObject = when (command) {
        "device.capabilities" -> {
            val pm = app.packageManager
            JSONObject().put("platform", "android").put("sdk", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL).put("screen", true).put("can_present", canPresent())
                .put("native_media", true).put("speech", media.speechStatus())
                .put("microphone", pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
                .put("microphone_permission", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                .put("speech_recognizer", SpeechRecognizer.isRecognitionAvailable(app))
                .put("on_device_speech", Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
                .put("camera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
                .put("camera_permission", app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                .put("offline_ocr", true).put("offline_barcodes", true).put("sensors", sensors.available())
        }
        "apps.list" -> {
            val entries = linkedMapOf<String, JSONObject>()
            for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
                @Suppress("DEPRECATION")
                val results = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                for (result in results) entries[result.activityInfo.packageName] = JSONObject()
                    .put("package", result.activityInfo.packageName).put("name", result.loadLabel(app.packageManager).toString())
            }
            JSONObject().put("apps", JSONArray(entries.values.sortedBy { it.optString("name") }))
        }
        "screen.show" -> {
            val request = JSONObject(params.toString()).put("job_id", UUID.randomUUID().toString()).put("mode", "card")
            require(request.optString("text").length <= 20000 && request.optString("title").length <= 300) { "card text is too long" }
            val buttons = request.optJSONArray("buttons") ?: JSONArray()
            require(buttons.length() <= 6) { "maximum 6 buttons" }
            val ids = mutableSetOf<String>()
            for (i in 0 until buttons.length()) {
                val button = buttons.getJSONObject(i)
                require(button.getString("id").matches(Regex("[A-Za-z0-9_-]{1,64}")) && ids.add(button.getString("id"))) { "button IDs must be unique" }
                require(button.getString("label").length in 1..100) { "invalid button label" }
            }
            if (request.has("ttl_s")) require(request.getInt("ttl_s") in 1..86400) { "ttl_s must be 1..86400" }
            if (request.has("image")) resourceUri(app, request.getString("image"))
            open(DisplayActivity::class.java, request)
            JSONObject().put("state", "requested").put("job_id", request.getString("job_id"))
        }
        "screen.clear" -> { screenActivity.get()?.finish(); screen = JSONObject().put("state", "hidden"); screen }
        "screen.status" -> JSONObject(screen.toString())
        "media.play" -> {
            require(canPresent()) { "Open the app or grant overlay access to show the player" }
            val result = media.play(params)
            open(DisplayActivity::class.java, JSONObject().put("mode", "media").put("job_id", result.getString("job_id")))
            result
        }
        "media.control" -> media.control(params)
        "media.status" -> media.status()
        "speech.say" -> media.speak(params)
        "speech.status" -> media.speechStatus()
        "speech.stop" -> media.stopSpeech()
        "voice.listen" -> {
            require(app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) && SpeechRecognizer.isRecognitionAvailable(app)) { "No microphone or speech recognizer on this device" }
            open(VoiceActivity::class.java)
            JSONObject().put("state", "awaiting_user")
        }
        "camera.capture" -> {
            require(app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) { "No camera on this device" }
            open(CameraActivity::class.java)
            JSONObject().put("state", "awaiting_user")
        }
        else -> error("unsupported native command")
    }

    private fun vision(params: JSONObject): JSONObject {
        val path = workspaceFile(app, params.getString("path"))
        require(path.isFile && path.length() <= 16 * 1024 * 1024) { "image missing or exceeds 16 MiB" }
        val mode = params.optString("mode", "both")
        require(mode in listOf("text", "barcode", "both")) { "mode must be text, barcode or both" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.path, bounds)
        require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000) { "unsupported image dimensions" }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(path.path, options) ?: error("cannot decode image")
        try {
            val rotation = try {
                when (androidx.exifinterface.media.ExifInterface(path.path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (_: Exception) { 0 }
            val image = InputImage.fromBitmap(bitmap, rotation)
            val result = JSONObject().put("processing", "on_device").put("path", params.getString("path"))
            if (mode != "barcode") {
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                try { result.put("text", Tasks.await(recognizer.process(image), 20, TimeUnit.SECONDS).text.take(20000)) }
                finally { recognizer.close() }
            }
            if (mode != "text") {
                val scanner = BarcodeScanning.getClient()
                try {
                    val codes = Tasks.await(scanner.process(image), 15, TimeUnit.SECONDS)
                    result.put("barcodes", JSONArray(codes.take(50).map { JSONObject().put("value", it.rawValue?.take(4096)).put("format", it.format) }))
                } finally { scanner.close() }
            }
            return result
        } finally { bitmap.recycle() }
    }

    fun close() {
        closed = true
        screenActivity.get()?.finish()
        media.close()
        sensors.close()
        if (current === this) current = null
    }
}
