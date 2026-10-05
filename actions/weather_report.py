# actions/weather_report.py
"""
Weather report action.

Fetches real current conditions from wttr.in (free, no API key) and returns a
spoken-friendly summary. Falls back to opening a Google weather search in the
browser if the live fetch fails.
"""

import webbrowser
from urllib.parse import quote_plus, quote


def _fetch_weather(city: str) -> str | None:
    """Return a short conditions string from wttr.in, or None on failure."""
    try:
        import urllib.request

        # %l location, %C condition, %t temp, %f feels-like, %w wind, %H humidity, %p precipitation
        fmt = "%l: %C, %t (feels %f), wind %w, humidity %H"
        url = f"https://wttr.in/{quote(city)}?format={quote(fmt)}"
        req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0", "Accept": "text/plain"})
        with urllib.request.urlopen(req, timeout=8) as resp:
            line = resp.read().decode("utf-8", "ignore").strip()
        # wttr.in returns "Unknown location" style errors as plain text
        if line and "unknown location" not in line.lower() and ":" in line:
            return " ".join(line.split())
    except Exception as e:
        print(f"[Weather] live fetch failed: {e}")
    return None


def weather_action(
    parameters: dict,
    player=None,
    session_memory=None
):
    """Weather report — returns a summary string (the Live API voices it)."""

    city = parameters.get("city")
    if not city or not isinstance(city, str):
        msg = "Sir, the city is missing for the weather report."
        _speak_and_log(msg, player)
        return msg

    city = city.strip()
    if not city:
        msg = "Sir, the city is missing for the weather report."
        _speak_and_log(msg, player)
        return msg

    time_ctx = parameters.get("time")
    time_ctx = time_ctx.strip() if isinstance(time_ctx, str) and time_ctx.strip() else "right now"

    # 1) Real conditions (fast, free, no key).
    line = _fetch_weather(city)
    if line:
        msg = f"Here's the weather for {city} {time_ctx}, sir. {line}."
        _speak_and_log(msg, player)
        if session_memory:
            try:
                session_memory.set_last_search(query=f"weather {city}", response=msg)
            except Exception:
                pass
        return msg

    # 2) Fallback: open a Google weather search in the browser.
    encoded_query = quote_plus(f"weather in {city} {time_ctx}")
    url = f"https://www.google.com/search?q={encoded_query}"
    try:
        webbrowser.open(url)
        msg = f"I couldn't fetch live conditions for {city}, sir — I've opened the weather in your browser instead."
    except Exception:
        msg = f"Sir, I couldn't retrieve the weather for {city}."
    _speak_and_log(msg, player)
    return msg


def _speak_and_log(message: str, player=None):
    if player:
        try:
            player.write_log(f"JARVIS: {message}")
        except Exception:
            pass
