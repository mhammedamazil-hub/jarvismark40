package com.jarvis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Simple log bus so the Activity can show what the agent is doing. */
object AgentBus {
    val logs = ArrayList<String>()
    var listener: ((String) -> Unit)? = null
    @Synchronized fun log(s: String) {
        logs.add(s)
        if (logs.size > 500) logs.removeAt(0)
        listener?.invoke(s)
    }
    @Synchronized fun clear() { logs.clear() }
}

/**
 * Runs the agent loop in the foreground (so it keeps working after you leave the
 * app): capture screen + read elements -> ask the model -> do ONE action -> repeat.
 */
class AgentService : Service() {
    private var grabber: ScreenGrabber? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val goal = intent?.getStringExtra("goal").orEmpty()
        val key = intent?.getStringExtra("key").orEmpty()
        val model = intent?.getStringExtra("model") ?: "openai/gpt-4o-mini"
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")

        val notif = buildNotif("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        AgentBus.clear()
        AgentBus.log("▶ Goal: $goal")
        if (key.isBlank()) { AgentBus.log("✖ No API key set."); finish(); return START_NOT_STICKY }

        grabber = if (data != null) ScreenGrabber(this, resultCode, data) else null
        if (grabber == null) AgentBus.log("⚠ No screen capture — using screen text only.")

        scope.launch { runLoop(goal, key, model) }
        return START_NOT_STICKY
    }

    private suspend fun runLoop(goal: String, key: String, model: String) {
        val a11y = JarvisAccessibilityService.instance
        if (a11y == null) { AgentBus.log("✖ Accessibility Controller not enabled."); finish(); return }
        val history = StringBuilder()
        var done = false
        for (step in 1..40) {
            if (!scope.isActive) break
            val nodes = withContext(Dispatchers.Main) { a11y.readScreen() }
            val shot = withContext(Dispatchers.IO) { grabber?.capture() }
            val summary = nodes.take(40).joinToString("\n") {
                val tag = buildString { if (it.clickable) append("[tap] "); if (it.editable) append("[edit] ") }
                "$tag'${it.text}' @(${it.cx},${it.cy})"
            }
            val action = try {
                withContext(Dispatchers.IO) {
                    ModelClient.decide(key, model, goal, shot, summary, history.toString())
                }
            } catch (e: Exception) {
                AgentBus.log("⚠ model error: ${e.message}")
                AgentAction("wait", reason = e.message ?: "error")
            }
            AgentBus.log("Step $step → ${action.action} ${action.reason}".trim())
            history.append("s$step:${action.action}; ")

            when (action.action.lowercase()) {
                "done" -> { AgentBus.log("✅ Goal complete."); done = true }
                "tap" -> withContext(Dispatchers.Main) { a11y.tap(action.x, action.y) }
                "longpress" -> withContext(Dispatchers.Main) { a11y.longPress(action.x, action.y) }
                "type" -> withContext(Dispatchers.Main) { a11y.typeText(action.text) }
                "launch" -> withContext(Dispatchers.Main) { a11y.launchApp(action.app) }
                "swipe" -> withContext(Dispatchers.Main) { a11y.swipe(action.x, action.y, action.x2, action.y2) }
                "back" -> withContext(Dispatchers.Main) { a11y.back() }
                "home" -> withContext(Dispatchers.Main) { a11y.home() }
                "recents" -> withContext(Dispatchers.Main) { a11y.recents() }
                else -> Unit
            }
            if (done) break
            updateNotif("Step $step: ${action.action}")
            delay(1500)
        }
        if (!done) AgentBus.log("⏹ Stopped (step limit reached).")
        finish()
    }

    private fun finish() {
        grabber?.release(); grabber = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE)
        else
            @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun buildNotif(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("JARVIS agent")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }

    override fun onDestroy() {
        scope.cancel()
        grabber?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "jarvis_agent"
        const val NOTIF_ID = 42
    }
}
