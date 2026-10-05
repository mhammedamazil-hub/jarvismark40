package com.jarvis.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wake-word plugin — hands-free "Yo JARVIS …". Keeps a speech recognizer looping (opt-in,
 * since it costs battery); when it hears the wake phrase it hands the rest of the sentence to
 * [onCommand], pauses briefly so the handler can use the mic, then resumes listening.
 *
 * The command routing (screen vs camera vs general) lives in the UI layer via [onCommand].
 */
class WakeWordPlugin(private val ctx: Context) : JarvisPlugin {
    override val id = "wakeword"
    override val displayName = "Wake word (\"Yo JARVIS\")"

    @Volatile var enabled = false
    var onCommand: ((String) -> Unit)? = null

    private val wakePhrases = listOf("jarvis", "yo jarvis", "hey jarvis", "javis", "hi jarvis")
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false
    @Volatile private var handling = false
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (enabled || !SpeechRecognizer.isRecognitionAvailable(ctx)) return
        enabled = true
        beginListening()
    }

    fun stop() {
        enabled = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }

    private fun beginListening() {
        if (!enabled || listening) return
        listening = true
        runCatching { recognizer?.destroy() }
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; restartSoon() }
            override fun onError(error: Int) { listening = false; restartSoon() }
            override fun onResults(results: Bundle?) {
                listening = false
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                process(said)
            }
            override fun onPartialResults(partial: Bundle?) {
                val said = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (wakeIndex(said) >= 0) runCatching { recognizer?.stopListening() }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        val ok = runCatching { sr.startListening(intent) }.isSuccess
        if (!ok) { listening = false; restartSoon() }
    }

    private fun process(said: String) {
        val idx = wakeIndex(said)
        if (idx < 0 || handling) { restartSoon(); return }
        val command = said.substring(idx).trim()
        handling = true
        onCommand?.invoke(command)
        // Give the handler time to use the mic + speak before we listen again.
        main.postDelayed({ handling = false; beginListening() }, 6000)
    }

    private fun wakeIndex(s: String): Int {
        val low = s.lowercase()
        for (p in wakePhrases) { val i = low.indexOf(p); if (i >= 0) return i }
        return -1
    }

    private fun restartSoon() {
        if (!enabled) return
        main.postDelayed({ if (enabled) beginListening() }, 900)
    }

    override fun statusLine(): String? = if (enabled) "🎧 listening for \"JARVIS\"" else null
}
