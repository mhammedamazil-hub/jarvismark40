# actions/reminder.py
"""
Cross-platform timed reminders for JARVIS.

An in-process scheduler (daemon thread) fires a NATIVE desktop notification
when each reminder comes due — works on Linux (notify-send), macOS
(osascript) and Windows (toast/beep). Pending reminders persist to
memory/reminders.json and are re-armed automatically when JARVIS restarts,
so a reminder set for later still fires as long as JARVIS is running then.

This replaces the previous Windows-only implementation (winsound /
win10toast / schtasks), which could never work on Linux Mint.
"""

import sys
import json
import time
import shutil
import threading
from datetime import datetime
from pathlib import Path

BASE_DIR   = Path(__file__).resolve().parent.parent
STORE_PATH = BASE_DIR / "memory" / "reminders.json"

CHECK_INTERVAL = 15  # seconds between due-checks

_lock    = threading.RLock()
_pending: list[dict] = []          # [{"when": "<iso>", "message": "..."}]
_started = False


# ── persistence ──────────────────────────────────────────────────────────────
def _load_store() -> None:
    global _pending
    try:
        if STORE_PATH.exists():
            data = json.loads(STORE_PATH.read_text(encoding="utf-8"))
            if isinstance(data, list):
                _pending = [
                    r for r in data
                    if isinstance(r, dict) and r.get("when") and r.get("message")
                ]
    except Exception:
        _pending = []


def _save_store() -> None:
    try:
        STORE_PATH.parent.mkdir(parents=True, exist_ok=True)
        STORE_PATH.write_text(
            json.dumps(_pending, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except Exception:
        pass


# ── notification (best-effort, cross-platform) ───────────────────────────────
def _notify(message: str) -> None:
    msg = str(message).replace('"', "'").strip()[:300] or "Reminder"
    plat = sys.platform
    try:
        if plat == "win32":
            try:
                from win10toast import ToastNotifier
                ToastNotifier().show_toast("JARVIS Reminder", msg, duration=15, threaded=True)
                return
            except Exception:
                pass
            try:
                import winsound
                for freq in (800, 1000, 1200):
                    winsound.Beep(freq, 200)
            except Exception:
                pass

        elif plat == "darwin":
            import subprocess
            subprocess.run(
                ["osascript", "-e",
                 f'display notification "{msg}" with title "JARVIS Reminder" sound name "Glass"'],
                capture_output=True,
            )

        else:  # Linux / BSD
            import subprocess
            if shutil.which("notify-send"):
                subprocess.run(
                    ["notify-send", "-a", "JARVIS", "-u", "critical",
                     "JARVIS Reminder", msg],
                    capture_output=True,
                )
            # Best-effort audible chime (speech or a system sound), if available.
            for speaker in ("spd-say", "espeak-ng", "espeak"):
                if shutil.which(speaker):
                    try:
                        subprocess.run([speaker, f"Reminder, sir. {msg}"], capture_output=True)
                    except Exception:
                        pass
                    break
    except Exception:
        pass


# ── scheduler ────────────────────────────────────────────────────────────────
def _fire_due(now: datetime | None = None) -> int:
    """Fire every reminder whose time has passed. Returns how many fired."""
    now = now or datetime.now()
    due, remaining = [], []
    with _lock:
        for r in _pending:
            try:
                when = datetime.fromisoformat(r["when"])
            except Exception:
                continue
            (due if when <= now else remaining).append(r)
        if due:
            _pending[:] = remaining
            _save_store()
    for r in due:
        print(f"[Reminder] ⏰ Firing: {r.get('message')}")
        _notify(r.get("message", "Reminder"))
    return len(due)


def _worker() -> None:
    while True:
        try:
            _fire_due()
        except Exception as e:
            print(f"[Reminder] scheduler error: {e}")
        time.sleep(CHECK_INTERVAL)


def _ensure_started() -> None:
    global _started
    with _lock:
        if not _started:
            _load_store()
            threading.Thread(target=_worker, daemon=True, name="ReminderScheduler").start()
            _started = True


# ── public API ───────────────────────────────────────────────────────────────
def reminder(
    parameters: dict | None = None,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    Schedule a reminder.

    parameters:
        date    (str) YYYY-MM-DD
        time    (str) HH:MM (24h)
        message (str) what to be reminded about
    """
    p        = parameters or {}
    date_str = str(p.get("date", "")).strip()
    time_str = str(p.get("time", "")).strip()
    message  = str(p.get("message", "Reminder")).strip() or "Reminder"

    if not date_str or not time_str:
        return "I need both a date and a time to set a reminder, sir."

    try:
        target = datetime.strptime(f"{date_str} {time_str}", "%Y-%m-%d %H:%M")
    except ValueError:
        return "I couldn't understand that date or time. Please use YYYY-MM-DD and HH:MM, sir."

    if target <= datetime.now():
        return "That time is already in the past, sir."

    _ensure_started()
    with _lock:
        _pending.append({"when": target.isoformat(), "message": message})
        _save_store()

    if player:
        player.write_log(f"[reminder] set for {date_str} {time_str}")

    return f"Reminder set for {target.strftime('%B %d at %I:%M %p')}, sir."


def list_reminders() -> str:
    """Return a human-readable list of pending reminders."""
    _ensure_started()
    with _lock:
        if not _pending:
            return "You have no pending reminders, sir."
        items = []
        for r in sorted(_pending, key=lambda x: x.get("when", "")):
            try:
                when = datetime.fromisoformat(r["when"]).strftime("%b %d, %I:%M %p")
            except Exception:
                when = r.get("when", "?")
            items.append(f"• {when} — {r.get('message', '')}")
        return "Pending reminders:\n" + "\n".join(items)


# Re-arm any reminders saved from a previous session as soon as this module loads.
_ensure_started()
