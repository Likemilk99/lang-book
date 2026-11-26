#!/usr/bin/env python3
"""Learner progress mirror on the Mac: personalizes «Живой разговор».

  python3 pipeline/learner.py                          # what the next conversation would get (A1, no topic)
  python3 pipeline/learner.py --level A2 --themes body-health,time
  python3 pipeline/learner.py --build-freq             # rebuild pipeline/freq_rank.json

How it works:
- Before each conversation the phone sends a snapshot of its progress (POST /progress in server.py): every word card
  with its recall estimate (computed on the phone, Memory.recall), stage, reps and lapses; grammar topics; recent
  daily stats. It is stored in books/.work/personal/learner.db (SQLite; personal data, gitignored).
- The phone stays the source of truth. The Mac copy is replaced as a whole by every snapshot and never goes back.
- brief() picks words for the conversation prompt, frequency first (the learner wants to speak as soon as possible):
    review — words they are forgetting: low recall and lapses, frequent and on-topic words first;
    new    — frequent words of the level not in their cards yet, core verbs first (2 on the topic + 3 general);
    known  — well-remembered words beyond the level word list, which the character may use freely.
- Journal and profile: every learner turn of a conversation (said, reply, fix) is appended to the journal; when the
  conversation is over, one model call updates the learner profile (typical mistakes, what works, what to push),
  which every next conversation gets in its prompt.
- Frequency: pipeline/freq_rank.json — lemma → rank in spoken+written Spanish (wordfreq «es», lemmatized with
  simplemma) for every word of the curriculum and of the books. It is committed, so the server needs neither package.
  wordfreq also counts Latin American Spanish; only our own (Spain) word lists are ranked, so nothing Latin American
  gets in.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import time
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
CURRICULUM = ASSETS / "curriculum"
FREQ_FILE = HERE / "freq_rank.json"
DB_FILE = ROOT / "books" / ".work" / "personal" / "learner.db"

CURRICULUM_LEVELS = ("A1", "A2", "B1", "B2")
# Words the conversation may introduce as new, by chat level (same split as chat.VOCAB_FILES, B1 adds its own).
NEW_FROM = {"A0": ["A1"], "A1": ["A1"], "A2": ["A1", "A2"], "B1": ["A1", "A2", "B1"]}
# Level word list the character already gets in the prompt (chat.VOCAB_FILES): known words beyond it are listed.
PROMPT_LIST = {"A0": ["A1"], "A1": ["A1"], "A2": ["A1", "A2"], "B1": []}
# Content words: function words (el, de, que, yo…) are known without cards and are not worth «introducing».
CONTENT_POS = {"noun", "verb", "adj", "adv"}

REVIEW_N, NEW_TOPIC_N, NEW_GENERAL_N, KNOWN_N = 10, 2, 3, 120
COVERAGE = (300, 1000, 2000)
SNAPSHOT_FORMAT = 1
MAX_WORDS = 20000

LEMMA_RE = re.compile(r"^[^\x00-\x1f]{1,80}$")


def log(msg: str) -> None:
    print(msg, flush=True)


# ------------------------------------------------------------------ word lists

def norm(l: str) -> str:
    return l.strip().lower()


def load_curriculum() -> dict[str, dict]:
    """lemma → {ru, pos, level, theme}; the first (lowest) level wins."""
    out: dict[str, dict] = {}
    for level in CURRICULUM_LEVELS:
        f = CURRICULUM / f"vocab_{level}.json"
        if not f.exists():
            continue
        for t in json.loads(f.read_text(encoding="utf-8"))["themes"]:
            for w in t["words"]:
                out.setdefault(norm(w["l"]), {"ru": w.get("ru", ""), "pos": w.get("pos", ""), "level": level,
                                              "theme": t["id"]})
    return out


_freq: dict[str, int] | None = None


def freq_rank() -> dict[str, int]:
    global _freq
    if _freq is None:
        _freq = json.loads(FREQ_FILE.read_text(encoding="utf-8")) if FREQ_FILE.exists() else {}
    return _freq


def rank_of(l: str) -> int | None:
    """Frequency rank of a lemma (1 = most frequent); reflexive verbs fall back to the base verb."""
    r = freq_rank()
    l = norm(l)
    if l in r:
        return r[l]
    if l.endswith("se") and l[:-2] in r:
        return r[l[:-2]]
    return None


def build_freq() -> None:
    """freq_rank.json for the curriculum and book lemmas (needs wordfreq + simplemma; see --help)."""
    try:
        import simplemma
        import wordfreq
    except ImportError:
        raise SystemExit("для --build-freq нужны wordfreq и simplemma, например:\n"
                         "  uv venv pipeline/.cache/freq-venv && uv pip install --python "
                         "pipeline/.cache/freq-venv/bin/python wordfreq simplemma\n"
                         "  pipeline/.cache/freq-venv/bin/python pipeline/learner.py --build-freq")
    freq: dict[str, float] = {}
    for w in wordfreq.top_n_list("es", 60000):
        if w.isalpha() and w == w.lower():
            lem = simplemma.lemmatize(w, lang="es")
            freq[lem] = freq.get(lem, 0.0) + wordfreq.word_frequency(w, "es")
    # A rank is a position among all lemmas by frequency. Summed frequency of all forms only when simplemma keeps the
    # word as its own lemma: it maps «estado» → estar, «sí» → él, «fuera» → ser, so those use their own surface
    # frequency instead. A reflexive verb gets a third of its base verb (hacerse is much rarer than hacer).
    desc = sorted(freq.values(), reverse=True)

    def rank(f: float) -> int:
        lo, hi = 0, len(desc)
        while lo < hi:
            mid = (lo + hi) // 2
            if desc[mid] > f:
                lo = mid + 1
            else:
                hi = mid
        return lo + 1

    def own(l: str) -> float | None:
        return freq.get(l) if simplemma.lemmatize(l, lang="es") == l else None

    wanted = set(load_curriculum())
    lemmas = ASSETS / "lemmas.json"
    if lemmas.exists():
        wanted |= {norm(l) for l in json.loads(lemmas.read_text(encoding="utf-8"))}
    out: dict[str, int] = {}
    for l in sorted(wanted):
        if " " in l:
            continue
        f = own(l)
        if f is None and re.search(r"(ar|er|ir)se$", l) and own(l[:-2]):
            f = own(l[:-2]) / 3
        if f is None:
            f = wordfreq.word_frequency(l, "es")
        if f > 0:
            out[l] = rank(f)
    FREQ_FILE.write_text(json.dumps(out, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
                         encoding="utf-8")
    log(f"✓ {FREQ_FILE.relative_to(ROOT)}: {len(out)} лемм с частотой из {len(wanted)}")


# ------------------------------------------------------------------ snapshot from the phone

def _int(x, lo: int = 0, hi: int = 10 ** 13) -> int | None:
    return x if isinstance(x, int) and not isinstance(x, bool) and lo <= x <= hi else None


def clean_snapshot(s: object) -> dict | None:
    """Only known fields of sane size; None if the snapshot is not usable."""
    if not isinstance(s, dict) or s.get("format") != SNAPSHOT_FORMAT or not isinstance(s.get("words"), list):
        return None
    if len(s["words"]) > MAX_WORDS:
        return None
    words = {}
    for w in s["words"]:
        if not isinstance(w, dict) or not isinstance(w.get("l"), str) or not LEMMA_RE.match(w["l"].strip()):
            continue
        vals = {k: _int(w.get(k)) for k in ("stage", "reps", "lapses", "interval", "seen", "added")}
        recall = _int(w.get("recall"), 0, 100)
        if recall is None or any(v is None for v in vals.values()):
            continue
        words[norm(w["l"])] = {"ru": str(w.get("ru") or "")[:200], "recall": recall, **vals}
    grammar = []
    for g in s.get("grammar") or []:
        if isinstance(g, dict) and isinstance(g.get("id"), str) and len(g["id"]) <= 80:
            vals = {k: _int(g.get(k), 0, 10 ** 6) for k in ("stage", "best", "last", "sessions")}
            if all(v is not None for v in vals.values()):
                grammar.append({"id": g["id"], "level": str(g.get("level") or "")[:4], **vals})
    days = []
    for d in s.get("days") or []:
        if isinstance(d, dict) and isinstance(d.get("day"), str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", d["day"]):
            vals = {k: _int(d.get(k), -1, 10 ** 6) for k in ("reviews", "correct", "speaking", "newWords",
                                                              "practice", "memoryPct")}
            if all(v is not None for v in vals.values()):
                days.append({"day": d["day"], **vals})
    return {"at": _int(s.get("at")) or int(time.time() * 1000), "appVersion": _int(s.get("appVersion")) or 0,
            "phrases": _int(s.get("phrases")) or 0, "words": words, "grammar": grammar[:500], "days": days[-400:]}


def connect(path: Path | None = None) -> sqlite3.Connection:
    path = path or DB_FILE
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE IF NOT EXISTS words(l TEXT PRIMARY KEY, ru TEXT, recall INT, stage INT, reps INT, lapses INT,
            interval INT, seen INT, added INT);
        CREATE TABLE IF NOT EXISTS grammar(id TEXT PRIMARY KEY, level TEXT, stage INT, best INT, last INT,
            sessions INT);
        CREATE TABLE IF NOT EXISTS days(day TEXT PRIMARY KEY, reviews INT, correct INT, speaking INT,
            new_words INT, practice INT, memory_pct INT);
        CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
        -- new words already offered in conversations: rotated so each chat brings different ones
        CREATE TABLE IF NOT EXISTS offered(l TEXT PRIMARY KEY, times INT, last INT);
        -- known words among the most frequent ones, one row per day
        CREATE TABLE IF NOT EXISTS coverage(day TEXT PRIMARY KEY, words INT, known INT, top300 INT, top1000 INT,
            top2000 INT);
        -- conversation journal: what the learner said, the character's reply and the fix (if any)
        -- (turn numbers restart when the Mac restarts mid-conversation, so rows are simply appended)
        CREATE TABLE IF NOT EXISTS turns(chat TEXT, n INT, at INT, topic TEXT, level TEXT, said TEXT, reply TEXT,
            fix_es TEXT, fix_ru TEXT);
        CREATE INDEX IF NOT EXISTS turns_chat ON turns(chat);
        -- the learner profile Claude keeps after each conversation (typical mistakes, what works, what to push)
        CREATE TABLE IF NOT EXISTS profile(id INT PRIMARY KEY CHECK(id = 1), json TEXT, updated INT, chats INT);
    """)
    return db


def is_known(w: dict) -> bool:
    return w["recall"] >= 70 and (w["stage"] >= 3 or w["reps"] >= 2)


def core_words(curriculum: dict[str, dict]) -> list[str]:
    """Content words of the curriculum, most frequent first."""
    ranked = [(rank_of(l), l) for l, e in curriculum.items() if e["pos"] in CONTENT_POS and " " not in l]
    return [l for r, l in sorted((r, l) for r, l in ranked if r)]


def coverage(words: dict[str, dict], curriculum: dict[str, dict]) -> dict:
    core = core_words(curriculum)
    known = {l for l, w in words.items() if is_known(w)}
    return {"words": len(words), "known": len(known),
            **{f"top{n}": sum(1 for l in core[:n] if l in known) for n in COVERAGE}}


def store(snap: dict, path: Path | None = None) -> dict:
    """Replace the mirror with a cleaned snapshot; returns the coverage numbers for the log."""
    cov = coverage(snap["words"], load_curriculum())
    db = connect(path)
    try:
        with db:
            db.execute("DELETE FROM words")
            db.executemany("INSERT INTO words VALUES (?,?,?,?,?,?,?,?,?)",
                           [(l, w["ru"], w["recall"], w["stage"], w["reps"], w["lapses"], w["interval"], w["seen"],
                             w["added"]) for l, w in snap["words"].items()])
            db.execute("DELETE FROM grammar")
            db.executemany("INSERT INTO grammar VALUES (?,?,?,?,?,?)",
                           [(g["id"], g["level"], g["stage"], g["best"], g["last"], g["sessions"])
                            for g in snap["grammar"]])
            db.executemany("INSERT OR REPLACE INTO days VALUES (?,?,?,?,?,?,?)",
                           [(d["day"], d["reviews"], d["correct"], d["speaking"], d["newWords"], d["practice"],
                             d["memoryPct"]) for d in snap["days"]])
            db.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)",
                           [("synced_at", str(snap["at"])), ("app_version", str(snap["appVersion"])),
                            ("phrases", str(snap["phrases"]))])
            db.execute("INSERT OR REPLACE INTO coverage VALUES (?,?,?,?,?,?)",
                       (date.today().isoformat(), cov["words"], cov["known"], cov["top300"], cov["top1000"],
                        cov["top2000"]))
    finally:
        db.close()
    return cov


def describe(cov: dict) -> str:
    return (f"слов в карточках {cov['words']}, знаю {cov['known']}; из самых частых знаю "
            + ", ".join(f"{cov[f'top{n}']}/{n}" for n in COVERAGE))


def load_words(db: sqlite3.Connection) -> dict[str, dict]:
    cols = ("ru", "recall", "stage", "reps", "lapses", "interval", "seen", "added")
    return {r[0]: dict(zip(cols, r[1:])) for r in db.execute(f"SELECT l, {', '.join(cols)} FROM words")}


# ------------------------------------------------------------------ what the conversation gets

def freq_bonus(rank: int | None) -> float:
    if rank is None:
        return -0.2  # rare book words (estrar, narvaso…) are not worth chat time
    return 0.4 if rank <= 500 else 0.25 if rank <= 1500 else 0.1 if rank <= 4000 else 0.0


def brief(level: str, themes: list[str], record: bool = False, path: Path | None = None) -> dict | None:
    """Words for one conversation; None if the phone never sent its progress."""
    path = path or DB_FILE
    if not path.exists():
        return None
    db = connect(path)
    try:
        words = load_words(db)
        if not words:
            return None
        offered = {r[0]: r[1] for r in db.execute("SELECT l, times FROM offered")}
        curriculum = load_curriculum()
        theme_list = list(dict.fromkeys(themes))
        themes = set(theme_list)

        def on_topic(l: str) -> bool:
            return curriculum.get(l, {}).get("theme") in themes

        review = []
        for l, w in words.items():
            weak = w["recall"] < 75 or (w["lapses"] >= 2 and w["recall"] < 90)
            if not weak:
                continue
            score = (1 - w["recall"] / 100) * (1 if w["reps"] else 0.6) + 0.1 * min(w["lapses"], 5) \
                + freq_bonus(rank_of(l)) + (0.3 if on_topic(l) else 0)
            review.append((score, l))
        review = [{"es": l, "ru": words[l]["ru"] or curriculum.get(l, {}).get("ru", "")}
                  for _, l in sorted(review, reverse=True)[:REVIEW_N]]

        have = set(words) | {l[:-2] for l in words if l.endswith("se")}
        levels = set(NEW_FROM.get(level, ["A1"]))
        cands = []
        for l, e in curriculum.items():
            r = rank_of(l)
            if e["level"] not in levels or e["pos"] not in CONTENT_POS or " " in l or l in have or r is None:
                continue
            # core verbs first; words already offered move back, so every conversation brings different ones
            eff = r * (0.6 if e["pos"] == "verb" else 1.0) + 400 * offered.get(l, 0)
            cands.append((eff, l))
        cands.sort()
        # the topic's first theme is its own (body-health at the doctor), the others are generic (time, numbers)
        order = {t: i for i, t in enumerate(theme_list)}
        topic_new = [l for _, l in sorted(((order[curriculum[l]["theme"]], eff), l) for eff, l in cands
                                          if on_topic(l))][:NEW_TOPIC_N]
        general = [l for _, l in cands if l not in topic_new][:NEW_GENERAL_N + NEW_TOPIC_N - len(topic_new)]
        new = [{"es": l, "ru": curriculum[l]["ru"]} for l in topic_new + general]

        listed = {l for l, e in curriculum.items() if e["level"] in PROMPT_LIST.get(level, [])}
        known = sorted((rank_of(l) or 10 ** 6, l) for l, w in words.items()
                       if is_known(w) and l not in listed and (level != "B1"))
        known = [l for _, l in known[:KNOWN_N]]

        if record and new:
            now = int(time.time())
            with db:
                db.executemany("INSERT INTO offered VALUES (?,1,?) ON CONFLICT(l) DO UPDATE SET "
                               "times = times + 1, last = excluded.last", [(w["es"], now) for w in new])
        return {"review": review, "new": new, "known": known, "stats": coverage(words, curriculum)}
    finally:
        db.close()


def prompt_block(b: dict | None) -> str:
    """The LEARNER'S WORDS section of the chat system prompt ("" without progress data)."""
    if not b:
        return ""
    lines = ["", "LEARNER'S WORDS (from their review app). They want to speak as soon as possible, so the most "
             "frequent everyday words matter most:"]
    if b["review"]:
        lines.append("- REVIEW — they are forgetting these: over the conversation use 3–5 of them naturally in "
                     "your lines, and put them first in meta.hints when they fit: "
                     + ", ".join(f"{w['es']} ({w['ru']})" for w in b["review"]))
    if b["new"]:
        lines.append("- NEW — not learned yet: introduce 1–2 of them when the scene allows, use each at least "
                     "twice, and list them in meta.words when they appear: "
                     + ", ".join(f"{w['es']} ({w['ru']})" for w in b["new"]))
    if b["known"]:
        lines.append("- KNOWN beyond the allowed list — you may use them freely: " + ", ".join(b["known"]))
    return "\n".join(lines) + "\n"


def chat_context(level: str, themes: list[str], record: bool = False) -> tuple[str, dict[str, str]]:
    """For a new conversation: the prompt sections (learner's words + profile) and word → "review" | "new"
    (tags for the pre-chat brief). Never fails the chat: a broken mirror means no section."""
    try:
        b = brief(level, themes, record=record)
        prof = load_profile()
    except Exception as err:  # noqa: BLE001
        log(f"⚠️  прогресс ученика: {err}")
        return "", {}
    tags = {}
    if b:
        tags = {w["es"].lower(): "new" for w in b["new"]} | {w["es"].lower(): "review" for w in b["review"]}
    return prompt_block(b) + profile_block(prof), tags


# ------------------------------------------------------------------ conversation journal and learner profile

KEEP_TURNS = 5000
PROFILE_SCHEMA = {
    "type": "object",
    "properties": {
        "mistakes": {"type": "array", "maxItems": 6, "items": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "the recurring error, short, in English, for the tutor"},
                "ru": {"type": "string", "description": "the same in Russian for the learner, short"},
                "wrong": {"type": "string", "description": "a typical wrong phrase of theirs"},
                "right": {"type": "string", "description": "the same said correctly (Spain)"},
            },
            "required": ["pattern", "ru", "wrong", "right"], "additionalProperties": False}},
        "can_do": {"type": "array", "maxItems": 6, "items": {"type": "string"},
                   "description": "what they already handle well, short, English"},
        "focus": {"type": "array", "maxItems": 4, "items": {"type": "string"},
                  "description": "what the next conversations should push (structures, frequent verbs/words), English"},
        "note_ru": {"type": "string", "description": "1–2 sentences to the learner in Russian about their progress"},
    },
    "required": ["mistakes", "can_do", "focus", "note_ru"],
    "additionalProperties": False,
}
PROFILE_SYSTEM = """You keep a short profile of a Russian-speaking learner of Spanish (Spain) for their conversation \
tutor. The learner lives in Spain and wants to SPEAK as soon as possible: practical, frequent everyday language.
Update the profile from the previous one and the new conversation:
- mistakes: RECURRING error patterns (grammar, word choice, word order, missing articles, ser/estar, gender, verb \
forms…), most important first. Merge with the previous ones; drop a pattern the learner now gets right; ignore one-off \
slips, missing punctuation/accents and obvious speech-recognition errors (the learner speaks, it is dictated).
- can_do: what they already say well (so the tutor builds on it).
- focus: 2–4 concrete things the next conversations should make them practise: the structures behind the mistakes \
and the most frequent verbs/words they avoid or lack.
Be concrete and short. Real examples from the learner's own lines."""


def record_turn(chat: str, topic: str, level: str, said: str, out: dict, path: Path | None = None) -> None:
    """One learner turn of «Живой разговор» → the journal (the opening [START] turn is not the learner's)."""
    if said.startswith("[START]"):
        return
    fix = out.get("fix") or {}
    db = connect(path)
    try:
        with db:
            db.execute("INSERT INTO turns VALUES (?,?,?,?,?,?,?,?,?)",
                       (chat, out["turn"], int(time.time()), topic[:120], level, said[:1000], out["reply"][:1000],
                        fix.get("es", "")[:300], fix.get("ru", "")[:300]))
            db.execute("DELETE FROM turns WHERE rowid IN (SELECT rowid FROM turns ORDER BY rowid DESC LIMIT -1 OFFSET ?)",
                       (KEEP_TURNS,))
    finally:
        db.close()


def load_profile(path: Path | None = None) -> dict | None:
    path = path or DB_FILE
    if not path.exists():
        return None
    db = connect(path)
    try:
        row = db.execute("SELECT json, updated, chats FROM profile WHERE id = 1").fetchone()
    finally:
        db.close()
    if not row:
        return None
    return {**json.loads(row[0]), "updated": row[1], "chats": row[2]}


def profile_block(p: dict | None) -> str:
    """The LEARNER PROFILE section of the chat system prompt ("" before the first profiled conversation)."""
    if not p or not (p.get("mistakes") or p.get("can_do") or p.get("focus")):
        return ""
    lines = ["", "LEARNER PROFILE (kept from their earlier conversations):"]
    if p.get("mistakes"):
        lines.append("- Typical mistakes — give natural chances to use these structures, recast them correctly, "
                     "and keep meta.fix focused on them: "
                     + "; ".join(f"{m['pattern']} («{m['wrong']}» → «{m['right']}»)" for m in p["mistakes"]))
    if p.get("can_do"):
        lines.append("- Already handles: " + "; ".join(p["can_do"]))
    if p.get("focus"):
        lines.append("- Push next: " + "; ".join(p["focus"]))
    return "\n".join(lines) + "\n"


def chat_turns(chat: str, path: Path | None = None) -> list[dict]:
    db = connect(path)
    try:
        cols = ("n", "topic", "level", "said", "reply", "fix_es", "fix_ru")
        return [dict(zip(cols, r)) for r in db.execute(
            f"SELECT {', '.join(cols)} FROM turns WHERE chat = ? ORDER BY rowid", (chat,))]
    finally:
        db.close()


def update_profile(chat: str, backend, model: str, effort: str, path: Path | None = None) -> dict | None:
    """After a conversation: ask the model to update the profile from this conversation, the previous profile and
    recent fixes of other conversations. None if the conversation was too short (fewer than 2 learner turns)."""
    import llm

    turns = chat_turns(chat, path)
    if len(turns) < 2:
        return None
    prev = load_profile(path)
    db = connect(path)
    try:
        others = db.execute("SELECT said, fix_es, fix_ru FROM turns WHERE chat != ? AND fix_es != '' "
                            "ORDER BY rowid DESC LIMIT 40", (chat,)).fetchall()
    finally:
        db.close()
    lines = []
    for t in turns:
        lines.append(f"Learner: {t['said']}" + (f"\n  fix: {t['fix_es']} ({t['fix_ru']})" if t["fix_es"] else ""))
        lines.append(f"Tutor: {t['reply']}")
    prompt = (f"Level: {turns[0]['level']}. Scene: {turns[0]['topic']}.\n\n"
              "PREVIOUS PROFILE:\n" + (json.dumps({k: prev[k] for k in ("mistakes", "can_do", "focus", "note_ru")},
                                                  ensure_ascii=False, indent=1) if prev else "(none yet)")
              + "\n\nTHE NEW CONVERSATION:\n" + "\n".join(lines)
              + ("\n\nRECENT FIXES FROM EARLIER CONVERSATIONS:\n"
                 + "\n".join(f"- {s} → {e} ({r})" for s, e, r in others) if others else ""))
    out = llm.json_call(backend, prompt, PROFILE_SYSTEM, PROFILE_SCHEMA, model, effort)
    prof = {"mistakes": [m for m in out.get("mistakes") or [] if isinstance(m, dict) and m.get("pattern")][:6],
            "can_do": [str(x) for x in out.get("can_do") or []][:6],
            "focus": [str(x) for x in out.get("focus") or []][:4],
            "note_ru": str(out.get("note_ru") or "")}
    for m in prof["mistakes"]:
        for k in ("pattern", "ru", "wrong", "right"):
            m[k] = str(m.get(k) or "")[:300]
    db = connect(path)
    try:
        with db:
            db.execute("INSERT OR REPLACE INTO profile VALUES (1, ?, ?, ?)",
                       (json.dumps(prof, ensure_ascii=False), int(time.time()), (prev or {}).get("chats", 0) + 1))
    finally:
        db.close()
    return prof


def describe_profile(p: dict) -> str:
    lines = [f"Профиль (разговоров: {p.get('chats', 0)}): {p.get('note_ru', '')}"]
    lines += [f"  ✗ {m['ru'] or m['pattern']}: «{m['wrong']}» → «{m['right']}»" for m in p.get("mistakes", [])]
    lines += [f"  ✓ {x}" for x in p.get("can_do", [])]
    lines += [f"  → {x}" for x in p.get("focus", [])]
    return "\n".join(lines)


def chat_block(level: str, themes: list[str], record: bool = False) -> str:
    return chat_context(level, themes, record)[0]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--level", default="A1", choices=list(NEW_FROM))
    ap.add_argument("--themes", default="", help="vocab theme ids of the topic, comma-separated")
    ap.add_argument("--build-freq", action="store_true")
    ap.add_argument("--update-profile", action="store_true",
                    help="update the profile from the last conversation in the journal (normally the server does it)")
    ap.add_argument("--llm", default="auto", choices=["auto", "claude", "local"])
    args = ap.parse_args()
    if args.build_freq:
        build_freq()
        return
    if args.update_profile:
        import llm

        db = connect()
        row = db.execute("SELECT chat FROM turns ORDER BY rowid DESC LIMIT 1").fetchone()
        db.close()
        if not row or not update_profile(row[0], llm.choose(args.llm), "opus", "medium"):
            print("В журнале нет разговора хотя бы из двух реплик.")
            return
    b = brief(args.level, [t for t in args.themes.split(",") if t])
    if b is None:
        print("Прогресса ещё нет: телефон присылает его перед каждым «Живым разговором».")
    else:
        print(describe(b["stats"]))
    prof = load_profile()
    if prof:
        print(describe_profile(prof))
    print(prompt_block(b) + profile_block(prof))


if __name__ == "__main__":
    sys.exit(main())
