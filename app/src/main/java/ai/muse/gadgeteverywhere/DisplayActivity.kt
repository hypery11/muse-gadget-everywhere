package ai.muse.gadgeteverywhere

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DisplayActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var download: Future<*>? = null
    private var imageBitmap: Bitmap? = null
    private var playerView: PlayerView? = null
    private var job = ""

    override fun onCreate(state: Bundle?) { super.onCreate(state); render(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render(intent) }

    private fun render(intent: Intent) {
        val bridge = DeviceBridge.current ?: return finish()
        handler.removeCallbacksAndMessages(null)
        download?.cancel(true)
        playerView?.player = null
        playerView = null
        val request = try { JSONObject(intent.getStringExtra("request") ?: "{}") } catch (_: Exception) { return finish() }
        job = request.optString("job_id")
        bridge.screenActivity = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (request.optString("mode") == "media") {
            playerView = PlayerView(this).apply { player = bridge.media.player; useController = true }
            setContentView(playerView)
            bridge.screen = JSONObject().put("state", "rendered").put("mode", "media").put("job_id", job)
            return
        }
        val (scroll, content) = Ui.screenFrame(this)
        window.setBackgroundDrawableResource(R.color.bg)
        val title = request.optString("title", getString(R.string.app_name))
        content.addView(Ui.twoToneTitle(this, title, "", 30f))
        content.addView(Ui.bodyText(this, 23f).apply { text = request.optString("text") })
        val imageSource = request.optString("image")
        if (imageSource.isNotBlank()) {
            val picture = ImageView(this).apply {
                adjustViewBounds = true
                contentDescription = title
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            content.addView(picture)
            val requestedJob = job
            download = worker.submit {
                try {
                    val bitmap = loadImage(imageSource)
                    runOnUiThread {
                        if (isDestroyed || job != requestedJob) bitmap.recycle()
                        else {
                            picture.setImageBitmap(bitmap)
                            imageBitmap = bitmap
                            bridge.screen.put("image_state", "rendered")
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (job == requestedJob && !isDestroyed) {
                            bridge.screen.put("image_state", "error")
                            bridge.emit("screen.error", JSONObject().put("job_id", requestedJob).put("error", "image unavailable"))
                        }
                    }
                }
            }
        }
        val buttons = request.optJSONArray("buttons") ?: JSONArray()
        for (i in 0 until buttons.length()) {
            val button = buttons.getJSONObject(i)
            content.addView(Ui.primaryButton(this, button.getString("label")).apply {
                setOnClickListener {
                    bridge.emit("screen.action", JSONObject().put("job_id", job).put("id", button.getString("id")))
                }
            })
        }
        content.addView(Ui.secondaryButton(this, getString(R.string.close_screen)).apply {
            setOnClickListener { finish() }
            post { requestFocus() }
        })
        setContentView(scroll)
        bridge.screen = JSONObject().put("state", "rendered").put("mode", "card").put("job_id", job)
            .put("title", title).put("text", request.optString("text"))
            .put("image_state", if (imageSource.isBlank()) "none" else "loading")
        bridge.emit("screen.shown", JSONObject().put("job_id", job))
        if (request.has("ttl_s")) handler.postDelayed({ finish() }, request.getLong("ttl_s") * 1000)
    }

    private fun loadImage(source: String): Bitmap {
        val uri = DeviceBridge.resourceUri(this, source)
        val data = if (uri.scheme == "file") java.io.File(requireNotNull(uri.path)).inputStream().use { boundedRead(it) }
        else {
            var url = URL(source)
            var bytes: ByteArray? = null
            repeat(5) {
                if (bytes == null) {
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.instanceFollowRedirects = false
                    try {
                        if (connection.responseCode in 300..399) {
                            val redirected = URL(url, connection.getHeaderField("Location") ?: error("missing redirect"))
                            DeviceBridge.resourceUri(this, redirected.toString())
                            require(url.protocol != "https" || redirected.protocol == "https") { "HTTPS downgrade" }
                            url = redirected
                        } else {
                            require(connection.responseCode in 200..299) { "image HTTP failure" }
                            bytes = connection.inputStream.use { boundedRead(it) }
                        }
                    } finally { connection.disconnect() }
                }
            }
            bytes ?: error("too many image redirects")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: error("image decode failed")
    }
    private fun boundedRead(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check(!Thread.currentThread().isInterrupted)
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= 8 * 1024 * 1024) { "image exceeds 8 MiB" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        download?.cancel(true)
        worker.shutdownNow()
        playerView?.player = null
        DeviceBridge.current?.let {
            if (it.screenActivity.get() === this) {
                it.screenActivity.clear()
                it.screen = JSONObject().put("state", "hidden").put("job_id", job)
            }
        }
        // The view can still be drawn until its window is detached; let GC
        // release the current bitmap rather than recycling under the renderer.
        imageBitmap = null
        super.onDestroy()
    }
}
