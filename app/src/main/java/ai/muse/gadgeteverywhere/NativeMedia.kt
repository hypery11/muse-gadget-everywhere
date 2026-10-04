package ai.muse.gadgeteverywhere

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Main-looper-owned playback and speech, with observable asynchronous jobs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeMedia(private val context: Context, private val emit: (String, JSONObject) -> Unit) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    private val mediaSession = MediaSession.Builder(context, player).build()
    private var mediaJob = ""
    private var mediaError: String? = null
    private var tts: TextToSpeech? = null
    @Volatile private var speechReady = false
    @Volatile private var speechState = "initializing"
    @Volatile private var speechJob = ""

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) emit("media.ended", status())
                GadgetService.playbackActive(player.playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                GadgetService.playbackActive(isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            }
            override fun onPlayerError(error: PlaybackException) {
                mediaError = error.errorCodeName
                emit("media.error", JSONObject().put("job_id", mediaJob).put("error", mediaError))
            }
        })
        tts = TextToSpeech(context) { code ->
            speechReady = code == TextToSpeech.SUCCESS
            speechState = if (speechReady) "ready" else "unavailable"
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { if (id == speechJob) { speechState = "speaking"; GadgetService.playbackActive(true, "speech") } }
            override fun onDone(id: String) {
                if (id == speechJob) { speechState = "completed"; GadgetService.playbackActive(false, "speech") }
                emit("speech.completed", JSONObject().put("job_id", id))
            }
            @Deprecated("Deprecated in Android")
            override fun onError(id: String) {
                if (id == speechJob) { speechState = "error"; GadgetService.playbackActive(false, "speech") }
                emit("speech.error", JSONObject().put("job_id", id))
            }
        })
    }

    fun play(params: JSONObject): JSONObject {
        val urls = mutableListOf(params.getString("url"))
        val queue = params.optJSONArray("queue") ?: JSONArray()
        require(queue.length() <= 50) { "queue limit is 50" }
        for (i in 0 until queue.length()) urls.add(queue.getString(i))
        val items = urls.mapIndexed { index, source ->
            val builder = MediaItem.Builder().setUri(DeviceBridge.resourceUri(context, source))
                .setMediaId(source)
            if (params.has("mime")) builder.setMimeType(params.getString("mime"))
            if (index == 0 && params.has("subtitle")) {
                val subtitle = params.getString("subtitle")
                val mime = if (subtitle.substringBefore('?').endsWith(".srt")) "application/x-subrip" else "text/vtt"
                builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(
                    DeviceBridge.resourceUri(context, subtitle),
                ).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            }
            builder.build()
        }
        mediaJob = UUID.randomUUID().toString()
        mediaError = null
        player.setMediaItems(items)
        player.prepare()
        player.play()
        return status().put("dispatch", "accepted")
    }

    fun control(params: JSONObject): JSONObject {
        when (params.getString("action")) {
            "pause" -> player.pause()
            "resume" -> { require(player.mediaItemCount > 0) { "no media loaded" }; player.play() }
            "stop" -> { player.stop(); player.clearMediaItems() }
            "next" -> { require(player.hasNextMediaItem()) { "no next item" }; player.seekToNextMediaItem() }
            "previous" -> { require(player.hasPreviousMediaItem()) { "no previous item" }; player.seekToPreviousMediaItem() }
            "seek" -> {
                val seconds = params.getDouble("value")
                require(seconds.isFinite() && seconds >= 0 && seconds <= 604800) { "invalid seek position" }
                require(player.isCurrentMediaItemSeekable) { "current stream is not seekable" }
                player.seekTo((seconds * 1000).toLong())
            }
            "volume" -> {
                val value = params.getDouble("value")
                require(value.isFinite() && value in 0.0..1.0) { "volume must be 0..1" }
                player.volume = value.toFloat()
            }
            else -> error("unknown media action")
        }
        return status()
    }

    fun status(): JSONObject = JSONObject().put("job_id", mediaJob)
        .put("state", when {
            mediaError != null -> "error"
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.playbackState == Player.STATE_ENDED -> "ended"
            player.isPlaying -> "playing"
            player.playbackState == Player.STATE_READY -> "paused"
            else -> "idle"
        }).put("position_s", player.currentPosition / 1000.0)
        .put("duration_s", if (player.duration >= 0) player.duration / 1000.0 else JSONObject.NULL)
        .put("queue_size", player.mediaItemCount).put("index", player.currentMediaItemIndex)
        .put("volume", player.volume.toDouble()).put("error", mediaError ?: JSONObject.NULL)

    fun speak(params: JSONObject): JSONObject {
        require(speechReady) { "TTS engine unavailable or still initializing; install a voice in Android settings" }
        val text = params.getString("text")
        require(text.isNotBlank() && text.length <= TextToSpeech.getMaxSpeechInputLength()) { "speech text is empty or too long" }
        val locale = Locale.forLanguageTag(params.optString("language", Locale.getDefault().toLanguageTag()))
        require((tts?.isLanguageAvailable(locale) ?: -1) >= TextToSpeech.LANG_AVAILABLE) { "requested TTS language is unavailable" }
        val rate = params.optDouble("rate", 1.0)
        require(rate.isFinite() && rate in 0.5..2.0) { "rate must be 0.5..2.0" }
        tts?.language = locale
        tts?.setSpeechRate(rate.toFloat())
        speechJob = UUID.randomUUID().toString()
        speechState = "queued"
        require(tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, speechJob) == TextToSpeech.SUCCESS) { "TTS rejected the utterance" }
        return speechStatus()
    }

    fun speechStatus(): JSONObject = JSONObject().put("ready", speechReady).put("state", speechState)
        .put("job_id", speechJob).put("engine", tts?.defaultEngine ?: JSONObject.NULL)
        .put("language", tts?.voice?.locale?.toLanguageTag() ?: JSONObject.NULL)

    fun stopSpeech(): JSONObject {
        tts?.stop()
        speechState = "stopped"
        GadgetService.playbackActive(false, "speech")
        return speechStatus()
    }

    fun close() {
        tts?.stop()
        tts?.shutdown()
        mediaSession.release()
        player.release()
        GadgetService.playbackActive(false)
        GadgetService.playbackActive(false, "speech")
    }
}
