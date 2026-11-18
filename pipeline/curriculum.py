#!/usr/bin/env python3
"""Генерирует учебную программу Lectura (словарь уровней + курс грамматики) через `claude -p`.

Структура — по уровням CEFR A1–B2 и инвентарям Plan Curricular del Instituto Cervantes (PCIC):
темы словаря похожи на «nociones específicas», темы грамматики — на инвентарь «Gramática».
Тексты и списки — СОБСТВЕННЫЕ (генерируются моделью), формулировки PCIC не копируются.

Контракт данных: android/app/src/main/java/app/lectura/model/Curriculum.kt
Результат:      android/app/src/main/assets/curriculum/vocab_<L>.json, grammar_<L>.json

Примеры:
  python3 pipeline/curriculum.py --levels A1,A2              # словарь + план + грамматика
  python3 pipeline/curriculum.py --levels B1 --steps vocab
  python3 pipeline/curriculum.py --show a1-ser-estar          # посмотреть готовую тему
  python3 pipeline/curriculum.py --stats                      # только статистика по готовым файлам

Порядок шагов: vocab → plan → grammar. План грамматики (все уровни сразу, чтобы темы не
повторялись) сохраняется в pipeline/curriculum_plan.json; его можно поправить руками —
при следующем запуске используется файл, а не новый запрос.

Каждый вызов claude кэшируется в pipeline/.cache/curriculum/, прерванный запуск просто
повторяют. Чтобы пересоздать тему — удалите её файл из кэша.
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import random
import re
import subprocess
import sys
import threading
import time
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "pipeline" / ".cache" / "curriculum"
PLAN_FILE = ROOT / "pipeline" / "curriculum_plan.json"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets" / "curriculum"
LEVELS = ["A1", "A2", "B1", "B2"]

# Сколько НОВЫХ слов вводит уровень (после удаления повторов).
VOCAB_TARGET = {"A1": 700, "A2": 900, "B1": 1300, "B2": 1600}
OVERGEN = 1.2  # просим больше: часть уйдёт при удалении повторов

# Число тем грамматики в плане (мин, макс).
PLAN_RANGE = {"A1": (18, 22), "A2": (18, 22), "B1": (18, 22), "B2": (15, 20)}

MIN_EXERCISES = 30
MAX_EXERCISES = 40

SPAIN_RULES = (
    "Use Peninsular (Spain) Spanish only: vosotros forms, Spain vocabulary "
    "(ordenador, móvil, coche, zumo, patata, coger, conducir, piso, gafas, vale), Spain spelling conventions."
)

# id, русское название, вес по уровням (A1, A2, B1, B2), что входит (для модели).
THEMES: list[tuple[str, str, tuple[float, float, float, float], str]] = [
    ("basic", "Базовые слова", (16, 10, 8, 7),
     "grammar and function words: articles, prepositions, personal/possessive/demonstrative/indefinite "
     "pronouns and determiners, conjunctions and connectors, question words, very general adverbs "
     "(también, ya, todavía, muy…), discourse markers, plus the most general core verbs "
     "(ser, estar, haber, tener, ir, hacer, poder, querer, decir, dar, ver, saber, poner, venir, deber…). "
     "At B1/B2: connectors and discourse markers (sin embargo, por lo tanto, en cambio, a pesar de…), "
     "general abstract verbs and adverbs."),
    ("identity", "Личные данные", (5, 4, 4, 4),
     "personal identification: name, age, address, nationality and countries' adjectives, origin, sex, "
     "marital status, documents, signing forms, greetings and courtesy formulas"),
    ("body-health", "Тело и здоровье", (5, 5, 6, 6),
     "parts of the body, physical description, health, illness, symptoms, doctor, hospital, pharmacy, "
     "hygiene and personal care, physical states (hambre, sed, sueño)"),
    ("feelings", "Характер и чувства", (4, 5, 6, 6),
     "personality and character, emotions and feelings, moods, attitudes, opinions, likes and dislikes"),
    ("family", "Семья и отношения", (5, 5, 5, 4),
     "family members, relatives, friends, couples, social relationships, social life, celebrations, invitations"),
    ("food", "Еда и напитки", (8, 6, 5, 4),
     "food and drink, meals, cooking and recipes, kitchen utensils, restaurant and bar (tapas, la cuenta), tastes"),
    ("education", "Образование", (4, 5, 5, 5),
     "school, university, classroom objects, subjects, studying, exams, languages and learning"),
    ("work", "Работа", (4, 5, 6, 6),
     "professions, workplace, office, job search, working conditions, companies, colleagues"),
    ("leisure", "Досуг, спорт, культура", (5, 6, 6, 6),
     "hobbies and free time, sports, games, music, cinema, theatre, museums, arts, reading, holidays activities"),
    ("media", "СМИ, общение и техника", (3, 5, 6, 7),
     "media (press, TV, radio, internet), phone and messages, computers and devices, social networks, "
     "correspondence, technology and science basics"),
    ("home", "Дом и жильё", (6, 6, 5, 4),
     "types of housing, rooms, furniture, household objects and appliances, housework, renting and buying, "
     "neighbours"),
    ("services", "Услуги", (2, 4, 5, 5),
     "public and private services: bank, post office, police, emergencies, administration, repairs, "
     "public offices, insurance"),
    ("shopping", "Покупки и деньги", (4, 5, 5, 5),
     "shops, shopping, clothes and accessories, sizes, prices, money, paying, markets, consumption"),
    ("travel", "Путешествия и транспорт", (5, 6, 6, 5),
     "means of transport, travelling, tickets, stations and airports, hotels and accommodation, tourism, "
     "luggage, driving, traffic, directions"),
    ("city", "Город", (4, 5, 5, 4),
     "city, town, village, streets, buildings and places in town, urban life, location and directions"),
    ("nature", "Природа и погода", (4, 5, 6, 6),
     "weather and climate, seasons, geography, landscape, animals, plants, environment and ecology"),
    ("time", "Время и календарь", (5, 4, 3, 3),
     "telling the time, days, months, parts of the day, frequency, temporal adverbs and expressions, "
     "dates, festivities"),
    ("numbers", "Числа и количество", (5, 3, 2, 2),
     "cardinal and ordinal numbers, quantities, measures, weights, percentages, quantifiers"),
    ("description", "Цвета, формы, свойства", (3, 4, 5, 5),
     "colours, shapes, sizes, materials, physical properties of objects, comparison adjectives, "
     "general qualities (bueno, nuevo, fácil…)"),
    ("society", "Общество", (1, 3, 6, 9),
     "society, politics, economy, law, religion, history, social problems, values; for A1/A2 only "
     "the most basic words of this field"),
]

VOCAB_SCHEMA = {
    "type": "object",
    "properties": {
        "words": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "l": {"type": "string", "description": "lemma: lowercase, no article"},
                    "ru": {"type": "string", "description": "short Russian translation (1-4 words)"},
                    "pos": {"type": "string", "enum": ["noun", "verb", "adj", "adv", "prep", "pron", "det",
                                                       "conj", "num", "interj", "expr"]},
                    "g": {"type": "string", "enum": ["m", "f", ""], "description": "gender for nouns, else ''"},
                },
                "required": ["l", "ru", "pos", "g"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["words"],
    "additionalProperties": False,
}

_STR = {"type": "string"}
_STR_LIST = {"type": "array", "items": _STR}

PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "levels": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "level": {"type": "string", "enum": LEVELS},
                    "topics": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "id": {"type": "string", "description": "stable id, e.g. a1-ser-estar"},
                                "title": {"type": "string", "description": "Russian title"},
                                "titleEs": {"type": "string", "description": "Spanish title as in textbooks"},
                                "scope": {"type": "string", "description":
                                          "What exactly this topic covers at this level and what it leaves for "
                                          "later topics (English, 1-3 sentences)"},
                                "related": _STR_LIST,
                            },
                            "required": ["id", "title", "titleEs", "scope", "related"],
                            "additionalProperties": False,
                        },
                    },
                },
                "required": ["level", "topics"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["levels"],
    "additionalProperties": False,
}

EXERCISE_SCHEMA = {
    "type": "object",
    "properties": {
        "type": {"type": "string", "enum": ["choice", "input", "order", "transform"]},
        "sentence": _STR,
        "answer": _STR,
        "options": _STR_LIST,
        "accept": _STR_LIST,
        "prompt": _STR,
        "ru": _STR,
        "explain": _STR,
        "lemmas": _STR_LIST,
    },
    "required": ["type", "sentence", "answer", "options", "accept", "prompt", "ru", "explain", "lemmas"],
    "additionalProperties": False,
}

EXERCISES_SCHEMA = {
    "type": "object",
    "properties": {"exercises": {"type": "array", "items": EXERCISE_SCHEMA}},
    "required": ["exercises"],
    "additionalProperties": False,
}

TOPIC_SCHEMA = {
    "type": "object",
    "properties": {
        "title": _STR,
        "titleEs": _STR,
        "summary": _STR,
        "sections": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": _STR,
                    "text": _STR,
                    "table": {"type": "array", "items": _STR_LIST},
                },
                "required": ["title", "text", "table"],
                "additionalProperties": False,
            },
        },
        "examples": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"es": _STR, "ru": _STR, "note": _STR},
                "required": ["es", "ru", "note"],
                "additionalProperties": False,
            },
        },
        "mistakes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"wrong": _STR, "right": _STR, "why": _STR},
                "required": ["wrong", "right", "why"],
                "additionalProperties": False,
            },
        },
        "exercises": {"type": "array", "items": EXERCISE_SCHEMA},
        "related": _STR_LIST,
    },
    "required": ["title", "titleEs", "summary", "sections", "examples", "mistakes", "exercises", "related"],
    "additionalProperties": False,
}

_print_lock = threading.Lock()


def log(*a) -> None:
    with _print_lock:
        print(*a, flush=True)


# ---------------------------------------------------------------- claude

def claude_json(prompt: str, system: str, schema: dict, model: str, effort: str, retries: int = 3) -> dict:
    cmd = [
        "claude", "-p", prompt,
        "--model", model,
        "--effort", effort,
        "--tools", "",
        "--strict-mcp-config",
        "--no-session-persistence",
        "--output-format", "json",
        "--system-prompt", system,
        "--json-schema", json.dumps(schema),
    ]
    last_err = ""
    for attempt in range(retries + 1):
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=1800, cwd="/tmp")
            out = json.loads(proc.stdout)
            if out.get("is_error") or "structured_output" not in out:
                last_err = (out.get("result") or proc.stderr or "no structured_output")[:500]
            else:
                return out["structured_output"]
        except (json.JSONDecodeError, subprocess.TimeoutExpired) as e:
            last_err = f"{type(e).__name__}: {e}"[:500]
        log(f"   ! попытка {attempt + 1} не удалась: {last_err}")
        time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"claude -p failed: {last_err}")


def cached(path: Path, fn):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    data = fn()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(path)
    return data


def write_compact(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


# ---------------------------------------------------------------- vocab

_ARTICLE = re.compile(r"^(el|la|los|las|un|una|unos|unas)\s+(?=\S)")


def norm_lemma(s: str, pos: str) -> str:
    s = unicodedata.normalize("NFC", s).strip().lower()
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"\s*\(.*?\)\s*", " ", s).strip()  # «(se)» и пояснения в скобках
    if pos == "noun":
        s = _ARTICLE.sub("", s)
    s = s.strip(" .,;:")
    return {"sólo": "solo", "éste": "este", "ésta": "esta", "ése": "ese", "aquél": "aquel"}.get(s, s)


def lemma_key(s: str) -> str:
    """Ключ для поиска повторов: без ¿?¡! и лишних пробелов (ударения сохраняем: si / sí)."""
    return re.sub(r"[¿?¡!]", "", s).strip()


def theme_counts(level: str) -> dict[str, int]:
    li = LEVELS.index(level)
    total_w = sum(t[2][li] for t in THEMES)
    return {t[0]: max(8, round(VOCAB_TARGET[level] * OVERGEN * t[2][li] / total_w)) for t in THEMES}


def gen_vocab_theme(level: str, theme: tuple, n: int, taken: list[str], args) -> dict:
    tid, title, _, scope = theme
    others = ", ".join(f"{t[0]} ({t[3].split(':')[0].split(',')[0]})" for t in THEMES if t[0] != tid)
    system = (
        "You are a lexicographer building graded vocabulary lists of Peninsular Spanish for a native Russian "
        "speaker, aligned with CEFR levels and the level inventories of the Plan Curricular del Instituto "
        "Cervantes (nociones específicas). Produce YOUR OWN list (do not reproduce PCIC wording). "
        f"{SPAIN_RULES}\n\n"
        "Rules for each entry:\n"
        "- l: lemma in lowercase without article; verbs in the infinitive (pronominal verbs as 'llamarse' "
        "only when the pronominal form is the main meaning); adjectives in masculine singular; nouns in singular "
        "(unless normally plural, e.g. 'gafas'). Fixed multi-word expressions are allowed with pos 'expr' "
        "(e.g. 'por favor', 'de acuerdo', 'tener ganas de'). No proper nouns.\n"
        "- ru: short Russian translation (1-4 words; the main meaning(s) relevant at this level, separated by ', ').\n"
        "- pos: one of noun, verb, adj, adv, prep, pron, det, conj, num, interj, expr.\n"
        "- g: 'm' or 'f' for nouns (for nouns of common gender like 'estudiante' use 'm'); '' for others.\n"
        "Choose by FREQUENCY and usefulness: the words a learner at this level needs most in everyday "
        "conversation in Spain. No duplicates. Do not include words that belong to lower levels "
        "(they are listed as already known)."
    )
    prompt = (
        f"Level: {level}. Theme: «{title}» (id {tid}). Theme scope: {scope}.\n"
        f"Other themes exist (words that clearly belong there go there, not here): {others}.\n\n"
        f"Give about {n} NEW words/expressions of level {level} for this theme, the most frequent and useful "
        f"first. {'These are the very first words a beginner learns: only the most frequent everyday words.' if level == 'A1' else ''}\n"
    )
    if taken:
        prompt += (
            f"\nALREADY KNOWN from lower levels — do NOT repeat any of these:\n{', '.join(taken)}\n"
        )
    return claude_json(prompt, system, VOCAB_SCHEMA, args.model, args.vocab_effort)


def build_vocab_level(level: str, lower: dict[str, str], args) -> dict:
    """lower: lemma_key → уровень, для всех слов более низких уровней."""
    counts = theme_counts(level)
    taken = sorted(lower)

    def do(theme):
        path = CACHE / "vocab" / level / f"{theme[0]}.json"
        res = cached(path, lambda: gen_vocab_theme(level, theme, counts[theme[0]], taken, args))
        log(f"   ✓ vocab {level}/{theme[0]}: {len(res['words'])}")
        return res

    with cf.ThreadPoolExecutor(args.jobs) as ex:
        raw = list(ex.map(do, THEMES))

    lists = [list(res["words"]) for res in raw]
    themes, _ = dedupe_level(lists, lower, set())

    # Проверка списка уровня целиком: недостающие частотные слова и ошибки.
    review = cached(CACHE / "vocab" / level / "_review.json",
                    lambda: gen_vocab_review(level, themes, lower, args))
    ids = [t[0] for t in THEMES]
    for w in review["add"]:
        lists[ids.index(w["theme"]) if w["theme"] in ids else 0].append(w)
    remove = {lemma_key(norm_lemma(r, "")) for r in review["remove"]}
    themes, (skipped_lower, skipped_dup, removed) = dedupe_level(lists, lower, remove)
    log(f"   {level}: проверка +{len(review['add'])} / −{removed}; убрано повторов с нижними уровнями "
        f"{skipped_lower}, внутри уровня {skipped_dup}")
    return {"level": level, "themes": themes}


def dedupe_level(lists: list[list[dict]], lower: dict[str, str], remove: set[str]):
    seen: set[str] = set()
    themes = []
    skipped_lower = skipped_dup = removed = 0
    for theme, items in zip(THEMES, lists):
        words = []
        for w in items:
            pos = w.get("pos", "").strip()
            lemma = norm_lemma(w.get("l", ""), pos)
            ru = re.sub(r"\s+", " ", w.get("ru", "")).strip()
            if not lemma or not ru:
                continue
            key = lemma_key(lemma)
            if key in remove:
                removed += 1
                continue
            if key in lower:
                skipped_lower += 1
                continue
            if key in seen:
                skipped_dup += 1
                continue
            seen.add(key)
            g = w.get("g", "") if pos == "noun" else ""
            if pos == "noun" and g not in ("m", "f"):
                g = ""
            e = {"l": lemma, "ru": ru, "pos": pos}
            if g:
                e["g"] = g
            words.append(e)
        themes.append({"id": theme[0], "title": theme[1], "words": words})
    return themes, (skipped_lower, skipped_dup, removed)


REVIEW_SCHEMA = {
    "type": "object",
    "properties": {
        "add": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {**VOCAB_SCHEMA["properties"]["words"]["items"]["properties"],
                               "theme": {"type": "string", "enum": [t[0] for t in THEMES]}},
                "required": ["l", "ru", "pos", "g", "theme"],
                "additionalProperties": False,
            },
        },
        "remove": {"type": "array", "items": {"type": "string"},
                   "description": "lemmas (exactly as listed) to delete from this level"},
    },
    "required": ["add", "remove"],
    "additionalProperties": False,
}


def gen_vocab_review(level: str, themes: list[dict], lower: dict[str, str], args) -> dict:
    n_add = {"A1": 200, "A2": 150, "B1": 150, "B2": 150}[level]
    system = (
        "You are a lexicographer reviewing a graded vocabulary list of Peninsular Spanish for a native Russian "
        f"speaker (CEFR, aligned with the level inventories of the Plan Curricular del Instituto Cervantes). {SPAIN_RULES}"
    )
    listing = "\n".join(f"[{t['id']}] " + ", ".join(w["l"] for w in t["words"]) for t in themes)
    prompt = (
        f"Below is the draft list of NEW words for level {level}, grouped by theme"
        + (", and the words already taught at lower levels" if lower else "") + ".\n\n"
        "Task 1 (add): find frequent, useful words and fixed expressions that a learner should know at level "
        f"{level} but that are MISSING from both lists"
        + (" — compare against the ~1000 most frequent words of everyday spoken Spanish in Spain (core nouns "
           "like vida, persona, cosa; core verbs like esperar, llamar, llevar, pasar; basic adjectives like joven, "
           "nuevo; pronouns and connectors; question words…). This is the FIRST level, so every very frequent "
           "everyday word belongs here" if level == "A1" else
           f" — words that are typical for {level} in frequency and in textbook syllabi") +
        f". Up to {n_add} words, the most important first; assign each to the best theme id. Same entry format as "
        "the list: l lowercase lemma (infinitive / masculine singular / no article), ru short Russian, pos, g.\n"
        "Task 2 (remove): lemmas from the draft that are wrong or should not be here: incorrect Spanish (e.g. "
        "'también no'), Latin American instead of Peninsular words, misspellings or outdated accents ('sólo'), "
        "feminine or plural forms instead of lemmas, proper nouns, duplicates/variants of a lower-level word, "
        f"or words clearly too rare/advanced for {level}"
        + (" (A1 must be only basic everyday vocabulary)" if level == "A1" else "") +
        ". Write them exactly as they appear in the draft.\n\n"
        f"DRAFT {level}:\n{listing}\n"
    )
    if lower:
        prompt += f"\nALREADY TAUGHT at lower levels (do not add these):\n{', '.join(sorted(lower))}\n"
    return claude_json(prompt, system, REVIEW_SCHEMA, args.model, "medium")


def run_vocab(levels: list[str], args) -> dict[str, dict]:
    """Строит словарь всех уровней до максимального запрошенного (нижние нужны для исключений)."""
    top = max(LEVELS.index(l) for l in levels)
    lower: dict[str, str] = {}
    result = {}
    for level in LEVELS[: top + 1]:
        log(f"▶ Словарь {level} (цель {VOCAB_TARGET[level]} новых слов)…")
        voc = build_vocab_level(level, lower, args)
        result[level] = voc
        for t in voc["themes"]:
            for w in t["words"]:
                lower[lemma_key(w["l"])] = level
        if level in levels:
            write_compact(ASSETS / f"vocab_{level}.json", voc)
        n = sum(len(t["words"]) for t in voc["themes"])
        log(f"   {level}: {n} слов — " + ", ".join(f"{t['id']} {len(t['words'])}" for t in voc["themes"]))
    return result


def load_vocab(level: str) -> dict | None:
    p = ASSETS / f"vocab_{level}.json"
    if not p.exists():
        return None
    d = json.loads(p.read_text(encoding="utf-8"))
    return d if sum(len(t["words"]) for t in d["themes"]) > 50 else None  # не заглушка


# ---------------------------------------------------------------- grammar plan

PLAN_SYSTEM = (
    "You are an experienced methodologist of Spanish as a foreign language (ELE) designing a grammar course "
    "for a native Russian speaker learning Peninsular Spanish. Align the course with the CEFR levels and the "
    "grammar inventory of the Plan Curricular del Instituto Cervantes (PCIC) and with standard Spain-published "
    "ELE syllabi, but write your OWN topic list and wording."
)

PLAN_PROMPT = """Design the grammar course for levels A1, A2, B1 and B2.

Requirements:
- Number of topics: A1 18-22, A2 18-22, B1 18-22, B2 15-20.
- Within each level, order topics in a sensible LEARNING order (each topic builds on previous ones).
- Each topic is one teachable unit (one lesson of explanation + 30-40 exercises). Not too broad
  (don't put all past tenses in one topic) and not too narrow.
- NO duplicates across levels. A later level may have a separate topic for advanced uses of something
  introduced earlier (e.g. "b1-ser-estar-adjetivos" — adjectives that change meaning with ser/estar),
  but its scope must clearly differ.
- Cover the PCIC-style inventory: noun gender/number, articles, adjectives and agreement, demonstratives,
  possessives, personal pronouns (subject, direct/indirect object, combined, reflexive, with prepositions),
  ser/estar/hay, presente (regular, stem-changing, irregular yo), reflexive verbs, gustar-type verbs,
  interrogatives, prepositions, quantifiers, comparisons, perífrasis (ir a, tener que, hay que, acabar de,
  volver a, estar + gerundio, llevar + gerundio, dejar de…), pretérito perfecto / indefinido / imperfecto /
  pluscuamperfecto and their contrast, futuro and condicional (forms and uses, probability), imperativo
  (affirmative, negative, with pronouns), presente de subjuntivo (forms; after deseo/voluntad, emoción,
  duda, valoración, cuando + futuro, para que, relative clauses), imperfecto de subjuntivo, perfecto and
  pluscuamperfecto de subjuntivo, conditional sentences (real, potential, unreal), relative pronouns,
  estilo indirecto, pasiva and se impersonal/pasiva refleja, por/para, connectors (causales, consecutivas,
  concesivas, temporales), indefinite pronouns and negation, numbers/time/dates where grammatical, etc.
  Distribute by level the way PCIC and Spanish ELE textbooks do (Peninsular norm: pretérito perfecto for
  today/this week; vosotros forms).
- id: lowercase latin, hyphens, prefixed with the level: "a1-ser-estar", "a2-preterito-indefinido",
  "b1-subjuntivo-deseos". Stable and descriptive.
- title: Russian title (clear for a beginner, may include Spanish terms, e.g. "Ser и estar: два глагола «быть»").
- titleEs: Spanish title as in textbooks.
- scope: what exactly this topic covers at this level and what it leaves to other topics (English).
- related: ids of 1-4 closely related topics from the whole plan (same or other levels).
"""


def build_plan(args) -> dict:
    if PLAN_FILE.exists():
        return json.loads(PLAN_FILE.read_text(encoding="utf-8"))
    log("▶ План грамматики (все уровни)…")
    plan = cached(CACHE / "plan.json",
                  lambda: claude_json(PLAN_PROMPT, PLAN_SYSTEM, PLAN_SCHEMA, args.plan_model, "high"))
    plan = clean_plan(plan)
    PLAN_FILE.write_text(json.dumps(plan, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    log(f"   сохранён {PLAN_FILE.relative_to(ROOT)} — проверьте его перед генерацией тем")
    return plan


def clean_plan(plan: dict) -> dict:
    by_level = {lv["level"]: lv for lv in plan["levels"]}
    ids: set[str] = set()
    out = {"levels": []}
    for level in LEVELS:
        lv = by_level.get(level)
        if not lv:
            raise SystemExit(f"в плане нет уровня {level}")
        topics = []
        for t in lv["topics"]:
            tid = re.sub(r"[^a-z0-9-]+", "-", t["id"].lower()).strip("-")
            if not tid.startswith(level.lower() + "-"):
                tid = f"{level.lower()}-{tid}"
            if tid in ids:
                log(f"   ! повтор id в плане: {tid} — пропущен")
                continue
            ids.add(tid)
            topics.append({**t, "id": tid})
        lo, hi = PLAN_RANGE[level]
        if not lo <= len(topics) <= hi:
            log(f"   ! {level}: {len(topics)} тем (ожидалось {lo}-{hi})")
        out["levels"].append({"level": level, "topics": topics})
    for lv in out["levels"]:
        for t in lv["topics"]:
            t["related"] = [r for r in dict.fromkeys(t["related"]) if r in ids and r != t["id"]]
    return out


def plan_topics(plan: dict, level: str) -> list[dict]:
    return next(lv["topics"] for lv in plan["levels"] if lv["level"] == level)


# ---------------------------------------------------------------- grammar topics

TOPIC_SYSTEM = f"""You are an excellent teacher of Spanish as a foreign language who is a native Russian speaker. You write a grammar course for an adult native Russian speaker learning Peninsular Spanish (currently around A1, studying alone with a phone app). The course follows CEFR levels and the PCIC grammar inventory, but all explanations and examples are YOUR OWN. {SPAIN_RULES}

You write ONE complete topic. All explanations are in RUSSIAN; Spanish terms and examples in Spanish.

THEORY
- title (Russian), titleEs (Spanish), summary: 1-2 Russian sentences — what the topic is and why it matters.
- sections: 3-6 sections. Each section text is a DETAILED, clear explanation (typically 120-300 words), written
  like a good teacher talking to the learner: rules, when to use, forms, exceptions, small inline examples
  (Spanish in the text with Russian meaning). Compare with Russian where it helps (what Russian does
  differently, what has no Russian equivalent, false friends of structure). Paragraphs separated by a blank
  line ("\\n\\n"); **bold** allowed for key words; simple lists with "• " at line start are allowed.
  No markdown headings inside text.
- table: where useful (conjugations, endings, pronoun forms, contrasts) give a table; FIRST ROW IS THE HEADER;
  all rows have the same number of cells; keep cells short. Use [] when no table is needed.
  Conjugation tables list all six persons including vosotros (yo, tú, él/ella/usted, nosotros/as, vosotros/as,
  ellos/ellas/ustedes).
- examples: 6-10 natural example sentences (es, ru, note = short Russian comment on what to notice, may be "").
- mistakes: 3-6 typical mistakes of RUSSIAN speakers on this topic (wrong, right, why — in Russian).

EXERCISES — 34-38 exercises, a varied bank from easy to harder, all on THIS topic:
- ~13 "choice": sentence contains exactly one gap "___"; options = 3-4 short options INCLUDING the correct
  one; exactly one option is correct in this context; answer = the correct option exactly as written in options.
  Distractors should be typical errors (wrong person, wrong tense, ser vs estar…). prompt may give a hint like
  "(ser / estar)" or "".
- ~10 "input": sentence with exactly one "___"; prompt says what to write (usually the base form in brackets,
  e.g. "(tener)", or a Russian hint); answer = the word(s) that fill the gap; accept = other fully correct
  variants (e.g. with/without subject pronoun, alternative correct forms), may be [].
- ~6 "order": answer = a full correct sentence of 4-10 words (with punctuation) that the app will shuffle
  word by word; sentence = ""; prompt = "" or a Russian hint; the word order must be unambiguous
  (avoid sentences where several orders are equally correct, or list the other correct orders in accept).
- ~6 "transform": prompt = instruction in Russian (e.g. "Поставьте в отрицательную форму", "Замените
  существительное местоимением"); sentence = source Spanish sentence; answer = the transformed sentence;
  accept = other correct variants.
For EVERY exercise: ru = Russian translation of the complete correct sentence; explain = short Russian
explanation of why the answer is right (1-2 sentences); lemmas = lemmas (dictionary forms, lowercase) of the
content words in the sentence (nouns, verbs, adjectives, adverbs; not articles/prepositions).
Use vocabulary of the topic's level or lower (a sample list is provided), everyday situations in Spain,
varied subjects (including vosotros sometimes). Sentences must be natural, grammatically perfect Peninsular
Spanish. Do not repeat the same sentence. Double-check every answer: it must be the ONLY correct option, and
the sentence with the answer inserted into "___" must be fully grammatical (e.g. no object pronoun that
duplicates a noun object still present in the sentence).

related: ids of related topics (choose from the plan ids provided)."""


def vocab_sample(level: str, n_same: int = 300, n_lower: int = 200) -> str:
    li = LEVELS.index(level)
    same, lower = [], []
    for i, lv in enumerate(LEVELS[: li + 1]):
        voc = load_vocab(lv) or {"themes": []}
        lemmas = [w["l"] for t in voc["themes"] for w in t["words"] if w.get("pos") != "num"]
        (same if i == li else lower).extend(lemmas)
    rng = random.Random(f"sample:{level}")
    if len(same) > n_same:
        same = rng.sample(same, n_same)
    if len(lower) > n_lower:
        lower = rng.sample(lower, n_lower)
    return ", ".join(lower + same)


def topic_context(plan: dict, level: str, topic: dict) -> str:
    li = LEVELS.index(level)
    lines = []
    for lv in plan["levels"][: li + 1]:
        for t in lv["topics"]:
            mark = "  <== THIS TOPIC" if t["id"] == topic["id"] else ""
            lines.append(f"{t['id']}: {t['titleEs']}{mark}")
    return "\n".join(lines)


def gen_topic(level: str, topic: dict, plan: dict, args) -> dict:
    prompt = (
        f"Level: {level}.\nTopic id: {topic['id']}\nTitle (ru): {topic['title']}\nTitle (es): {topic['titleEs']}\n"
        f"Scope: {topic['scope']}\n\n"
        f"Course plan up to this level (topics before this one are known to the learner; do not re-teach "
        f"topics that come later, and keep exercises focused on this topic):\n{topic_context(plan, level, topic)}\n\n"
        f"Vocabulary sample of levels ≤ {level} (prefer these and other very common words):\n"
        f"{vocab_sample(level)}\n"
    )
    return claude_json(prompt, TOPIC_SYSTEM, TOPIC_SCHEMA, args.model, args.effort)


def gen_more_exercises(level: str, topic: dict, have: list[dict], need: int, args) -> dict:
    existing = "\n".join(f"- {e['sentence'] or e['answer']}" for e in have)
    prompt = (
        f"Level: {level}. Topic: {topic['id']} — {topic['titleEs']} ({topic['title']}).\nScope: {topic['scope']}\n\n"
        f"The topic already has these exercises (do NOT repeat them):\n{existing}\n\n"
        f"Write {need} MORE new exercises for this topic, mixing the types (mostly choice and input, some order "
        f"and transform), following exactly the exercise rules.\n\n"
        f"Vocabulary sample of levels ≤ {level}:\n{vocab_sample(level)}\n"
        "Return only exercises."
    )
    return claude_json(prompt, TOPIC_SYSTEM, EXERCISES_SCHEMA, args.model, args.effort)


_GAP = re.compile(r"_{2,}")


def _s(x) -> str:
    return re.sub(r"[ \t]+", " ", str(x or "")).strip()


def _loose(x: str) -> str:
    return re.sub(r"[^\w\s]", "", x.lower()).strip()


def clean_exercise(e: dict, rng: random.Random) -> tuple[dict | None, str]:
    t = e.get("type")
    sentence = _GAP.sub("___", _s(e.get("sentence")))
    answer = _s(e.get("answer"))
    prompt = _s(e.get("prompt"))
    ru = _s(e.get("ru"))
    explain = _s(e.get("explain"))
    accept, seen_acc = [], {_loose(answer)}
    for a in (_s(a) for a in e.get("accept") or []):
        if a and _loose(a) not in seen_acc:  # без вариантов, отличающихся лишь регистром/пунктуацией
            seen_acc.add(_loose(a))
            accept.append(a)
    lemmas = [l for l in dict.fromkeys(_s(l).lower() for l in e.get("lemmas") or []) if l]
    if not answer or not ru or not explain:
        return None, "пустые поля"
    out = {"type": t, "sentence": sentence, "answer": answer}
    if t in ("choice", "input"):
        if sentence.count("___") != 1:
            return None, f"{t}: нет ровно одного ___"
    if t == "choice":
        opts = [o for o in dict.fromkeys(_s(o) for o in e.get("options") or []) if o]
        if answer not in opts:
            return None, "choice: ответа нет среди вариантов"
        if not 3 <= len(opts) <= 4:
            return None, f"choice: {len(opts)} вариантов"
        rng.shuffle(opts)
        out["options"] = opts
    elif t == "input":
        pass
    elif t == "order":
        n = len(answer.split())
        if not 3 <= n <= 12:
            return None, f"order: {n} слов"
        out["sentence"] = ""
    elif t == "transform":
        if not sentence or not prompt:
            return None, "transform: нет исходного предложения или задания"
        if sentence == answer:
            return None, "transform: ответ = исходное"
    else:
        return None, f"тип {t}"
    if accept:
        out["accept"] = accept
    if prompt:
        out["prompt"] = prompt
    out["ru"] = ru
    out["explain"] = explain
    if lemmas:
        out["lemmas"] = lemmas
    return out, ""


def clean_table(table) -> list[list[str]]:
    rows = [[_s(c) for c in r] for r in (table or []) if any(_s(c) for c in r)]
    if len(rows) < 2:
        return []
    width = max(len(r) for r in rows)
    return [r + [""] * (width - len(r)) for r in rows]


def assemble_topic(level: str, ptopic: dict, raw: dict, extra: list[dict], plan_ids: set[str]) -> tuple[dict, dict]:
    rng = random.Random(ptopic["id"])
    stats: dict[str, int] = {}
    exercises, seen = [], set()
    for e in raw["exercises"] + extra:
        ce, why = clean_exercise(e, rng)
        if ce is None:
            stats[why] = stats.get(why, 0) + 1
            continue
        key = (ce["sentence"].lower(), ce["answer"].lower())
        if key in seen:
            stats["повтор"] = stats.get("повтор", 0) + 1
            continue
        seen.add(key)
        exercises.append(ce)
    exercises = exercises[:MAX_EXERCISES]
    sections = []
    for s in raw["sections"]:
        if not _s(s.get("title")) or not (s.get("text") or "").strip():
            continue
        sec = {"title": _s(s["title"]), "text": s["text"].strip()}
        tbl = clean_table(s.get("table"))
        if tbl:
            sec["table"] = tbl
        sections.append(sec)
    examples = []
    for x in raw["examples"]:
        if _s(x.get("es")) and _s(x.get("ru")):
            ex = {"es": _s(x["es"]), "ru": _s(x["ru"])}
            if _s(x.get("note")):
                ex["note"] = _s(x["note"])
            examples.append(ex)
    mistakes = [{"wrong": _s(m["wrong"]), "right": _s(m["right"]), "why": _s(m["why"])}
                for m in raw["mistakes"] if _s(m.get("wrong")) and _s(m.get("right")) and _s(m.get("why"))]
    related = [r for r in dict.fromkeys(ptopic.get("related", []) + raw.get("related", []))
               if r in plan_ids and r != ptopic["id"]]
    topic = {
        "id": ptopic["id"],
        "title": _s(raw.get("title")) or ptopic["title"],
        "titleEs": _s(raw.get("titleEs")) or ptopic["titleEs"],
        "summary": _s(raw.get("summary")),
        "sections": sections,
        "examples": examples,
        "mistakes": mistakes,
        "exercises": exercises,
        "related": related,
    }
    return topic, stats


def topic_problems(t: dict) -> list[str]:
    p = []
    if not t["summary"]:
        p.append("нет summary")
    if len(t["sections"]) < 3:
        p.append(f"{len(t['sections'])} разделов")
    if sum(len(s["text"]) for s in t["sections"]) < 1500:
        p.append("слишком короткое объяснение")
    if len(t["examples"]) < 5:
        p.append(f"{len(t['examples'])} примеров")
    if len(t["mistakes"]) < 3:
        p.append(f"{len(t['mistakes'])} ошибок")
    return p


def build_topic(level: str, ptopic: dict, plan: dict, plan_ids: set[str], args) -> tuple[dict, dict]:
    tid = ptopic["id"]
    path = CACHE / "grammar" / level / f"{tid}.json"
    raw = None
    for attempt in range(2):
        raw = cached(path, lambda: gen_topic(level, ptopic, plan, args))
        topic, _ = assemble_topic(level, ptopic, raw, [], plan_ids)
        probs = topic_problems(topic)
        if not probs:
            break
        log(f"   ! {tid}: {', '.join(probs)}" + (" — пересоздаю" if attempt == 0 else ""))
        if attempt == 0:
            path.unlink()
    extra: list[dict] = []
    for r in range(3):
        topic, stats = assemble_topic(level, ptopic, raw, extra, plan_ids)
        if len(topic["exercises"]) >= MIN_EXERCISES:
            break
        need = max(8, 36 - len(topic["exercises"]))
        more = cached(CACHE / "grammar" / level / f"{tid}.more{r}.json",
                      lambda: gen_more_exercises(level, ptopic, topic["exercises"], need, args))
        extra += more["exercises"]
    log(f"   ✓ {tid}: {len(topic['sections'])} разд., {len(topic['examples'])} прим., "
        f"{len(topic['exercises'])} упр." + (f" (отброшено: {stats})" if stats else ""))
    return topic, stats


def run_grammar(levels: list[str], plan: dict, args) -> None:
    plan_ids = {t["id"] for lv in plan["levels"] for t in lv["topics"]}
    for level in levels:
        ptopics = plan_topics(plan, level)
        log(f"▶ Грамматика {level}: {len(ptopics)} тем (параллельно: {args.jobs})…")
        if not load_vocab(level):
            log(f"   ! нет словаря {level} — упражнения без подсказки словаря")
        with cf.ThreadPoolExecutor(args.jobs) as ex:
            results = list(ex.map(lambda t: build_topic(level, t, plan, plan_ids, args), ptopics))
        topics = [t for t, _ in results]
        write_compact(ASSETS / f"grammar_{level}.json", {"level": level, "topics": topics})
        n_ex = sum(len(t["exercises"]) for t in topics)
        log(f"   {level}: {len(topics)} тем, {n_ex} упражнений → grammar_{level}.json")


# ---------------------------------------------------------------- report

def stats() -> None:
    vocab_levels: dict[str, str] = {}
    for level in LEVELS:
        v = ASSETS / f"vocab_{level}.json"
        if v.exists():
            d = json.loads(v.read_text(encoding="utf-8"))
            n = sum(len(t["words"]) for t in d["themes"])
            print(f"vocab {level}: {n} слов — " + ", ".join(f"{t['id']} {len(t['words'])}" for t in d["themes"]))
            for t in d["themes"]:
                for w in t["words"]:
                    vocab_levels.setdefault(w["l"], level)
    for level in LEVELS:
        g = ASSETS / f"grammar_{level}.json"
        if not g.exists():
            continue
        d = json.loads(g.read_text(encoding="utf-8"))
        n_ex = sum(len(t["exercises"]) for t in d["topics"])
        types: dict[str, int] = {}
        known = total = 0
        li = LEVELS.index(level)
        for t in d["topics"]:
            for e in t["exercises"]:
                types[e["type"]] = types.get(e["type"], 0) + 1
                for l in e.get("lemmas", []):
                    total += 1
                    lv = vocab_levels.get(l)
                    known += lv is not None and LEVELS.index(lv) <= li
        cover = f", леммы в словаре ≤{level}: {100 * known // max(1, total)}%" if total else ""
        print(f"grammar {level}: {len(d['topics'])} тем, {n_ex} упражнений {types}{cover}")


def show(tid: str) -> None:
    for level in LEVELS:
        g = ASSETS / f"grammar_{level}.json"
        if not g.exists():
            continue
        for t in json.loads(g.read_text(encoding="utf-8"))["topics"]:
            if t["id"] == tid:
                print(json.dumps(t, ensure_ascii=False, indent=1))
                return
    raise SystemExit(f"тема {tid} не найдена")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--levels", default=",".join(LEVELS), help="уровни через запятую, напр. A1,A2")
    ap.add_argument("--steps", default="vocab,plan,grammar", help="какие шаги: vocab,plan,grammar")
    ap.add_argument("--model", default="sonnet", help="модель claude для словаря и тем")
    ap.add_argument("--plan-model", default="opus", help="модель для плана грамматики (один запрос)")
    ap.add_argument("--effort", default="medium", help="effort для тем грамматики")
    ap.add_argument("--vocab-effort", default="low", help="effort для словаря")
    ap.add_argument("--jobs", type=int, default=4, help="параллельных запросов claude")
    ap.add_argument("--stats", action="store_true", help="только статистика по готовым файлам")
    ap.add_argument("--show", metavar="TOPIC_ID", help="напечатать готовую тему")
    args = ap.parse_args()

    if args.stats:
        stats()
        return
    if args.show:
        show(args.show)
        return

    levels = [l.strip().upper() for l in args.levels.split(",") if l.strip()]
    for l in levels:
        if l not in LEVELS:
            ap.error(f"неизвестный уровень {l}")
    levels.sort(key=LEVELS.index)
    steps = {s.strip() for s in args.steps.split(",")}

    if "vocab" in steps:
        run_vocab(levels, args)
    plan = None
    if "plan" in steps or "grammar" in steps:
        plan = build_plan(args)
        for lv in plan["levels"]:
            print(f"   план {lv['level']}: {len(lv['topics'])} тем")
    if "grammar" in steps:
        run_grammar(levels, plan, args)
    stats()


if __name__ == "__main__":
    main()
