import json
import platform
from pathlib import Path

_CONFIG_PATH = Path(__file__).parent / "api_keys.json"


def get_config() -> dict:
    try:
        with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _detect_os() -> str:
    system = platform.system().lower()
    if system.startswith("win"):
        return "windows"
    if system == "darwin":
        return "mac"
    return "linux"


def get_os() -> str:
    """Returns 'windows' | 'mac' | 'linux'.

    A valid 'os_system' in api_keys.json wins as an explicit override;
    otherwise we auto-detect from the running platform (so a missing or
    regenerated config can never silently force the wrong OS branch).
    """
    cfg = str(get_config().get("os_system", "")).strip().lower()
    if cfg in ("windows", "mac", "linux"):
        return cfg
    return _detect_os()


def is_windows() -> bool:
    return get_os() == "windows"


def is_mac() -> bool:
    return get_os() == "mac"


def is_linux() -> bool:
    return get_os() == "linux"
