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
python main.py
```

### 🪟 Windows / 🍎 macOS
```bash
python -m venv venv
# Windows:  venv\Scripts\activate      macOS: source venv/bin/activate
pip install -r requirements.txt
python -m playwright install
python main.py
```

There is a **single `requirements.txt`** for every OS. Windows-only packages
(`comtypes`, `pycaw`, `win10toast`, `pywinauto`) are gated with environment markers,
so they are installed on Windows and automatically skipped on Linux/macOS.

> 💡 First launch asks for a free **Gemini** key and a free **OpenRouter** key.
> They're saved locally to `config/api_keys.json` and are **never committed**.

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
