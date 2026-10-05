# Releasing & updating JARVIS — step by step

This app is distributed through **GitHub Releases** (not the Play Store), and it has a
**built-in updater** so users tap **Update** instead of reinstalling by hand. This file is
the whole process — follow it top to bottom.

---

## The big picture (only 3 things you ever do)

1. **One-time:** install the CI build workflow.
2. **Every release:** bump the version, build the APK.
3. **Every release:** publish a GitHub Release with the APK attached + a version tag.

Users then get an **"Update available"** prompt inside the app automatically.

---

## Step 1 — one-time: install the build workflow (30 seconds)

The automation token that pushes this repo can't write to `.github/workflows/`, so add it
yourself through GitHub's website:

1. Open the repo → **Add file → Create new file**.
2. Name it exactly: `.github/workflows/android.yml`
3. Paste the contents of [`mobile/ci/android-build.yml`](ci/android-build.yml).
4. **Commit**.

(You only do this once. After that, every tag triggers a build.)

---

## Step 2 — build the APK

**Automatic (recommended):** push a version tag and GitHub Actions builds it for you:
```bash
git tag v1.3.0 && git push origin v1.3.0
```
Then open **Actions → "Build JARVIS Mobile APK"** and wait for the green check. The APK is
attached to the Release (Step 3) and also available as a downloadable artifact.

**Locally (optional):**
```bash
cd mobile
gradle :app:assembleDebug      # or ./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Needs JDK 17 + Android SDK (API 34).

---

## Step 3 — publish the release (this is what users update FROM)

1. Go to the repo → **Releases → Draft a new release**.
2. **Create a new tag** — e.g. `v1.3.0`. It **must be higher** than the previous tag
   (`v1.2.0` → `v1.3.0`). The updater compares these numbers.
3. **Upload the APK** (`app-debug.apk`) as a release asset — drag it into the assets box.
4. **Publish release**.

> If you pushed the tag first, a Release may already exist for it — just edit it and upload
> the APK, then publish.

---

## Step 4 — how the in-app updater decides (so you know what to bump)

The app calls GitHub's `/releases/latest`, reads the **tag** and the **`.apk` asset**, and
compares the tag to the app's **`versionName`** (`mobile/app/build.gradle.kts`).

**So the rule is: `versionName` in the app = the tag you publish.**

Example flow:
- App currently ships `versionName = "1.2.0"`.
- You're ready to release: set `versionName = "1.3.0"` and `versionCode = 4` in
  `mobile/app/build.gradle.kts`, commit.
- Build, then publish a Release tagged **`v1.3.0`** with the APK.
- Users on 1.2.0 open the app → **"Update available — JARVIS 1.3.0"** → tap **Download & install**.

If the tag equals the installed version, nothing happens (correct — they're up to date).

### Release checklist
- [ ] Bump `versionName` + `versionCode` in `mobile/app/build.gradle.kts`.
- [ ] Commit + push to `arena/01a0e385-jarvismark40` (or merge to `main`).
- [ ] `git tag vX.Y.Z && git push origin vX.Y.Z`
- [ ] Confirm Actions built the APK; upload it to the Release if needed.
- [ ] Publish the Release. Done.

---

## Branding — name & logo

- **Name:** `mobile/app/src/main/res/values/strings.xml` → `app_name` (currently **JARVIS**).
- **Logo:** `mobile/app/src/main/res/mipmap-*/ic_launcher.png` (the arc-reactor). To rebrand,
  replace those PNGs (keep the sizes: 48/72/96/144/192 px) and change `app_name`.

> Note: "JARVIS" is a Marvel/Disney trademark. It's fine for a personal/fan project off the
> Play Store, but if this goes public you may want your own name — just change `app_name` +
> the logo. Everything else stays the same.

---

## Permissions users grant (so you can explain them)

| Permission | Why | When |
|---|---|---|
| Accessibility | see + drive apps | once, manual |
| Screen capture | screenshots for the model | each Start |
| Display over other apps | floating bubble + focus reminder | optional |
| Microphone | voice in | first voice use |
| Camera | "look at my camera" | first camera use |
| Install unknown apps | in-app updates | first update |

---

## Handoff — for the next developer / AI

**Architecture:** a tiny plugin host. A feature = one `JarvisPlugin` class + one line in
`BuiltinPlugins.install()`. See `mobile/README.md → Write a plugin`.

**Built-in plugins:** Voice, Camera, Live, Wake word, Memory, Focus.

**Where things live:**
- Provider list + endpoints: `Providers.kt` (add a provider = one entry).
- Model calls (OpenAI + Anthropic styles): `ModelClient.kt`.
- Persona (customize the AI): `filesDir/persona.md`, edited in `PersonaActivity`.
- Memory: SharedPreferences `jarvis_memory` (`MemoryPlugin`).
- Settings: SharedPreferences `jarvis`.
- Repo target for updates: `UpdateManager.kt → object Github` (OWNER/REPO).
- The agent loop: `AgentService.kt`; eyes/hands: `JarvisAccessibilityService.kt`.

**Known to-need-real-device-testing** (couldn't be compiled in the author's sandbox):
CameraX capture, MediaProjection screenshots, the focus overlay, TTS/STT, the wake-word
listening loop, and the updater's install intent. The first CI build is the real gate.

**To change the update source** (e.g. you fork the repo): edit `Github.OWNER` / `Github.REPO`
in `UpdateManager.kt`.
