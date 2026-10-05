# JARVIS — Architecture Map

A modular, low-RAM personal AI assistant. Every capability is a small module with a
single entry function, so the LLM (Gemini) can call it as a *tool*. Heavy libraries
are imported **lazily** (inside functions) so the app boots fast on small machines.

```
main.py            The brain (JarvisLive) + entry point. Owns the Gemini Live voice
                   session, the tool list (TOOL_DECLARATIONS) and the dispatch
                   (_execute_tool: one if/elif chain that calls each action).
                   Also owns the console + text-command loop and main().

ui.py              PyQt6 HUD. JarvisUI facade (what main.py talks to) over MainWindow.
                   HudCanvas = the voice-reactive "face" drawn in software (no GPU).
                   System-tray icon + menu; closing hides to tray. Video-on-HUD
                   surface (QGraphicsVideoItem) that shares the centre with the face.

core/
  prompt.txt       The system prompt / persona.
  gemini.py        THE MODEL LADDER. Every one-shot Gemini call goes through ask():
                   a measured order of models, a per-call deadline, and cooldowns
                   (429→5m, 503/504→30m, 404→6h) so a sick model is skipped, not
                   paid for twice. Tiers: fast / smart / search.

config/
  settings.py      OS detection (get_os/is_windows/is_linux/...), persona, seriousness.
  api_keys.json    Gemini + OpenRouter keys (GIT-IGNORED, never committed).

actions/           One file = one capability. Each exposes an async/sync entry the
                   tool-caller invokes, usually `run(params, player=ui, speak=...)`.
    open_app.py        launch apps / files / URLs (fuzzy matching).
    cmd_control.py     safe shell: allow-list + regex deny-list (blocks rm -rf, mkfs…).
    computer_control.py volume/brightness/wifi/shortcuts/power (Linux + Windows).
    computer_settings.py OS settings changes.
    screen_processor.py screenshot + "what's on screen" + real-time camera
                        (start_camera_stream / camera_look / stop_camera_stream).
    video_player.py    "video where the face is": play a file/URL/YouTube/search in
                        the HUD (yt-dlp resolve), always starts muted.
    gods_eye.py        launch the open-source God's Eye View 3D globe (clone + serve
                        + open browser). Needs Node.js on first run.
    youtube_video.py   YouTube search / play / summarize / trending.
    web_search.py      live web search (DDG) with YouTube-fallback Gemini answer.
    research.py        deep_research: multi-pass search → Markdown report.
    flight_finder.py   flight lookup.
    code_helper.py     write/edit/explain/run Python, build, screen_debug, optimize.
    dev_agent.py       developer-oriented agent helpers.
    file_controller.py move/copy/rename/delete/organize files.
    file_processor.py  read/summarize/answer about local files.
    desktop.py         desktop / taskbar / window operations + AI desktop tasks.
    browser_control.py open/navigate the browser.
    send_message.py    WhatsApp / Telegram messaging.
    outreach.py        LEGAL business-outreach assistant: prospect store, LLM-written
                       personalised pitches, human-in-the-loop send with a daily cap +
                       delays (email first, then app DMs). Data in ~/.jarvis/outreach.
    prospector.py      finds local-business LEADS via free OpenStreetMap
                       (Nominatim + Overpass); flags businesses with NO website and
                       loads them into the outreach list.
    reminder.py        reminders + Pomodoro timer (persisted).
    computer_use.py    AI-driven mouse/keyboard control (screen coords from AI).
    weather_report.py  live weather.
    game_updater.py    rename game EXEs + fix metadata (Windows-focused).

agent/             The autonomous multi-step engine for "do a whole task" requests.
    planner.py        goal → ordered plan (steps with tool/params/depends_on).
    executor.py       runs steps, honours depends_on + {{step_n.result}} refs, calls
                      error_handler on failure. TOOL_DECLARATIONS / TOOL_MAP / execute.
    task_queue.py     priority queue + background worker; submit/cancel/get_status.
    error_handler.py  on step failure: retry / skip / replan / abort (LLM-decided).

memory/            memory_manager: session memory, reminders, usage logging (JSON).

or_client.py       OpenRouter REST client (secondary model provider).

scripts/
    install_autostart.sh   Turn on always-on (login autostart + tray launcher + menu
                           entry + optional systemd --user service). `--uninstall` reverses.
    setup-linux.sh         One-time system + Python dependency installer (Mint).

requirements.txt  Single cross-platform dep list (Windows-only pkgs gated by markers).
face.png          The HUD/tray face asset.
```

## Data flow (one turn)
1. You type/speak → `ui.py` / `main.py`.
2. `JarvisLive` builds context + tool list → Gemini Live.
3. Gemini returns **either** a chat reply **or** a tool call.
4. Tool call → `main.py:_execute_tool` → the matching `actions/*` entry (with `player=ui`).
5. One-shot side calls (search classify, code, image reading…) go through
   `core/gemini.py`'s ladder instead of a bare model name.
6. Action result → back to Gemini → spoken/written answer. Video/globe open on the UI.

## Design rules
- **Lazy imports** everywhere heavy (mss, sounddevice, numpy, PIL, yt_dlp,
  QtMultimedia, cv2, pyautogui…) so boot stays fast and a missing optional dep
  degrades gracefully instead of crashing.
- **One tool per turn** keeps the model cheap and the RAM low.
- **Safety first**: `cmd_control` allow/deny + regex, `pc_automate`/`computer_use`
  refuse self-clicks, generated code is validated before `exec`.
- Everything persists under `config/` and `memory/`; API keys are never committed.
