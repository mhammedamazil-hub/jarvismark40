"""
cmd_control — natural-language system command execution for MARK XXXIX / JARVIS.

Translates a plain-English task into a single shell command and runs it on the
host. This is the planner/executor's escape hatch for "open this file", "run a
system command", "show my IP", "kill that process", etc. — the low-level glue
that lets JARVIS control the entire PC.

Cross-platform: Linux (primary), Windows, macOS.
Safety: a small denylist blocks catastrophic, irreversible disk/system
destruction. Everything else is executed — this is a full-control assistant.
"""
import re
import sys
import json
import shutil
import platform
import subprocess
from pathlib import Path

BASE_DIR        = Path(__file__).resolve().parent.parent
API_CONFIG_PATH = BASE_DIR / "config" / "api_keys.json"

# Commands that would irreversibly destroy the system or all user data.
_DENY_PATTERNS = [
    r"\brm\s+-[a-z]*(?:rf|fr)[a-z]*\s+/(?:\s|$|\*)",     # rm -rf /  or  rm -fr /  (any flag order)
    r"\brm\s+-[a-z]*(?:rf|fr)[a-z]*\s+[~$]",              # rm -rf ~  /  rm -rf $HOME
    r"\brm\s+-r[a-z]*\s+-f[a-z]*\s+/",                    # rm -r -f /   (split flags)
    r"\brm\s+-f[a-z]*\s+-r[a-z]*\s+/",                    # rm -f -r /
    r"\bmkfs\b",                                          # format a disk
    r"\bdd\s+if=",                                        # raw disk write
    r">\s*/dev/(?:sd|hd|nvme|disk)",                       # overwrite a block device
    r":\(\)\s*\{",                                        # fork bomb  :(){ :|:& };:
    r"\bchmod\s+-R\s+0?777\s+/\s*$",
    r"\bwipefs\b",
    r"\bshred\s+.*\s/dev/",
]


def _get_api_key() -> str:
    with open(API_CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f).get("gemini_api_key", "")


def _os_info() -> dict:
    home = Path.home()
    return {
        "system":    platform.system(),          # Linux / Windows / Darwin
        "home":      str(home),
        "desktop":   str(home / "Desktop"),
        "downloads": str(home / "Downloads"),
        "documents": str(home / "Documents"),
        "shell":     "PowerShell" if platform.system() == "Windows" else "bash",
    }


def _is_denied(cmd: str) -> bool:
    c = cmd.lower()
    return any(re.search(p, c) for p in _DENY_PATTERNS)


def _translate(task: str) -> str | None:
    """Ask Gemini for ONE shell command for this task. Returns the command or None."""
    try:
        import google.generativeai as genai

        key = _get_api_key()
        if not key:
            return None

        genai.configure(api_key=key)
        info  = _os_info()
        model = genai.GenerativeModel(
            model_name="gemini-2.5-flash",
            system_instruction=(
                "You translate a plain-English computer task into ONE shell command "
                f"for {info['system']} using {info['shell']}. Rules:\n"
                "- Output ONLY the command. No markdown, no backticks, no explanation.\n"
                "- Use the real paths below; never invent them.\n"
                "- Prefer safe, non-destructive commands; never wipe data.\n"
                f"  Home={info['home']}\n  Desktop={info['desktop']}\n"
                f"  Downloads={info['downloads']}\n  Documents={info['documents']}\n"
                "- To OPEN a file/folder/URL on Linux use: xdg-open <path>\n"
                "  On Windows use: start \"\" <path>   On macOS use: open <path>\n"
                "- If several commands are needed, join with && on one line.\n"
            ),
        )
        resp = model.generate_content(task)
        cmd  = (resp.text or "").strip()
        cmd  = re.sub(r"```(?:bash|sh|powershell|cmd|shell)?", "", cmd).strip().rstrip("`").strip()
        cmd  = next((ln.strip() for ln in cmd.splitlines() if ln.strip()), "")
        return cmd or None
    except Exception:
        return None


def _open_cmd(target: str) -> str:
    sysname = platform.system()
    if sysname == "Windows":
        return f'start "" "{target}"'
    if sysname == "Darwin":
        return f'open "{target}"'
    return f'xdg-open "{target}"'


def _heuristic(task: str) -> str | None:
    """Offline fallback for obvious 'open X' tasks (no LLM needed)."""
    m = re.match(r"(?i)^(?:please\s+)?(?:open|launch|show)\s+(.+)$", task.strip())
    if not m:
        return None
    target = m.group(1).strip().strip('"').strip("'")
    if re.match(r"(?i)^(https?://|www\.)", target):
        return _open_cmd(target)
    p = Path(target).expanduser()
    if p.exists():
        return _open_cmd(str(p))
    # treat as an app name / bare path
    return _open_cmd(target)


def _find_terminal() -> str | None:
    if platform.system() == "Windows":
        return "cmd"
    for term in ("x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "xterm"):
        if shutil.which(term):
            return term
    return None


def _run_visible(cmd: str) -> str:
    """Best-effort: run the command inside a visible terminal window."""
    term = _find_terminal()
    sysname = platform.system()
    try:
        if sysname == "Windows":
            subprocess.Popen(f'start cmd /k {cmd}', shell=True)
        elif term == "gnome-terminal":
            subprocess.Popen([term, "--", "bash", "-lc", f"{cmd}; exec bash"])
        elif term and term != "x-terminal-emulator":
            subprocess.Popen([term, "-e", "bash", "-lc", f"{cmd}; exec bash"])
        elif term:  # x-terminal-emulator
            subprocess.Popen([term, "-e", "bash", "-lc", f"{cmd}; exec bash"])
        else:
            return _run(cmd, timeout=120)
        return "Launched in a terminal window, sir."
    except Exception as e:
        return f"Could not open a terminal ({e}); ran it headless instead:\n{_run(cmd, timeout=120)}"


def _run(cmd: str, timeout: int = 120) -> str:
    try:
        if platform.system() == "Windows":
            proc = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        else:
            proc = subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return f"Command timed out after {timeout}s, sir."
    except Exception as e:
        return f"Command failed to start: {e}"

    out = (proc.stdout or "").strip()
    err = (proc.stderr or "").strip()
    if proc.returncode == 0:
        return out if out else "Done."
    combined = (out + ("\n" if out and err else "") + err).strip()
    return combined or f"Command failed (exit {proc.returncode}), sir."


def cmd_control(parameters: dict, player=None, speak=None) -> str:
    params  = parameters or {}
    task    = str(params.get("task", "")).strip()
    visible = bool(params.get("visible", False))

    if not task:
        return "No command specified, sir."

    if player and hasattr(player, "write_log"):
        player.write_log(f"SYS: cmd_control — {task}")

    cmd = _translate(task) or _heuristic(task)
    if not cmd:
        msg = "I couldn't work out a command for that, sir."
        if speak:
            speak(msg)
        return msg

    if _is_denied(cmd):
        msg = "I won't run that — it could damage the system, sir."
        if player and hasattr(player, "write_log"):
            player.write_log(f"SYS: cmd_control BLOCKED — {cmd}")
        if speak:
            speak(msg)
        return msg

    if player and hasattr(player, "write_log"):
        player.write_log(f"SYS: exec — {cmd}")

    result = _run_visible(cmd) if visible else _run(cmd)
    if len(result) > 1500:
        result = result[:1500] + "\n… (truncated)"
    return result
