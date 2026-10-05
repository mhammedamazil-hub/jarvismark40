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
    private lateinit var switchWake: Switch
    private lateinit var switchSpeakSteps: Switch
    private lateinit var btnPersona: Button
    private lateinit var edtBlockApps: EditText
    private lateinit var edtBlockMins: EditText
    private lateinit var btnFocusStart: Button
    private lateinit var btnFocusStop: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogCount = -1
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private val voice: VoicePlugin? get() = PluginRegistry.firstOfType(VoicePlugin::class.java)
    private val live: LivePlugin? get() = PluginRegistry.firstOfType(LivePlugin::class.java)
    private val cam: CameraPlugin? get() = PluginRegistry.firstOfType(CameraPlugin::class.java)
    private val wake: WakeWordPlugin? get() = PluginRegistry.firstOfType(WakeWordPlugin::class.java)
    private val focus: FocusPlugin? get() = PluginRegistry.firstOfType(FocusPlugin::class.java)

    private val poller = object : Runnable {
        override fun run() { refreshA11y(); refreshStatus(); refreshLog(); handler.postDelayed(this, 500) }
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
        switchWake = findViewById(R.id.switchWake)
        switchSpeakSteps = findViewById(R.id.switchSpeakSteps)
        btnPersona = findViewById(R.id.btnPersona)
        edtBlockApps = findViewById(R.id.edtBlockApps)
        edtBlockMins = findViewById(R.id.edtBlockMins)
        btnFocusStart = findViewById(R.id.btnFocusStart)
        btnFocusStop = findViewById(R.id.btnFocusStop)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)

        // Boot the plugin system (voice first; camera/live are future plugins).
        BuiltinPlugins.install(this)
        PluginRegistry.init(this)

        // Load the custom persona (.md) so it shapes every reply.
        val pf = java.io.File(filesDir, "persona.md")
        if (pf.exists()) JarvisConfig.persona = pf.readText().trim()

        spinnerProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Providers.all.map { it.label }
        )
        val savedId = prefs.getString("provider", Providers.all[0].id)
        spinnerProvider.setSelection(Providers.indexOf(savedId))
        edtKey.setText(prefs.getString("key", ""))
        edtModel.setText(prefs.getString("model", selectedProvider().defaultModel))
        edtKey.hint = "${selectedProvider().keyHint}  (get one: ${selectedProvider().keyUrl})"
        switchConfirm.isChecked = prefs.getBoolean("confirmSend", true)
        switchSpeakSteps.isChecked = prefs.getBoolean("speakSteps", false)

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val prov = Providers.all[pos.coerceIn(0, Providers.all.size - 1)]
                edtModel.setText(prov.defaultModel)
                edtKey.hint = "${prov.keyHint}  (get one: ${prov.keyUrl})"
                prefs.edit().putString("provider", prov.id).apply()
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

        // Wake word: hands-free "Yo JARVIS …" routes to screen / camera / general answers.
        switchSpeakSteps.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speakSteps", on).apply() }
        switchWake.setOnCheckedChangeListener { _, on ->
            val w = wake
            if (on) {
                w?.onCommand = { cmd -> handleVoiceCommand(cmd) }
                w?.start()
                AgentBus.log("🎧 Wake word ON — say \"Yo JARVIS…\"")
            } else {
                w?.stop()
                AgentBus.log("🎧 Wake word OFF")
            }
        }

        // Persona editor + Focus (app blocking).
        btnPersona.setOnClickListener { startActivity(Intent(this, PersonaActivity::class.java)) }
        btnFocusStart.setOnClickListener {
            val apps = edtBlockApps.text.toString().split(",", " and ")
                .map { it.trim() }.filter { it.isNotEmpty() }
            val mins = edtBlockMins.text.toString().toIntOrNull() ?: 5
            if (apps.isEmpty()) { AgentBus.log("⛔ List apps to block, e.g. YouTube, Instagram"); return@setOnClickListener }
            val f = focus ?: return@setOnClickListener
            AgentBus.log("⛔ " + f.startFocus(apps, mins))
        }
        btnFocusStop.setOnClickListener {
            focus?.stopFocus()
            AgentBus.log("⛔ Focus off.")
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

    private fun selectedProvider(): Provider =
        Providers.all[spinnerProvider.selectedItemPosition.coerceIn(0, Providers.all.size - 1)]

    private fun providerValue(): String = selectedProvider().id

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

    private fun refreshStatus() {
        val lines = PluginRegistry.statusLines()
        txtStatus.text = if (lines.isEmpty()) "Plugins: —" else lines.joinToString("   ")
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
        val provider = providerValue()
        val key = edtKey.text.toString().trim()
        val model = edtModel.text.toString().trim().ifBlank { selectedProvider().defaultModel }
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
                onResult = { q ->
                    btnAsk.text = "⚡ Ask JARVIS"
                    if (PluginRegistry.onCommand(q.lowercase())) return@listen  // a plugin handled it
                    run(q)
                },
                onError = { err -> btnAsk.text = "⚡ Ask JARVIS"; AgentBus.log("🎙 $err") }
            )
        }
    }

    /**
     * Route a wake-word command: strip the wake phrase, let plugins handle it first
     * (memory "remember…", "block youtube…", "say…"), else fall back to a screen/camera/
     * general live answer.
     */
    private fun handleVoiceCommand(cmd: String) {
        val stripped = cmd.lowercase()
            .removePrefix("yo ").removePrefix("hey ").removePrefix("hi ").removePrefix("jarvis").trim()
        if (stripped.isEmpty()) return
        if (PluginRegistry.onCommand(stripped)) { AgentBus.log("🎧 handled: $stripped"); return }
        val question = when {
            stripped.contains("camera") || stripped.contains("what is this") || stripped.contains("what's this") ||
                stripped.contains("this object") ->
                "Look at what I'm showing you on the camera and tell me what this object is, briefly."
            stripped.contains("screen") || stripped.contains("what do i do") || stripped.contains("what should i") ||
                stripped.contains("help me") || stripped.contains("read this") ->
                "Look at my screen and tell me what I should do next, briefly."
            else -> stripped
        }
        AgentBus.log("🎧 \"$stripped\"")
        askJarvis(spoken = false, fixedQuestion = question)
    }

    private fun requestCapture() {
        if (!isAccessibilityEnabled()) { txtA11y.text = "Enable the Controller first (step 1)."; return }
        prefs.edit()
            .putString("key", edtKey.text.toString().trim())
            .putString("model", edtModel.text.toString().trim())
            .putString("provider", providerValue())
            .putBoolean("confirmSend", switchConfirm.isChecked)
            .putBoolean("speakSteps", switchSpeakSteps.isChecked)
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
                putExtra("speakSteps", switchSpeakSteps.isChecked)
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
