# JARVIS Mobile — standalone phone agent

An Android app that **sees your screen and drives any app** to reach a goal you type
("open WhatsApp and message Ravi 'on my way'", "open Chrome and search logo design").
It thinks for itself using an online vision model (OpenRouter) — **no laptop needed**.

- **Eyes:** screenshots (MediaProjection) + the screen's text/buttons (Accessibility).
- **Hands:** taps / swipes / typing / launching apps via the Accessibility Service
  (this is Android's equivalent of `pyautogui`).
- **Brain:** one cheap OpenRouter vision model you pick in the app decides each step.

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
