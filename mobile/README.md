# JARVIS Mobile — standalone phone agent

An Android app that **sees your screen and drives any app** to reach a goal you type
("open WhatsApp and message Ravi 'on my way'", "open Chrome and search logo design").
It thinks for itself using an online vision model (OpenRouter) — **no laptop needed**.

- **Eyes:** screenshots (MediaProjection) + the screen's text/buttons (Accessibility).
- **Hands:** taps / swipes / typing / launching apps via the Accessibility Service
  (this is Android's equivalent of `pyautogui`).
- **Brain:** a cheap vision model you pick in the app (OpenRouter or Gemini) decides each step.
- **Voice:** push-to-talk to give a goal hands-free; JARVIS speaks replies and confirmations.
- **Phone + tablet:** the layout scrolls and centers to a readable column on any screen.
- **Plugins:** new eyes (camera), live answers, and voice commands drop in like apps.

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

### Phone **and** tablet
The layout scrolls and self-centers to a readable column on tablets (via `values-sw600dp`),
so it's usable on both without cutting anything off.

### 🔌 Plugins — add features like apps
JARVIS is built on a tiny plugin host (`JarvisPlugin` + `PluginRegistry`). A plugin can give
the model **new eyes** (extra images), **new context**, **new voice commands**, and **its own
tools** — all merged into the agent loop automatically. Adding a feature = one `JarvisPlugin`
class + one line in `BuiltinPlugins.install()`.

Voice is plugin #1. The next drop-ins (same pattern, each isolated and low-risk):
**Camera** ("see my camera when I show it"), **Live** (continuous watch + instant spoken
answers), and **Wake word** (hands-free "Hey JARVIS", opt-in).

## Build locally (optional)
```bash
cd mobile
gradle :app:assembleDebug      # or: ./gradlew assembleDebug if you have the wrapper
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 + Android SDK (API 34). `minSdk 26` (Android 8+).

## Honest limits
- The Accessibility Controller must be enabled once, manually (Android requires it).
- Needs internet (the model is online). Costs a little per step (cheap model ≈ pennies).
- Banking / secure apps detect accessibility and block it — can't and shouldn't bypass.
- Screenshot capture asks for consent each time you Start (Android rule).
- Android only (APK). iOS would be a separate, more locked-down project.
