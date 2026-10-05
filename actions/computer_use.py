"""
JARVIS computer-use engine + scriptable-app skills.

Two automation engines, picked automatically:
  1. computer_use()  — a vision-in-the-loop agent that operates ANY GUI:
     screenshot -> Gemini vision -> ONE next action -> execute -> verify -> repeat,
     with stuck-detection and self-correction. The general "operate anything" primitive.
  2. blender()       — the PEAK path for scriptable apps: generate a real bpy
     (Blender Python) script for the described object and run it in Blender.

Low-RAM friendly: screenshots are downscaled + JPEG-compressed before being sent
to the vision model, and returned coordinates are rescaled back to the real
screen, so it stays light on 4GB machines without losing click accuracy.
All heavy deps (pyautogui / mss / PIL / google.genai) are imported lazily so the
core control logic can be imported and unit-tested without a display or API key.
"""
from __future__ import annotations

import glob
import hashlib
import json
import os
import platform
import re
import shutil
import tempfile
import time
from typing import Callable


# --------------------------------------------------------------------------- #
#  Action parsing / validation (pure, unit-tested)
# --------------------------------------------------------------------------- #
VALID_ACTIONS = {
    "click", "double_click", "right_click", "type", "hotkey", "press",
    "scroll", "move", "drag", "wait", "done", "fail",
}

_ACTION_ALIASES = {
    "doubleclick": "double_click", "dclick": "double_click", "rightclick": "right_click",
    "keypress": "press", "key": "press", "enter_text": "type", "write": "type",
    "input": "type", "finished": "done", "complete": "done", "completed": "done",
    "error": "fail", "abort": "fail",
}


def _extract_json(text: str) -> dict:
    t = (text or "").strip()
    t = re.sub(r"```(?:json)?", "", t).strip()
    s, e = t.find("{"), t.rfind("}")
    if s == -1 or e == -1 or e < s:
        raise ValueError("no JSON object in model output")
    return json.loads(t[s:e + 1])


def _parse_action(text: str) -> dict:
    """Parse & validate a single model action. Raises ValueError if unusable."""
    data = _extract_json(text)
    if not isinstance(data, dict):
        raise ValueError("action is not a JSON object")

    action = str(data.get("action", "")).strip().lower()
    action = _ACTION_ALIASES.get(action, action)
    if action not in VALID_ACTIONS:
        raise ValueError(f"unknown action: {action!r}")

    out: dict = {"action": action}
    for k in ("x", "y", "x2", "y2", "amount", "seconds"):
        if data.get(k) is not None:
            try:
                out[k] = int(round(float(data[k])))
            except (ValueError, TypeError):
                pass
    for k in ("text", "key", "direction", "button", "summary", "reason"):
        if data.get(k) is not None:
            out[k] = str(data[k])

    if action == "hotkey":
        keys = data.get("keys") or data.get("key") or ""
        if isinstance(keys, str):
            keys = re.split(r"[+\s]+", keys.strip()) if keys.strip() else []
        out["keys"] = [str(k) for k in keys if str(k)]

    if action == "type" and "text" not in out:
        out["text"] = str(data.get("value", ""))

    return out


def _scale_coords(action: dict, img_size, screen_size) -> dict:
    """Rescale coordinates from the (possibly downscaled) image to the real screen."""
    if not img_size or not screen_size:
        return action
    iw, ih = img_size
    sw, sh = screen_size
    if iw <= 0 or ih <= 0 or (iw == sw and ih == sh):
        return action
    sx, sy = sw / iw, sh / ih
    for k in ("x", "x2"):
        if k in action:
            action[k] = int(round(action[k] * sx))
    for k in ("y", "y2"):
        if k in action:
            action[k] = int(round(action[k] * sy))
    return action


# --------------------------------------------------------------------------- #
#  Default executor (maps actions onto the existing computer_control primitives)
# --------------------------------------------------------------------------- #
def _default_exec(action: dict) -> str:
    import actions.computer_control as cc
    a = action["action"]
    if a == "click":
        return cc._click(action.get("x"), action.get("y"), button=action.get("button", "left"))
    if a == "double_click":
        return cc._click(action.get("x"), action.get("y"), clicks=2)
    if a == "right_click":
        return cc._click(action.get("x"), action.get("y"), button="right")
    if a == "type":
        return cc._smart_type(action.get("text", ""))
    if a == "hotkey":
        return cc._hotkey(*action.get("keys", []))
    if a == "press":
        return cc._press(action.get("key", "enter"))
    if a == "scroll":
        return cc._scroll(action.get("direction", "down"), action.get("amount", 3))
    if a == "move":
        return cc._move(action.get("x", 0), action.get("y", 0))
    if a == "drag":
        return cc._drag(action.get("x", 0), action.get("y", 0),
                        action.get("x2", 0), action.get("y2", 0))
    if a == "wait":
        time.sleep(min(10.0, float(action.get("seconds", 1))))
        return f"waited {action.get('seconds', 1)}s"
    return "noop"


# --------------------------------------------------------------------------- #
#  Default capture (downscaled + JPEG for low RAM) + vision (google.genai)
# --------------------------------------------------------------------------- #
def _default_capture(max_w: int = 1280, quality: int = 70):
    """Return (jpeg_bytes, mime, (img_w, img_h), (screen_w, screen_h)).

    The image is downscaled to <=max_w wide and JPEG-compressed so the vision
    payload stays small on low-RAM machines; the real screen size is returned so
    coordinates can be rescaled back before clicking.
    """
    import io
    import mss
    import mss.tools
    import pyautogui
    import PIL.Image

    with mss.mss() as sct:
        shot = sct.grab(sct.monitors[0])
        png = mss.tools.to_png(shot.rgb, shot.size)

    img = PIL.Image.open(io.BytesIO(png)).convert("RGB")
    if img.width > max_w:
        img = img.resize((max_w, max(1, int(img.height * max_w / img.width))), PIL.Image.BILINEAR)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality, optimize=True)
    return buf.getvalue(), "image/jpeg", (img.width, img.height), tuple(pyautogui.size())


_VISION_SYSTEM = (
    "You are JARVIS's computer-use engine driving a {w}x{h} {os} desktop to finish a task. "
    "Each turn you get a fresh screenshot and the actions already taken. "
    "Reply with exactly ONE next action as a JSON object (no prose, no markdown). "
    "Use absolute pixel coordinates inside {w}x{h}. Prefer keyboard shortcuts and precise clicks; "
    "wait for apps to load. When the task is fully and verifiably done, use action \"done\" with a "
    "short \"summary\". If it is impossible or blocked, use action \"fail\" with a \"reason\".\n"
    "Schema: {\"action\":\"click|double_click|right_click|type|hotkey|press|scroll|move|drag|wait|done|fail\","
    "\"x\":int,\"y\":int,\"x2\":int,\"y2\":int,\"button\":\"left|right\",\"text\":\"...\","
    "\"keys\":[\"ctrl\",\"s\"],\"key\":\"enter\",\"direction\":\"up|down\",\"amount\":int,"
    "\"seconds\":number,\"summary\":\"...\",\"reason\":\"...\"}"
)


def _default_vision(img_bytes, mime, task, history, img_size, stuck_hint=""):
    from google import genai
    from google.genai import types

    w, h = img_size
    client = genai.Client(api_key=_get_api_key(), http_options={"api_version": "v1beta"})
    hist = "\n".join(history[-14:]) or "(none yet)"
    prompt = f"TASK: {task}\n\nACTIONS SO FAR:\n{hist}{stuck_hint}\n\nReturn the single next action as JSON."
    resp = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            types.Part.from_bytes(data=img_bytes, mime_type=mime),
            types.Part.from_text(text=prompt),
        ],
        config=types.GenerateContentConfig(
            system_instruction=_VISION_SYSTEM.format(w=w, h=h, os=platform.system()),
            temperature=0.2,
        ),
    )
    return resp.text


# --------------------------------------------------------------------------- #
#  The closed loop: see -> decide -> act -> verify -> adapt
# --------------------------------------------------------------------------- #
def _run_loop(task, max_steps=30, speak=None,
              vision_fn=None, exec_fn=None, capture_fn=None) -> str:
    vision_fn  = vision_fn  or _default_vision
    exec_fn    = exec_fn    or _default_exec
    capture_fn = capture_fn or _default_capture

    history: list[str] = []
    last_hash = None
    stuck = 0
    parse_errors = 0

    if speak:
        speak(f"Taking control to {task}, sir.")

    for i in range(1, max_steps + 1):
        try:
            img_bytes, mime, img_size, screen_size = capture_fn()
        except Exception as e:
            return f"FAILED: could not capture the screen ({e})."

        h = hashlib.md5(img_bytes).hexdigest()
        stuck = stuck + 1 if h == last_hash else 0
        last_hash = h

        stuck_hint = ""
        if stuck >= 2:
            stuck_hint = ("\n\nWARNING: the screen has not changed after recent actions. "
                          "Change approach — different coordinates, a keyboard shortcut, scroll, or wait.")

        try:
            raw = vision_fn(img_bytes, mime, task, history, img_size, stuck_hint)
            action = _parse_action(raw)
            action = _scale_coords(action, img_size, screen_size)
            parse_errors = 0
        except Exception as e:
            parse_errors += 1
            history.append(f"[step {i}] parse error: {e}")
            if parse_errors >= 3:
                return f"FAILED: the vision model returned unusable actions repeatedly on: {task}"
            continue

        a = action["action"]
        shown = {k: v for k, v in action.items() if k not in ("action", "summary", "reason")}
        history.append(f"[step {i}] {a} {json.dumps(shown)}")
        print(f"[ComputerUse] step {i}: {a} {shown}")

        if a == "done":
            summary = action.get("summary") or f"Completed: {task}"
            if speak:
                speak(summary)
            return summary
        if a == "fail":
            reason = action.get("reason") or "the task could not be completed"
            if speak:
                speak(f"I hit a wall, sir. {reason}")
            return f"FAILED: {reason}"

        try:
            res = exec_fn(action)
            if res:
                history.append(f"    -> {str(res)[:120]}")
        except Exception as e:
            history.append(f"    -> exec error: {e}")

        if stuck >= 5:
            return f"FAILED: no visible progress after several attempts on: {task}"

        time.sleep(0.12)

    return f"STOPPED: reached the {max_steps}-step limit on: {task}"


# --------------------------------------------------------------------------- #
#  Blender skill — the PEAK path for scriptable apps
# --------------------------------------------------------------------------- #
def _find_blender() -> str | None:
    exe = shutil.which("blender")
    if exe:
        return exe
    system = platform.system()
    cands: list[str] = []
    if system == "Windows":
        cands += glob.glob(r"C:\Program Files\Blender Foundation\Blender*\blender.exe")
        cands += glob.glob(r"C:\Program Files (x86)\Blender Foundation\Blender*\blender.exe")
    elif system == "Darwin":
        cands += ["/Applications/Blender.app/Contents/MacOS/Blender"]
    else:
        cands += ["/usr/bin/blender", "/usr/local/bin/blender", "/snap/bin/blender",
                  "/var/lib/flatpak/exports/bin/org.blender.Blender"]
    for c in cands:
        if os.path.exists(c):
            return c
    return None


_BPY_SYSTEM = (
    "You are an expert Blender artist who writes the bpy (Blender Python) API. "
    "Given a description, return ONE complete, runnable bpy script that builds the "
    "object with real geometry, a Principled BSDF material, a camera and a light, "
    "frames the object, and renders a PNG to the user's Desktop as 'jarvis_render.png'. "
    "Clear the default scene first. Use metric units. Make it detailed and clean. "
    "Return ONLY Python code — no markdown, no backticks, no explanation."
)


def _default_gen_bpy(description: str) -> str:
    from google import genai
    from google.genai import types
    client = genai.Client(api_key=_get_api_key(), http_options={"api_version": "v1beta"})
    resp = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=f"Build this in Blender: {description}",
        config=types.GenerateContentConfig(system_instruction=_BPY_SYSTEM, temperature=0.4),
    )
    code = resp.text.strip()
    code = re.sub(r"```(?:python)?", "", code).strip().rstrip("`").strip()
    return code


def _default_run_blender(exe: str, script_path: str, open_gui: bool = True) -> str:
    import subprocess
    args = [exe]
    if not open_gui:
        args.append("--background")
    args += ["--python", script_path]
    try:
        subprocess.Popen(args)  # don't block on the GUI; headless would block
        return ("Blender launched" + (" in the background" if not open_gui else "")
                + " and is building your model. A render will appear on your Desktop.")
    except Exception as e:
        raise RuntimeError(f"Could not launch Blender: {e}")


def blender_create(description: str, open_gui: bool = True, speak=None,
                   gen_fn: Callable | None = None, run_fn: Callable | None = None) -> str:
    gen_fn = gen_fn or _default_gen_bpy
    run_fn = run_fn or _default_run_blender

    exe = _find_blender()
    if not exe:
        return ("I couldn't find Blender on this system, sir. Install it from "
                "blender.org and make sure 'blender' is on your PATH.")
    if not description:
        return "Tell me what to build in Blender, sir."

    if speak:
        speak(f"Firing up Blender to build {description}, sir.")

    script = gen_fn(description)
    fd, path = tempfile.mkstemp(suffix="_jarvis_blender.py")
    os.close(fd)
    with open(path, "w", encoding="utf-8") as f:
        f.write(script)
    try:
        return run_fn(exe, path, open_gui=open_gui)
    finally:
        try:
            os.unlink(path)
        except Exception:
            pass


# --------------------------------------------------------------------------- #
#  Tool entry points (called by the executor)
# --------------------------------------------------------------------------- #
def _get_api_key() -> str:
    from pathlib import Path
    base = Path(__file__).resolve().parent.parent
    cfg = base / "config" / "api_keys.json"
    with open(cfg, "r", encoding="utf-8") as f:
        return json.load(f)["gemini_api_key"]


def computer_use(parameters: dict, player=None, speak=None) -> str:
    task = (parameters.get("task") or parameters.get("description")
            or parameters.get("goal") or "").strip()
    if not task:
        return "No task provided for computer_use, sir."
    try:
        max_steps = int(parameters.get("max_steps", 30))
    except (ValueError, TypeError):
        max_steps = 30
    return _run_loop(task, max_steps=max_steps, speak=speak)


def blender(parameters: dict, player=None, speak=None) -> str:
    description = (parameters.get("description") or parameters.get("task") or "").strip()
    open_gui = parameters.get("open_gui", True)
    return blender_create(description, open_gui=bool(open_gui), speak=speak)
