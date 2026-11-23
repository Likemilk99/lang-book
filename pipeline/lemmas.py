#!/usr/bin/env python3
"""Словарный перевод начальных форм (лемм) из книг → assets/lemmas.json.

  python3 pipeline/lemmas.py              # дополнить словарь новыми леммами из книг
  python3 pipeline/lemmas.py --model opus # другой моделью

Зачем: в книге у каждого слова перевод «в контексте» (Token.ru = «руку», «высоких», «пошёл»),
а карточка в словаре хранит начальную форму (la mano, alto, ir) — ей нужен словарный перевод
(«рука», «высокий», «идти»). Приложение берёт его так:
  1) словарь учебной программы (assets/curriculum/vocab_*.json) — если лемма там есть;
  2) assets/lemmas.json — этот файл, для остальных лемм из книг;
  3) иначе — перевод в контексте.

Леммы, которые уже есть в словаре программы, и имена собственные сюда не попадают.
Каждая пачка лемм переводится `claude -p` один раз (кэш — books/.work/lemmas/). После новых книг
скрипт просто запускается снова: в файл добавятся только новые леммы.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
OUT = ASSETS / "lemmas.json"
WORK = ROOT / "books" / ".work" / "lemmas"

sys.path.insert(0, str(HERE))
from prepare_book import claude_json  # noqa: E402

BATCH = 80
PROPER = re.compile(r"собств|топоним|фамилия|\bимя\b|название")

SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"l": {"type": "string"}, "ru": {"type": "string"}},
                "required": ["l", "ru"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["items"],
    "additionalProperties": False,
}

SYSTEM = """You write the Russian side of Spanish (Spain) vocabulary flashcards for a Russian-speaking learner.
For each Spanish dictionary form ("l") give a short DICTIONARY translation into Russian ("ru"):
- the Russian word in ITS dictionary form: noun — nominative singular, verb — infinitive,
  adjective — masculine nominative singular (Spanish "alto" → "высокий", "mano" → "рука", "ir" → "идти, ехать");
- 1–3 meanings separated by ", ", most common first; the meaning used in the example sentences goes first;
- reflexive verbs keep their own meaning ("irse" → "уходить, уезжать", "levantarse" → "вставать");
- contractions and function words get their usual gloss ("del" → "от, из (de + el)", "al" → "к (a + el)");
- no articles, no grammar labels, no explanations, no quotes.
Return every input lemma exactly once, with "l" copied exactly as given."""


def collect() -> dict[str, list[dict]]:
    """Леммы книг, которых нет в словаре программы → до двух примеров употребления."""
    known = set()
    for f in sorted((ASSETS / "curriculum").glob("vocab_*.json")):
        for th in json.loads(f.read_text(encoding="utf-8"))["themes"]:
            for w in th["words"]:
                if w.get("pos") != "expr":
                    known.add(w["l"].lower())
    found: dict[str, list[dict]] = defaultdict(list)
    for f in sorted((ASSETS / "books").glob("*.json")):
        book = json.loads(f.read_text(encoding="utf-8"))
        for ch in book["chapters"]:
            for p in ch["paragraphs"]:
                for s in p["sentences"]:
                    text = " ".join(t["t"] for t in s["tokens"])
                    for t in s["tokens"]:
                        lemma, g = t.get("l") or "", t.get("g") or ""
                        if not any(c.isalpha() for c in lemma) or lemma[:1].isupper() or PROPER.search(g):
                            continue
                        key = lemma.lower().strip()
                        if key in known:
                            continue
                        if len(found[key]) < 2:
                            found[key].append({"form": t["t"], "ru": t.get("ru", ""), "g": g, "sentence": text})
    return found


def translate(batch: list[tuple[str, list[dict]]], model: str) -> dict[str, str]:
    payload = [{"l": l, "examples": ex} for l, ex in batch]
    digest = hashlib.sha1((model + json.dumps(payload, ensure_ascii=False, sort_keys=True)).encode()).hexdigest()[:16]
    cache = WORK / f"{digest}.json"
    if cache.exists():
        return json.loads(cache.read_text(encoding="utf-8"))
    prompt = (
        "Translate these Spanish dictionary forms. Each has 1–2 example uses from a book "
        "(form in text, its contextual translation, grammar note, sentence).\n\n"
        + json.dumps(payload, ensure_ascii=False, indent=1)
    )
    out = claude_json(prompt, SYSTEM, SCHEMA, model=model, effort="low")
    wanted = {l for l, _ in batch}
    res = {it["l"].lower().strip(): it["ru"].strip() for it in out["items"] if it["l"].lower().strip() in wanted and it["ru"].strip()}
    WORK.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    return res


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--jobs", type=int, default=4)
    args = ap.parse_args()

    have: dict[str, str] = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {}
    found = collect()
    todo = sorted((l, ex) for l, ex in found.items() if l not in have)
    print(f"лемм вне словаря программы: {len(found)}, уже переведено: {len(found) - len(todo)}, новых: {len(todo)}")
    batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
    done = 0
    with cf.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(translate, b, args.model): b for b in batches}
        for fut in cf.as_completed(futures):
            try:
                have.update(fut.result())
            except Exception as e:  # noqa: BLE001 — одна пачка не должна валить остальные
                print(f"   ! пачка не переведена: {e}", file=sys.stderr)
            done += 1
            print(f"   пачек {done}/{len(batches)}")
    missing = [l for l, _ in todo if l not in have]
    if missing:
        print(f"без перевода осталось {len(missing)} (запустите ещё раз): {', '.join(missing[:20])}…")
    OUT.write_text(json.dumps(dict(sorted(have.items())), ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    print(f"✓ {OUT.relative_to(ROOT)}: {len(have)} лемм")


if __name__ == "__main__":
    main()
