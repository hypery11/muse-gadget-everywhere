package ai.muse.gadgeteverywhere

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Camera permission and a visible preview precede every user-initiated capture. */
@Suppress("DEPRECATION")
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private val thread = HandlerThread("muse-camera")
    private lateinit var worker: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var opening = false
    @Volatile private var active = false
    private val main = Handler(android.os.Looper.getMainLooper())
    private var orientation = 0
    private var captured = ""
    @Volatile private var generation = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        thread.start()
        worker = Handler(thread.looper)
        val (scroll, content) = Ui.screenFrame(this)
        content.addView(Ui.twoToneTitle(this, getString(R.string.camera_title), "", 28f))
        content.addView(Ui.bodyText(this).apply { text = getString(R.string.camera_help) })
        preview = TextureView(this).apply {
            surfaceTextureListener = this@CameraActivity
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.run { dp(300) })
        }
        content.addView(preview)
        status = Ui.bodyText(this)
        content.addView(status)
        content.addView(Ui.primaryButton(this, getString(R.string.camera_take)).apply {
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                else if (camera == null) openCamera() else capture()
            }
        })
        content.addView(Ui.secondaryButton(this, getString(R.string.camera_analyze)).apply {
            setOnClickListener {
                if (captured.isNotEmpty()) LocalClient.execute(this@CameraActivity, "vision.analyze", JSONObject().put("path", captured)) { status.text = it.toString(2) }
            }
        })
        setContentView(scroll)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!active || opening || camera != null || !preview.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { status.setText(R.string.camera_permission); return }
        val requestGeneration = ++generation
        try {
            val manager = getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.firstOrNull() ?: error("No camera")
            val info = manager.getCameraCharacteristics(id)
            orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = requireNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val sizes = map.getOutputSizes(ImageFormat.JPEG)
            val size = sizes.filter { it.width <= 1920 && it.height <= 1920 }.maxByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ source ->
                    try {
                    source.acquireLatestImage()?.use { image ->
                        if (!active || requestGeneration != generation) return@use
                        try {
                            val buffer = image.planes[0].buffer
                            require(buffer.remaining() <= 16 * 1024 * 1024)
                            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                            val relative = "camera/photo-${System.currentTimeMillis()}.jpg"
                            val file = DeviceBridge.workspaceFile(this@CameraActivity, relative)
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                            DeviceBridge.current?.emit("camera.captured", JSONObject().put("path", relative))
                            runOnUiThread { captured = relative; status.text = getString(R.string.camera_saved, relative) }
                        } catch (_: Exception) { runOnUiThread { status.setText(R.string.camera_failed) } }
                    }
                    } catch (_: IllegalStateException) { /* reader closed on pause */ }
                }, worker)
            }
            val previewSize = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
            previewSize?.let { preview.surfaceTexture?.setDefaultBufferSize(it.width, it.height) }
            opening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (requestGeneration == generation) opening = false
                    if (!active || requestGeneration != generation || !preview.isAvailable) { device.close(); return }
                    camera = device
                    surface = Surface(preview.surfaceTexture)
                    device.createCaptureSession(listOf(requireNotNull(surface), requireNotNull(reader).surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (!active || requestGeneration != generation || camera == null) { value.close(); return }
                            session = value
                            try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(requireNotNull(surface)) }
                                value.setRepeatingRequest(request.build(), null, worker)
                            } catch (_: Exception) { runOnUiThread { status.setText(R.string.camera_failed) } }
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) { runOnUiThread { status.setText(R.string.camera_failed) } }
                    }, main)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (requestGeneration == generation) { camera = null; opening = false } }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (requestGeneration == generation) { camera = null; opening = false; status.setText(R.string.camera_failed) } }
            }, main)
        } catch (_: Exception) { opening = false; status.setText(R.string.camera_failed) }
    }
    private fun capture() {
        try {
            val device = camera ?: return
            val rotation = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(requireNotNull(reader).surface)
                set(CaptureRequest.JPEG_ORIENTATION, (orientation - rotation + 360) % 360)
            }
            requireNotNull(session).capture(request.build(), null, worker)
        } catch (_: Exception) { status.setText(R.string.camera_failed) }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else status.setText(R.string.camera_permission)
    }
    override fun onResume() { super.onResume(); active = true; openCamera() }
    private fun closeCamera() {
        generation++
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        surface?.release(); surface = null
        opening = false
    }
    override fun onStop() {
        active = false
        closeCamera()
        super.onStop()
    }
    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { openCamera() }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { closeCamera(); return true }
}
