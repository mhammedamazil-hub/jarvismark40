package com.jarvis.mobile

/**
 * Bridges the running agent to the floating overlay so the agent can (a) push live
 * status to the bubble and (b) pause and ask the user to confirm a risky action
 * (e.g. sending a message) before it happens.
 */
object OverlayBridge {
    @Volatile var controller: BubbleService? = null

    fun available(): Boolean = controller != null

    /** Ask the user to confirm. Returns false if there's no overlay to ask (safe default). */
    suspend fun confirm(prompt: String): Boolean {
        val c = controller ?: return false
        return runCatching { c.requestConfirm(prompt) }.getOrDefault(false)
    }

    fun status(s: String) {
        controller?.setStatus(s)
    }
}
