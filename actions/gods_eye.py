# gods_eye.py — launch the famous open-source "God's Eye View" globe from JARVIS.
#
# God's Eye View (github.com/bilawalsidhu/gods-eye-view) is a browser app that
# puts live public feeds — aircraft, ships, satellites, earthquakes, fires, public
# cameras — onto one photorealistic 3D globe you can fly and talk to. It is the
# "better than the Earth" world-view you asked for.
#
# JARVIS doesn't reimplement it; it *hosts and opens* it, so you can say
# "open god's eye" and it's on screen. First run clones the repo and runs
# `npm install` (a few minutes, and it needs Node.js). After that it starts fast.
#
# Honest notes:
#   * Needs Node.js + npm (Mint: `sudo apt install nodejs npm`) and git.
#   * Photorealistic 3D city tiles + some layers want optional API keys
#     (Google Maps 3D, Cesium ion). The base globe works keyless.
#   * It runs in your browser, so its RAM cost is the browser's, not JARVIS's.
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import threading
from pathlib import Path

try:
    from config import is_linux
except Exception:  # pragma: no cover
    def is_linux(): return sys.platform.startswith("linux")

REPO_URL = "https://github.com/bilawalsidhu/gods-eye-view.git"

BASE_DIR = Path(__file__).resolve().parent.parent
APPS_DIR = Path.home() / ".jarvis" / "apps"
INSTALL_DIR = APPS_DIR / "gods-eye"

_proc: subprocess.Popen | None = None
_lock = threading.Lock()


def _say(speak, text: str) -> None:
    if callable(speak):
        try:
            speak(text)
        except Exception:
            pass


def _log(player, text: str) -> None:
    if player is not None and hasattr(player, "write_log"):
        try:
            player.write_log(f"[GodsEye] {text}")
        except Exception:
            pass


def _open_browser(url: str) -> None:
    try:
        if sys.platform == "darwin":
            subprocess.Popen(["open", url])
        elif is_linux():
            subprocess.Popen(["xdg-open", url])
        else:
            subprocess.Popen(["cmd", "/c", "start", "", url], shell=False)
    except Exception as e:
        print(f"[GodsEye] browser open failed: {e}")


def _deps_missing() -> list[str]:
    missing = []
    if not shutil.which("git"):
        missing.append("git")
    if not (shutil.which("npm") or shutil.which("node")):
        missing.append("nodejs/npm")
    return missing


def _clone() -> bool:
    APPS_DIR.mkdir(parents=True, exist_ok=True)
    if INSTALL_DIR.exists() and (INSTALL_DIR / "package.json").exists():
        return True
    if INSTALL_DIR.exists():
        shutil.rmtree(INSTALL_DIR, ignore_errors=True)
    r = subprocess.run(
        ["git", "clone", "--depth", "1", REPO_URL, str(INSTALL_DIR)],
        capture_output=True, text=True, timeout=600,
    )
    return r.returncode == 0 and (INSTALL_DIR / "package.json").exists()


def _npm_install() -> bool:
    npm = shutil.which("npm") or "npm"
    r = subprocess.run(
        [npm, "install", "--no-audit", "--no-fund"],
        cwd=str(INSTALL_DIR), capture_output=True, text=True, timeout=1800,
    )
    return r.returncode == 0


def _start_server() -> str | None:
    """Start the Vite dev server, return the local URL it prints (or a default)."""
    global _proc
    npm = shutil.which("npm") or "npm"
    _proc = subprocess.Popen(
        [npm, "run", "dev"],
        cwd=str(INSTALL_DIR),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1,
    )
    url = None
    # Vite prints "Local:   http://localhost:5173/" within a few seconds.
    import time
    deadline = time.time() + 60
    while time.time() < deadline and url is None:
        line = _proc.stdout.readline()
        if not line:
            break
        print("[GodsEye]", line.rstrip())
        m = re.search(r"(https?://localhost:\d+)", line)
        if m:
            url = m.group(1)
    return url or "http://localhost:5173/"


def _launch(speak, player) -> None:
    try:
        _log(player, "Preparing God's Eye (first run installs dependencies)…")
        if not _clone():
            _say(speak, "I couldn't download God's Eye. Check your internet connection.")
            _log(player, "git clone failed.")
            return
        if not (INSTALL_DIR / "node_modules").exists():
            _say(speak, "First run — installing God's Eye. This takes a few minutes, sir.")
            _log(player, "npm install (this is the slow first run)…")
            if not _npm_install():
                _say(speak, "The install failed. I've logged the details.")
                _log(player, "npm install failed.")
                return
        url = _start_server()
        _say(speak, "God's Eye is live. Opening the globe now.")
        _log(player, f"Serving at {url}")
        _open_browser(url)
    except Exception as e:
        _log(player, f"launch error: {e}")
        _say(speak, "Something went wrong launching God's Eye.")


def gods_eye(parameters: dict, player=None, speak=None) -> str:
    action = (parameters.get("action") or "open").lower().strip()

    if action in ("stop", "close", "quit"):
        return stop_gods_eye(parameters, player, speak)

    missing = _deps_missing()
    if missing:
        if "git" in missing and "nodejs/npm" in missing:
            msg = ("God's Eye needs git and Node.js. On Mint: "
                   "sudo apt install git nodejs npm")
        elif "git" in missing:
            msg = "God's Eye needs git. On Mint: sudo apt install git"
        else:
            msg = "God's Eye needs Node.js. On Mint: sudo apt install nodejs npm"
        _log(player, msg)
        _say(speak, "I need a couple of system packages for God's Eye. I've put the command on screen.")
        return msg

    with _lock:
        if _proc is not None and _proc.poll() is None:
            url = "http://localhost:5173/"
            _open_browser(url)
            _say(speak, "God's Eye is already running — bringing it up.")
            return "God's Eye already running; re-opened."

    _say(speak, "Spinning up God's Eye, sir.")
    threading.Thread(target=_launch, args=(speak, player), daemon=True,
                     name="GodsEyeLaunch").start()
    return "Launching God's Eye (first run installs dependencies — a few minutes)."


def stop_gods_eye(parameters: dict, player=None, speak=None) -> str:
    global _proc
    with _lock:
        if _proc is not None and _proc.poll() is None:
            try:
                _proc.terminate()
            except Exception:
                pass
            _proc = None
            _say(speak, "God's Eye closed.")
            return "God's Eye stopped."
    _say(speak, "God's Eye isn't running.")
    return "God's Eye wasn't running."
