#!/usr/bin/env python3
"""Готовит книгу для приложения Lectura с помощью Claude Code (`claude -p`).

Режимы:
  1) Адаптация существующего текста (txt / epub):
       python3 pipeline/prepare_book.py --source book.txt --title "Marianela" --author "Galdós"
  2) Оригинальная история на тему:
       python3 pipeline/prepare_book.py --topic "кот, который живёт в Мадриде" --chapters 5
  3) Только обогащение готовой книги (файл перезаписывается на месте):
       python3 pipeline/prepare_book.py --enrich books/madrid.json
  4) Оригинал без изменений (общественное достояние), с разметкой, словариком и т.д.:
       python3 pipeline/prepare_book.py --original --source pipeline/sources/becquer-monte.txt \
           --title "El monte de las ánimas" --author "Gustavo Adolfo Bécquer" --level B2 \
           --work-id becquer-monte-animas --year 1861 --license "Общественное достояние: автор умер в 1870 г."
     Текст не пересказывается: только нормализуются пробелы, убираются сноски и примечания.
     Главы — по заголовкам самого произведения («# Часть», «## Раздел», римские цифры,
     CAPÍTULO …), длинные разделы режутся по ~900–1300 слов на границе абзацев.

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

# Для оригиналов: тот же формат + пометка редких/устаревших слов (Token.r).
ANNOTATE_SCHEMA_ORIGINAL = json.loads(json.dumps(ANNOTATE_SCHEMA))
ANNOTATE_SCHEMA_ORIGINAL["properties"]["sentences"]["items"]["properties"]["tokens"]["items"]["properties"]["r"] = {
    "type": "string", "enum": ["rare", "old"],
}

ANNOTATE_ORIGINAL_EXTRA = """
These sentences come from an ORIGINAL, unadapted literary text written in {year_txt}
(author: {author}). The text is old/literary Peninsular Spanish; do not modernise it — "t" must
reproduce the author's text EXACTLY, character for character, including old spellings and accents.
- ru (sentence and word): translate faithfully in the sense the word has IN THIS OLD/LITERARY
  CONTEXT (e.g. "aposento" = "покои, комната", "mozo" = "юноша/парень", "dueña" = "дуэнья"),
  in natural literary Russian.
- g: as above; also say when a form is vosotros ("…, 2 л. мн. ч. (vosotros)"), vos ("(vos, устар.)"),
  vuestra merced ("(вежл., устар.)"), enclitic pronoun on a finite verb ("díjole = le dijo, устар."),
  future subjunctive ("futuro de subjuntivo, устар.") or another old usage. -ra/-se subjunctive forms
  are normal modern Spanish: just name the tense.
- Old orthography of common words (á/é/ó/ú as one-letter words, fué, dió, vió, fuí, obscuro, oir):
  keep "t" as written, give the MODERN spelling in "l" (a, ser/ir, dar, ver, oscuro, oír) and add
  "(старая орфография: сейчас «…»)" to g. Do not mark these with "r".
- r: ONLY for words a learner should NOT memorise:
    "old"  = archaic word, form or meaning no longer used in modern Spain (vuestra merced, aposento,
             díjole, fuere, maguer, asaz, the old meaning of a still-existing word, old spellings);
    "rare" = literary, poetic or very rare word that even a B2 learner would not need
             (e.g. "fragor", "céfiro", "zarzal", "ojiva", "cendal").
  Omit "r" for every normal word, including literary-but-common ones (sombra, hermoso, sangre, susurro).
  Never put "r" on punctuation."""

GLOSSARY_SCHEMA = {
    "type": "object",
    "properties": {
        "title_ru": {"type": "string", "description": "Russian translation of the chapter title"},
        "glossary": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "l": {"type": "string", "description": "dictionary form (lemma)"},
                    "ru": {"type": "string", "description": "Russian meaning in this chapter"},
                    "note": {"type": "string", "description": "short note in Russian, may be empty"},
                    "form": {"type": "string", "description": "the form exactly as it appears in the text"},
                },
                "required": ["l", "ru", "note", "form"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["title_ru", "glossary"],
    "additionalProperties": False,
}

GLOSSARY_SYSTEM = """You prepare a pre-reading glossary for one chapter of an ORIGINAL (unadapted) Spanish
literary text, for a native Russian speaker reading it at about B1–B2 level.
Return:
- title_ru: a natural Russian translation of the chapter title (roman numerals stay as they are,
  e.g. "El monte de las ánimas, I" → "Гора духов, I").
- glossary: 8 to 15 KEY words or fixed expressions of this chapter that are beyond A2 level AND matter
  for understanding the plot or atmosphere of THIS chapter (things, places, actions the story turns on;
  archaic words that occur repeatedly). Skip words that are merely decorative or appear once in a
  description. In READING ORDER (order of first occurrence in the chapter).
  * l: dictionary form (infinitive / masculine singular / singular);
  * ru: Russian meaning as used in this chapter (1–4 words);
  * note: 0–1 short sentence in Russian when useful: old or literary usage, modern equivalent in
    Spain, cultural context (e.g. "устар.; сейчас говорят «habitación»"). Empty string if nothing to add;
  * form: the word EXACTLY as it is written in the text at its first occurrence (same accents and
    capitalisation), so it can be found by search."""

ENRICH_ORIGINAL_EXTRA = """
IMPORTANT — this chapter is an ORIGINAL, unadapted literary text (19th/early 20th century), not a
graded reader. Adjust the tasks:
- speakers/characters: as above (old texts also use «», quotes or no marks — use the logic of the text).
- phrases: 0 to 6. Only chunks from the DIALOGUE that are STILL NATURAL in modern Spain and worth
  reusing; skip archaic or bookish ones (vuestra merced, os digo que…). If nothing qualifies, return [].
  In the note, mention if the register is formal or old-fashioned.
- questions: 3 to 5, in SIMPLE MODERN Spanish (B1), about the plot of the chapter; answers short.
- replies: ONLY if the chapter has real dialogue between people. Prompts must be modern, natural
  Spanish adapted from that dialogue (not archaic). 0 to 4 items; if there is no real dialogue, return [].
"""


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


# ---------------------------------------------------------------- originals: text, chapters, sentences

_ROMAN = r"[IVXLC]+"
# Заголовок раздела: «I», «II.», «CAPÍTULO III», «Capítulo 3. Título», «PRIMERA PARTE»…
_HEADING_RE = re.compile(
    rf"^(?:(?:CAP[IÍ]TULO|Cap[ií]tulo)\s+(?:{_ROMAN}|\d+)\b.*|{_ROMAN}\.?|(?:PARTE|Parte)\s+{_ROMAN}\b.*)$"
)
_TOC_HEAD_RE = re.compile(r"^(?:[IÍ]NDICE|CONTENIDO|TABLA DE MATERIAS|CONTENTS)\.?$", re.I)


def clean_original(text: str) -> str:
    """Убирает разметку Gutenberg и примечания, сохраняя текст автора; абзацы — через пустую строку."""
    text = text.replace("\r\n", "\n").replace("﻿", "")
    # Примечания транскрибера / издателя в квадратных скобках (могут занимать несколько строк).
    text = re.sub(r"\[(?:Nota del transcriptor|Nota del editor|Transcriber'?s? [Nn]ote|Illustration|Ilustración|imagen)"
                  r"[^\]]*\]", "", text, flags=re.S | re.I)
    # Маркеры сносок: [1], [A], [12], (1) сразу после слова или знака.
    text = re.sub(r"(?<=[^\s\[])\[(?:\d{1,3}|[A-Z])\]", "", text)
    text = re.sub(r"(?<=[\w.,;:!?»])\((?:\d{1,3})\)", "", text)
    # Курсив/жирный в plain text Gutenberg: _слово_, =слово=.
    text = re.sub(r"_([^_\n]+(?:\n[^_\n]+)*)_", r"\1", text)
    text = re.sub(r"=([^=\n]+)=", r"\1", text)
    paras = []
    for raw in re.split(r"\n\s*\n", text):
        lines = [ln.strip() for ln in raw.strip().split("\n")]
        if not any(lines):
            continue
        # Текст самих сносок ([1] Текст…) и строки из одних звёздочек/черт.
        if re.match(r"^\[(?:\d{1,3}|[A-Z])\]\s", lines[0]) or re.fullmatch(r"[\s*.·_=-]+", " ".join(lines)):
            continue
        # Явная разметка заголовков (# Часть / ## Раздел) — отдельной строкой.
        if lines[0].startswith("#"):
            paras.append(lines[0])
            lines = lines[1:]
            if not any(lines):
                continue
        p = " ".join(ln for ln in lines if ln)
        # Перенос слова через дефис на конце строки: «pala- bra» → «palabra».
        p = re.sub(r"(\w)- (\w)", lambda m: m.group(1) + m.group(2) if m.group(2).islower() else m.group(0), p)
        p = re.sub(r"\s+", " ", p).strip()
        p = re.sub(r"\s+([,.;:!?»)])", r"\1", p)
        p = re.sub(r"([¿¡«(])\s+", r"\1", p)
        p = p.replace("--", "—")
        if p:
            paras.append(p)
    # Оглавление: заголовок ÍNDICE и следующие за ним короткие строки.
    out, in_toc = [], False
    for p in paras:
        if _TOC_HEAD_RE.match(p):
            in_toc = True
            continue
        if in_toc and len(p.split()) <= 12 and not p.startswith("#"):
            continue
        in_toc = False
        out.append(p)
    return "\n\n".join(out)


def _is_heading(p: str) -> bool:
    return p.startswith("#") or bool(_HEADING_RE.match(p))


def split_original(text: str, min_words: int = 900, max_words: int = 1300) -> list[dict]:
    """Делит очищенный текст на главы: [{"title", "paragraphs": [str]}].

    Части («# Título») никогда не склеиваются друг с другом; разделы внутри части («## II»,
    римские цифры, CAPÍTULO) — склеиваются, пока глава короче min_words; длинный раздел
    режется по max_words на границе абзаца.
    """
    paras = [p for p in text.split("\n\n") if p.strip()]
    parts: list[dict] = []  # {"title", "sections": [{"label", "paras"}]}
    for p in paras:
        if p.startswith("# ") or (p.startswith("#") and not p.startswith("##")):
            parts.append({"title": p.lstrip("#").strip(), "sections": []})
            continue
        if not parts:
            parts.append({"title": "", "sections": []})
        secs = parts[-1]["sections"]
        if _is_heading(p):
            secs.append({"label": p.lstrip("#").strip().rstrip("."), "paras": []})
            continue
        if not secs:
            secs.append({"label": "", "paras": []})
        secs[-1]["paras"].append(p)

    def wc(ps: list[str]) -> int:
        return sum(len(x.split()) for x in ps)

    chapters: list[dict] = []
    for part in parts:
        secs = [s for s in part["sections"] if s["paras"]]
        # 1) Длинные разделы → куски по ~max_words (равными долями).
        pieces: list[dict] = []
        for s in secs:
            n = wc(s["paras"])
            k = max(1, -(-n // max_words))
            target = n / k
            cur, cn, idx = [], 0, 0
            for para in s["paras"]:
                w = len(para.split())
                if cur and cn + w / 2 > target * (idx + 1) and idx < k - 1:
                    pieces.append({"label": s["label"], "paras": cur, "cont": idx > 0})
                    cur, idx = [], idx + 1
                cur.append(para)
                cn += w
            pieces.append({"label": s["label"], "paras": cur, "cont": idx > 0})
        # 2) Короткие соседние куски склеиваем, пока глава < min_words.
        groups: list[list[dict]] = []
        for pc in pieces:
            if groups and wc([x for g in groups[-1] for x in g["paras"]]) < min_words \
                    and wc([x for g in groups[-1] for x in g["paras"]]) + wc(pc["paras"]) <= max_words + 200:
                groups[-1].append(pc)
            else:
                groups.append([pc])
        # Хвост короче трети нормы — к предыдущей главе.
        if len(groups) > 1 and wc([x for g in groups[-1] for x in g["paras"]]) < min_words // 3:
            groups[-2].extend(groups.pop())
        for gi, g in enumerate(groups):
            labels = list(dict.fromkeys(pc["label"] for pc in g if pc["label"]))
            cont = g[0]["cont"]
            if not labels:
                # Часть без разделов, порезанная по длине: I, II, III…
                sec = to_roman(gi + 1) if len(groups) > 1 else ""
            elif len(labels) == 1:
                sec = labels[0] + (" (continuación)" if cont else "")
            else:
                sec = f"{labels[0]}–{labels[-1]}" if all(re.fullmatch(_ROMAN, x) for x in labels) else ", ".join(labels)
            title = ", ".join(x for x in (part["title"], sec) if x) or part["title"]
            chapters.append({"title": title, "part": part["title"], "sec": sec,
                             "paragraphs": [x for pc in g for x in pc["paras"]]})
    # Главы без заголовков нумеруем римскими цифрами.
    for i, ch in enumerate(chapters):
        if not ch["title"]:
            ch["title"] = to_roman(i + 1)
    return chapters


def unify_part_titles(chapters: list[dict]) -> None:
    """Переводы заголовков глав одной части делаем одинаковыми: «Гора духов, I», «Гора духов, II»…
    (каждую главу переводит отдельный запрос, и название части могло выйти по-разному)."""
    part_ru: dict[str, str] = {}
    for ch in chapters:
        part, sec, ru = ch.get("part", ""), ch.get("sec", ""), ch.get("title_ru", "")
        if not part or not sec or not ru:
            continue
        if part not in part_ru:
            # Отрезаем перевод раздела с конца: «Гора духов, I (продолжение)» → «Гора духов».
            head = re.sub(r",?\s*[IVXLC]+(?:\s*[–-]\s*[IVXLC]+)?(?:\s*\([^)]*\))?\s*$", "", ru).strip() or ru
            part_ru[part] = head
        ch["title_ru"] = f"{part_ru[part]}, {sec.replace('(continuación)', '(продолжение)')}"


def to_roman(n: int) -> str:
    out = ""
    for v, s in ((1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"),
                 (50, "L"), (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")):
        while n >= v:
            out, n = out + s, n - v
    return out


# Сокращения, после которых точка не конец предложения.
_ABBREV = {
    "sr", "sra", "srta", "sres", "d", "dª", "dña", "da", "ud", "uds", "vd", "vds", "v", "vv", "vm", "excmo",
    "excma", "ilmo", "ilma", "s", "sto", "sta", "fr", "fray", "p", "pbro", "dr", "dra", "lic", "etc", "pág",
    "págs", "cap", "núm", "art", "vol", "cía", "ntra", "nra", "sto", "mr", "mme", "mlle", "st", "gral",
    "cfr", "cf", "ej", "c", "ib", "id", "op", "ms", "n", "pl", "fol",
}
_OPENERS = "¿¡«“\"'(—–"


def split_sentences(par: str, long_words: int = 60) -> list[str]:
    """Режет абзац на предложения, не меняя ни одного символа (кроме пробела на границе).

    Граница — после . ! ? … (и закрывающих » ” ) ' "), если дальше пробел и заглавная буква,
    ¿ ¡ « или тире новой реплики с заглавной. Не режет после сокращений (Sr., D., Dña.) и
    инициалов, и перед ремаркой автора («—¿Vienes? —preguntó él.»).
    Очень длинные (> long_words слов) предложения дополнительно режутся по «; » / «: ».
    """
    out, start, i, n = [], 0, 0, len(par)
    # Короткие цитаты («…», “…”, "…") не режем: иначе кавычка уходит в другое предложение.
    protected = [False] * n
    for m in re.finditer(r"«[^«»]*»|“[^“”]*”|\"[^\"]*\"", par):
        if len(m.group(0).split()) <= 60:
            last = m.end() - 2  # знаки прямо перед закрывающей кавычкой могут завершать предложение
            while last > m.start() and par[last] in ".!?…":
                last -= 1
            for k in range(m.start(), last + 1):
                protected[k] = True
    while i < n:
        c = par[i]
        if c in ".!?…" and protected[i]:
            i += 1
            continue
        if c in ".!?…":
            j = i + 1
            while j < n and par[j] in ".!?…":
                j += 1
            while j < n and par[j] in "»”\"')]":
                j += 1
            if j < n and par[j] == " ":
                k = j + 1
                nxt = par[k] if k < n else ""
                nxt2 = par[k + 1] if k + 1 < n else ""
                boundary = False
                if nxt.isupper() or nxt.isdigit() or nxt in "¿¡«“\"'(":
                    boundary = True
                elif nxt in "—–" and (nxt2.isupper() or nxt2 in "¿¡«"):
                    boundary = True
                if boundary and c == ".":
                    m = re.search(r"([\wªº]+)\.$", par[start:i + 1])
                    if m and (m.group(1).lower() in _ABBREV or (len(m.group(1)) == 1 and m.group(1).isupper())):
                        boundary = False
                if boundary:
                    out.append(par[start:j])
                    start = j + 1
                    i = j + 1
                    continue
            i = j
            continue
        i += 1
    if start < n:
        out.append(par[start:])
    out = [s.strip() for s in out if s.strip()]

    res = []
    for s in out:
        res.extend(_split_long(s, long_words))
    assert " ".join(res) == par, f"split_sentences changed text:\n{par}\n---\n{' '.join(res)}"
    return res


def _split_long(s: str, long_words: int) -> list[str]:
    words = s.split()
    if len(words) <= long_words:
        return [s]
    # Кандидаты: «; » и «: » вне кавычек «» (не режем внутри цитаты).
    cands, depth = [], 0
    for i, c in enumerate(s):
        if c in "«“":
            depth += 1
        elif c in "»”":
            depth = max(0, depth - 1)
        elif c in ";:" and depth == 0 and i + 1 < len(s) and s[i + 1] == " ":
            cands.append(i + 1)
    if not cands:
        return [s]
    # Ближайшая к середине точка, где обе части ≥ 12 слов.
    best = None
    for pos in cands:
        a, b = len(s[:pos].split()), len(s[pos:].split())
        if a >= 12 and b >= 12 and (best is None or abs(a - b) < best[0]):
            best = (abs(a - b), pos)
    if best is None:
        return [s]
    pos = best[1]
    return _split_long(s[:pos].strip(), long_words) + _split_long(s[pos:].strip(), long_words)


_TOKEN_RE = re.compile(r"\w+(?:[-'’]\w+)*|\.\.\.|[^\w\s]", re.U)


def realign_tokens(src: str, tokens: list[dict]) -> list[dict]:
    """Если токены модели не воспроизводят исходник, строим токены из исходника заново
    и переносим разметку (l/ru/g/r) с совпавших токенов модели."""
    import difflib
    words = _TOKEN_RE.findall(src)
    a = [w.lower() for w in words]
    b = [t["t"].lower() for t in tokens]
    out = [{"t": w} for w in words]
    sm = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
    for blk in sm.get_matching_blocks():
        for k in range(blk.size):
            tk = tokens[blk.b + k]
            out[blk.a + k] = {"t": words[blk.a + k], **{f: tk[f] for f in ("l", "ru", "g", "r") if f in tk}}
    return out


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
    system, schema = ANNOTATE_SYSTEM, ANNOTATE_SCHEMA
    if getattr(args, "original", False):
        year_txt = str(args.year) if args.year else "the 19th or early 20th century"
        system += ANNOTATE_ORIGINAL_EXTRA.format(year_txt=year_txt, author=args.author or "unknown")
        schema = ANNOTATE_SCHEMA_ORIGINAL
    res = claude_json(
        f"Annotate these {len(sentences)} sentences:\n\n{numbered}",
        system, schema, args.model, args.annotate_effort,
    )
    out = res["sentences"]
    if len(out) != len(sentences):
        raise RuntimeError(f"expected {len(sentences)} sentences, got {len(out)}")
    for src, s in zip(sentences, out):
        s["src"] = src
    return out


def glossary_chapter(title: str, paragraphs: list[str], args) -> dict:
    """Перевод заголовка и словарик главы оригинала (8–15 ключевых слов выше A2)."""
    text = "\n\n".join(paragraphs)
    prompt = (f"Book: {args.title} ({args.author}, {args.year or 'n.d.'}).\n"
              f"Chapter title: {title}\n\n<chapter>\n{text}\n</chapter>")
    return claude_json(prompt, GLOSSARY_SYSTEM, GLOSSARY_SCHEMA, args.model, args.enrich_effort)


def clean_glossary(items: list[dict], chapter_text: str) -> list[dict]:
    """Оставляет слова, которые реально есть в тексте главы, без повторов, в порядке чтения."""
    low = chapter_text.lower()
    out, seen = [], set()
    for it in items:
        l, ru = (it.get("l") or "").strip(), (it.get("ru") or "").strip()
        form = (it.get("form") or "").strip() or l
        if not l or not ru or norm_key(l) in seen:
            continue
        m = re.search(rf"(?<!\w){re.escape(form.lower())}(?!\w)", low)
        if not m:
            m = re.search(rf"(?<!\w){re.escape(l.lower())}(?!\w)", low)
            if not m:
                continue
            form = chapter_text[m.start():m.end()]
        else:
            form = chapter_text[m.start():m.end()]
        seen.add(norm_key(l))
        out.append((m.start(), {"l": l, "ru": ru, "note": (it.get("note") or "").strip(), "form": form}))
    out.sort(key=lambda x: x[0])
    return [x[1] for x in out[:15]]


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
                   known: list[dict], args, original: bool = False) -> dict:
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
    if original:
        system += ENRICH_ORIGINAL_EXTRA
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
    original = book.get("kind") == "original"

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
        data = enrich_chapter(chapters[ci], ci, len(chapters), level, book["title"], known, args, original)
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


# Справочник тегов: тот же, что в android/.../model/Tags.kt. id не менять, новые можно добавлять.
TAGS = {
    # Ситуации
    "cafe": "кафе, бар, ресторан, заказ еды",
    "shopping": "магазин, рынок, покупки, цены",
    "transport": "метро, автобус, такси, как пройти, город",
    "travel": "путешествие, отель, туризм, экскурсии",
    "airport": "аэропорт, вокзал, билеты, багаж",
    "health": "врач, аптека, самочувствие",
    "work": "работа, коллеги, собеседование",
    "study": "учёба, школа, курсы, университет",
    "home": "дом, квартира, соседи, аренда, быт",
    "money": "банк, деньги, оплата",
    "officials": "полиция, документы, госучреждения",
    "phone": "звонки, сообщения, интернет",
    # Общение
    "meeting": "знакомство, рассказ о себе",
    "plans": "приглашения, договориться о встрече, планы",
    "requests": "просьбы, вопросы, уточнения",
    "opinions": "мнения, чувства, согласие и несогласие",
    "family": "семья, друзья, отношения",
    "smalltalk": "болтовня, погода, светская беседа",
    "problems": "проблемы, жалобы, извинения",
    # Жанр
    "everyday": "повседневная жизнь",
    "adventure": "приключения",
    "mystery": "детектив, тайна",
    "romance": "романтика, любовь",
    "humor": "юмор",
    "classic": "адаптация классики",
    "nonfiction": "нон-фикшн, полезные знания",
}


def tag_book(book: dict, args) -> None:
    """Расставляет теги книги (3–8 из справочника TAGS)."""
    chapters = []
    for i, ch in enumerate(book["chapters"]):
        sents = [" ".join(t["t"] for t in s["tokens"]) for p in ch["paragraphs"] for s in p["sentences"]][:6]
        phrases = "; ".join(ph.get("es", "") for ph in ch.get("phrases", [])[:6])
        chapters.append(f"{i + 1}. {ch['title']} ({ch.get('titleRu', '')})\n   {' '.join(sents)}\n   Фразы: {phrases}")
    catalog = "\n".join(f"- {k}: {v}" for k, v in TAGS.items())
    schema = {
        "type": "object",
        "properties": {"tags": {"type": "array", "items": {"type": "string", "enum": list(TAGS)}}},
        "required": ["tags"],
        "additionalProperties": False,
    }
    system = (
        "You tag graded Spanish readers so a learner can filter a library by topic. "
        "Pick 3-8 tags from the catalog that describe what situations, communication functions and genre "
        "the book really covers (a tag only if a meaningful part of the book is about it). "
        "Always include exactly one or two genre tags (everyday/adventure/mystery/romance/humor/classic/nonfiction)."
    )
    kind = ("an ORIGINAL unadapted classic text (use the genre tag 'classic' + the story's genre)"
            if book.get("kind") == "original" else "a graded reader")
    prompt = (f"Catalog:\n{catalog}\n\nBook: {book['title']} (level {book.get('level', '')}; {kind}).\n"
              "Chapters:\n" + "\n".join(chapters))
    res = claude_json(prompt, system, schema, args.model, "low")
    tags = [t for t in dict.fromkeys(res.get("tags", [])) if t in TAGS]
    book["tags"] = tags
    print("   Теги: " + ", ".join(tags))


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
    src.add_argument("--tags", type=Path, metavar="PATH.json",
                     help="только расставить теги готовой книге (файл перезаписывается на месте)")
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
    ap.add_argument("--original", action="store_true",
                    help="(--source) оригинальный текст без адаптации: разметка, словарик, редкие слова")
    ap.add_argument("--work-id", default="", help="общий id произведения (связывает адаптации и оригинал)")
    ap.add_argument("--year", type=int, default=0, help="год первой публикации оригинала")
    ap.add_argument("--license", default="", help='например "Общественное достояние: автор умер в 1870 г."')
    ap.add_argument("--source-note", default="", help="текст поля source (по умолчанию — имя файла)")
    ap.add_argument("--min-words", type=int, default=900, help="(--original) минимум слов в главе")
    ap.add_argument("--max-words", type=int, default=1300, help="(--original) максимум слов в главе")
    args = ap.parse_args()

    if args.source and not args.title:
        ap.error("--title обязателен вместе с --source")
    if args.original and not args.source:
        ap.error("--original работает только с --source")
    if args.original and not args.license:
        ap.error("для --original нужен --license (почему текст в общественном достоянии)")

    if args.tags:
        book = json.loads(args.tags.read_text(encoding="utf-8"))
        print(f"▶ Теги для «{book['title']}»…")
        tag_book(book, args)
        write_book(args.tags, book)
        print(f"✅ {args.tags} обновлён")
        if args.push:
            push(args.tags)
        return

    if args.enrich:
        book = json.loads(args.enrich.read_text(encoding="utf-8"))
        work = BOOKS_DIR / ".work" / book["id"]
        enrich_book(book, work, args)
        tag_book(book, args)
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
    elif args.original:
        title = args.title
        parts = split_original(clean_original(read_source(args.source)), args.min_words, args.max_words)
        parts = parts[args.skip_chunks:]
        if args.max_chunks:
            parts = parts[: args.max_chunks]
        chapters = [{"title": pt["title"], "part": pt["part"], "sec": pt["sec"],
                     "paragraphs": [split_sentences(p) for p in pt["paragraphs"]],
                     "raw": pt["paragraphs"]} for pt in parts]
        print(f"▶ Оригинал: {len(chapters)} гл.: " + "; ".join(
            f"{c['title']} ({sum(len(p.split()) for p in c['raw'])} сл.)" for c in chapters))
        source_note = f"Оригинальный текст: {args.source.name}"

        def do_gloss(i: int) -> dict:
            r = cached(work / f"gloss_{i:03d}.json",
                       lambda: glossary_chapter(chapters[i]["title"], chapters[i]["raw"], args))
            print(f"   ✓ словарик главы {i + 1}/{len(chapters)}: {r['title_ru']}")
            return r

        print(f"▶ Словарики и переводы заголовков (параллельно: {args.jobs})…")
        with cf.ThreadPoolExecutor(args.jobs) as ex:
            for ch, g in zip(chapters, ex.map(do_gloss, range(len(chapters)))):
                ch["title_ru"] = g["title_ru"].strip()
                ch["glossary"] = clean_glossary(g["glossary"], "\n\n".join(ch["raw"]))
        unify_part_titles(chapters)
    else:
        title = args.title
        text = re.sub(r"(?m)^#+[ \t]*", "", read_source(args.source))  # разметка заголовков не нужна
        chunks = split_chunks(text, args.chunk_words)[args.skip_chunks:]
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
    bad = realigned = 0
    out_chapters = []
    for ci, ch in enumerate(chapters):
        paragraphs: dict[int, list] = {}
        for _, pairs in sorted(annotated.get(ci, [])):
            for pi, s in pairs:
                tokens = s["tokens"]
                for tk in tokens:
                    if tk.get("r") not in ("rare", "old") or "l" not in tk:
                        tk.pop("r", None)
                if not tokens_ok(s["src"], tokens):
                    bad += 1
                    if args.original:
                        # Текст оригинала должен остаться буква в букву.
                        tokens = realign_tokens(s["src"], tokens)
                        realigned += 1
                paragraphs.setdefault(pi, []).append({"ru": s["ru"], "tokens": tokens})
        out_ch = {
            "title": ch["title"],
            "titleRu": ch.get("title_ru", ""),
            "paragraphs": [{"sentences": paragraphs[k]} for k in sorted(paragraphs)],
        }
        if ch.get("glossary"):
            out_ch["glossary"] = ch["glossary"]
        out_chapters.append(out_ch)

    book = {
        "format": 1,
        "id": book_id,
        "title": title,
        "author": args.author,
        "level": args.level,
        "lang": "es-ES",
        "source": args.source_note or source_note,
        "kind": "original" if args.original else "adapted",
    }
    if args.work_id:
        book["workId"] = args.work_id
    if args.year:
        book["year"] = args.year
    if args.license:
        book["license"] = args.license
    book["chapters"] = out_chapters
    out = BOOKS_DIR / f"{book_id}.json"
    write_book(out, book)
    n_sent = sum(len(p["sentences"]) for c in out_chapters for p in c["paragraphs"])
    n_words = sum(1 for c in out_chapters for p in c["paragraphs"] for s in p["sentences"]
                  for t in s["tokens"] if "l" in t)
    print(f"✅ {out}  — {len(out_chapters)} гл., {n_sent} предл., {n_words} слов")
    if bad:
        print(f"   (в {bad} предл. токены не совпали с исходником буква в букву"
              + (f"; пересобраны из исходника: {realigned})" if realigned else " — не критично)"))
    if args.original:
        n_r = {"rare": 0, "old": 0}
        for c in out_chapters:
            for p in c["paragraphs"]:
                for s in p["sentences"]:
                    for t in s["tokens"]:
                        if t.get("r") in n_r:
                            n_r[t["r"]] += 1
        print(f"   Редких слов: {n_r['rare']}, устаревших: {n_r['old']}; "
              f"словарик: {sum(len(c.get('glossary', [])) for c in out_chapters)} слов")

    # 4. Обогащение: говорящие, разговорные фразы, вопросы, «выбери ответ»
    if not args.no_enrich:
        enrich_book(book, work, args)
        tag_book(book, args)
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
