#!/usr/bin/env python3
"""Готовит книгу для приложения Lectura с помощью Claude Code (`claude -p`).

Режимы:
  1) Адаптация существующего текста (txt / epub):
       python3 pipeline/prepare_book.py --source book.txt --title "Marianela" --author "Galdós"
  2) Оригинальная история на тему:
       python3 pipeline/prepare_book.py --topic "кот, который живёт в Мадриде" --chapters 5
  3) Только обогащение готовой книги (файл перезаписывается на месте):
       python3 pipeline/prepare_book.py --enrich books/madrid.json

В конце режимов 1 и 2 тоже идёт обогащение (отключается --no-enrich): по одному запросу
на главу — кто говорит каждую реплику (Sentence.sp, Book.characters), разговорные фразы
главы (phrases), вопросы на понимание (questions) и «выбери уместный ответ» (replies).

Результат: books/<id>.json — формат, который читает приложение.
С флагом --push файл сразу копируется на телефон через adb.

Промежуточные результаты кэшируются в books/.work/<id>/, поэтому прерванный
запуск можно просто повторить — готовые шаги не пересчитываются.
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import html
import json
import random
import re
import subprocess
import sys
import time
import unicodedata
import zipfile
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOOKS_DIR = ROOT / "books"
ANDROID_PKG = "app.lectura"
ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"

LEVEL_GUIDE = {
    "A0": (
        "Absolute beginner. Present tense only. Sentences of 3-7 words. "
        "Only the ~400 most frequent Spanish words plus a few concrete nouns from the story. "
        "Repeat key words often. Very simple, concrete actions and descriptions."
    ),
    "A1": (
        "Beginner A1. Mostly present tense; 'ir a + infinitivo' for future; "
        "pretérito perfecto (he visto) allowed occasionally. Sentences of 5-12 words. "
        "Mainly the ~1000 most frequent words. Repeat key vocabulary. No subjunctive."
    ),
    "A2": (
        "Elementary A2. Present, pretérito perfecto, pretérito indefinido and imperfecto. "
        "Sentences up to ~15 words. ~2000 most frequent words. Simple subordinate clauses."
    ),
    "B1": (
        "Intermediate B1. All indicative tenses, simple present subjunctive in common patterns. "
        "Natural sentences up to ~20 words. Everyday and literary vocabulary, avoid rare archaisms."
    ),
    "B2": (
        "Upper-intermediate B2. Near-original style, simplified only where the original is "
        "archaic or very dense. All tenses and moods."
    ),
}

CONVERSATIONAL = (
    "FOCUS ON SPOKEN, CONVERSATIONAL SPANISH: tell the story mostly through dialogue "
    "(at least half of the sentences should be lines of dialogue). Show how people really "
    "ask for things, ask questions and answer them, greet, apologise, agree/disagree, make plans, "
    "order in a bar, buy things, ask for directions, talk about feelings and opinions. "
    "Use natural high-frequency conversational chunks (¿Qué tal?, ¿Me pones…?, ¿Te apetece…?, "
    "Vale, Venga, ¿En serio?, Me encanta, ¿Cuánto cuesta?, ¿Puedes…?) and repeat useful patterns "
    "several times in slightly different forms. Format dialogue Spanish-style: each line starts with "
    "an em dash (—), e.g. —¿Qué quieres? —pregunta Ana. Put each speaker's line in its own paragraph."
)

SPAIN_RULES = (
    "Use Peninsular (Spain) Spanish: vosotros forms, Spain vocabulary "
    "(coche, ordenador, móvil, zumo, patata, coger), Spain spelling conventions."
)

ADAPT_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string", "description": "Chapter title in Spanish (simple)"},
        "title_ru": {"type": "string", "description": "Chapter title in Russian"},
        "paragraphs": {
            "type": "array",
            "items": {"type": "array", "items": {"type": "string"}},
            "description": "Paragraphs; each paragraph is a list of sentences",
        },
    },
    "required": ["title", "title_ru", "paragraphs"],
    "additionalProperties": False,
}

STORY_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "chapters": {"type": "array", "items": ADAPT_SCHEMA},
    },
    "required": ["title", "chapters"],
    "additionalProperties": False,
}

ANNOTATE_SCHEMA = {
    "type": "object",
    "properties": {
        "sentences": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "ru": {"type": "string"},
                    "tokens": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "t": {"type": "string"},
                                "l": {"type": "string"},
                                "ru": {"type": "string"},
                                "g": {"type": "string"},
                            },
                            "required": ["t"],
                            "additionalProperties": False,
                        },
                    },
                },
                "required": ["ru", "tokens"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["sentences"],
    "additionalProperties": False,
}

ANNOTATE_SYSTEM = """You annotate Spanish (Spain) sentences for a native Russian speaker who is learning Spanish.

For EACH input sentence, in the same order, return:
- ru: a natural Russian translation of the whole sentence.
- tokens: the sentence split into tokens IN ORDER, so that the tokens reproduce the sentence exactly.
  * Words and punctuation marks are separate tokens. Each punctuation mark (. , ; : ! ? ¡ ¿ « » " — …) is its own token with ONLY the field "t".
  * Keep contractions (al, del) and verbs with attached pronouns (dímelo, levantarse) as ONE token.
  * Keep the original capitalization in "t".
  * For every WORD token also give:
      l  = dictionary form (infinitive for verbs; masculine singular for adjectives; singular for nouns; the word itself for invariable words)
      ru = Russian meaning of this word IN THIS CONTEXT, short (1-4 words)
      g  = short grammar note IN RUSSIAN. Verbs: Spanish tense name + person/number, e.g. "presente, 3 л. ед. ч.", "pretérito indefinido, 1 л. мн. ч.", "infinitivo", "gerundio". If the form is irregular, add "(неправ.)". Nouns: "сущ., м. р." / "сущ., ж. р., мн. ч.". Others: "прил.", "нареч.", "предлог", "артикль", "местоим.", "союз".
Return exactly as many sentences as were given."""

_STR = {"type": "string"}
_STR_LIST = {"type": "array", "items": _STR}

# Ответ модели для шага enrich. Варианты ответов модель отдаёт как correct + wrong,
# а перемешивает их и вычисляет индекс answer уже Python (см. build_choice).
ENRICH_SCHEMA = {
    "type": "object",
    "properties": {
        "characters": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"name": _STR, "gender": {"type": "string", "enum": ["m", "f"]}},
                "required": ["name", "gender"],
                "additionalProperties": False,
            },
        },
        "speakers": {
            "type": "array",
            "description": "One item per input sentence, in order",
            "items": {
                "type": "object",
                "properties": {
                    "i": {"type": "integer", "description": "Sentence number from the input"},
                    "sp": {"type": "string", "description": "Speaker name, or empty string for narration"},
                },
                "required": ["i", "sp"],
                "additionalProperties": False,
            },
        },
        "phrases": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"es": _STR, "ru": _STR, "note": _STR, "pattern": _STR},
                "required": ["es", "ru", "note", "pattern"],
                "additionalProperties": False,
            },
        },
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"q": _STR, "ru": _STR, "correct": _STR, "wrong": _STR_LIST},
                "required": ["q", "ru", "correct", "wrong"],
                "additionalProperties": False,
            },
        },
        "replies": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "prompt": _STR, "promptRu": _STR, "correct": _STR, "wrong": _STR_LIST, "why": _STR,
                },
                "required": ["prompt", "promptRu", "correct", "wrong", "why"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["characters", "speakers", "phrases", "questions", "replies"],
    "additionalProperties": False,
}

ENRICH_SYSTEM = """You prepare conversation-practice material for one chapter of a graded Spanish reader.
The learner is a native Russian speaker at level {level} ({level_guide}).
The goal is CONVERSATIONAL Peninsular Spanish: how people in Spain really talk.
{spain}
All Spanish you write must be Peninsular (tú/vosotros, Spain vocabulary) and at the learner's level.
All translations, notes and explanations are in RUSSIAN.

You receive the chapter's sentences, numbered, with paragraph breaks, and the list of already known
characters of the book. Return:

1. speakers — exactly one item per input sentence {{i, sp}}, in order, i = the sentence number.
   sp = who says the sentence, or "" for pure narration.
   * A sentence is dialogue if it contains a line of direct speech (Spanish dialogue starts with an
     em dash —, or is inside «» / quotes). Assign the speaker of that speech even when the sentence
     also contains a speech tag ("—Hola —dice Ana." → Ana) or starts with narration
     ("Carmen sonríe y dice: —No pasa nada." → Carmen).
   * A sentence without a dash that continues the same person's line in the same paragraph belongs
     to that person. Thoughts in «» ("piensa: «Me gusta…»") belong to the thinker.
   * Pure narration ("Por la noche, Boris duerme.") → "".
   * Use the dialogue logic (who asks, who answers, alternating turns) to work out untagged lines.
2. characters — everyone who speaks in this chapter: {{name, gender}} (gender "m" or "f").
   * If a speaker is in the known characters list, reuse EXACTLY that name spelling.
   * Use the character's name as written in the text. For an unnamed speaker (a waiter, a doctor,
     a shop assistant…) use a short capitalised Spanish role label without article, e.g.
     "Camarero", "Doctora", "Recepcionista" — and reuse a known label if it is the same person.
   * Every non-empty sp must be exactly one of the character names (known or returned here).
3. phrases — 4 to 8 of the MOST USEFUL conversational chunks / speech patterns that occur IN THIS
   CHAPTER'S TEXT, the ones a learner would want to say in real life in Spain
   (asking for things, ordering, questions, answers, reactions, agreeing, making plans, politeness).
   * Take them from the lines of DIALOGUE (direct speech), not from narration. Prefer chunks the
     learner can reuse in their own conversations; skip story-specific statements that teach no
     reusable pattern, and trivial ones like "Hola" unless the chapter has nothing better.
   * es: the chunk in Spanish, as it appears in the text (you may cut it down to the reusable part,
     keep ¿? ¡! punctuation). Do not repeat the same chunk twice.
   * ru: a natural Russian equivalent (how a Russian would say it, not word-for-word).
   * note: 1–2 short sentences in Russian: when and how it is used, register (informal/neutral/polite,
     tú vs usted), typical answer or a common alternative used in Spain.
   * pattern: a reusable template with ___ for the slot, e.g. "¿Me pones ___?", "Me duele ___",
     "¿Te apetece ___?". If no natural slot exists (fixed expressions like "No pasa nada",
     "Buenos días"), repeat the phrase itself — do not invent artificial slots.
4. questions — 3 to 5 multiple-choice comprehension questions about what happens in this chapter.
   * q: the question in simple Spanish at the learner's level; ru: its Russian translation.
   * correct: the right answer (short, Spanish); wrong: 2–3 wrong answers in Spanish of the same
     shape and length, plausible (use people, places and things from the story) but clearly false
     according to the text. Exactly one answer may be correct.
5. replies — 3 to 6 "choose the appropriate reply" exercises based on the dialogues of this chapter.
   * prompt: a line another person says TO THE LEARNER that naturally calls for a reply — a question,
     offer, request, invitation, greeting, thanks, apology or piece of news (Spanish, taken or adapted
     from the chapter's dialogue);
     promptRu: its Russian translation.
   * correct: one natural, appropriate reply a Spaniard would give in that situation.
   * wrong: exactly 2 replies that are grammatical, natural-sounding Spanish but inappropriate here.
     At least one of them must stay ON TOPIC (same situation and vocabulary) but be the wrong speech
     act or meaning, so the learner has to understand the prompt rather than spot unrelated words:
     e.g. answers a different question (¿Cuánto cuesta? → "Dos cafés, por favor"), says the waiter's
     line instead of the customer's ("¿Qué te pongo?"), uses the wrong social formula ("De nada" as a
     reply to "Perdón"), or contradicts the situation. Never gibberish, never grammar mistakes, never
     a second acceptable answer. Keep all three options about the same length.
   * why: 1–2 short sentences in Russian: why the correct reply fits and what is wrong with the others.
     The options are shuffled later, so NEVER refer to them by position ("второй", "третий вариант")
     — quote them instead («De nada» …).
   * Skip lines said to an animal, to oneself or shouted to nobody — they are not good prompts.
"""


# ---------------------------------------------------------------- source text

class _HTMLText(HTMLParser):
    BLOCK = {"p", "div", "br", "h1", "h2", "h3", "h4", "li", "section", "blockquote"}

    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "head"):
            self._skip += 1
        if tag in self.BLOCK:
            self.parts.append("\n\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style", "head"):
            self._skip -= 1
        if tag in self.BLOCK:
            self.parts.append("\n\n")

    def handle_data(self, data):
        if not self._skip:
            self.parts.append(data)


def read_epub(path: Path) -> str:
    with zipfile.ZipFile(path) as z:
        container = z.read("META-INF/container.xml").decode("utf-8", "replace")
        opf_path = re.search(r'full-path="([^"]+)"', container).group(1)
        opf = z.read(opf_path).decode("utf-8", "replace")
        base = opf_path.rsplit("/", 1)[0] + "/" if "/" in opf_path else ""
        manifest = dict(re.findall(r'<item[^>]*?id="([^"]+)"[^>]*?href="([^"]+)"', opf))
        manifest.update({i: h for h, i in re.findall(r'<item[^>]*?href="([^"]+)"[^>]*?id="([^"]+)"', opf)})
        spine = re.findall(r'<itemref[^>]*?idref="([^"]+)"', opf)
        texts = []
        for idref in spine:
            href = manifest.get(idref)
            if not href:
                continue
            try:
                raw = z.read(base + html.unescape(href)).decode("utf-8", "replace")
            except KeyError:
                continue
            p = _HTMLText()
            p.feed(raw)
            texts.append("".join(p.parts))
        return "\n\n".join(texts)


def read_source(path: Path) -> str:
    if path.suffix.lower() == ".epub":
        text = read_epub(path)
    else:
        text = path.read_text(encoding="utf-8", errors="replace")
    # Срезаем шапку и подвал Project Gutenberg, если есть.
    m = re.search(r"\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG.*?\*\*\*", text, re.S | re.I)
    if m:
        text = text[m.end():]
    m = re.search(r"\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG", text, re.I)
    if m:
        text = text[: m.start()]
    return text


def split_chunks(text: str, words_per_chunk: int) -> list[str]:
    paras = [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n", text)]
    paras = [p for p in paras if p]
    chunks, cur, n = [], [], 0
    for p in paras:
        w = len(p.split())
        if cur and n + w > words_per_chunk:
            chunks.append("\n\n".join(cur))
            cur, n = [], 0
        cur.append(p)
        n += w
    if cur:
        chunks.append("\n\n".join(cur))
    return chunks


# ---------------------------------------------------------------- claude -p

def claude_json(prompt: str, system: str, schema: dict, model: str, effort: str, retries: int = 2) -> dict:
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
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=900, cwd="/tmp")
            out = json.loads(proc.stdout)
            if out.get("is_error") or "structured_output" not in out:
                last_err = (out.get("result") or proc.stderr or "no structured_output")[:500]
            else:
                return out["structured_output"]
        except (json.JSONDecodeError, subprocess.TimeoutExpired) as e:
            last_err = f"{type(e).__name__}: {e}"[:500]
        print(f"   ! попытка {attempt + 1} не удалась: {last_err}", file=sys.stderr)
        time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"claude -p failed: {last_err}")


def cached(path: Path, fn):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    data = fn()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    return data


# ---------------------------------------------------------------- steps

def adapt_chunk(chunk: str, idx: int, total: int, level: str, title: str, args) -> dict:
    system = (
        "You turn literature into graded Spanish readers for a native Russian speaker. "
        f"Target level: {level}. {LEVEL_GUIDE[level]} {SPAIN_RULES} "
        f"{CONVERSATIONAL if args.style == 'dialog' else ''} "
        "Retell the given passage at this level: keep the plot, characters, names, key details and mood; "
        "drop digressions that are too complex. If the source is not Spanish, write the retelling in Spanish. "
        "Split the result into short paragraphs and each paragraph into sentences. "
        "Give the chapter a short Spanish title (and its Russian translation)."
    )
    prompt = (
        f"Book: {title}. Passage {idx + 1} of {total}.\n\n"
        f"<passage>\n{chunk}\n</passage>"
    )
    return claude_json(prompt, system, ADAPT_SCHEMA, args.model, args.effort)


def generate_story(topic: str, level: str, args) -> dict:
    system = (
        "You write original graded Spanish readers for a native Russian speaker. "
        f"Target level: {level}. {LEVEL_GUIDE[level]} {SPAIN_RULES} "
        f"{CONVERSATIONAL if args.style == 'dialog' else ''} "
        "Write an engaging story with a clear plot, likeable characters, dialogue and a satisfying ending. "
        "Each chapter has short paragraphs; each paragraph is a list of sentences."
    )
    prompt = (
        f"Topic / idea (may be given in Russian): {topic}\n"
        f"Chapters: {args.chapters}. Length: about {args.words} words per chapter.\n"
        "Give the story a Spanish title. Each chapter needs a Spanish title and its Russian translation."
    )
    return claude_json(prompt, system, STORY_SCHEMA, args.model, args.effort)


def annotate(sentences: list[str], args) -> list[dict]:
    numbered = "\n".join(f"{i + 1}. {s}" for i, s in enumerate(sentences))
    res = claude_json(
        f"Annotate these {len(sentences)} sentences:\n\n{numbered}",
        ANNOTATE_SYSTEM, ANNOTATE_SCHEMA, args.model, args.annotate_effort,
    )
    out = res["sentences"]
    if len(out) != len(sentences):
        raise RuntimeError(f"expected {len(sentences)} sentences, got {len(out)}")
    for src, s in zip(sentences, out):
        s["src"] = src
    return out


# ---------------------------------------------------------------- enrich

_NO_SPACE_BEFORE = {".", ",", ";", ":", "!", "?", ")", "»", "…", "”", "%", "..."}
_NO_SPACE_AFTER = {"¿", "¡", "(", "«", "“", "—", "–"}


def sentence_text(tokens: list[dict]) -> str:
    """Склеивает токены в текст так же, как приложение (Book.kt, computeSpaces)."""
    out, no_space_next, quote_open = [], True, False
    for tk in tokens:
        t = tk["t"]
        space = not no_space_next and t not in _NO_SPACE_BEFORE
        no_space_next = t in _NO_SPACE_AFTER
        if t == '"':
            if quote_open:
                space, quote_open = False, False
            else:
                quote_open, no_space_next = True, True
        out.append((" " if space else "") + t)
    return "".join(out)


def norm_key(s: str) -> str:
    """Ключ для сравнения имён/фраз: без диакритики, регистра, пунктуации и артикля."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c)).casefold()
    s = re.sub(r"[^\w\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return re.sub(r"^(el|la|los|las) ", "", s)


def chapter_sentences(ch: dict) -> list[dict]:
    return [s for p in ch["paragraphs"] for s in p["sentences"]]


def enrich_chapter(ch: dict, ci: int, total: int, level: str, book_title: str,
                   known: list[dict], args) -> dict:
    blocks, n = [], 0
    for p in ch["paragraphs"]:
        lines = []
        for s in p["sentences"]:
            n += 1
            lines.append(f"{n}. {sentence_text(s['tokens'])}")
        blocks.append("\n".join(lines))
    known_txt = ", ".join(f"{c['name']} ({c['gender'] or '?'})" for c in known) or "(none yet)"
    prompt = (
        f"Book: {book_title}. Chapter {ci + 1} of {total}: {ch['title']}.\n"
        f"Known characters: {known_txt}\n\n"
        f"The chapter has {n} sentences (blank line = new paragraph):\n\n" + "\n\n".join(blocks)
    )
    system = ENRICH_SYSTEM.format(level=level, level_guide=LEVEL_GUIDE[level], spain=SPAIN_RULES)
    res = claude_json(prompt, system, ENRICH_SCHEMA, args.model, args.enrich_effort)
    res["n"] = n
    return res


def build_choice(correct: str, wrong: list[str], max_wrong: int, rng: random.Random):
    """[correct] + wrong → перемешанные options и индекс правильного; None, если вариантов мало."""
    opts = [correct.strip()]
    for w in wrong:
        w = w.strip()
        if w and norm_key(w) not in {norm_key(o) for o in opts}:
            opts.append(w)
    opts = opts[: 1 + max_wrong]
    if not opts[0] or len(opts) < 3:
        return None
    order = list(range(len(opts)))
    rng.shuffle(order)
    options = [opts[i] for i in order]
    answer = order.index(0)
    assert 0 <= answer < len(options)
    return options, answer


def enrich_book(book: dict, work: Path, args) -> None:
    """Добавляет в книгу characters, Sentence.sp и Chapter.phrases/questions/replies (на месте)."""
    chapters = book["chapters"]
    level = book.get("level") if book.get("level") in LEVEL_GUIDE else "A1"

    registry: dict[str, dict] = {}  # norm_key(name) -> {"name", "gender"}

    def add_chars(items: list[dict]) -> None:
        for c in items:
            name = (c.get("name") or "").strip()
            if not name:
                continue
            g = c.get("gender") if c.get("gender") in ("m", "f") else ""
            k = norm_key(name)
            if k not in registry:
                registry[k] = {"name": name, "gender": g}
            elif not registry[k]["gender"] and g:
                registry[k]["gender"] = g

    existing = book.get("characters") or []
    add_chars(existing)

    def do(ci: int, known: list[dict]) -> dict:
        n = len(chapter_sentences(chapters[ci]))
        path = work / f"enrich_{ci:03d}.json"
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("n") == n:
                print(f"   ✓ глава {ci + 1}/{len(chapters)} (кэш)")
                return data
            print(f"   ! {path.name}: глава изменилась ({data.get('n')} → {n} предл.), пересчитываю")
        data = enrich_chapter(chapters[ci], ci, len(chapters), level, book["title"], known, args)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"   ✓ глава {ci + 1}/{len(chapters)}: {chapters[ci]['title']}")
        return data

    print(f"▶ Обогащаю {len(chapters)} гл.: говорящие, фразы, вопросы, ответы (параллельно: {args.jobs})…")
    results: dict[int, dict] = {}
    rest = list(range(len(chapters)))
    if not registry and rest:
        # Первая глава — отдельно, чтобы остальные знали имена персонажей.
        results[0] = do(0, [])
        add_chars(results[0]["characters"])
        rest = rest[1:]
    known = list(registry.values())
    with cf.ThreadPoolExecutor(args.jobs) as ex:
        for ci, r in zip(rest, ex.map(lambda ci: do(ci, known), rest)):
            results[ci] = r
    for ci in sorted(results):
        add_chars(results[ci]["characters"])

    used: set[str] = set()
    n_sent = n_sp = dropped = verbatim = 0
    totals = {"phrases": 0, "questions": 0, "replies": 0}
    for ci, ch in enumerate(chapters):
        r = results[ci]
        rng = random.Random(f"{book['id']}:{ci}")
        sents = chapter_sentences(ch)
        sp_by_idx = {it["i"]: (it.get("sp") or "").strip() for it in r["speakers"]}
        for i, s in enumerate(sents):
            s.pop("sp", None)
            sp = sp_by_idx.get(i + 1, "")
            n_sent += 1
            if not sp:
                continue
            c = registry.get(norm_key(sp))
            if c is None:
                dropped += 1
                continue
            s["sp"] = c["name"]
            used.add(norm_key(sp))
            n_sp += 1

        text_key = norm_key(" ".join(sentence_text(s["tokens"]) for s in sents))
        phrases, seen = [], set()
        for p in r["phrases"]:
            es = p["es"].strip()
            k = norm_key(es)
            if not es or not p["ru"].strip() or k in seen:
                continue
            seen.add(k)
            verbatim += k in text_key
            phrases.append({"es": es, "ru": p["ru"].strip(), "note": p["note"].strip(),
                            "pattern": p["pattern"].strip() or es})
        ch["phrases"] = phrases[:8]

        questions = []
        for q in r["questions"]:
            built = build_choice(q["correct"], q["wrong"], 3, rng)
            if built and q["q"].strip():
                questions.append({"q": q["q"].strip(), "ru": q["ru"].strip(),
                                  "options": built[0], "answer": built[1]})
        ch["questions"] = questions[:5]

        replies = []
        for rp in r["replies"]:
            built = build_choice(rp["correct"], rp["wrong"], 2, rng)
            if built and rp["prompt"].strip():
                replies.append({"prompt": rp["prompt"].strip(), "promptRu": rp["promptRu"].strip(),
                                "options": built[0], "answer": built[1], "why": rp["why"].strip()})
        ch["replies"] = replies[:6]
        for key in totals:
            totals[key] += len(ch[key])

    keep = {norm_key(c["name"]) for c in existing} | used
    characters = [c for k, c in registry.items() if k in keep]
    # characters — перед chapters, остальной порядок ключей сохраняем.
    rebuilt = {k: v for k, v in book.items() if k not in ("characters", "chapters")}
    rebuilt["characters"] = characters
    rebuilt["chapters"] = chapters
    book.clear()
    book.update(rebuilt)

    pct = 100 * n_sp / n_sent if n_sent else 0
    print("   Персонажи: " + (", ".join(f"{c['name']} ({c['gender']})" for c in characters) or "—"))
    print(f"   Фраз: {totals['phrases']} (дословно из текста: {verbatim}), "
          f"вопросов: {totals['questions']}, «выбери ответ»: {totals['replies']}")
    print(f"   Реплик с говорящим: {n_sp}/{n_sent} ({pct:.0f}%)"
          + (f"; отброшено неизвестных говорящих: {dropped}" if dropped else ""))


def write_book(path: Path, book: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(book, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def tokens_ok(src: str, tokens: list[dict]) -> bool:
    norm = lambda s: re.sub(r"\s+", "", s)
    return norm(src) == norm("".join(t["t"] for t in tokens))


def slugify(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")[:40] or f"book-{int(time.time())}"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--source", type=Path, help="txt или epub")
    src.add_argument("--topic", help="тема оригинальной истории")
    src.add_argument("--enrich", type=Path, metavar="PATH.json",
                     help="только шаг обогащения для готовой книги (файл перезаписывается на месте)")
    ap.add_argument("--title", help="название (для --source обязательно)")
    ap.add_argument("--author", default="")
    ap.add_argument("--level", default="A1", choices=list(LEVEL_GUIDE))
    ap.add_argument("--id", help="идентификатор книги (по умолчанию из названия)")
    ap.add_argument("--style", default="dialog", choices=["dialog", "narrative"],
                    help="dialog = упор на разговорные диалоги (по умолчанию), narrative = обычный пересказ")
    ap.add_argument("--model", default="sonnet", help="модель claude (sonnet / opus / haiku)")
    ap.add_argument("--effort", default="medium", help="effort для адаптации")
    ap.add_argument("--annotate-effort", default="low", help="effort для разметки слов")
    ap.add_argument("--enrich-effort", default="medium",
                    help="effort для обогащения (говорящие, фразы, вопросы, ответы)")
    ap.add_argument("--no-enrich", action="store_true", help="не запускать шаг обогащения")
    ap.add_argument("--chunk-words", type=int, default=900, help="размер куска оригинала в словах")
    ap.add_argument("--max-chunks", type=int, default=0, help="обработать только первые N кусков (0 = все)")
    ap.add_argument("--skip-chunks", type=int, default=0, help="пропустить первые N кусков (оглавление и т.п.)")
    ap.add_argument("--chapters", type=int, default=5, help="(--topic) число глав")
    ap.add_argument("--words", type=int, default=250, help="(--topic) слов в главе")
    ap.add_argument("--batch", type=int, default=15, help="предложений в одном запросе разметки")
    ap.add_argument("--jobs", type=int, default=4, help="параллельных запросов claude")
    ap.add_argument("--push", action="store_true", help="скопировать на телефон через adb")
    args = ap.parse_args()

    if args.source and not args.title:
        ap.error("--title обязателен вместе с --source")

    if args.enrich:
        book = json.loads(args.enrich.read_text(encoding="utf-8"))
        work = BOOKS_DIR / ".work" / book["id"]
        enrich_book(book, work, args)
        write_book(args.enrich, book)
        print(f"✅ {args.enrich} обновлён")
        if args.push:
            push(args.enrich)
        return

    book_id = args.id or slugify(args.title or args.topic)
    work = BOOKS_DIR / ".work" / book_id
    work.mkdir(parents=True, exist_ok=True)

    # 1. Текст глав
    if args.topic:
        print(f"▶ Пишу оригинальную историю ({args.level}, {args.chapters} гл.)…")
        story = cached(work / "story.json", lambda: generate_story(args.topic, args.level, args))
        title = args.title or story["title"]
        chapters = story["chapters"]
        source_note = f"Оригинальная история: {args.topic}"
    else:
        title = args.title
        chunks = split_chunks(read_source(args.source), args.chunk_words)[args.skip_chunks:]
        if args.max_chunks:
            chunks = chunks[: args.max_chunks]
        print(f"▶ Адаптирую {len(chunks)} фрагм. под {args.level} (параллельно: {args.jobs})…")

        def do_adapt(i: int) -> dict:
            r = cached(work / f"adapt_{i:03d}.json",
                       lambda: adapt_chunk(chunks[i], i, len(chunks), args.level, title, args))
            print(f"   ✓ глава {i + 1}/{len(chunks)}: {r['title']}")
            return r

        with cf.ThreadPoolExecutor(args.jobs) as ex:
            chapters = list(ex.map(do_adapt, range(len(chunks))))
        source_note = f"Адаптация: {args.source.name}"

    # 2. Разметка предложений (перевод + слова)
    jobs = []  # (chapter, paragraph, start, sentences)
    for ci, ch in enumerate(chapters):
        flat = [(pi, s.strip()) for pi, para in enumerate(ch["paragraphs"]) for s in para if s.strip()]
        for b in range(0, len(flat), args.batch):
            jobs.append((ci, b // args.batch, flat[b: b + args.batch]))
    print(f"▶ Размечаю слова: {len(jobs)} запросов…")

    def do_annotate(job):
        ci, bi, items = job
        res = cached(work / f"ann_{ci:03d}_{bi:03d}.json", lambda: annotate([s for _, s in items], args))
        print(f"   ✓ глава {ci + 1}, блок {bi + 1}")
        return job, res

    annotated: dict[int, list[tuple[int, dict]]] = {}
    with cf.ThreadPoolExecutor(args.jobs) as ex:
        for (ci, bi, items), res in ex.map(do_annotate, jobs):
            annotated.setdefault(ci, []).append((bi, list(zip([pi for pi, _ in items], res))))

    # 3. Сборка книги
    bad = 0
    out_chapters = []
    for ci, ch in enumerate(chapters):
        paragraphs: dict[int, list] = {}
        for _, pairs in sorted(annotated.get(ci, [])):
            for pi, s in pairs:
                if not tokens_ok(s["src"], s["tokens"]):
                    bad += 1
                paragraphs.setdefault(pi, []).append({"ru": s["ru"], "tokens": s["tokens"]})
        out_chapters.append({
            "title": ch["title"],
            "titleRu": ch.get("title_ru", ""),
            "paragraphs": [{"sentences": paragraphs[k]} for k in sorted(paragraphs)],
        })

    book = {
        "format": 1,
        "id": book_id,
        "title": title,
        "author": args.author,
        "level": args.level,
        "lang": "es-ES",
        "source": source_note,
        "chapters": out_chapters,
    }
    out = BOOKS_DIR / f"{book_id}.json"
    write_book(out, book)
    n_sent = sum(len(p["sentences"]) for c in out_chapters for p in c["paragraphs"])
    n_words = sum(1 for c in out_chapters for p in c["paragraphs"] for s in p["sentences"]
                  for t in s["tokens"] if "l" in t)
    print(f"✅ {out}  — {len(out_chapters)} гл., {n_sent} предл., {n_words} слов")
    if bad:
        print(f"   (в {bad} предл. токены не совпали с исходником буква в букву — не критично)")

    # 4. Обогащение: говорящие, разговорные фразы, вопросы, «выбери ответ»
    if not args.no_enrich:
        enrich_book(book, work, args)
        write_book(out, book)
        print(f"✅ {out} обогащён")

    if args.push:
        push(out)


def push(path: Path) -> None:
    dest = f"/sdcard/Android/data/{ANDROID_PKG}/files/books/{path.name}"
    adb = str(ADB) if ADB.exists() else "adb"
    subprocess.run([adb, "shell", "mkdir", "-p", dest.rsplit("/", 1)[0]], check=True)
    subprocess.run([adb, "push", str(path), dest], check=True)
    print(f"📱 Скопировано на телефон: {dest}")


if __name__ == "__main__":
    main()
