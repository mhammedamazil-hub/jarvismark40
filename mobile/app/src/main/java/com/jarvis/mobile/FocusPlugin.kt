package com.jarvis.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Focus plugin — digital wellbeing on autopilot. Block distracting apps for N minutes and
 * JARVIS enforces it automatically: the moment a blocked app comes to the foreground it
 * bounces you home and flashes a full-screen "blocked" reminder. No further action needed.
 *
 * Say "block youtube for 5 minutes", or set it from the UI. (Needs Accessibility on + the
 * "draw over other apps" permission for the reminder overlay.)
 */
class FocusPlugin(private val ctx: Context) : JarvisPlugin {
    override val id = "focus"
    override val displayName = "Focus (block apps)"

    @Volatile var active = false
    private var blockedPackages = setOf<String>()
    private var endAt = 0L
    private var lastBlockMs = 0L
    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlay: View? = null

    private val listener: (String) -> Unit = { pkg -> onForeground(pkg) }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() >= endAt) { stopFocus(auto = true); return }
            main.postDelayed(this, 1000)
        }
    }

    override fun onInit(ctx: Context) {
        JarvisAccessibilityService.foregroundListener = listener
    }

    /** Block [appNames] (resolved to packages) for [minutes]. Returns a human summary. */
    fun startFocus(appNames: List<String>, minutes: Int): String {
        val a11y = JarvisAccessibilityService.instance
        val pkgs = appNames.mapNotNull { a11y?.resolvePackage(it) }.toSet()
        if (pkgs.isEmpty()) return "I couldn't find those apps — is the Controller enabled?"
        blockedPackages = pkgs
        endAt = SystemClock.elapsedRealtime() + minutes.coerceAtLeast(1) * 60_000L
        active = true
        main.removeCallbacks(tick); main.post(tick)
        PluginRegistry.firstOfType(VoicePlugin::class.java)
            ?.speak("Focus mode on. Blocking ${appNames.joinToString(", ")} for $minutes minutes.")
        return "Blocking ${appNames.joinToString(", ")} for $minutes min."
    }

    fun stopFocus(auto: Boolean = false) {
        active = false
        blockedPackages = emptySet()
        main.removeCallbacks(tick)
        hideOverlay()
        if (auto) PluginRegistry.firstOfType(VoicePlugin::class.java)?.speak("Focus session complete. Well done.")
    }

    fun remainingMinutes(): Int =
        if (!active) 0 else ((endAt - SystemClock.elapsedRealtime()) / 60000).toInt().coerceAtLeast(0)

    private fun onForeground(pkg: String) {
        if (!active || pkg !in blockedPackages) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockMs < 4000) return          // debounce re-entries
        lastBlockMs = now
        val label = appLabel(pkg)
        PluginRegistry.firstOfType(VoicePlugin::class.java)
            ?.speak("$label is blocked. ${remainingMinutes()} minutes of focus left.")
        JarvisAccessibilityService.instance?.home()     // bounce out of the app
        showOverlay(label)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun showOverlay(label: String) {
        main.post {
            hideOverlay()
            if (!Settings.canDrawOverlays(ctx)) return@post
            val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#F000040A"))
                setPadding(dp(28), dp(28), dp(28), dp(28))
            }
            root.addView(TextView(ctx).apply {
                text = "⛔ $label is blocked"
                setTextColor(Color.parseColor("#FF5252")); textSize = 24f; gravity = Gravity.CENTER
            })
            root.addView(TextView(ctx).apply {
                text = "JARVIS is keeping you focused.\n${remainingMinutes()} min left."
                setTextColor(Color.parseColor("#9FF6FF")); textSize = 15f; gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            root.setOnClickListener { hideOverlay() }
            try { wm.addView(root, p); overlay = root; main.postDelayed({ hideOverlay() }, 4000) }
            catch (_: Exception) {}
        }
    }

    private fun hideOverlay() {
        overlay?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        overlay = null
    }

    override fun onCommand(command: String): Boolean {
        val c = command.trim().lowercase()
        if (!c.startsWith("block")) return false
        val mins = Regex("(\\d+)\\s*(min|minute)").find(c)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        val appsPart = c.removePrefix("block").substringBefore(" for ").trim()
        val apps = appsPart.split(",", " and ").map { it.trim() }.filter { it.isNotEmpty() }
        if (apps.isEmpty()) return false
        val summary = startFocus(apps, mins)
        PluginRegistry.firstOfType(VoicePlugin::class.java)?.speak(summary)
        return true
    }

    override fun statusLine(): String? = if (active) "⛔ focus: ${remainingMinutes()} min left" else null

    override fun onDestroy() {
        main.removeCallbacks(tick)
        hideOverlay()
        if (JarvisAccessibilityService.foregroundListener === listener)
            JarvisAccessibilityService.foregroundListener = null
    }
}
