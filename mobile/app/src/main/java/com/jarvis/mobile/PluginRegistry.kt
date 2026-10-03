package com.jarvis.mobile

/**
 * The plugin host. Plugins register here; the agent asks the registry for their
 * combined tools, vision, and context every step. Failures in one plugin never take
 * down the loop.
 */
object PluginRegistry {
    private val _plugins = LinkedHashMap<String, JarvisPlugin>()
    val plugins: List<JarvisPlugin> get() = _plugins.values.toList()

    @Synchronized fun register(p: JarvisPlugin) { _plugins[p.id] = p }

    @Synchronized fun get(id: String): JarvisPlugin? = _plugins[id]

    /** First registered plugin of the given type, or null. */
    @Suppress("UNCHECKED_CAST")
    fun <T : JarvisPlugin> firstOfType(cls: Class<T>): T? =
        _plugins.values.firstOrNull { cls.isInstance(it) } as? T

    @Synchronized fun clear() {
        _plugins.values.forEach { runCatching { it.onDestroy() } }
        _plugins.clear()
    }

    fun init(ctx: android.content.Context) {
        _plugins.values.forEach { runCatching { it.onInit(ctx) } }
    }

    fun tools(): List<PluginTool> = _plugins.values.flatMap { runCatching { it.tools() }.getOrElse { emptyList() } }

    /** Merge every plugin's contribution for this frame (vision + context + a line to speak). */
    fun onFrame(frame: AgentFrame): PluginContribution {
        val vision = mutableListOf<String>()
        val ctx = mutableListOf<String>()
        var speak: String? = null
        for (p in _plugins.values) {
            val c = runCatching { p.onFrame(frame) }.getOrNull() ?: continue
            vision.addAll(c.visionB64)
            c.context?.let { ctx.add(it) }
            if (speak == null) speak = c.speak
        }
        return PluginContribution(vision, ctx.joinToString("\n").ifBlank { null }, speak)
    }

    /** Route a spoken/typed command to the first plugin that handles it. */
    fun onCommand(command: String): Boolean =
        _plugins.values.any { runCatching { it.onCommand(command) }.getOrDefault(false) }

    fun statusLines(): List<String> =
        _plugins.values.mapNotNull { runCatching { it.statusLine() }.getOrNull() }
}
