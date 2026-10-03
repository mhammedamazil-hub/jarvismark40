# JARVIS Mobile — standalone phone agent

<p align="center"><img src="docs/banner.jpg" alt="JARVIS Mobile concept banner" width="720"></p>

An Android app that **sees your screen and drives any app** to reach a goal you type
("open WhatsApp and message Ravi 'on my way'", "open Chrome and search logo design").
It thinks for itself with an online vision model you pick (OpenRouter or Gemini), **talks to
you by voice**, **sees your camera**, answers instantly, and grows through **plugins** —
**no laptop needed**.

- **Eyes:** screenshots (MediaProjection) + the screen's text/buttons (Accessibility) + your camera.
- **Hands:** taps / swipes / typing / launching apps via the Accessibility Service
  (this is Android's equivalent of `pyautogui`).
- **Brain:** a cheap vision model you pick in the app (OpenRouter or Gemini) decides each step.
- **Voice:** push-to-talk goals; JARVIS speaks replies, confirmations, and answers.
- **Live + camera:** ask it anything — it looks at your screen (+ camera) and answers aloud.
- **Phone + tablet:** the layout scrolls and centers to a readable column on any screen.
- **Plugins:** new eyes, voice commands, and tools drop in like apps.

## Build the APK (automatic — your plan)

**One-time setup — install the workflow (30 seconds):**
The automation token used to push this repo can't write to `.github/workflows/`,
so add it yourself through GitHub's website (where you have permission):
1. Open the repo → **Add file → Create new file**.
2. Name it exactly `.github/workflows/android.yml`.
3. Paste the contents of [`mobile/ci/android-build.yml`](ci/android-build.yml) and **Commit**.

**Then, every release:**
1. Push a version tag: `git tag v1.0.0 && git push origin v1.0.0`
2. GitHub Actions (**Actions → "Build JARVIS Mobile APK"**) builds the debug APK and
   attaches it to the **Release**. Download `app-debug.apk`.
   - You can also run the workflow manually (Run workflow) and grab the artifact.

## Install + use
1. Copy the APK to your phone; tap it (allow "install unknown apps").
2. Open **JARVIS Mobile**.
3. **Step 1** — tap *Enable JARVIS Controller* → in Accessibility, turn on
   **JARVIS Controller**. (One time. This is what lets it see + drive apps.)
4. Pick a **provider** — **OpenRouter** (paste an `sk-or-…` key, default model
   `openai/gpt-4o-mini`) or **Gemini** (paste a Google AI Studio `AIza…` key,
   model `gemini-2.0-flash`). Any cheap vision model works.
5. Leave **🔒 Confirm before sending** ON — JARVIS pauses for your Yes/No before
   it types or sends anything (shown on the floating bubble).
6. Type a **goal**, tap **Start**, and approve the screen-capture prompt. It works
   while you use other apps. **Stop** ends it.

### Floating bubble (use it from inside any app)
Tap **Show floating bubble** and allow **"Display over other apps"**. A draggable
**◉ JARVIS** bubble now floats on top of everything — tap it, type a goal, hit
**Start**, and it drives your phone without you opening the app again.


### 🎙 Voice — talk to it, it talks back
- **Speak goal** (push-to-talk): tap, say your goal, and it fills the box and starts —
  hands-free. (First run asks for the microphone permission.)
- **JARVIS speaks its replies**: it says confirmations ("JARVIS wants to send 'on my way'.
  Proceed?") and the final "Goal complete." / "Stopped." out loud. Toggle it off anytime.

### ⚡ Live + 📷 Camera — the real JARVIS moment
- **Ask JARVIS (mic):** tap, ask anything ("what's on my screen?", "summarize this page"),
  and it reads your screen, thinks, and **answers out loud** — no multi-step goal needed.
- **Look at camera:** point your camera at something and it describes it. Toggle
  **back camera** for objects, or leave it on selfie.
- The camera frame + the screen text are both sent to the model, so it can see what you show it.

### Phone **and** tablet
The layout scrolls and self-centers to a readable column on tablets (via `values-sw600dp`),
so it's usable on both without cutting anything off.

### 🔌 Plugins — add features like apps
JARVIS is built on a tiny plugin host (`JarvisPlugin` + `PluginRegistry`). A plugin can give
the model **new eyes** (extra images), **new context**, **new voice commands**, and **its own
tools** — all merged into the agent loop automatically. Adding a feature = one `JarvisPlugin`
class + one line in `BuiltinPlugins.install()`.

Shipped plugins: **Voice**, **Camera**, **Live**. See [the roadmap](ROADMAP.md) for what's next.

## Write a plugin (the community hook)

A plugin is a single class implementing `JarvisPlugin`, registered in
`BuiltinPlugins.install()`. Copy this starter into a new file under
`app/src/main/java/com/jarvis/mobile/`:

```kotlin
class GreeterPlugin(private val ctx: Context) : JarvisPlugin {
    override val id = "greeter"
    override val displayName = "Greeter"

    // Advertise a tool the model knows it can ask for.
    override fun tools() = listOf(PluginTool("greet", "Say a friendly greeting"))

    // Runs every agent step. Add vision (images), prompt context, or a line to speak.
    override fun onFrame(frame: AgentFrame): PluginContribution {
        if (frame.goal.contains("greet", ignoreCase = true))
            return PluginContribution(context = "The user wants a warm greeting.")
        return PluginContribution()
    }

    // Handle a spoken/typed command.
    override fun onCommand(command: String): Boolean {
        if (command.trim().lowercase().startsWith("greet")) {
            PluginRegistry.firstOfType(VoicePlugin::class.java)?.speak("Good to see you.")
            return true
        }
        return false
    }
}
```

Then register it:

```kotlin
// BuiltinPlugins.install()
PluginRegistry.register(GreeterPlugin(ctx.applicationContext))
```

That's it — the agent loop, UI, and voice all pick it up automatically. One plugin failing
never crashes the loop (every hook is guarded).

## How it works

```
   you ──▶ goal / "ask" ─┐
                         ▼
  ┌─────────────── MainActivity ───────────────┐        ┌──────────── Plugins ────────────┐
  │  Spinner(provider)  Key  Model  Confirm    │        │ Voice · Camera · Live · …        │
  └───────┬───────────────────────┬────────────┘        │  (add eyes/context/commands)     │
          │ Start                 │ Ask/Look            └───────────────┬──────────────────┘
          ▼                       ▼                                    │ vision + context
   AgentService            LivePlugin.ask()                             ▼
   (foreground loop)             │                             ModelClient.ask()
          │                      │                                    ▲
   read screen (a11y)            │                                    │
   + screenshot (MediaProj.)      │                             ModelClient.decide()
          │                      │                                    │ one JSON action
          └──────────► ModelClient.decide() ◄────────────────────────┘
                              │
                    JarvisAccessibilityService → tap/swipe/type/launch
                              │
                     confirm-before-send? ──▶ bubble asks Yes/No
```

- **AgentService** runs the see→think→act loop in the foreground (survives leaving the app).
- **ModelClient** speaks to OpenRouter or Gemini (same request shape) — `decide()` returns one
  device action, `ask()` returns a spoken answer.
- **BubbleService** floats over any app and hosts the confirm dialog.
- Every risky step (type / send / pay / delete) pauses for your approval.

## Build locally (optional)
```bash
cd mobile
gradle :app:assembleDebug      # or: ./gradlew assembleDebug if you have the wrapper
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 + Android SDK (API 34). `minSdk 26` (Android 8+).

## Screenshots

> Coming soon — real device screenshots + a demo GIF. (The maintainer validates on a
> Linux-Mint → Android workflow; PRs with screenshots on your device are very welcome!)

## Roadmap

See [`mobile/ROADMAP.md`](ROADMAP.md). Highlights still open: **wake word**, **memory/recall**,
**scheduled goals**, **remote dashboard**, and **more plugins**.

## Contributing

Features are plugins, so the best contribution is a new one — start from
[Write a plugin](#write-a-plugin-the-community-hook). Full guide: [`CONTRIBUTING.md`](../CONTRIBUTING.md).
Please read the [Code of Conduct](../CODE_OF_CONDUCT.md) and **never commit API keys**.

## License

[MIT](../LICENSE) — use it, fork it, ship it.

## Honest limits
- The Accessibility Controller must be enabled once, manually (Android requires it).
- Needs internet (the model is online). Costs a little per step (cheap model ≈ pennies).
- Banking / secure apps detect accessibility and block it — can't and shouldn't bypass.
- Screenshot capture asks for consent each time you Start (Android rule).
- Android only (APK). iOS would be a separate, more locked-down project.
