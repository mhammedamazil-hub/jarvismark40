package com.jarvis.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * JARVIS's voice — the first plugin.
 *
 *  • Text-to-speech: the agent speaks confirmations, results, and anything a plugin
 *    asks it to "say". Toggle replies from the main screen.
 *  • Speech-to-text: push-to-talk so you can give a goal hands-free.
 *
 * Uses the platform SpeechRecognizer + TextToSpeech, so there are no extra deps and it
 * works offline for TTS on most devices.
 */
class VoicePlugin(private val appCtx: Context) : JarvisPlugin {
    override val id = "voice"
    override val displayName = "Voice (talk + listen)"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false

    /** Master switch for spoken replies (bound to the UI toggle). */
    @Volatile var repliesEnabled: Boolean = true

    override fun onInit(ctx: Context) {
        tts = TextToSpeech(appCtx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    /** Say something out loud. Safe to call from any thread; no-op if not ready/muted. */
    fun speak(text: String) {
        if (!repliesEnabled || text.isBlank() || !ttsReady) return
        // TextToSpeech caps input length, so speak in chunks queued back-to-back.
        text.chunked(3200).forEach { chunk ->
            tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, chunk.hashCode().toString())
        }
    }

    /**
     * Push-to-talk. Callbacks arrive on the main thread. [onResult] gets the transcript,
     * [onError] gets a short reason. Requires RECORD_AUDIO (requested by the UI).
     */
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit = {}) {
        if (!SpeechRecognizer.isRecognitionAvailable(appCtx)) { onError("Speech recognition not available"); return }
        if (listening) return
        listening = true
        runCatching { recognizer?.destroy() }   // release any previous listener
        val sr = SpeechRecognizer.createSpeechRecognizer(appCtx)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; onError("Speech error $error") }
            override fun onResults(results: Bundle?) {
                listening = false
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (said.isNotBlank()) onResult(said) else onError("Didn't catch that")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr.startListening(intent)
    }

    fun stopListening() { runCatching { recognizer?.stopListening() }; listening = false }

    override fun tools() = listOf(
        PluginTool("speak", "Say something out loud to the user"),
        PluginTool("listen", "Capture a spoken goal from the user")
    )

    override fun onCommand(command: String): Boolean {
        val c = command.trim().lowercase()
        return when {
            c.startsWith("say ") -> { speak(command.trim().removePrefix("say ")); true }
            else -> false
        }
    }

    override fun statusLine(): String? = if (ttsReady) "🎙 Voice ready" else null

    override fun onDestroy() {
        runCatching { tts?.stop(); tts?.shutdown() }
        runCatching { recognizer?.destroy() }
        tts = null; recognizer = null; ttsReady = false
    }
}
