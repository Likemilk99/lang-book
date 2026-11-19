#!/usr/bin/env python3
"""Разговорник Lectura: готовые фразы для устного общения (A0 — выживание, A1 — повседневные ситуации).

  python3 pipeline/phrasebook.py                 # оба уровня
  python3 pipeline/phrasebook.py --levels A0     # один уровень
  python3 pipeline/phrasebook.py --stats         # статистика по готовым файлам

Фразы пишет `claude -p` (подписка пользователя), тема за темой: в запрос передаются уже готовые фразы
уровня (и для A1 — все фразы A0), чтобы не было повторов. Каждая тема кэшируется в
pipeline/.cache/phrasebook/<level>-<theme>.json — чтобы пересоздать тему, удалите её файл.

Результат: android/app/src/main/assets/phrasebook/phrasebook_<L>.json (контракт — model/Phrasebook.kt).
Озвучка: python3 pipeline/audio.py --phrasebook → assets/phrasebook/audio/<id>.m4a (+ <id>_<k>.m4a для вариантов).

Фраза может иметь шаблон («Quería ___.») с вариантами подстановки: в приложении по нему строится
упражнение «Скажи по шаблону». es каждого варианта == pattern с подставленным fill.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = HERE / ".cache" / "phrasebook"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets" / "phrasebook"

sys.path.insert(0, str(HERE))
from prepare_book import claude_json  # noqa: E402

LEVELS = {
    "A0": ("Выживание", "Самые нужные фразы с первого дня: понять, переспросить, попросить, решить проблему."),
    "A1": ("Повседневные ситуации", "Кафе, магазин, врач, жильё, документы, звонки — фразы для реальных дел."),
}

# id, эмодзи, название, что входит (для модели), сколько фраз.
THEMES = {
    "A0": [
        ("hello", "👋", "Приветствие и вежливость",
         "greetings for different times of day, goodbye, please/thank you/you're welcome, sorry vs excuse me "
         "(perdón, disculpe, lo siento), how are you and short answers", 14),
        ("control", "🆘", "Управлять разговором",
         "I don't understand, could you repeat, slower please, how do you say X, what does X mean, "
         "I speak a little Spanish, do you speak English, can you write it down, one moment, I'm learning Spanish", 14),
        ("me", "🙋", "О себе",
         "my name is, I'm from Russia, I live in, I work as / I'm a student, I've been in Spain for…, "
         "I'm here on holiday / for work, this is my husband/wife/friend", 12),
        ("questions", "❓", "Главные вопросы",
         "where is, how much is it, what time, is there, do you have, can I (¿se puede…?), what is this, "
         "which one, how do I get to", 14),
        ("asking", "🙏", "Попросить",
         "polite requests to staff and strangers: quería…, ¿me pone…?, ¿me da…?, ¿puede…?, necesito…, "
         "¿me ayuda, por favor?, give me this one, a bit more / less", 14),
        ("cafe", "☕", "Кафе и бар",
         "ordering coffee/drinks/tapas in Spain, table for two, the menu, the bill, to take away, "
         "is the kitchen open, the toilet please", 14),
        ("shop", "🛒", "Магазин и деньги",
         "can I pay by card, cash, receipt, bag, it's too expensive, I'm just looking, how much is all, "
         "do you have a smaller size", 12),
        ("transport", "🚇", "Транспорт и дорога",
         "a ticket to, which platform, does this bus go to, where do I get off, a taxi to this address, "
         "is it far, turn left/right, straight on", 12),
        ("time", "🕐", "Время и числа",
         "what time is it, at what time does it open/close, today/tomorrow/yesterday, in five minutes, "
         "it's too late/early, phone number dictation", 10),
        ("problems", "⚠️", "Проблемы и помощь",
         "I have a problem, it doesn't work, I'm lost, I've lost my…, help!, call the police/an ambulance, "
         "someone stole my…, I need help, it's urgent", 14),
        ("health", "💊", "Здоровье",
         "I feel bad, my head/stomach hurts, I need a doctor, pharmacy, I'm allergic to…, I have a fever, "
         "where is the hospital", 10),
        ("reactions", "💬", "Реакции и короткие ответы",
         "yes of course, okay (vale), I don't know, no problem, perfect, really?, what a pity, "
         "I agree, it depends, me too / me neither", 14),
    ],
    "A1": [
        ("smalltalk", "🗨️", "Знакомство и разговор",
         "what do you do, where are you from, how long have you been in Spain, do you like it here, "
         "family, hobbies, weekend plans, nice to meet you; tú with peers", 14),
        ("restaurant", "🍽️", "Ресторан",
         "booking a table, menú del día, what do you recommend, I'm vegetarian / allergic, "
         "it's not what I ordered, can we have the bill / pay separately, tip", 14),
        ("shopping", "🛍️", "Покупки и возврат",
         "do you have it in another size/colour, can I try it on, I'd like to return/exchange this, "
         "it's broken, receipt/invoice (factura), is there a discount, when will it arrive", 14),
        ("transport", "🚆", "Транспорт и поездки",
         "monthly pass (abono), return ticket, the train is late, I missed my stop, renfe/metro questions, "
         "how long does it take, booking a taxi", 12),
        ("home", "🏠", "Жильё и аренда",
         "renting a flat: deposit (fianza), bills included?, there's no hot water, the … is broken, "
         "can you send someone to fix it, neighbours' noise, when can I see the flat", 14),
        ("doctor", "🩺", "Врач и аптека",
         "booking an appointment (pedir cita), it hurts since yesterday, I've had a cough for a week, "
         "do I need a prescription, how many times a day, health card (tarjeta sanitaria)", 14),
        ("papers", "📄", "Документы и банк",
         "cita previa, NIE, empadronamiento, what documents do I need, where do I sign, "
         "open a bank account, my card is blocked, can you give me a copy; usted with officials", 14),
        ("phone", "📞", "Телефон",
         "who's calling, I'm calling about…, can you tell him to call me back, I can't hear you well, "
         "wrong number, could you spell it, my number is…", 12),
        ("plans", "📅", "Встречи и планы",
         "shall we meet, does … suit you, I can't that day, how about…, let's meet at…, I'm running late, "
         "sorry, I can't come, see you then", 12),
        ("complaints", "😤", "Жалобы и проблемы",
         "polite but firm complaints: this isn't right, I've been waiting for an hour, I want to speak to the manager, "
         "can you solve it, it's the second time, I've lost my passport, they've stolen my phone", 14),
        ("opinion", "🤔", "Мнение и чувства",
         "I think that, I prefer, I like/don't like, I'm tired/happy/worried, it seems to me, "
         "what do you think, I'm not sure, I agree / disagree", 12),
        ("work", "💼", "Работа и учёба",
         "I'm looking for a job, I work from home, my schedule, a day off, can I leave early, "
         "I don't understand this task, could you explain it again", 12),
    ],
}

SYSTEM = (
    "You write a phrasebook for a native Russian speaker who is learning Spanish from zero to TALK with people "
    "in Spain and solve everyday problems. Peninsular Spanish only: vosotros, Spain vocabulary (móvil, ordenador, "
    "zumo, coger, vale, piso), Spanish usage in Spain. Phrases must be what people really say in Spain today — "
    "natural, short, high-frequency, immediately usable. Use usted with staff, officials, doctors and strangers, "
    "tú with peers and friends; say which in the note when it matters. If a phrase depends on the speaker's "
    "gender, write the masculine form and give the feminine one in the note (e.g. «Женщина: Encantada»). "
    "Translations are natural Russian (not word-for-word). Notes are in Russian, one short practical tip "
    "(when to use it, what you will likely hear back, a pitfall) — or empty if nothing useful to add."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "phrases": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "es": {"type": "string"},
                    "ru": {"type": "string"},
                    "note": {"type": "string"},
                    "pattern": {"type": "string"},
                    "slots": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "fill": {"type": "string"},
                                "es": {"type": "string"},
                                "ru": {"type": "string"},
                            },
                            "required": ["fill", "es", "ru"],
                        },
                    },
                },
                "required": ["es", "ru", "note", "pattern", "slots"],
            },
        }
    },
    "required": ["phrases"],
}

LEVEL_RULES = {
    "A0": "Level A0 (absolute beginner): at most ~7 words per phrase, present tense or fixed chunks "
          "(fixed chunks like «He perdido…», «¿Podría…?», «Quería…» are welcome even though their grammar is later).",
    "A1": "Level A1: at most ~12 words, present tense plus useful fixed chunks with perfecto/indefinido, "
          "condicional of courtesy (podría, me gustaría) and imperative of courtesy where people really use them.",
}


def norm(s: str) -> str:
    return re.sub(r"[^\w]+", " ", s.lower()).strip()


def make_theme(level: str, theme: tuple, known: list[str], model: str, effort: str) -> list[dict]:
    tid, _, title, scope, n = theme
    path = CACHE / f"{level}-{tid}.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    prompt = (
        f"{LEVEL_RULES[level]}\n\n"
        f"Theme: «{title}» — {scope}.\n"
        f"Write exactly {n} phrases for this theme, most useful first.\n\n"
        "Templates: for about a third of the phrases that are natural frames for substitution, set `pattern` to the "
        "phrase with ONE gap written as ___ (three underscores) and give 3–4 `slots`. Each slot: `fill` (what goes "
        "into the gap), `es` (the full phrase = pattern with ___ replaced by fill, exact same spelling and "
        "punctuation), `ru` (natural Russian translation of that full phrase). The phrase's own `es` must equal the "
        "pattern filled with the first slot's fill. For phrases without a template, `pattern` is \"\" and `slots` "
        "is [].\n\n"
        "Do NOT repeat (even with small changes) any of these phrases that the learner already has:\n"
        + "\n".join(f"- {k}" for k in known)
    )
    data = claude_json(prompt, SYSTEM, SCHEMA, model, effort)
    phrases = data["phrases"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(phrases, ensure_ascii=False, indent=1), encoding="utf-8")
    return phrases


def fill(pattern: str, f: str) -> str:
    return pattern.replace("___", f, 1)


def contract(prep: str, f: str) -> str:
    """«a» + «el marisco» → «al marisco», «de» + «el banco» → «del banco», иначе просто через пробел."""
    if f.startswith("el "):
        return ("al " if prep.lower() == "a" else "del ") + f[3:]
    return f"{prep} {f}"


def fold_preposition(pattern: str, slots: list[dict]) -> tuple[str, list[dict]]:
    """Шаблон «… a ___.» с подстановкой «el …» дал бы неграмотное «a el»: переносим предлог в подстановку
    («Soy alérgico ___.» + «al marisco» / «a la leche»)."""
    m = re.search(r"\b(a|de) ___", pattern, re.IGNORECASE)
    if not m or not any(s["fill"].strip().startswith("el ") for s in slots):
        return pattern, slots
    prep = m.group(1)
    pattern = pattern[: m.start()] + "___" + pattern[m.end():]
    return pattern, [{**s, "fill": contract(prep, s["fill"].strip())} for s in slots]


def clean(phrases: list[dict], seen: set[str]) -> tuple[list[dict], list[str]]:
    """Убирает повторы и чинит/отбрасывает неверные шаблоны. Возвращает (фразы, предупреждения)."""
    out, warn = [], []
    for p in phrases:
        es, ru = p["es"].strip(), p["ru"].strip()
        if not es or not ru or norm(es) in seen:
            warn.append(f"повтор/пусто: {es}")
            continue
        seen.add(norm(es))
        item = {"es": es, "ru": ru}
        if p.get("note", "").strip():
            item["note"] = p["note"].strip()
        pattern = p.get("pattern", "").strip()
        slots = [s for s in p.get("slots", []) if s.get("fill", "").strip() and s.get("ru", "").strip()]
        if pattern and pattern.count("___") == 1 and len(slots) >= 2:
            pattern, slots = fold_preposition(pattern, slots)
            good = []
            for s in slots:
                full = fill(pattern, s["fill"].strip())
                if norm(full) != norm(s["es"]):
                    warn.append(f"вариант не совпал с шаблоном, исправлен: {s['es']!r} → {full!r}")
                good.append({"fill": s["fill"].strip(), "es": full, "ru": s["ru"].strip()})
            if norm(good[0]["es"]) != norm(es):
                # Сама фраза — тоже вариант шаблона: ставим её первой, если её нет среди вариантов.
                good = [v for v in good if norm(v["es"]) != norm(es)]
                m = re.fullmatch(re.escape(pattern).replace("___", "(.+)"), es)
                if m:
                    good.insert(0, {"fill": m.group(1), "es": es, "ru": ru})
                else:
                    warn.append(f"фраза не подходит к шаблону, шаблон убран: {es!r} / {pattern!r}")
                    good = []
            if len(good) >= 2:
                item["pattern"] = pattern
                item["slots"] = good
        elif pattern:
            warn.append(f"шаблон отброшен: {pattern!r}")
        out.append(item)
    return out, warn


def build(level: str, model: str, effort: str) -> dict:
    seen: set[str] = set()
    known: list[str] = []
    if level == "A1":
        a0 = ASSETS / "phrasebook_A0.json"
        if a0.exists():
            for t in json.loads(a0.read_text(encoding="utf-8"))["themes"]:
                for p in t["phrases"]:
                    seen.add(norm(p["es"]))
                    known.append(p["es"])
    themes = []
    for theme in THEMES[level]:
        tid, emoji, title, _, _ = theme
        print(f"✍️  {level} · {title}", flush=True)
        raw = make_theme(level, theme, known, model, effort)
        phrases, warn = clean(raw, seen)
        for w in warn:
            print("   ⚠️", w)
        for i, p in enumerate(phrases):
            p["id"] = f"{level.lower()}-{tid}-{i + 1:02d}"
        known += [p["es"] for p in phrases]
        themes.append({"id": f"{level.lower()}-{tid}", "emoji": emoji, "title": title,
                       "phrases": [{"id": p.pop("id"), **p} for p in phrases]})
    title, intro = LEVELS[level]
    return {"level": level, "title": title, "intro": intro, "themes": themes}


def stats() -> None:
    for f in sorted(ASSETS.glob("phrasebook_*.json")):
        b = json.loads(f.read_text(encoding="utf-8"))
        n = sum(len(t["phrases"]) for t in b["themes"])
        pat = sum(1 for t in b["themes"] for p in t["phrases"] if p.get("pattern"))
        var = sum(len(p.get("slots", [])) for t in b["themes"] for p in t["phrases"])
        print(f"{b['level']}: тем {len(b['themes'])}, фраз {n}, с шаблоном {pat}, вариантов {var}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--levels", default="A0,A1")
    ap.add_argument("--model", default="opus")
    ap.add_argument("--effort", default="medium")
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()
    if args.stats:
        stats()
        return
    ASSETS.mkdir(parents=True, exist_ok=True)
    for level in [l.strip().upper() for l in args.levels.split(",") if l.strip()]:
        book = build(level, args.model, args.effort)
        out = ASSETS / f"phrasebook_{level}.json"
        out.write_text(json.dumps(book, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"✅ {out.relative_to(ROOT)}")
    stats()


if __name__ == "__main__":
    main()
