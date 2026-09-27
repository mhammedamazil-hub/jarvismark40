"""
JARVIS runtime settings + persona composition.

Persists lightweight user settings (currently: serious mode) to
config/settings.json (gitignored, per-machine) and composes the assistant
persona accordingly. Pure and unit-testable.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def _base() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


SETTINGS_PATH = _base() / "config" / "settings.json"
_DEFAULT = {"serious_mode": False}

SERIOUS_PERSONA = (
    "SERIOUS MODE ACTIVE. Drop all casual tone, filler, humor and friendly asides. "
    "Be terse, clinical and mission-focused. Address the user as 'sir' only. "
    "Lead with the answer or the action — no preamble. Precision and speed over warmth."
)

NORMAL_PERSONA = (
    "NORMAL MODE. You are JARVIS: calm, sharp, professional, with light wit when appropriate."
)


def load_settings() -> dict:
    try:
        if SETTINGS_PATH.exists():
            data = json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                merged = dict(_DEFAULT)
                merged.update(data)
                return merged
    except Exception:
        pass
    return dict(_DEFAULT)


def save_settings(data: dict) -> None:
    try:
        SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS_PATH.write_text(json.dumps(data, indent=2), encoding="utf-8")
    except Exception as e:
        print(f"[Settings] save failed: {e}")


def is_serious() -> bool:
    return bool(load_settings().get("serious_mode", False))


def set_serious(value: bool) -> bool:
    data = load_settings()
    data["serious_mode"] = bool(value)
    save_settings(data)
    return bool(value)


def compose_persona(base_prompt: str, serious: bool) -> str:
    """Append the mode directive to the base persona."""
    base = (base_prompt or "").rstrip()
    directive = SERIOUS_PERSONA if serious else NORMAL_PERSONA
    return (base + "\n\n" + directive) if base else directive
