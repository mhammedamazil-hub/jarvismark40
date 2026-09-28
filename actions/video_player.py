# video_player.py — "video where the face is" (ported from Mark LV)
#
# Plays a video INSIDE the HUD, on the same surface the avatar uses, instead of
# throwing it at the browser. Accepts a local file, a direct media URL, a YouTube
# link, or just a description ("play the new Dune trailer") to search for.
#
# It ALWAYS starts muted — a soundtrack talking over the assistant is the one way
# this feature could make JARVIS worse. Sound is turned on by asking, or from the
# button in the video header.
#
# yt-dlp does the resolving; Qt (PyQt6.QtMultimedia) does the playing, on the UI
# side. If either is missing we say so and fall back to opening externally rather
# than failing silently.
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import quote_plus

try:
    from config import is_linux, is_mac, is_windows
except Exception:  # pragma: no cover
    def is_linux(): return sys.platform.startswith("linux")
    def is_mac(): return sys.platform == "darwin"
    def is_windows(): return sys.platform.startswith("win")


_MEDIA_EXT = (".mp4", ".webm", ".mkv", ".mov", ".m4v", ".avi", ".ogg", ".ogv")
_YT_RE = re.compile(r"(youtube\.com|youtu\.be)", re.I)


def _say(speak, text: str) -> None:
    if callable(speak):
        try:
            speak(text)
        except Exception:
            pass


def _log(player, text: str) -> None:
    if player is not None and hasattr(player, "write_log"):
        try:
            player.write_log(f"[Video] {text}")
        except Exception:
            pass


def _open_external(url_or_path: str) -> None:
    try:
        if is_mac():
            subprocess.Popen(["open", url_or_path])
        elif is_linux():
            subprocess.Popen(["xdg-open", url_or_path])
        else:
            subprocess.Popen(["cmd", "/c", "start", "", url_or_path], shell=False)
    except Exception as e:
        print(f"[Video] external open failed: {e}")


def _looks_like_media_url(s: str) -> bool:
    low = s.lower()
    return low.startswith(("http://", "https://")) and any(low.split("?")[0].endswith(e) for e in _MEDIA_EXT)


def _yt_dlp_available() -> bool:
    try:
        import yt_dlp  # noqa: F401
        return True
    except Exception:
        return shutil.which("yt-dlp") is not None


def _resolve_with_yt_dlp(url_or_query: str):
    """Return (playable_url, title) using yt-dlp, or (None, None) on failure.

    Prefers a single progressive stream (video+audio together) because the HUD
    player is one surface; falls back through mp4/any-best if a combined format
    is not offered.
    """
    try:
        import yt_dlp
    except Exception:
        return None, None

    # A bare description -> a YouTube search URL.
    target = url_or_query
    if not url_or_query.lower().startswith(("http://", "https://")):
        target = "https://www.youtube.com/results?search_query=" + quote_plus(url_or_query)
        # yt-dlp can't play a results page; ask it for the first entry instead.
        target = f"ytsearch1:{url_or_query}"

    fmt = "best[height<=720][ext=mp4][acodec!=none][vcodec!=none]/best[ext=mp4][acodec!=none]/18/best[ext=mp4]/best"
    opts = {
        "format": fmt,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "socket_timeout": 20,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(target, download=False)
            if info is None:
                return None, None
            if "entries" in info and info["entries"]:
                info = info["entries"][0]
            url = info.get("url")
            title = info.get("title") or "video"
            return url, title
    except Exception as e:
        print(f"[Video] yt-dlp resolve failed: {e}")
        return None, None


def play_video(parameters: dict, player=None, speak=None) -> str:
    """Play a video in the HUD. `source` (file/URL/YouTube) or `query` (search)."""
    source = (parameters.get("source") or "").strip()
    query  = (parameters.get("query") or "").strip()
    target = source or query

    if not target:
        return "Tell me what to play — a file, a link, or what to search for."

    # Answer BEFORE the seconds of resolving, so the user hears progress (Mark LV).
    _say(speak, "Bringing it up now, sir.")
    _log(player, f"Resolving: {target}")

    # 1) Local file
    p = Path(target).expanduser()
    if p.exists() and p.is_file():
        if hasattr(player, "play_video"):
            player.play_video(str(p.resolve()), title=p.name)
            return f"Playing {p.name} in the HUD (muted)."
        _open_external(str(p.resolve()))
        return f"Opening {p.name} (no in-HUD player available)."

    # 2) Direct media URL
    if _looks_like_media_url(target):
        if hasattr(player, "play_video"):
            player.play_video(target, title=target.rsplit("/", 1)[-1])
            return "Playing it in the HUD (muted)."
        _open_external(target)
        return "Opening the video (no in-HUD player available)."

    # 3) YouTube link or a search description -> resolve a stream URL
    if not _yt_dlp_available():
        msg = ("yt-dlp isn't installed, so I can't stream that in the HUD. "
               "Install it with: pip install yt-dlp")
        _log(player, msg)
        _say(speak, "I need yt dash d l p for in-app playback. Opening it in the browser instead.")
        _open_external(target)
        return msg

    url, title = _resolve_with_yt_dlp(target)
    if not url:
        _say(speak, "I couldn't find a playable stream for that.")
        return "Couldn't resolve a playable stream."

    if hasattr(player, "play_video"):
        player.play_video(url, title=title or "video")
        return f"Playing “{title}” in the HUD (muted). Say 'unmute' for sound."
    _open_external(url)
    return f"Opening “{title}” (no in-HUD player available)."


def stop_video(parameters: dict, player=None, speak=None) -> str:
    if player is not None and hasattr(player, "stop_video"):
        player.stop_video()
    return "Video stopped."
