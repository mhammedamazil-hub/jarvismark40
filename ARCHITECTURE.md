# JARVIS — Architecture Map

A modular, low-RAM personal AI assistant. Every capability is a small module with a
single entry function, so the LLM (Gemini) can call it as a *tool*. Heavy libraries
are imported **lazily** (inside functions) so the app boots fast on small machines.

```
main.py            Entry point. JarvisLive brain + JarvisUI facade. Starts everything,
                   owns the console + text-command loop.

ui.py              PyQt6 window (JarvisUI). Iron-Man style HUD, chat log, camera/mic
                   states, and the **system-tray** icon with Show/Hide, Start/Stop
                   camera, mute, quit. Closing the window hides to tray.

core/
  brain.py         Gemini "thinking" loop. Builds the system prompt, streams the reply,
                   and decides which TOOL to call (one tool per turn). `attach_player`
                   injects the live audio player so speak/narrate reuse it.
  config.py        Loads config/api_keys.json (git-ignored) with env-var fallbacks.

actions/           One file = one capability. Each exposes an async
                   `run(params, player=None, speak=None, ...)` the tool-caller invokes.
    automation.py    app/file launching (fuzzy matching, fuzzy→spelling→AI→keyword).
    cmd_control.py   safe shell: allow/deny list + regex denylist (blocks rm -rf, mkfs…).
    screen_processor.py  screenshot + "what's on my screen" + **real-time camera**
                     (`start_camera`/`camera_look`/`stop_camera`) via a background
                     `_CameraStream` capture thread + short-lived Gemini Live sessions.
    code_helper.py   write/edit/explain/run Python, build, screen_debug, optimize.
    file_processor.py organize/move/rename files by type; screenshots & audio helpers.
    video_search.py  YouTube search + in-app player (yt-dlp stream → mpv).
    web_search.py    live web search (DuckDuckGo) with YouTube-fallback Gemini answer.
    game_updater.py  rename game EXEs + fix metadata (Windows-focused).
    reminder.py      reminders + Pomodoro timer, persisted to JSON.
    pc_automate.py   mouse/keyboard control (screen coords from AI).

agent/             The autonomous multi-step engine for "do a whole task" requests.
    planner.py        goal → ordered plan (steps with tool/params/depends_on).
    executor.py       runs steps, honours `depends_on` + `{{step_n.result}}` refs,
                      calls error_handler on failure. Exposes TOOL_DECLARATIONS,
                      TOOL_MAP, _run_single_tool, _call_tool, execute.
    task_queue.py     priority queue + background worker; submit/cancel/get_status.
    error_handler.py  on step failure: retry / skip / replan / abort (LLM-decided).

memory/            Session memory, reminders, usage logging (persistent JSON).

desktop/           Tkinter mini-assistant window (legacy secondary UI).

scripts/
    install_autostart.sh   Turn on always-on (login autostart + tray launcher + menu
                           entry + optional systemd --user service). `--uninstall` reverses.
    setup-linux.sh         One-time system + Python dependency installer.

requirements.txt  Single cross-platform dep list (Windows-only pkgs gated by markers).
```

## Data flow (one turn)
1. You type/speak → `ui.py` / `main.py`.
2. `core/brain.py` builds context + tool list → Gemini.
3. Gemini returns **either** a chat reply **or** a tool call.
4. Tool call → `agent/executor.py:_call_tool` → the matching `actions/*.run()`.
5. Action result → back to Gemini → spoken/written answer.

## Design rules
- **Lazy imports** everywhere heavy (mss, sounddevice, numpy, PIL, yt_dlp, pyautogui…).
- **One tool per turn** keeps the model cheap and the RAM low.
- **Safety first**: `cmd_control` allow/deny + regex, `pc_automate` refuses self-clicks,
  generated code is validated before `exec`.
- Everything persists under `config/` and `memory/`; API keys are never committed.
