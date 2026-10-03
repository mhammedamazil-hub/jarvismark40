package com.jarvis.mobile

import android.content.Context

/**
 * Registers the built-in plugins. This is where the feature list lives — each new
 * capability is one more [JarvisPlugin] and one line here.
 *
 * Roadmap (same pattern, each an isolated drop-in):
 *   • CameraPlugin  — "see my camera when I show it" (front/back frames as vision)
 *   • LivePlugin    — continuous watch + instant spoken answers ("live screen")
 *   • WakeWordPlugin— hands-free "Hey JARVIS" trigger (opt-in, RAM-aware)
 */
object BuiltinPlugins {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        PluginRegistry.register(VoicePlugin(ctx.applicationContext))
        PluginRegistry.register(CameraPlugin(ctx.applicationContext))
        PluginRegistry.register(LivePlugin(ctx.applicationContext))
        PluginRegistry.register(WakeWordPlugin(ctx.applicationContext))
        // Future drop-ins: MemoryPlugin, ReminderPlugin, RemotePlugin… (same pattern)
    }
}
