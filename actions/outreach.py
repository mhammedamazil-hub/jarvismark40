# outreach.py — a LEGAL, human-in-the-loop business outreach assistant.
#
# WHY IT'S BUILT THIS WAY (read this)
#   Blasting thousands of automated Instagram DMs is against Instagram's Terms
#   (accounts get action-blocked/banned fast) and brushes against anti-spam law
#   (CAN-SPAM, GDPR/PECR, India's DPDP Act). It also barely works — blast DMs get
#   near-zero replies. So this engine optimises for what actually books clients:
#
#     • PERSONALISATION  — every message is written by the LLM for THAT business,
#                          which is the single biggest lever on reply rate.
#     • B2B EMAIL FIRST  — identified cold email with an opt-out is defensible and
#                          scalable; DMs are the risky last resort, not the default.
#     • HUMAN IN THE LOOP — JARVIS drafts + queues; YOU approve; it sends ONE at a
#                          time with a daily cap and a human-like delay.
#     • FOLLOW-UP        — tracks state so you nurture instead of spamming once.
#
# Data lives in ~/.jarvis/outreach (outside the repo, private, never committed).
from __future__ import annotations

import json
import smtplib
import ssl
import time
from datetime import datetime, date
from email.message import EmailMessage
from pathlib import Path

BASE        = Path.home() / ".jarvis" / "outreach"
PROSPECTS   = BASE / "prospects.json"
CONFIG_PATH = BASE / "config.json"
SENT_LOG    = BASE / "sent_log.json"

DEFAULT_CONFIG = {
    "business": {
        "name": "Your Studio",
        "services": ["ads", "posters", "websites"],
        "value_prop": ("I help local businesses stand out with scroll-stopping ads, "
                       "posters and websites that actually bring in customers."),
        "sender_name": "Your Name",
        "portfolio": "",
        "cta": "Want me to put together a quick mockup so you can see it before you commit to anything?",
    },
    "daily_cap": 20,          # max messages sent per day (stay under the radar)
    "delay_seconds": 45,      # pause between sends so it looks human
    "default_channel": "email",
    "require_confirm": True,  # drafts must be approved before sending
    "smtp": {                 # optional — for email sending
        "host": "", "port": 587, "user": "", "password": "", "from_addr": ""
    },
}


# --------------------------------------------------------------------------- io
def _ensure() -> None:
    BASE.mkdir(parents=True, exist_ok=True)


def _load_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def _save_json(path: Path, data) -> None:
    _ensure()
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def load_config() -> dict:
    cfg = _load_json(CONFIG_PATH, {})
    merged = json.loads(json.dumps(DEFAULT_CONFIG))
    for k, v in cfg.items():
        if isinstance(v, dict) and isinstance(merged.get(k), dict):
            merged[k].update(v)
        else:
            merged[k] = v
    return merged


def load_prospects() -> list[dict]:
    return _load_json(PROSPECTS, [])


def save_prospects(rows: list[dict]) -> None:
    _save_json(PROSPECTS, rows)


def _sent_today() -> int:
    log = _load_json(SENT_LOG, [])
    today = date.today().isoformat()
    # Only SUCCESSFUL sends count against the daily cap — a failed attempt (bad
    # SMTP, blocked number) must not burn the day's budget without sending anything.
    return sum(1 for e in log if e.get("day") == today and e.get("ok"))


def _record_send(prospect: dict, channel: str, ok: bool, note: str = "") -> None:
    log = _load_json(SENT_LOG, [])
    log.append({
        "day": date.today().isoformat(),
        "ts": datetime.now().isoformat(timespec="seconds"),
        "who": prospect.get("business") or prospect.get("handle") or prospect.get("email"),
        "channel": channel, "ok": ok, "note": note,
    })
    _save_json(SENT_LOG, log[-2000:])


# ----------------------------------------------------------------------- adding
def _norm(p: dict) -> dict:
    return {
        "business": (p.get("business") or p.get("name") or "").strip(),
        "contact":  (p.get("contact") or "").strip(),
        "handle":   (p.get("handle") or "").strip(),
        "email":    (p.get("email") or "").strip(),
        "channel":  (p.get("channel") or "").strip(),
        "notes":    (p.get("notes") or "").strip(),
        "status":   "pending",       # pending -> drafted -> contacted -> replied
        "draft":    "",
        "last_contacted": "",
        "followups": 0,
    }


def _key(p: dict) -> str:
    return (p.get("email") or p.get("handle") or p.get("contact") or p.get("business") or "").lower()


def add_prospects(new: list[dict]) -> tuple[int, int]:
    rows = load_prospects()
    have = {_key(r) for r in rows}
    added = dupes = 0
    for raw in new:
        p = _norm(raw)
        k = _key(p)
        if not k or k in have:
            dupes += 1
            continue
        rows.append(p)
        have.add(k)
        added += 1
    save_prospects(rows)
    return added, dupes


# --------------------------------------------------------------------- drafting
def _draft_prompt(prospect: dict, cfg: dict) -> str:
    b = cfg["business"]
    services = ", ".join(b.get("services", []))
    personal = prospect.get("notes") or "no specific detail known"
    channel = (prospect.get("channel") or cfg.get("default_channel") or "email")
    return f"""Write ONE short, warm, high-converting cold outreach message.

WHO YOU ARE (the sender): {b.get('sender_name')} from {b.get('name')}.
WHAT YOU OFFER: {services}.
VALUE PROPOSITION: {b.get('value_prop')}
PORTFOLIO: {b.get('portfolio') or 'n/a'}

WHO YOU'RE WRITING TO:
  Business: {prospect.get('business') or 'a local business'}
  Contact:  {prospect.get('contact') or 'the owner'}
  Something specific about them: {personal}
  Channel: {channel}

RULES:
- 3-5 sentences max. No fluff, no "I hope this email finds you well".
- Open with THEM (reference the specific detail), not with you.
- State the offer in one line, then ask ONE question about whether they currently
  need {services} work and roughly what budget/scope they have in mind.
- End with a low-friction call to action: {b.get('cta')}
- Sound like a real person, not a template. No exclamation marks. No emoji.
- If channel is email, include a suggested subject line on the first line as
  "Subject: ..." then a blank line then the body.

Return ONLY the message."""


def draft_for(prospect: dict, cfg: dict) -> str:
    from core.gemini import ask  # the measured model ladder
    text = ask("smart", _draft_prompt(prospect, cfg))
    prospect["draft"] = text.strip()
    prospect["status"] = "drafted"
    _persist(prospect)
    return prospect["draft"]


def _persist(prospect: dict) -> None:
    rows = load_prospects()
    k = _key(prospect)
    for i, r in enumerate(rows):
        if _key(r) == k:
            rows[i] = prospect
            break
    save_prospects(rows)


# ---------------------------------------------------------------------- sending
def _send_email(prospect: dict, body: str, cfg: dict) -> tuple[bool, str]:
    smtp = cfg.get("smtp", {})
    if not (smtp.get("host") and smtp.get("user") and smtp.get("password")):
        return False, "Email not configured. Set outreach.smtp in ~/.jarvis/outreach/config.json."
    to = prospect.get("email")
    if not to:
        return False, "No email address for this prospect."
    subject = "Quick idea for your business"
    lines = body.split("\n")
    if lines and lines[0].lower().startswith("subject:"):
        subject = lines[0].split(":", 1)[1].strip()
        body = "\n".join(lines[1:]).strip()
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = smtp.get("from_addr") or smtp.get("user")
    msg["To"] = to
    msg.set_content(body + f"\n\n— {cfg['business'].get('sender_name','')}\n"
                     f"(Reply 'stop' and I won't reach out again.)")
    try:
        ctx = ssl.create_default_context()
        with smtplib.SMTP(smtp["host"], int(smtp.get("port", 587)), timeout=30) as s:
            s.starttls(context=ctx)
            s.login(smtp["user"], smtp["password"])
            s.send_message(msg)
        return True, "sent"
    except Exception as e:  # noqa: BLE001
        return False, str(e)


def _send_app(prospect: dict, body: str, cfg: dict, player=None, speak=None) -> tuple[bool, str]:
    channel = (prospect.get("channel") or cfg.get("default_channel") or "whatsapp").lower()
    receiver = prospect.get("handle") or prospect.get("contact") or prospect.get("email")
    if not receiver:
        return False, "No handle/contact for this prospect."
    try:
        from actions.send_message import send_message
        r = send_message(parameters={"platform": channel, "receiver": receiver, "message": body},
                         player=player, speak=speak)
        ok = "sent" in str(r).lower() or "done" in str(r).lower()
        return ok, str(r)
    except Exception as e:  # noqa: BLE001
        return False, str(e)


def send_one(prospect: dict, cfg: dict, player=None, speak=None) -> tuple[bool, str]:
    channel = (prospect.get("channel") or cfg.get("default_channel") or "email").lower()
    body = prospect.get("draft") or draft_for(prospect, cfg)
    if channel == "email":
        ok, note = _send_email(prospect, body, cfg)
    else:
        ok, note = _send_app(prospect, body, cfg, player, speak)
    if ok:
        prospect["status"] = "contacted"
        prospect["last_contacted"] = date.today().isoformat()
        _persist(prospect)
    _record_send(prospect, channel, ok, note)
    return ok, note


# ------------------------------------------------------------------ the entry
def outreach(params: dict, player=None, speak=None) -> str:
    action = (params.get("action") or "status").lower().strip()
    cfg = load_config()

    def log(m):
        if player and hasattr(player, "write_log"):
            player.write_log(f"[Outreach] {m}")

    # ---- add prospects (single or a list) ----
    if action in ("add", "import"):
        items = params.get("prospects")
        if isinstance(items, dict):
            items = [items]
        if not items and params.get("business"):
            items = [{k: params.get(k) for k in
                      ("business", "contact", "handle", "email", "channel", "notes")}]
        if not items:
            return "Give me prospects to add (business, contact, handle/email, channel, notes)."
        added, dupes = add_prospects(items)
        return f"Added {added} prospect(s); skipped {dupes} duplicate(s)."

    # ---- status ----
    if action in ("status", "list", ""):
        rows = load_prospects()
        by = {}
        for r in rows:
            by[r.get("status", "pending")] = by.get(r.get("status", "pending"), 0) + 1
        cap_left = max(0, int(cfg.get("daily_cap", 20)) - _sent_today())
        summary = ", ".join(f"{v} {k}" for k, v in by.items()) or "no prospects yet"
        return (f"Prospects: {summary}. Sent today: {_sent_today()} "
                f"(cap {cfg.get('daily_cap')}, {cap_left} left).")

    # ---- draft the next pending (or a specific one) ----
    if action in ("draft", "draft_next"):
        rows = load_prospects()
        target = None
        if params.get("business"):
            target = next((r for r in rows if _key(r) == _key({"business": params["business"]})), None)
        if target is None:
            target = next((r for r in rows if r.get("status") == "pending"), None)
        if target is None:
            return "No pending prospects to draft."
        try:
            msg = draft_for(target, cfg)
        except Exception as e:  # noqa: BLE001
            return f"Drafting failed: {e}"
        log(f"Drafted for {target.get('business')}")
        return f"Draft for {target.get('business')}:\n\n{msg}\n\nSay 'approve' to send, or 'next'."

    # ---- approve + send the most recently drafted ----
    if action in ("send", "approve", "send_next"):
        if _sent_today() >= int(cfg.get("daily_cap", 20)):
            return f"Daily cap ({cfg.get('daily_cap')}) reached — stopping for today to stay safe."
        rows = load_prospects()
        target = next((r for r in rows if r.get("status") == "drafted"), None) \
            or next((r for r in rows if r.get("status") == "pending"), None)
        if target is None:
            return "Nothing queued to send. Use 'draft' first."
        ok, note = send_one(target, cfg, player, speak)
        who = target.get("business") or target.get("handle")
        return (f"{'✅ Sent' if ok else '⚠️ Failed'} to {who}: {note}. "
                f"({_sent_today()}/{cfg.get('daily_cap')} today)")

    # ---- draft a batch up to the daily cap ----
    if action in ("queue", "prepare", "draft_batch"):
        rows = load_prospects()
        pending = [r for r in rows if r.get("status") == "pending"]
        room = max(0, int(cfg.get("daily_cap", 20)) - _sent_today())
        n = min(int(params.get("count", room or 10)), room or len(pending))
        drafted = 0
        for r in pending[:n]:
            try:
                draft_for(r, cfg)
                drafted += 1
            except Exception as e:  # noqa: BLE001
                log(f"draft failed for {r.get('business')}: {e}")
        return f"Drafted {drafted} message(s). Review them, then say 'send' to go one at a time."

    # ---- send the whole approved queue, one at a time, with delays ----
    if action in ("send_all", "run", "run_campaign"):
        if not cfg.get("require_confirm", True) and not params.get("confirmed"):
            return "This will send to every drafted prospect. Say 'send_all confirmed' to proceed."
        rows = load_prospects()
        queue = [r for r in rows if r.get("status") == "drafted"]
        delay = float(cfg.get("delay_seconds", 45))
        sent = failed = 0
        for r in queue:
            if _sent_today() >= int(cfg.get("daily_cap", 20)):
                log("daily cap hit — pausing campaign")
                break
            ok, _ = send_one(r, cfg, player, speak)
            sent += ok
            failed += (not ok)
            if ok and delay:
                time.sleep(delay)
        return f"Campaign pass done: {sent} sent, {failed} failed. ({_sent_today()}/{cfg.get('daily_cap')} today)"

    # ---- follow-up nudge to contacted-but-no-reply ----
    if action in ("followup", "nudge"):
        rows = load_prospects()
        due = [r for r in rows if r.get("status") == "contacted"
               and r.get("followups", 0) < 2]
        if not due:
            return "No one due for a follow-up yet."
        r = due[0]
        r["followups"] = r.get("followups", 0) + 1
        r["draft"] = (f"Hi {r.get('contact') or 'there'} — just floating this back up. "
                      f"Still happy to put together a quick {', '.join(cfg['business'].get('services', []))} "
                      f"mockup for {r.get('business')} if it's useful. No worries if not a fit.")
        r["status"] = "drafted"
        _persist(r)
        return f"Follow-up drafted for {r.get('business')}:\n\n{r['draft']}\n\nSay 'send' to send it."

    return (f"Unknown outreach action '{action}'. Try: add, status, draft, send, "
            f"queue, send_all, followup.")
