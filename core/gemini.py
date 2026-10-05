"""
One place where JARVIS makes its one-shot Gemini calls.

WHY THIS EXISTS (ported from Mark LV's core/gemini.py)
    Before, every action built its own `genai.Client(...)` with the model name
    written inline, and not one of them set a timeout. That is three real faults:

      NO TIMEOUT.   The SDK waits forever by default. One unwell alias turned a
                    call into an unbounded hang.
      NO FALLBACK.  One hardcoded model meant that when it was unwell the feature
                    was simply gone.
      NO ONE PLACE. A new model release meant editing a dozen files.

    This module fixes all three: a measured ladder of models, a per-call
    deadline, and a cooldown so a model that just failed is not paid for again on
    the very next call.

THE LADDER, MEASURED (Mark LV, same key, same prompt)
      gemini-3.5-flash-lite      0.56s      gemini-2.5-flash        0.67s
      gemini-3.1-flash-lite      0.60s      gemini-2.5-flash-lite   0.74s
      gemini-flash-lite-latest   0.60s      gemini-3.5-flash        1.13s
      gemini-3-flash-preview     504, after 14.7s of waiting
      gemini-3.6-flash           504, after 12.0s
      gemini-flash-latest        503 UNAVAILABLE
    The three that fail sit at the BOTTOM, not deleted — a model that is down
    today is real quota tomorrow, and the cooldown sets a sick one aside after
    one attempt instead of paying for it on every call.

COOLDOWNS — how long a failure is believed
      429 quota exhausted .......... 5 min   (quotas refill)
      503/504/DEADLINE_EXCEEDED .... 30 min  (an outage outlasts a retry)
      404 not found / no access .... 6 hours (your key does not have it)

NOTE ON THE LIVE MODEL
    The conversational Live session (main.py) is deliberately NOT routed through
    this ladder — Live models are not drop-in replacements for one another. This
    module is for the *side* one-shot calls (search classification, code, image
    reading, deciding a shell command, ...) that used to hang forever.
"""
from __future__ import annotations

import json
import sys
import threading
import time
from pathlib import Path

_BASE = Path(sys.executable).parent if getattr(sys, "frozen", False) \
    else Path(__file__).resolve().parent.parent
_KEY_FILE = _BASE / "config" / "api_keys.json"

# Tiers. Pick the cheapest that can do the job.
FAST   = "fast"     # short classification, extraction, one-line decisions
SMART  = "smart"    # reasoning, generation, long documents, images
SEARCH = "search"   # grounded search — needs grounding metadata, REST only

# Every model this key can reach, in the order to try them. Change a model HERE
# and the whole app follows.
_LADDERS = {
    FAST: (
        "gemini-2.5-flash-lite", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-3.5-flash",
        "gemini-3.6-flash", "gemini-3-flash-preview",
    ),
    SMART: (
        "gemini-2.5-flash", "gemini-3.5-flash", "gemini-2.5-flash-lite",
        "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
        "gemini-3.6-flash", "gemini-3-flash-preview", "gemini-flash-latest",
    ),
    SEARCH: (
        "gemini-2.5-flash", "gemini-3.5-flash", "gemini-2.5-flash-lite",
        "gemini-flash-latest",
    ),
}

# Cooldown (seconds) keyed by the failure we recognised in the exception text.
_COOLDOWN_BY_MARKER = (
    ("RESOURCE_EXHAUSTED", 300),      # 429 quota
    ("429", 300),
    ("DEADLINE_EXCEEDED", 1800),      # 504
    ("504", 1800),
    ("UNAVAILABLE", 1800),            # 503
    ("503", 1800),
    ("SERVICE_UNAVAILABLE", 1800),
    ("NOT_FOUND", 21600),             # 404 — key doesn't have it
    ("PERMISSION_DENIED", 21600),
    ("404", 21600),
)

DEFAULT_TIMEOUT = 12.0   # every call carries a deadline; nothing waits forever

_lock = threading.Lock()
_resting: dict[str, float] = {}   # model -> monotonic-ish wall time it wakes at


def _api_key() -> str:
    try:
        with open(_KEY_FILE, "r", encoding="utf-8") as f:
            return json.load(f).get("gemini_api_key", "")
    except Exception:
        return ""


def _resting_until(model: str) -> float:
    with _lock:
        return _resting.get(model, 0.0)


def _put_to_rest(model: str, seconds: float) -> None:
    with _lock:
        _resting[model] = time.time() + seconds


def _cooldown_for(exc: Exception):
    """Return cooldown seconds for a recognised failure, else None (don't rest)."""
    text = str(exc).upper()
    for marker, secs in _COOLDOWN_BY_MARKER:
        if marker in text:
            return secs
    return None


def _strip_fences(text: str) -> str:
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1]
        if text.endswith("```"):
            text = text[: -3]
    return text.strip()


def ask(
    tier: str,
    prompt: str,
    *,
    system: str | None = None,
    json_mode: bool = False,
    temperature: float | None = None,
    timeout: float = DEFAULT_TIMEOUT,
    want_json: bool | None = None,
):
    """
    Make one one-shot Gemini call through the measured ladder.

    Returns the model's text (fences stripped). Raises RuntimeError if every rung
    is resting or failing. `want_json=True` additionally parses and returns a
    Python object; if parsing fails it falls back to returning the raw text.
    """
    if want_json is not None:
        json_mode = json_mode or want_json

    try:
        from google import genai
        from google.genai import types
    except Exception as e:  # pragma: no cover - env without the SDK
        raise RuntimeError(f"google-genai not available: {e}")

    key = _api_key()
    if not key:
        raise RuntimeError("No gemini_api_key in config/api_keys.json")

    client = genai.Client(api_key=key)

    cfg_kwargs = {"http_options": types.HttpOptions(timeout=int(timeout * 1000))}
    if system:
        cfg_kwargs["system_instruction"] = system
    if json_mode:
        cfg_kwargs["response_mime_type"] = "application/json"
    if temperature is not None:
        cfg_kwargs["temperature"] = temperature
    config = types.GenerateContentConfig(**cfg_kwargs)

    now = time.time()
    last_err = None
    # Two passes at most: the second only runs if the first found *every* rung
    # resting. A brief network blip can misclassify as 503 for all models at once;
    # without this, the feature would stay dead for the full 30 min even after the
    # network recovered in two seconds. Self-heal: clear cooldowns and try once more.
    for _pass in (0, 1):
        now = time.time()
        tried_any = False
        for model in _LADDERS.get(tier, _LADDERS[FAST]):
            if _resting_until(model) > now:
                continue  # a rung we already set aside — don't pay for it again
            tried_any = True
            try:
                resp = client.models.generate_content(model=model, contents=prompt, config=config)
                text = (getattr(resp, "text", None) or "").strip()
                if not text:
                    # Some responses carry text only via candidates/parts
                    try:
                        text = resp.candidates[0].content.parts[0].text.strip()
                    except Exception:
                        text = ""
                if not text:
                    raise RuntimeError("empty response")
                text = _strip_fences(text)
                if want_json:
                    try:
                        return json.loads(text)
                    except Exception:
                        return text
                return text
            except Exception as e:  # noqa: BLE001 - we classify then decide
                last_err = e
                secs = _cooldown_for(e)
                print(f"[GeminiLadder] {model} failed ({type(e).__name__}: {str(e)[:80]})"
                      + (f" — resting {secs}s" if secs else " — no cooldown"))
                if secs:
                    _put_to_rest(model, secs)
        if tried_any:
            break  # we made a real attempt; don't burn a second full pass
        reset_cooldowns()  # everything was resting — clear and self-heal once

    raise RuntimeError(f"All {tier} rungs failed. Last error: {last_err}")


def reset_cooldowns() -> None:
    with _lock:
        _resting.clear()


def status() -> dict:
    """For diagnostics/UI: which models are resting and for how long."""
    now = time.time()
    with _lock:
        return {m: round(until - now) for m, until in _resting.items() if until > now}
