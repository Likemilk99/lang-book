#!/usr/bin/env python3
"""Готовит книгу для приложения Lectura с помощью Claude Code (`claude -p`).

Два режима:
  1) Адаптация существующего текста (txt / epub):
       python3 pipeline/prepare_book.py --source book.txt --title "Marianela" --author "Galdós"
  2) Оригинальная история на тему:
       python3 pipeline/prepare_book.py --topic "кот, который живёт в Мадриде" --chapters 5

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
    ap.add_argument("--title", help="название (для --source обязательно)")
    ap.add_argument("--author", default="")
    ap.add_argument("--level", default="A1", choices=list(LEVEL_GUIDE))
    ap.add_argument("--id", help="идентификатор книги (по умолчанию из названия)")
    ap.add_argument("--model", default="sonnet", help="модель claude (sonnet / opus / haiku)")
    ap.add_argument("--effort", default="medium", help="effort для адаптации")
    ap.add_argument("--annotate-effort", default="low", help="effort для разметки слов")
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
    BOOKS_DIR.mkdir(exist_ok=True)
    out = BOOKS_DIR / f"{book_id}.json"
    out.write_text(json.dumps(book, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    n_sent = sum(len(p["sentences"]) for c in out_chapters for p in c["paragraphs"])
    n_words = sum(1 for c in out_chapters for p in c["paragraphs"] for s in p["sentences"]
                  for t in s["tokens"] if "l" in t)
    print(f"✅ {out}  — {len(out_chapters)} гл., {n_sent} предл., {n_words} слов")
    if bad:
        print(f"   (в {bad} предл. токены не совпали с исходником буква в букву — не критично)")

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
