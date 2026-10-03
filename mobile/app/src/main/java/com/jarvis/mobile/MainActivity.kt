package com.jarvis.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var btnAccessibility: Button
    private lateinit var txtA11y: TextView
    private lateinit var spinnerProvider: Spinner
    private lateinit var edtKey: EditText
    private lateinit var edtModel: EditText
    private lateinit var edtGoal: EditText
    private lateinit var switchConfirm: Switch
    private lateinit var btnBubble: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnVoice: Button
    private lateinit var switchVoice: Switch
    private lateinit var btnAsk: Button
    private lateinit var btnLook: Button
    private lateinit var switchBackCam: Switch
    private lateinit var txtLog: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogCount = -1
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private val voice: VoicePlugin? get() = PluginRegistry.firstOfType(VoicePlugin::class.java)
    private val live: LivePlugin? get() = PluginRegistry.firstOfType(LivePlugin::class.java)
    private val cam: CameraPlugin? get() = PluginRegistry.firstOfType(CameraPlugin::class.java)

    private val poller = object : Runnable {
        override fun run() { refreshA11y(); refreshLog(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        txtA11y = findViewById(R.id.txtAccessibilityState)
        spinnerProvider = findViewById(R.id.spinnerProvider)
        edtKey = findViewById(R.id.edtKey)
        edtModel = findViewById(R.id.edtModel)
        edtGoal = findViewById(R.id.edtGoal)
        switchConfirm = findViewById(R.id.switchConfirm)
        btnBubble = findViewById(R.id.btnBubble)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnVoice = findViewById(R.id.btnVoice)
        switchVoice = findViewById(R.id.switchVoice)
        btnAsk = findViewById(R.id.btnAsk)
        btnLook = findViewById(R.id.btnLook)
        switchBackCam = findViewById(R.id.switchBackCam)
        txtLog = findViewById(R.id.txtLog)

        // Boot the plugin system (voice first; camera/live are future plugins).
        BuiltinPlugins.install(this)
        PluginRegistry.init(this)

        spinnerProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, listOf("OpenRouter", "Gemini")
        )
        val savedProvider = prefs.getString("provider", "OpenRouter") ?: "OpenRouter"
        spinnerProvider.setSelection(if (savedProvider == "Gemini") 1 else 0)
        edtKey.setText(prefs.getString("key", ""))
        edtModel.setText(prefs.getString("model", defaultModelFor(savedProvider)))
        edtKey.hint = if (savedProvider == "Gemini") "Google AI Studio key (AIza…)" else "OpenRouter API key (sk-or-…)"
        switchConfirm.isChecked = prefs.getBoolean("confirmSend", true)

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val prov = if (pos == 1) "Gemini" else "OpenRouter"
                edtModel.setText(defaultModelFor(prov))
                edtKey.hint = if (prov == "Gemini") "Google AI Studio key (AIza…)" else "OpenRouter API key (sk-or-…)"
                prefs.edit().putString("provider", prov).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btnBubble.setOnClickListener { toggleBubble() }
        btnStart.setOnClickListener { requestCapture() }
        btnStop.setOnClickListener { stopService(Intent(this, AgentService::class.java)) }
        switchConfirm.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("confirmSend", on).apply() }

        // Voice: push-to-talk to give a goal hands-free, and a toggle for spoken replies.
        switchVoice.isChecked = true
        switchVoice.setOnCheckedChangeListener { _, on -> voice?.repliesEnabled = on }
        btnVoice.setOnClickListener { speakGoal() }

        // Live + camera: ask JARVIS anything (it sees screen + camera and answers aloud),
        // or point the camera at something and have it described.
        switchBackCam.setOnCheckedChangeListener { _, on -> cam?.frontFacing = !on }
        btnAsk.setOnClickListener { askJarvis(spoken = true) }
        btnLook.setOnClickListener {
            askJarvis(spoken = false, fixedQuestion = "Look at what I'm showing you and describe it briefly.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 3)

        // Launched from the floating bubble with a goal -> run it straight away.
        intent?.getStringExtra("goal")?.let { g ->
            edtGoal.setText(g)
            if (isAccessibilityEnabled()) requestCapture()
        }
    }

    private fun defaultModelFor(provider: String) =
        if (provider == "Gemini") "gemini-2.0-flash" else "openai/gpt-4o-mini"

    private fun providerValue(): String =
        if (spinnerProvider.selectedItemPosition == 1) "gemini" else "openrouter"

    override fun onResume() {
        super.onResume()
        handler.post(poller)
        // Came back from the "draw over apps" settings screen with permission granted?
        if (Settings.canDrawOverlays(this) && BubbleService.instance == null && prefs.getBoolean("bubbleWanted", false)) {
            startService(Intent(this, BubbleService::class.java))
        }
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(poller) }

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

    private fun toggleBubble() {
        if (BubbleService.instance != null) {
            stopService(Intent(this, BubbleService::class.java))
            prefs.edit().putBoolean("bubbleWanted", false).apply()
            btnBubble.text = "Show floating bubble"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("bubbleWanted", true).apply()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        startService(Intent(this, BubbleService::class.java))
        btnBubble.text = "Hide floating bubble"
    }

    /** Push-to-talk: hear a goal, fill the box, and start JARVIS on it. */
    private fun speakGoal() {
        val v = voice ?: run { AgentBus.log("🎙 Voice plugin not ready"); return }
        v.speak("Listening")
        btnVoice.text = "🎙 Listening…"
        v.listen(
            onResult = { said ->
                btnVoice.text = "🎙 Speak goal"
                edtGoal.setText(said)
                v.speak("Got it. Starting.")
                if (isAccessibilityEnabled()) requestCapture()
                else AgentBus.log("🎙 Heard: $said — enable the Controller first")
            },
            onError = { err ->
                btnVoice.text = "🎙 Speak goal"
                AgentBus.log("🎙 $err")
            }
        )
    }

    /**
     * Live ask: JARVIS reads the screen (+ camera) and answers aloud. With [spoken] it
     * listens for your question; otherwise it uses [fixedQuestion] (e.g. "describe what I'm
     * showing you" for the camera-look button).
     */
    private fun askJarvis(spoken: Boolean, fixedQuestion: String? = null) {
        val lv = live ?: run { AgentBus.log("⚡ Live plugin not ready"); return }
        val isGemini = spinnerProvider.selectedItemPosition == 1
        val provider = if (isGemini) "gemini" else "openrouter"
        val key = edtKey.text.toString().trim()
        val model = edtModel.text.toString().trim()
            .ifBlank { if (isGemini) "gemini-2.0-flash" else "openai/gpt-4o-mini" }
        val run = { q: String ->
            btnAsk.text = "⚡ Thinking…"
            lv.askNow(provider, key, model, q) { answer ->
                btnAsk.text = "⚡ Ask JARVIS"
                AgentBus.log("JARVIS: $answer")
            }
        }
        if (fixedQuestion != null) {
            run(fixedQuestion)
        } else {
            voice?.speak("Yes?")
            btnAsk.text = "🎙 Listening…"
            voice?.listen(
                onResult = { q -> btnAsk.text = "⚡ Ask JARVIS"; run(q) },
                onError = { err -> btnAsk.text = "⚡ Ask JARVIS"; AgentBus.log("🎙 $err") }
            )
        }
    }

    private fun requestCapture() {
        if (!isAccessibilityEnabled()) { txtA11y.text = "Enable the Controller first (step 1)."; return }
        prefs.edit()
            .putString("key", edtKey.text.toString().trim())
            .putString("model", edtModel.text.toString().trim())
            .putString("provider", if (providerValue() == "gemini") "Gemini" else "OpenRouter")
            .putBoolean("confirmSend", switchConfirm.isChecked)
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
                putExtra("provider", providerValue())
                putExtra("confirmSend", switchConfirm.isChecked)
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            moveTaskToBack(true)   // let the agent work while you use other apps
        }
    }

    companion object { const val REQ_CAPTURE = 1001 }
}
