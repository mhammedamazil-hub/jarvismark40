# JARVIS Mobile — Roadmap

The vision: a real, open, on-device JARVIS in your pocket — it sees (screen + camera),
listens, talks back, drives any app, and grows through **plugins**. This is the plan; PRs
welcome on any of it.

## ✅ Shipped

- **Sees the screen** — screenshot (MediaProjection) + readable elements (Accessibility).
- **Drives any app** — tap / swipe / type / launch / back / home (the Android "pyautogui").
- **On-device agent loop** — see → think → act, one step at a time, survives leaving the app.
- **Two brains** — OpenRouter or Gemini, pick key + model in-app.
- **Floating bubble** — give a goal from inside any app.
- **Confirm-before-send** — JARVIS asks before it types/sends/pays/deletes.
- **Voice** — push-to-talk goals; JARVIS speaks replies + confirmations.
- **Wake word** — hands-free "Yo JARVIS…" routes to screen / camera / general answers.
- **Camera plugin** — "look at what I'm showing you" (front/back, on-demand frame).
- **Live plugin** — instant see-screen(+camera)-and-answer, spoken.
- **9 providers** — OpenRouter, Gemini, OpenAI, NVIDIA NIM, Anthropic, Groq, Together, Mistral, DeepSeek.
- **HUD UI** — animated arc-reactor background + neon controls (Iron-Man grade).
- **Plugin architecture** — a feature is one `JarvisPlugin` + one line.
- **Phone + tablet** — responsive, scrolling layout.

## 🔜 Next (great first contributions)

- **Memory / recall** — remember preferences and past goals (persisted, on-device).
- **Scheduled goals** — "every morning at 8, open X and …" (WorkManager).
- **Remote dashboard** — control from a laptop/browser over local network (QR pair).
- **Live watch mode** — continuous proactive help (opt-in; watch the API cost).
- **Continuous camera** — stream frames to Live mode, not just on-demand stills.

## 🧪 Quality / infra

- Screenshots + a demo GIF in the README.
- Automated UI tests (Espresso) + a CI device-matrix note.
- Accessibility audit (TalkBack) so JARVIS is usable by everyone.
- Translations (the UI strings are already in `strings.xml`).

## 💡 Ideas (needs discussion)

- More plugins: reminders, clipboard intelligence, hardware/status HUD, quick shortcuts.
- Pluggable "model ladder" (fallback models + cooldowns) for reliability.
- A plugin marketplace / in-app plugin manager (sandboxing TBD — do it safely).

> Want to build one? Read [`mobile/README.md` → Write a plugin](mobile/README.md#write-a-plugin)
> and open a PR. Small, focused plugins are the fastest way to make JARVIS yours.
