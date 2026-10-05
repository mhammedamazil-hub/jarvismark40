package com.jarvis.mobile

/**
 * Everything a plugin sees for one agent iteration. The agent hands this to every
 * registered plugin so they can add eyes (camera frames), context, or spoken lines.
 */
data class AgentFrame(
    val goal: String,
    val step: Int,
    val screenB64: String?,      // screenshot JPEG as base64 (null if capture failed)
    val screenSummary: String,   // readable on-screen elements (text + centre x,y)
    val history: String          // what the agent has done so far
)

/** What a plugin contributes for one iteration. */
data class PluginContribution(
    val visionB64: List<String> = emptyList(),   // extra images to show the model (e.g. camera)
    val context: String? = null,                 // extra text merged into the prompt
    val speak: String? = null                    // something for JARVIS to say out loud
)

/** A capability a plugin advertises to the model and the UI. */
data class PluginTool(val name: String, val description: String)

/**
 * A drop-in feature. Register with [PluginRegistry] (see [BuiltinPlugins]).
 *
 * A plugin can:
 *  • advertise [tools] — merged into the model's system prompt so it knows it has them
 *  • contribute [onFrame] vision (camera), context, or a spoken line each iteration
 *  • handle [onCommand] — a spoken/typed command like "say …" or "take a photo"
 *  • report a [statusLine] for the UI
 *
 * Adding a feature = writing one of these and registering it. That's the whole point:
 * countless features, each a plugin.
 */
interface JarvisPlugin {
    val id: String
    val displayName: String
    val enabledByDefault: Boolean get() = true

    fun onInit(ctx: android.content.Context) {}
    fun tools(): List<PluginTool> = emptyList()
    fun onFrame(frame: AgentFrame): PluginContribution = PluginContribution()

    /** Return true if the plugin handled this command. */
    fun onCommand(command: String): Boolean = false

    fun statusLine(): String? = null
    fun onDestroy() {}
}
