# Contributing to JARVIS Mobile

First off — thank you. This project exists so anyone can have a real, open, on-device
JARVIS. Every contribution, from a typo fix to a whole new plugin, moves it forward.

## The golden rule: features are plugins

JARVIS Mobile is built around a tiny plugin host. **The best way to add a capability is to
write a plugin**, not to grow the core. See [`mobile/README.md` → Write a plugin](mobile/README.md#write-a-plugin)
for a copy-paste starter. New eyes (camera), new voice commands, memory, reminders — all
plugins.

## Ways to help

- **Write a plugin** — the highest-leverage contribution (see the guide above).
- **Fix bugs / harden device support** — CameraX, MediaProjection, and Accessibility behave
  differently across OEMs. Real-device reports are gold.
- **Improve docs** — clarity, screenshots, translations.
- **Test on your device** — tell us what Android version / phone works and what doesn't.

## Dev setup

- Android Studio (latest stable), **JDK 17**, Android **SDK 34**.
- `minSdk 26` (Android 8+).
- Build: `cd mobile && gradle :app:assembleDebug` (or open in Android Studio and Run).
- The APK is also built automatically by GitHub Actions (see the mobile README for the
  one-time workflow-install step).

## Ground rules

- **Never commit API keys.** Keys live on the device only (SharedPreferences). If you add a
  feature that needs a secret, keep it out of the repo.
- **Keep the core small.** Prefer a plugin over editing `AgentService`/`MainActivity`.
- **Respect the user.** JARVIS must never silently send/pay/delete — risky actions stay
  behind the confirm gate. Don't add anything that bypasses user consent or platform rules
  (e.g. don't try to defeat banking-app accessibility blocks).
- **No large binaries or datasets** committed to git.

## Pull requests

1. Fork, branch from `main`, make focused changes.
2. Follow the existing code style (Kotlin, `findViewById`, no viewBinding).
3. Update the README / roadmap if you add a user-facing feature.
4. Open the PR describing **what** and **why**, and note the device/Android version you
   tested on. Screenshots/GIFs welcome.

## Reporting bugs

Open an issue with: device + Android version, what you did, what happened, what you expected,
and any log lines from the app's Activity log.

By contributing you agree your work is licensed under the project's [MIT License](LICENSE).
