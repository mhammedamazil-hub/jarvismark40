package com.jarvis.mobile

import android.content.Context

/**
 * Memory plugin — JARVIS remembers facts about you across sessions, on-device, and folds
 * them into every prompt (via [JarvisConfig]). Say "remember that …", ask "what do you
 * remember", or "forget everything".
 */
class MemoryPlugin(private val ctx: Context) : JarvisPlugin {
    override val id = "memory"
    override val displayName = "Memory (remembers you)"

    private val prefs = ctx.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)
    private val items: MutableList<String> = load()

    private fun load(): MutableList<String> =
        (prefs.getString("items", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()

    private fun save() {
        prefs.edit().putString("items", items.joinToString("\n")).apply()
        JarvisConfig.memory = items.joinToString("\n") { "- $it" }
    }

    fun remember(text: String) { if (text.isNotBlank()) { items.add(text.trim()); save() } }
    fun recall(): List<String> = items.toList()
    fun clear() { items.clear(); save() }

    override fun onInit(ctx: Context) { JarvisConfig.memory = items.joinToString("\n") { "- $it" } }

    override fun onCommand(command: String): Boolean {
        val c = command.trim().lowercase()
        val voice = PluginRegistry.firstOfType(VoicePlugin::class.java)
        when {
            c.startsWith("remember ") -> {
                remember(c.removePrefix("remember ").removePrefix("that ").trim())
                voice?.speak("Got it. I'll remember that.")
                return true
            }
            c.contains("what do you remember") || c.contains("recall") ||
                c.contains("what do you know about me") -> {
                voice?.speak(
                    if (items.isEmpty()) "I don't have anything remembered yet."
                    else items.joinToString(". ")
                )
                return true
            }
            c.startsWith("forget") -> { clear(); voice?.speak("Memory cleared."); return true }
        }
        return false
    }

    override fun statusLine(): String? = if (items.isNotEmpty()) "🧠 ${items.size} memories" else null

    override fun onDestroy() { save() }
}
