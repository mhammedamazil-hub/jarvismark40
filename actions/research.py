"""
JARVIS deep-research engine.

When asked to research a topic, this goes into an autonomous loop:
  generate diverse search queries -> search -> collect findings + sources ->
  generate follow-up queries to fill gaps -> repeat -> synthesize a full
Markdown report with a sources list -> save to the Desktop.

LLM steps (query generation + synthesis) use the OpenRouter client (same free
path as the memory system); searching reuses the web_search action. All steps
are injectable so the loop logic is unit-testable without keys or network.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Callable


_URL_RE = re.compile(r"https?://[^\s\)\]\>\"']+")


def _extract_urls(text: str) -> list[str]:
    return _URL_RE.findall(text or "")


def _slug(text: str) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "_", (text or "").strip().lower()).strip("_")
    return (s or "research")[:60]


def _parse_str_list(raw: str) -> list[str]:
    raw = re.sub(r"```(?:json)?", "", (raw or "").strip()).strip().rstrip("`").strip()
    s, e = raw.find("["), raw.rfind("]")
    if s == -1 or e == -1:
        return []
    try:
        data = json.loads(raw[s:e + 1])
        return [str(x).strip() for x in data if str(x).strip()]
    except Exception:
        return []


# ---- default LLM/search/save steps (lazy imports) ----
def _gen_queries(topic: str, findings: list, round_no: int, n: int) -> list[str]:
    from or_client import client
    if round_no == 1 or not findings:
        prompt = (f"Generate {n} diverse, high-quality web search queries to research "
                  f"this topic thoroughly from different angles (basics, latest news, "
                  f"comparisons, expert opinions, data/statistics):\n\n{topic}\n\n"
                  f"Return ONLY a JSON array of {n} query strings.")
    else:
        brief = "\n".join(f"- {q}: {t[:180]}" for q, t in findings[-8:])
        prompt = (f"Topic: {topic}\n\nWhat I've found so far:\n{brief}\n\n"
                  f"Name {n} important follow-up questions or angles that are still "
                  f"MISSING. Return ONLY a JSON array of {n} search query strings.")
    raw = client.chat(prompt, system="Return ONLY a JSON array of strings.",
                      max_tokens=300, temperature=0.5)
    return _parse_str_list(raw)


def _search(query: str) -> str:
    from actions.web_search import web_search
    return (web_search(parameters={"query": query}, player=None) or "").strip()


def _synthesize(topic: str, findings: list, sources: list) -> str:
    from or_client import client
    body = "\n\n".join(f"### {q}\n{t}" for q, t in findings)
    src = "\n".join(f"- {u}" for u in sources[:40]) or "(no direct URLs captured)"
    prompt = (f"Write a comprehensive, well-structured Markdown research report on: {topic}\n\n"
              f"Synthesize the findings below (do not merely list them). Include: an "
              f"executive summary, clear thematic sections, key facts/numbers, and a "
              f"Sources section. Be thorough and accurate.\n\n"
              f"FINDINGS:\n{body[:14000]}\n\nSOURCES:\n{src}")
    return client.chat(prompt,
                       system="You are a meticulous research analyst. Output clean Markdown.",
                       max_tokens=4000, temperature=0.3)


def _save_report(topic: str, report: str) -> str:
    desktop = Path.home() / "Desktop"
    if not desktop.exists():
        desktop = Path.home()
    path = desktop / f"JARVIS_research_{_slug(topic)}.md"
    path.write_text(report, encoding="utf-8")
    return str(path)


# ---- the engine ----
def deep_research(topic: str, max_rounds: int = 4, queries_per_round: int = 4,
                  speak: Callable | None = None, progress: Callable | None = None,
                  gen_queries_fn: Callable | None = None, search_fn: Callable | None = None,
                  synth_fn: Callable | None = None, save_fn: Callable | None = None) -> str:
    gen_queries_fn = gen_queries_fn or _gen_queries
    search_fn      = search_fn      or _search
    synth_fn       = synth_fn       or _synthesize
    save_fn        = save_fn        or _save_report

    topic = (topic or "").strip()
    if not topic:
        return "Give me a topic to research, sir."

    if speak:
        speak(f"Going deep on {topic}, sir. I'll dig until there's nothing left.")

    seen: set[str] = set()
    findings: list[tuple[str, str]] = []
    sources: list[str] = []

    for r in range(1, max_rounds + 1):
        try:
            queries = gen_queries_fn(topic, findings, r, queries_per_round) or []
        except Exception:
            queries = []
        round_queries: list[str] = []
        local: set[str] = set()
        for q in (queries or []):
            key = q.strip().lower()
            if q and key not in seen and key not in local:
                local.add(key)
                round_queries.append(q)
            if len(round_queries) >= queries_per_round:
                break
        queries = round_queries
        if not queries:
            if r == 1:
                break
            continue
        for q in queries:
            seen.add(q.strip().lower())
            try:
                text = search_fn(q)
            except Exception:
                text = ""
            if text:
                findings.append((q, text))
                for u in _extract_urls(text):
                    if u not in sources:
                        sources.append(u)
        if progress:
            progress(f"Research pass {r}/{max_rounds}: {len(findings)} findings, {len(sources)} sources.")

    if not findings:
        return f"I couldn't gather anything on {topic}, sir."

    report = synth_fn(topic, findings, sources)
    path = save_fn(topic, report)
    summary = (f"Deep research complete on '{topic}', sir. {len(findings)} findings across "
               f"{max_rounds} passes, {len(sources)} sources. Full report saved to {path}.")
    if speak:
        speak(summary)
    return summary


def deep_research_tool(parameters: dict, player=None, speak=None, progress=None) -> str:
    topic = (parameters.get("topic") or parameters.get("query")
             or parameters.get("task") or parameters.get("goal") or "").strip()
    try:
        max_rounds = int(parameters.get("max_rounds", 4))
    except (ValueError, TypeError):
        max_rounds = 4
    return deep_research(topic, max_rounds=max_rounds, speak=speak, progress=progress)
