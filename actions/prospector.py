# prospector.py — find local-business leads for the outreach engine.
#
# Uses the FREE, keyless OpenStreetMap stack:
#   • Nominatim  — turns "cafes in Kochi" into coordinates.
#   • Overpass   — returns every matching business in that area, WITH the tags
#                  they published (name, website, email…).
#
# The gold: businesses that have NO `website` tag are exactly the ones who need
# you — that becomes the personalisation hook ("I noticed you don't have a site
# yet…"). No API key, no scraping-tofu, works on Linux.
#
# All network calls are stdlib urllib (no extra deps) and every failure degrades
# to a clear message instead of a crash.
from __future__ import annotations

import json
import math
import urllib.parse
import urllib.request

_UA = {"User-Agent": "JARVIS-outreach/1.0 (personal assistant; contact: you)"}

# niche -> (osm key, osm value). value=None means "any value of that key".
_NICHE_TAGS = {
    "cafe": ("amenity", "cafe"), "cafes": ("amenity", "cafe"), "coffee": ("amenity", "cafe"),
    "restaurant": ("amenity", "restaurant"), "restaurants": ("amenity", "restaurant"),
    "gym": ("leisure", "fitness_centre"), "gyms": ("leisure", "fitness_centre"),
    "salon": ("shop", "hairdresser"), "salons": ("shop", "hairdresser"),
    "barber": ("shop", "hairdresser"), "barbers": ("shop", "hairdresser"),
    "bakery": ("shop", "bakery"), "bakeries": ("shop", "bakery"),
    "hotel": ("tourism", "hotel"), "hotels": ("tourism", "hotel"),
    "clinic": ("amenity", "clinic"), "dentist": ("amenity", "dentist"),
    "pharmacy": ("amenity", "pharmacy"), "pharmacies": ("amenity", "pharmacy"),
    "mechanic": ("shop", "car_repair"), "car repair": ("shop", "car_repair"),
    "garage": ("shop", "car_repair"),
    "florist": ("shop", "florist"), "flowers": ("shop", "florist"),
    "grocery": ("shop", "convenience"), "supermarket": ("shop", "supermarket"),
    "shop": ("shop", None), "store": ("shop", None), "stores": ("shop", None),
    "business": ("office", None), "office": ("office", None),
}


def _resolve_niche(niche: str):
    n = (niche or "").strip().lower()
    if n in _NICHE_TAGS:
        return _NICHE_TAGS[n]
    for k, v in _NICHE_TAGS.items():
        if k in n or n in k:
            return v
    return None


def _get_json(url: str, timeout: int = 30, data: bytes | None = None):
    req = urllib.request.Request(url, headers=_UA, data=data)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def _geocode(area: str):
    url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + urllib.parse.quote(area)
    data = _get_json(url, timeout=30)
    if not data:
        return None
    d = data[0]
    return float(d["lat"]), float(d["lon"]), d.get("display_name", area)


def _bbox(lat: float, lon: float, km: float):
    dlat = km / 110.574
    dlon = km / (111.320 * max(math.cos(math.radians(lat)), 0.01))
    return (lat - dlat, lon - dlon, lat + dlat, lon + dlon)  # s, w, n, e


def _overpass(bbox, key: str, value, limit: int):
    s, w, n, e = bbox
    if value:
        sel = f'node["{key}"="{value}"]({s},{w},{n},{e});'
    else:
        sel = f'node["{key}"]({s},{w},{n},{e});'
    query = f"[out:json][timeout:25];{sel}out {limit};"
    url = "https://overpass-api.de/api/interpreter"
    return _get_json(url, timeout=45, data=("data=" + urllib.parse.quote(query)).encode())


def _to_prospects(elements: list[dict], niche: str, area: str) -> tuple[list[dict], int]:
    out, with_site = [], 0
    seen = set()
    for el in elements:
        tags = el.get("tags", {}) or {}
        name = (tags.get("name") or tags.get("name:en") or "").strip()
        if not name:
            continue
        key = name.lower()
        if key in seen:
            continue
        seen.add(key)
        website = (tags.get("website") or tags.get("contact:website")
                   or tags.get("url") or "").strip()
        email = (tags.get("email") or tags.get("contact:email") or "").strip()
        note = f"{niche} in {area}"
        if website:
            with_site += 1
            note += f" — has a site ({website})"
        else:
            note += " — NO website found"
        out.append({
            "business": name,
            "email": email,
            "handle": "",
            "channel": "email" if email else "",
            "notes": note,
            "_has_website": bool(website),
        })
    return out, with_site


def prospector(params: dict, player=None, speak=None) -> str:
    niche = (params.get("niche") or params.get("query") or "").strip()
    area  = (params.get("area") or params.get("location") or "").strip()
    if not niche or not area:
        return ("Tell me the niche and the area, e.g. "
                "\"find cafes in Kochi\" → niche=cafes, area=Kochi.")

    tag = _resolve_niche(niche)
    if not tag:
        sample = ", ".join(sorted({k for k in _NICHE_TAGS if not k.endswith("s")})[:12])
        return (f"I don't have a map category for '{niche}' yet. Try one of: {sample}…")

    radius_km = float(params.get("radius_km", 5) or 5)
    limit     = int(params.get("limit", 50) or 50)
    only_no_site = str(params.get("only_no_website", "true")).lower() in ("1", "true", "yes")

    def log(m):
        if player and hasattr(player, "write_log"):
            player.write_log(f"[Prospector] {m}")

    try:
        log(f"Locating {area}…")
        geo = _geocode(area)
        if not geo:
            return f"I couldn't find '{area}' on the map. Try a clearer place name."
        lat, lon, pretty = geo
        log(f"Searching {niche} near {pretty[:48]}…")
        data = _overpass(_bbox(lat, lon, radius_km), tag[0], tag[1], limit)
        elements = data.get("elements", [])
        if not elements:
            return f"No {niche} found within {radius_km:.0f} km of {pretty[:48]}."

        prospects, with_site = _to_prospects(elements, niche, pretty.split(",")[0])
        if only_no_site:
            prospects = [p for p in prospects if not p["_has_website"]]

        if not prospects:
            return (f"Found {len(elements)} {niche} near {pretty[:40]}, but they all "
                    f"already have websites. Widen the radius or try another area.")

        # strip the private flag before storing, then load into the outreach engine
        clean = [{k: v for k, v in p.items() if not k.startswith("_")} for p in prospects]
        from actions.outreach import add_prospects
        added, dupes = add_prospects(clean)
        skipped_site = with_site if only_no_site else 0
        return (f"📍 Found {len(elements)} {niche} near {pretty.split(',')[0]}: "
                f"{len(prospects)} without a website"
                + (f" ({skipped_site} skipped — already have sites)" if skipped_site else "")
                + f". Added {added} to your outreach list ({dupes} already there). "
                f"Say \"outreach draft\" to start.")

    except urllib.error.URLError as e:
        return f"Network trouble reaching the map service ({e}). Check your connection and retry."
    except Exception as e:  # noqa: BLE001
        return f"Prospecting failed: {e}"
