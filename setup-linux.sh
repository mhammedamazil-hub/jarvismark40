#!/usr/bin/env bash
# ============================================================================
#  JARVIS (MARK XXXIX) — Linux (Mint / Ubuntu / Debian) one-shot setup
#  Usage:  bash setup-linux.sh
# ============================================================================
set -euo pipefail

echo "==> [1/4] Installing system libraries (sudo required)…"
sudo apt update
sudo apt install -y \
    python3-venv python3-pip \
    portaudio19-dev \
    xclip xsel \
    python3-xlib \
    xdotool wmctrl \
    brightnessctl \
    libnotify-bin \
    libgl1 libglib2.0-0

echo "==> [2/4] Creating virtual environment…"
python3 -m venv venv
# shellcheck disable=SC1091
source venv/bin/activate

echo "==> [3/4] Installing Python packages…"
pip install --upgrade pip
pip install -r requirements.txt

echo "==> [4/4] Installing Playwright browsers…"
python -m playwright install

cat <<'EOF'

✅  Setup complete!

   Run it with:
       source venv/bin/activate
       python main.py

   First launch will ask for your Gemini + OpenRouter API keys
   (they are stored locally in config/api_keys.json and never committed).
EOF
