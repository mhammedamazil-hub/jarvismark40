#web_search.py
import json
import sys
from pathlib import Path

def _get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


BASE_DIR        = _get_base_dir()
API_CONFIG_PATH = BASE_DIR / "config" / "api_keys.json"


def _get_api_key() -> str:
    with open(API_CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)["gemini_api_key"]


def _gemini_search(query: str) -> str:
    from google import genai

    client   = genai.Client(api_key=_get_api_key())
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=query,
        config={"tools": [{"google_search": {}}]},
    )

    text = ""
    for part in response.candidates[0].content.parts:
        if hasattr(part, "text") and part.text:
            text += part.text

    text = text.strip()
    if not text:
        raise ValueError("Gemini returned an empty response.")
    return text


def _ddg_search(query: str, max_results: int = 6) -> list[dict]:
    try:
        from ddgs import DDGS
    except ImportError:
        from duckduckgo_search import DDGS

    results = []
    with DDGS() as ddgs:
        for r in ddgs.text(query, max_results=max_results):
            results.append({
                "title":   r.get("title",  ""),
                "snippet": r.get("body",   ""),
                "url":     r.get("href",   ""),
            })
    return results


def _format_ddg(query: str, results: list[dict]) -> str:
    if not results:
        return f"No results found for: {query}"

    lines = [f"Search results for: {query}\n"]
    for i, r in enumerate(results, 1):
        if r.get("title"):   lines.append(f"{i}. {r['title']}")
        if r.get("snippet"): lines.append(f"   {r['snippet']}")
        if r.get("url"):     lines.append(f"   {r['url']}")
        lines.append("")
    return "\n".join(lines).strip()

def _compare(items: list[str], aspect: str) -> str:
    query = (
        f"Compare {', '.join(items)} in terms of {aspect}. "
        "Give specific facts and data."
    )
    try:
        return _gemini_search(query)
    except Exception as e:
        print(f"[WebSearch] ⚠️ Gemini compare failed: {e} — falling back to DDG")

    # DDG fallback: fetch results per item and merge
    all_results: dict[str, list] = {}
    for item in items:
        try:
            all_results[item] = _ddg_search(f"{item} {aspect}", max_results=3)
        except Exception:
            all_results[item] = []

    lines = [f"Comparison — {aspect.upper()}", "─" * 40]
    for item in items:
        lines.append(f"\n▸ {item}")
        for r in all_results.get(item, [])[:2]:
            if r.get("snippet"):
                lines.append(f"  • {r['snippet']}")
    return "\n".join(lines)

def _ai_summary(query: str, results: list[dict]) -> str:
    """Optional 2-3 sentence answer synthesized strictly from the search results."""
    try:
        from or_client import client
        ctx = "\n".join(
            f"- {r.get('title', '')}: {r.get('snippet', '')}"
            for r in results[:5] if r.get("snippet")
        )
        if not ctx:
            return ""
        return client.chat(
            f"Using ONLY the search results below, answer in 2-3 sentences: {query}\n\n{ctx}",
            system="Answer concisely using only the provided results. Do not invent facts.",
            max_tokens=300,
        )
    except Exception:
        return ""


def web_search(
    parameters:     dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    params = parameters or {}
    query  = params.get("query", "").strip()
    mode   = params.get("mode",  "search").lower().strip()
    items  = params.get("items", [])
    aspect = params.get("aspect", "general").strip() or "general"

    if not query and not items:
        return "Please provide a search query, sir."

    if items and mode != "compare":
        mode = "compare"

    if player:
        player.write_log(f"[Search] {query or ', '.join(items)}")

    print(f"[WebSearch] 🔍 Query: {query!r}  Mode: {mode}")

    # Compare mode → dedicated grounded comparison.
    if mode == "compare" and items:
        return _compare(items, aspect)

    # Normal search → prefer grounded results WITH sources.
    # 1) DuckDuckGo (free, real sources), optionally topped with a short AI answer.
    try:
        results = _ddg_search(query, max_results=6)
        if results:
            summary    = _ai_summary(query, results)
            formatted  = _format_ddg(query, results)
            print(f"[WebSearch] ✅ DDG: {len(results)} result(s).")
            return f"{summary}\n\n{formatted}" if summary else formatted
    except Exception as e:
        print(f"[WebSearch] ⚠️ DDG failed: {e}")

    # 2) Gemini grounded search (google_search tool) — real, sourced.
    try:
        return _gemini_search(query)
    except Exception as e:
        print(f"[WebSearch] ⚠️ Gemini search failed: {e}")

    # 3) Last resort: ungrounded LLM answer.
    try:
        from or_client import client
        return client.chat(
            query,
            system="You are a web search assistant. Answer factually and concisely. If unsure, say so.",
        )
    except Exception as e:
        print(f"[WebSearch] ❌ All backends failed: {e}")
        return f"Search failed, sir: {e}"