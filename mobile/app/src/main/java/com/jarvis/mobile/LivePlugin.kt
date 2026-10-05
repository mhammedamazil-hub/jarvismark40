package com.jarvis.mobile

import android.content.Context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Live plugin — "see my screen and answer instantly."
 *
 * Ask JARVIS anything right now: it reads the current screen (via Accessibility) and, if
 * the camera is available, grabs a frame of what you're showing it, then answers out loud.
 * This is the conversational JARVIS moment — no multi-step goal, just look + think + speak.
 */
class LivePlugin(private val ctx: Context) : JarvisPlugin {
    override val id = "live"
    override val displayName = "Live (instant see & answer)"

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    @Volatile var busy: Boolean = false

    /** Ask JARVIS something now. [onAnswer] fires on the main thread with the spoken reply. */
    fun askNow(
        provider: String, key: String, model: String,
        question: String, onAnswer: (String) -> Unit
    ) {
        if (busy) { onAnswer("One moment — still thinking."); return }
        if (key.isBlank()) { onAnswer("Add my API key on the main screen first."); return }
        busy = true
        scope.launch {
            val a11y = JarvisAccessibilityService.instance
            val summary = withContext(Dispatchers.Main) { a11y?.readScreen() }
                ?.take(50)
                ?.joinToString("\n") {
                    val tag = buildString { if (it.clickable) append("[tap] "); if (it.editable) append("[edit] ") }
                    "$tag'${it.text}'"
                }.orEmpty()

            val cam = PluginRegistry.firstOfType(CameraPlugin::class.java)
            val camB64 = cam?.let { withTimeoutOrNull(5000) { grabFrame(it) } }
            val images = listOfNotNull(camB64)

            val prompt = buildString {
                append("The user just asked you: ").append(question).append('\n')
                if (summary.isNotBlank()) append("SCREEN TEXT (readable elements):\n").append(summary)
                else append("(No screen text readable.)")
                if (images.isNotEmpty()) append("\nA CAMERA image of what they're showing you is attached — look at it.")
            }

            val answer = withContext(Dispatchers.IO) {
                ModelClient.ask(provider, key, model, prompt, images)
            }
            busy = false
            withContext(Dispatchers.Main) {
                PluginRegistry.firstOfType(VoicePlugin::class.java)?.speak(answer)
                onAnswer(answer)
            }
        }
    }

    private suspend fun grabFrame(cam: CameraPlugin): String? =
        suspendCancellableCoroutine { cont -> cam.captureNow { cont.resume(it) } }

    override fun statusLine(): String? = if (busy) "⚡ Thinking…" else "⚡ Live ready"

    override fun onDestroy() { job.cancel() }
}
