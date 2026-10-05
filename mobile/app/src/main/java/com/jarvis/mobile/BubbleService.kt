package com.jarvis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The floating JARVIS bubble: always on top, draggable, tap to give a goal without
 * opening the app, and it hosts the confirm-before-send dialog. All UI is built
 * programmatically (no extra layouts to keep it robust).
 */
class BubbleService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var confirmBox: LinearLayout? = null
    private var statusText: TextView? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null

    companion object {
        @Volatile var instance: BubbleService? = null
        private const val TAG = "JarvisBubble"
        private const val CHANNEL = "jarvis_bubble"
        private const val NOTIF_ID = 43
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Foreground keeps the bubble alive; if a strict OEM/Android-14 rejects the
        // untyped FGS we still run (overlay persists) rather than crashing.
        try { startForeground(NOTIF_ID, buildNotif()) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no 'draw over other apps' permission")
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        remove(bubble); remove(panel); remove(confirmBox)
        bubble = null; panel = null; confirmBox = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun remove(v: View?) {
        if (v != null) { try { wm.removeView(v) } catch (_: Exception) {} }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun baseParams(x: Int, y: Int, focusable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, type, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
    }

    private fun showBubble() {
        val b = TextView(this).apply {
            text = "◉ JARVIS"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC001018"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val p = baseParams(0, 320, focusable = false)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    p.x = startX + dx; p.y = startY + dy
                    try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(p.x, p.y); true }
                else -> false
            }
        }
        wm.addView(b, p)
        bubble = b
    }

    private fun togglePanel(bx: Int, by: Int) {
        if (panel != null) { remove(panel); panel = null; return }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F000141A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S — give a goal"; setTextColor(Color.parseColor("#00E5FF")); textSize = 15f
        })
        val goal = EditText(this).apply {
            hint = "e.g. open WhatsApp, message Ravi 'on my way'"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#5A6E72"))
        }
        statusText = TextView(this).apply {
            text = AgentBus.logs.lastOrNull() ?: "Idle."
            setTextColor(Color.parseColor("#9FE8F0")); textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply { text = "Start" }
        val stop = Button(this).apply { text = "Stop" }
        val close = Button(this).apply { text = "✕" }
        start.setOnClickListener {
            val g = goal.text.toString().trim()
            if (g.isNotEmpty()) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("goal", g)
                })
                remove(panel); panel = null
            }
        }
        stop.setOnClickListener { stopService(Intent(this, AgentService::class.java)); setStatus("Stopped.") }
        close.setOnClickListener { remove(panel); panel = null }
        row.addView(start); row.addView(stop); row.addView(close)
        root.addView(goal); root.addView(statusText); root.addView(row)
        wm.addView(root, baseParams(dp(12), by + dp(12), focusable = true))
        panel = root
    }

    fun setStatus(s: String) {
        statusText?.post { statusText?.text = s }
    }

    /** Show a Yes/No dialog over whatever app is open; suspends until the user answers. */
    suspend fun requestConfirm(prompt: String): Boolean {
        if (confirmBox != null) return false
        val d = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            val root = LinearLayout(this@BubbleService).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F2010A12"))
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            root.addView(TextView(this@BubbleService).apply {
                text = prompt; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 0, 0, dp(12))
            })
            val row = LinearLayout(this@BubbleService).apply { orientation = LinearLayout.HORIZONTAL }
            val no = Button(this@BubbleService).apply { text = "No" }
            val yes = Button(this@BubbleService).apply { text = "Yes, proceed" }
            no.setOnClickListener { finishConfirm(false) }
            yes.setOnClickListener { finishConfirm(true) }
            row.addView(no); row.addView(yes)
            root.addView(row)
            pendingConfirm = d
            try {
                wm.addView(root, baseParams(dp(24), dp(220), focusable = true))
                confirmBox = root
            } catch (e: Exception) {
                Log.w(TAG, "confirm addView failed: ${e.message}")
                pendingConfirm = null
                d.complete(false)
            }
        }
        return d.await()
    }

    private fun finishConfirm(result: Boolean) {
        remove(confirmBox); confirmBox = null
        pendingConfirm?.complete(result); pendingConfirm = null
    }

    private fun buildNotif(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "JARVIS bubble", NotificationManager.IMPORTANCE_MIN))
        }
        return android.app.Notification.Builder(this).let { b ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                b.setChannelId(CHANNEL).setContentTitle("JARVIS bubble active")
                    .setSmallIcon(R.drawable.ic_launcher).setOngoing(true).build()
            else
                @Suppress("DEPRECATION")
                b.setSmallIcon(R.drawable.ic_launcher).setContentTitle("JARVIS bubble active")
                    .setOngoing(true).build()
        }
    }
}
