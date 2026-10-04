package ai.muse.gadgeteverywhere

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject

/** User-initiated speech input, with explicit local processing and send actions. */
class VoiceActivity : Activity(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private lateinit var transcript: EditText
    private lateinit var status: TextView
    private lateinit var offline: CheckBox
    private var listening = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val (scroll, content) = Ui.screenFrame(this)
        content.addView(Ui.twoToneTitle(this, getString(R.string.voice_title), "", 28f))
        content.addView(Ui.bodyText(this).apply { text = getString(R.string.voice_help) })
        val localAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        offline = CheckBox(this).apply {
            text = getString(R.string.voice_offline)
            setTextColor(getColor(R.color.ink))
            isChecked = localAvailable
            isEnabled = localAvailable
        }
        content.addView(offline)
        status = Ui.bodyText(this).apply { text = getString(if (localAvailable) R.string.voice_local_ready else R.string.voice_engine_online) }
        content.addView(status)
        transcript = EditText(this).apply {
            hint = getString(R.string.voice_transcript)
            setTextColor(getColor(R.color.ink))
            setHintTextColor(getColor(R.color.ink_dim))
        }
        content.addView(transcript)
        content.addView(Ui.primaryButton(this, getString(R.string.voice_listen)).apply { setOnClickListener { startListening() } })
        content.addView(Ui.secondaryButton(this, getString(R.string.voice_stop)).apply { setOnClickListener { recognizer?.stopListening() } })
        content.addView(Ui.primaryButton(this, getString(R.string.voice_send)).apply {
            setOnClickListener {
                val text = transcript.text.toString().trim()
                if (text.isNotEmpty()) LocalClient.execute(this@VoiceActivity, "events.emit", JSONObject()
                    .put("type", "voice.text").put("data", JSONObject().put("text", text))
                    .put("notify", true).put("ttl_s", 300)) { status.text = it.toString(2) }
            }
        })
        content.addView(Ui.secondaryButton(this, getString(R.string.voice_local_action)).apply {
            setOnClickListener {
                val text = transcript.text.toString().trim().lowercase()
                val action = mapOf("pause" to "pause", "暫停" to "pause", "resume" to "resume", "繼續" to "resume", "stop" to "stop", "停止" to "stop")[text]
                if (action != null) LocalClient.execute(this@VoiceActivity, "media.control", JSONObject().put("action", action)) { status.text = it.toString(2) }
                else if (text.startsWith("scene ") || text.startsWith("場景 ")) {
                    LocalClient.execute(this@VoiceActivity, "automation.run", JSONObject().put("id", text.substringAfter(' '))) { status.text = it.toString(2) }
                } else status.setText(R.string.voice_local_commands)
            }
        })
        setContentView(scroll)
    }
    private fun startListening() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.setText(R.string.voice_unavailable); return }
        try {
            recognizer?.destroy()
            recognizer = if (offline.isChecked && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                         else SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(this)
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline.isChecked))
            listening = true
            status.setText(R.string.voice_recording)
        } catch (_: Exception) { status.setText(R.string.voice_unavailable) }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else status.setText(R.string.voice_permission)
    }
    override fun onResults(results: Bundle) {
        listening = false
        transcript.setText(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        status.setText(R.string.voice_review)
    }
    override fun onPartialResults(results: Bundle) { transcript.setText(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()) }
    override fun onError(error: Int) { listening = false; status.text = getString(R.string.voice_error, error) }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rms: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(type: Int, params: Bundle?) {}
    override fun onStop() { recognizer?.cancel(); listening = false; super.onStop() }
    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }
}
