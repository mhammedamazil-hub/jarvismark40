package com.jarvis.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var btnAccessibility: Button
    private lateinit var txtA11y: TextView
    private lateinit var edtKey: EditText
    private lateinit var edtModel: EditText
    private lateinit var edtGoal: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var txtLog: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogCount = -1
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    private val poller = object : Runnable {
        override fun run() { refreshA11y(); refreshLog(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        txtA11y = findViewById(R.id.txtAccessibilityState)
        edtKey = findViewById(R.id.edtKey)
        edtModel = findViewById(R.id.edtModel)
        edtGoal = findViewById(R.id.edtGoal)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        txtLog = findViewById(R.id.txtLog)

        edtKey.setText(prefs.getString("key", ""))
        edtModel.setText(prefs.getString("model", "openai/gpt-4o-mini"))

        btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btnStart.setOnClickListener { requestCapture() }
        btnStop.setOnClickListener { stopService(Intent(this, AgentService::class.java)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() { super.onResume(); handler.post(poller) }
    override fun onPause() { handler.removeCallbacks(poller) }

    private fun refreshA11y() {
        val on = isAccessibilityEnabled()
        txtA11y.text = if (on) "Status: ✅ Controller enabled" else "Status: ⚠ Not enabled — tap step 1"
        btnStart.isEnabled = on
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo?.serviceInfo?.packageName == packageName } ||
                JarvisAccessibilityService.isRunning()
    }

    private fun refreshLog() {
        val logs = AgentBus.logs
        if (logs.size != lastLogCount) {
            lastLogCount = logs.size
            txtLog.text = logs.joinToString("\n")
        }
    }

    private fun requestCapture() {
        if (!isAccessibilityEnabled()) { txtA11y.text = "Enable the Controller first (step 1)."; return }
        prefs.edit()
            .putString("key", edtKey.text.toString().trim())
            .putString("model", edtModel.text.toString().trim())
            .apply()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            val svc = Intent(this, AgentService::class.java).apply {
                putExtra("goal", edtGoal.text.toString())
                putExtra("key", edtKey.text.toString().trim())
                putExtra("model", edtModel.text.toString().trim())
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        }
    }

    companion object { const val REQ_CAPTURE = 1001 }
}
