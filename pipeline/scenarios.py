#!/usr/bin/env python3
"""Сценарии Lectura: одна жизненная ситуация целиком — фразы, диалог, роль за обе стороны, неудобный поворот,
«понять ответ» и свободные ответы голосом.

  python3 pipeline/scenarios.py            # все сценарии (готовые берутся из кэша)
  python3 pipeline/scenarios.py --stats

Пишет `claude -p` (подписка пользователя), по сценарию за запрос; кэш — pipeline/.cache/scenarios/<id>.json
(удалите файл, чтобы пересоздать сценарий). Ключевые фразы, совпавшие с разговорником, берутся из него
(тот же id — одна карточка в повторении и одна озвучка).

Результат: android/app/src/main/assets/scenarios/scenarios.json (контракт — model/Scenario.kt).
Озвучка: python3 pipeline/audio.py --scenarios.
Ученик в репликах «me» — мужского рода (как и в разговорнике); женская форма — в подсказке фразы.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = HERE / ".cache" / "scenarios"
OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "scenarios" / "scenarios.json"
PHRASEBOOK = ROOT / "android" / "app" / "src" / "main" / "assets" / "phrasebook"

sys.path.insert(0, str(HERE))
from phrasebook import SYSTEM as PB_SYSTEM, clean as clean_phrases, norm  # noqa: E402
from prepare_book import claude_json  # noqa: E402

# id, уровень, эмодзи, название, что происходит (для модели).
SCENARIOS = [
    ("cafe", "A0", "☕", "В кафе",
     "order a coffee and something to eat at the bar, ask for the bill and pay. Twist: what you asked for is "
     "sold out and the card machine doesn't work, only cash"),
    ("pharmacy", "A0", "💊", "В аптеке",
     "you have a headache and a sore throat; ask the pharmacist for something, how to take it, price. Twist: the "
     "medicine you name needs a prescription (receta) — the pharmacist suggests an alternative"),
    ("shop", "A0", "🛍️", "Покупка одежды",
     "buy a T-shirt / trousers: size, colour, fitting room, price, pay. Twist: your size is out of stock, they offer "
     "to order it or another colour"),
    ("train", "A0", "🚆", "Билет на поезд",
     "buy a return train ticket Madrid–Toledo at the ticket office: time, price, platform. Twist: the next train is "
     "full, the next one is later, and the platform changes"),
    ("directions", "A0", "🧭", "Спросить дорогу",
     "ask a passer-by the way to the metro station / a street, understand left/right/straight. Twist: it's far, "
     "they suggest a bus and which stop"),
    ("neighbour", "A0", "👋", "Знакомство с соседом",
     "meet a neighbour in the lift / doorway: greetings, names, where you're from, what floor, small talk. "
     "Twist: the neighbour invites you for a coffee / complains politely about noise"),
    ("doctor", "A1", "🩺", "У врача",
     "get an appointment at the centro de salud and see the GP: symptoms since when, fever, allergies, how to take "
     "the medicine. Twist: no appointments today — they send you to urgencias / give one for tomorrow"),
    ("flat", "A1", "🏠", "Смотрим квартиру",
     "view a flat for rent with the owner: rent, deposit (fianza), bills, furniture, when can you move in. "
     "Twist: the owner asks for a work contract / payslips or two months' deposit"),
    ("repair", "A1", "🔧", "Сломалось в квартире",
     "phone the landlord: there's no hot water, the boiler (caldera) doesn't work since yesterday. "
     "Twist: the technician can't come until Monday — negotiate, ask who pays"),
    ("bank", "A1", "🏦", "Банк и документы",
     "open a bank account; documents (passport, NIE, empadronamiento), fees, card. Twist: you need cita previa / "
     "a document is missing — ask what exactly and when to come back"),
    ("police", "A1", "🚓", "Украли телефон",
     "report a stolen phone at the comisaría (poner una denuncia): what, where, when, describe it. "
     "Twist: they ask for the IMEI / tell you to wait or come back"),
    ("restaurant", "A1", "🍽️", "В ресторане",
     "book a table by phone, order the menú del día, mention an allergy, ask for the bill. Twist: they bring the "
     "wrong dish and charge too much — complain politely"),
]

THEM_RULES = (
    "In dialogues, `who` is \"me\" (the learner, a man) or \"them\" (the other person). The other person talks "
    "naturally like a real Spaniard in this job — normal speed, typical expressions — but at the level given. "
    "The learner's lines are short and simple, built from the key phrases."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "situation": {"type": "string"},
        "canDo": {"type": "string"},
        "them": {
            "type": "object",
            "properties": {"role": {"type": "string"}, "gender": {"type": "string", "enum": ["m", "f"]}},
            "required": ["role", "gender"],
        },
        "phrases": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "es": {"type": "string"}, "ru": {"type": "string"}, "note": {"type": "string"},
                    "pattern": {"type": "string"},
                    "slots": {"type": "array", "items": {
                        "type": "object",
                        "properties": {"fill": {"type": "string"}, "es": {"type": "string"}, "ru": {"type": "string"}},
                        "required": ["fill", "es", "ru"]}},
                },
                "required": ["es", "ru", "note", "pattern", "slots"],
            },
        },
        "dialogs": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "lines": {"type": "array", "items": {
                        "type": "object",
                        "properties": {"who": {"type": "string", "enum": ["me", "them"]},
                                       "es": {"type": "string"}, "ru": {"type": "string"}},
                        "required": ["who", "es", "ru"]}},
                },
                "required": ["title", "lines"],
            },
        },
        "understand": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "es": {"type": "string"}, "ru": {"type": "string"}, "question": {"type": "string"},
                    "options": {"type": "array", "items": {"type": "string"}},
                    "answer": {"type": "integer"}, "why": {"type": "string"},
                },
                "required": ["es", "ru", "question", "options", "answer", "why"],
            },
        },
        "prompts": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "es": {"type": "string"}, "ru": {"type": "string"}, "hint": {"type": "string"},
                    "accepted": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["es", "ru", "hint", "accepted"],
            },
        },
    },
    "required": ["situation", "canDo", "them", "phrases", "dialogs", "understand", "prompts"],
}

LEVEL_RULES = {
    "A0": "Level A0 (absolute beginner): learner's lines at most ~7 words; the other person's lines at most ~12 words, "
          "simple and clear.",
    "A1": "Level A1: learner's lines at most ~12 words; the other person's lines up to ~18 words, natural.",
}


def phrasebook_phrases(level: str) -> list[dict]:
    out = []
    for lv in ("A0", "A1"):
        f = PHRASEBOOK / f"phrasebook_{lv}.json"
        if f.exists():
            for t in json.loads(f.read_text(encoding="utf-8"))["themes"]:
                out += t["phrases"]
        if lv == level:
            break
    return out


def make(sc: tuple, model: str, effort: str) -> dict:
    sid, level, _, title, scope = sc
    path = CACHE / f"{sid}.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    known = phrasebook_phrases(level)
    prompt = (
        f"{LEVEL_RULES[level]}\n\nScenario «{title}»: {scope}.\n\n"
        "Write a speaking scenario for the learner:\n"
        "- `situation`: 1–2 sentences in Russian — where he is and what he needs.\n"
        "- `canDo`: what he will be able to do, in Russian, starting with «Могу» (e.g. «Могу купить лекарство в аптеке»).\n"
        "- `them`: the other person's role in Russian (e.g. «Фармацевт») and gender.\n"
        "- `phrases`: 8–10 key phrases the learner needs here (most useful first). PREFER the exact text of a phrase "
        "from the learner's phrasebook below when it fits; templates (`pattern` with ___ and 3–4 `slots`, slot 0 = the "
        "phrase itself, each slot `es` = pattern with fill) only where natural, else pattern \"\" and slots [].\n"
        "- `dialogs`: exactly 2. First: the normal conversation (8–12 lines). Second: the same situation with the twist "
        "(8–12 lines) — the learner copes using the key phrases (asks to repeat, asks for alternatives…). "
        "Titles in Russian. Every line has a natural Russian translation.\n"
        "- `understand`: 5 typical things the other person says that a beginner might not understand (not copied "
        "from the dialogues; different wording). For each: Russian translation, a short question in Russian "
        "(«Что вам предлагают?», «Что нужно сделать?»), 3 options in Russian, index of the right one, `why` in Russian.\n"
        "- `prompts`: 4–5 questions the other person asks the learner (Spanish, with Russian translation) that he must "
        "answer in his own words; `accepted`: 4–6 different short correct answers at this level, all natural; "
        "`hint` in Russian: what to say (e.g. «Скажите, что болит голова со вчерашнего дня»).\n\n"
        f"{THEM_RULES}\n\nLearner's phrasebook (reuse exact text when it fits):\n"
        + "\n".join(f"- {p['es']}" for p in known)
    )
    data = claude_json(prompt, PB_SYSTEM, SCHEMA, model, effort)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    return data


def build(sc: tuple, data: dict) -> tuple[dict, list[str]]:
    sid, level, emoji, title, _ = sc
    warn: list[str] = []
    by_text = {norm(p["es"]): p for p in phrasebook_phrases("A1")}
    phrases, seen, new = [], set(), []
    for p in data["phrases"]:
        hit = by_text.get(norm(p["es"]))
        if hit:
            if norm(hit["es"]) not in seen:
                seen.add(norm(hit["es"]))
                phrases.append(hit)
        else:
            new.append(p)
    cleaned, w = clean_phrases(new, seen)
    warn += w
    for i, p in enumerate(cleaned, 1):
        phrases.append({"id": f"sc-{sid}-{i:02d}", **p})
    dialogs = []
    for k, d in enumerate(data["dialogs"][:2]):
        lines = [{"who": l["who"], "es": l["es"].strip(), "ru": l["ru"].strip()} for l in d["lines"] if l["es"].strip()]
        if not any(l["who"] == "me" for l in lines):
            warn.append(f"диалог {k}: нет реплик ученика")
        dialogs.append({"id": "ab"[k], "title": d["title"].strip(), "lines": lines})
    understand = []
    for u in data["understand"]:
        if 0 <= u["answer"] < len(u["options"]) and len(u["options"]) >= 2:
            understand.append(u)
        else:
            warn.append(f"понять: неверный ответ «{u['es']}»")
    prompts = [p for p in data["prompts"] if len(p["accepted"]) >= 2]
    reused = sum(1 for p in phrases if not p["id"].startswith("sc-"))
    print(f"   фраз {len(phrases)} (из разговорника {reused}), реплик {sum(len(d['lines']) for d in dialogs)}, "
          f"понять {len(understand)}, вопросов {len(prompts)}")
    return {
        "id": sid, "level": level, "emoji": emoji, "title": title,
        "situation": data["situation"].strip(), "canDo": data["canDo"].strip(),
        "them": data["them"], "phrases": phrases, "dialogs": dialogs,
        "understand": understand, "prompts": prompts,
    }, warn


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="opus")
    ap.add_argument("--effort", default="medium")
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()
    if args.stats:
        for s in json.loads(OUT.read_text(encoding="utf-8"))["scenarios"]:
            print(s["level"], s["id"], len(s["phrases"]), [len(d["lines"]) for d in s["dialogs"]],
                  len(s["understand"]), len(s["prompts"]))
        return
    out = []
    for sc in SCENARIOS:
        print(f"🎬 {sc[1]} · {sc[3]}", flush=True)
        item, warn = build(sc, make(sc, args.model, args.effort))
        for w in warn:
            print("   ⚠️", w)
        out.append(item)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"scenarios": out}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"✅ {OUT.relative_to(ROOT)}: {len(out)} сценариев")


if __name__ == "__main__":
    main()
