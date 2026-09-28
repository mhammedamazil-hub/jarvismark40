# 🤖 MARK XXXIX-OR (39)
### Cross-Platform Personal AI Assistant — based on the original by FatihMakes

> 📺 **[Original project / setup video](https://youtu.be/ldvDNzwnM8k)** · Creator: [@FatihMakes](https://www.youtube.com/@FatihMakes)
>
> This is a community fork (used with permission). It keeps the original engine and
> adds first-class **Linux (Mint/Ubuntu)** support, a rebuilt interface, and upgraded
> autonomous features. Original work © FatihMakes — licensed **CC BY-NC 4.0**
> (personal / non-commercial use, attribution required).

A real-time voice AI that can hear, see, understand, and control your computer — on any OS. Local execution. Zero subscriptions.

---

## ⚡ Quick Start

### 🐧 Linux (Mint / Ubuntu / Debian) — recommended
```bash
bash setup-linux.sh          # installs system libs + python deps + playwright
source venv/bin/activate
python main.py               # one-off run (Ctrl+C to quit)
```

### 🪟 Windows / 🍎 macOS
```bash
python -m venv venv
# Windows:  venv\Scripts\activate      macOS: source venv/bin/activate
pip install -r requirements.txt
python -m playwright install
python main.py
```

## 🚀 Always-On — auto-start on login + live in the tray (Linux)

Make JARVIS a **Google-Assistant-style, always-there** companion that starts when
you log in and sits in the system tray (click the tray icon to summon the window,
right-click for Start/Stop camera, mute, quit):

```bash
bash scripts/install_autostart.sh            # enable always-on
bash scripts/install_autostart.sh --uninstall  # remove it again
```

This installs:
| What | Where |
|------|-------|
| Login auto-start | `~/.config/autostart/jarvis.desktop` |
| `jarvis` command (run anytime) | `~/.local/bin/jarvis` |
| App-menu launcher | `~/.local/share/applications/jarvis.desktop` |
| Optional crash-restart service | `~/.config/systemd/user/jarvis.service` (see below) |

- After install, log out/in once (or run `jarvis`) — JARVIS starts automatically.
- Closing the window **hides it to the tray**, it does not quit. Quit from the tray menu.
- Want it to auto-restart if it ever crashes? `bash scripts/install_autostart.sh --service`
  then `systemctl --user enable --now jarvis.service`.

> 🎙️ **Honest scope note:** the tray icon is the reliable "summon" path. A
> *global hotkey that works while hidden* and an on-device *"Hey JARVIS"* wake word
> are **not** bundled — they need extra native deps (`pynput` / `openWakeWord`) and
> extra RAM, so they were left out of the low-RAM default.

There is a **single `requirements.txt`** for every OS. Windows-only packages
(`comtypes`, `pycaw`, `win10toast`, `pywinauto`) are gated with environment markers,
so they are installed on Windows and automatically skipped on Linux/macOS.

> 💡 First launch asks for a free **Gemini** key and a free **OpenRouter** key.
> They're saved locally to `config/api_keys.json` and are **never committed**.

---

## 🆕 Latest abilities

- **📺 Video in the HUD** — *"play the new Dune trailer"*, a YouTube link, a video
  URL, or a local file plays **right where the face is**. It always starts muted;
  say *"unmute"* (or use the header button) for sound, and the mic mutes itself
  while the film is audible so JARVIS doesn't talk over it.
- **🌍 God's Eye** — *"open god's eye"* launches the famous open-source
  [God's Eye View](https://github.com/bilawalsidhu/gods-eye-view): a live 3D globe
  of real public data (aircraft, ships, satellites, earthquakes, fires, public
  cameras). First run installs it (needs Node.js); after that it opens fast.
- **🪜 Model ladder** — every one-shot Gemini call now runs through a measured
  ladder of models with timeouts + cooldowns (`core/gemini.py`), so a quota hit or
  an outage steps to the next model instead of hanging or failing.
- **💼 Business outreach** (`outreach`) — a *legal* assistant for landing ad /
  poster / website clients: it keeps a prospect list, writes a **personalised**
  pitch for each business with the LLM, and sends them **one at a time** with a
  daily cap and human-like delays. **Email first** (identified, with an opt-out),
  then WhatsApp/Telegram/LinkedIn. It always drafts and waits for your approval.
  Say *"outreach status"*, *"outreach draft"*, *"outreach send"*. Configure your
  studio + SMTP in `~/.jarvis/outreach/config.json`.

---

## 📋 Requirements

| Requirement | Details |
|---|---|
| **OS** | Windows 10/11, macOS, or Linux (Mint/Ubuntu/Debian) |
| **Python** | 3.11 or 3.12 |
| **Microphone** | Required for voice interaction |
| **API Keys** | Free Gemini API key + free OpenRouter API key |

### Linux system packages (installed by `setup-linux.sh`)
`portaudio19-dev` (audio) · `python3-xlib` `xdotool` `wmctrl` (window control) ·
`brightnessctl` (screen brightness) · `xclip`/`xsel` (clipboard) · `libnotify-bin` (toasts) ·
`libgl1` (OpenCV)

---

## ✨ Capabilities

| Feature | Description |
|---|---|
| 🎙️ Real-time Voice | Ultra-low latency conversation in any language |
| 🖥️ System Control | Launch apps, manage files, run terminal commands |
| 🧩 Autonomous Tasks | Multi-step planning for complex goals |
| 👁️ Visual Awareness | Real-time screen + webcam vision |
| 🧠 Persistent Memory | Remembers your projects, preferences, context |
| ⌨️ Hybrid Input | Switch freely between keyboard and voice |
| 🔀 OpenRouter routing | Non-voice modules route LLM calls through OpenRouter free tier |

---

## ⚖️ License & Credit

Original project **MARK XXXIX-OR** by **FatihMakes** ([@FatihMakes](https://www.youtube.com/@FatihMakes)).
Personal and non-commercial use only. Licensed under **[Creative Commons BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)**.

⭐ Star the original to support the journey to Mark 100.
