#!/usr/bin/env python3
"""«Мои фразы»: ответы «Обо мне» и «Входящие» из приложения → простой испанский с озвучкой → обратно на телефон.

  Обычно удобнее pipeline/server.py — то же самое по Wi-Fi, телефон находит Mac сам.
  Этот скрипт — запасной путь по кабелю (adb).

  python3 pipeline/personal.py                # телефон по USB: забрать, перевести, озвучить, вернуть
  python3 pipeline/personal.py --level A0     # проще (по умолчанию A1)
  python3 pipeline/personal.py --no-audio     # без озвучки (в приложении будет системный голос)

Как устроено:
- Приложение пишет ждущие перевода записи в files/personal/inbox.json (на телефоне:
  /sdcard/Android/data/app.lectura/files/personal/). Скрипт забирает его через adb.
- Каждая запись переводится `claude -p` один раз (кэш — books/.work/personal/results.json по id записи):
  «Обо мне» — ответ от первого лица на вопрос + несколько вариантов ответа (для проверки речи),
  «Входящие» — 1–2 фразы, как это сказать по-испански (Испания) просто и естественно.
- Озвучка (Qwen3-TTS, как у книг): фразы — голос ученика m1, вопросы «Обо мне» — f2;
  файлы books/.work/personal/audio/*.m4a.
- На телефон уходят personal.json (все готовые результаты) и audio/. Приложение при открытии само
  отмечает записи готовыми и добавляет фразы в повторение.

ЛИЧНЫЕ ДАННЫЕ: всё лежит только в books/.work/personal (не в git, репозиторий публичный) и на телефоне.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
WORK = ROOT / "books" / ".work" / "personal"
ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"
PHONE_DIR = "/sdcard/Android/data/app.lectura/files/personal"

sys.path.insert(0, str(HERE))
from phrasebook import SYSTEM as PB_SYSTEM  # noqa: E402
from prepare_book import claude_json  # noqa: E402

SCHEMA = {
    "type": "object",
    "properties": {
        "phrases": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"es": {"type": "string"}, "ru": {"type": "string"}, "note": {"type": "string"}},
                "required": ["es", "ru", "note"],
            },
        },
        "accepted": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["phrases", "accepted"],
}

LEVEL = {
    "A0": "absolute beginner (A0): very short sentences, present tense, the most common words",
    "A1": "beginner (A1): short simple sentences; present tense plus fixed chunks (he perdido, me gustaría, llevo X meses)",
}


# ------------------------------------------------------------------ общее с server.py

RESULTS = WORK / "results.json"
AUDIO = WORK / "audio"


def entry_key(i: dict) -> str:
    """Ключ кэша — id записи И её текст: после переустановки приложения id начинаются заново,
    и старый перевод не должен приклеиться к новому ответу."""
    return f"{i['id']}:" + hashlib.sha1(f"{i['kind']}|{i.get('ref', '')}|{i['ru']}".encode()).hexdigest()[:10]


def load_results() -> dict[str, dict]:
    return json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}


def save_results(results: dict[str, dict]) -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    tmp = RESULTS.with_suffix(".tmp")
    tmp.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(RESULTS)


def make_result(it: dict, out: dict) -> dict:
    """Ответ claude → результат для приложения (контракт model/Personal.kt PersonalResult)."""
    phrases = []
    for k, p in enumerate(out["phrases"][:3], 1):
        if p["es"].strip():
            phrases.append({"id": f"me-{it['id']}-{k}", "es": p["es"].strip(), "ru": p["ru"].strip(),
                            **({"note": p["note"].strip()} if p["note"].strip() else {})})
    res = {"inboxId": it["id"], "kind": it["kind"], "ref": it.get("ref", ""), "ru": it["ru"], "phrases": phrases}
    if it["kind"] == "about":
        full = " ".join(p["es"] for p in phrases)
        res["question"] = it.get("question", "")
        res["accepted"] = [full] + [a for a in out["accepted"] if a.strip() and a.strip() != full]
    return res


def clips_for(results: list[dict]) -> list[tuple[str, str, str]]:
    """(имя, текст, голос): фразы — голос ученика m1, вопросы «Обо мне» — f2."""
    clips = []
    for r in results:
        for p in r["phrases"]:
            clips.append((p["id"], p["es"], "m1"))
        if r["kind"] == "about" and r.get("question"):
            clips.append((f"q-{r['ref']}", r["question"], "f2"))
    return clips


def adb() -> str:
    return str(ADB) if ADB.exists() else "adb"


def pull_inbox() -> list[dict]:
    dst = WORK / "inbox.json"
    r = subprocess.run([adb(), "pull", f"{PHONE_DIR}/inbox.json", str(dst)], capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"Не удалось забрать inbox.json с телефона (подключён ли он по USB?):\n{r.stderr.strip()}")
    return json.loads(dst.read_text(encoding="utf-8")).get("items", [])


def translate(item: dict, level: str, model: str, effort: str) -> dict:
    if item["kind"] == "about":
        prompt = (
            f"The learner is a {LEVEL[level]} learner of Spanish (Spain). Someone asks him: «{item['question']}».\n"
            f"His answer, written in Russian: «{item['ru']}».\n\n"
            "Write his answer in simple, natural Peninsular Spanish, first person, masculine, as he would SAY it "
            "in a conversation: `phrases` = 1–3 short sentences that together make the answer (keep his facts, names, "
            "numbers; drop what is too hard for the level). `ru` = natural Russian translation of each sentence; "
            "`note` = a short tip in Russian or \"\". `accepted` = 3–5 other correct, natural ways to answer the same "
            "question with the SAME facts, at this level (shorter ones too) — used to check his spoken answer."
        )
    else:
        prompt = (
            f"The learner is a {LEVEL[level]} learner of Spanish (Spain). He wants to be able to say this "
            f"(written in Russian): «{item['ru']}».\n\n"
            "Give how to say it in simple, natural Peninsular Spanish, as people really say it in Spain: `phrases` = "
            "the best phrase first, then optionally 1 simpler or alternative way (max 2 in total). Use usted if he "
            "would say it to staff/strangers, tú to friends — mention which in `note` (Russian) if it matters. "
            "`ru` = natural Russian translation. `accepted` = [] ."
        )
    return claude_json(prompt, PB_SYSTEM, SCHEMA, model, effort)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--inbox", type=Path, help="взять inbox.json из файла, а не с телефона")
    ap.add_argument("--level", default="A1", choices=list(LEVEL))
    ap.add_argument("--model", default="opus")
    ap.add_argument("--effort", default="medium")
    ap.add_argument("--no-audio", action="store_true")
    ap.add_argument("--no-push", action="store_true", help="не копировать результат на телефон")
    args = ap.parse_args()

    WORK.mkdir(parents=True, exist_ok=True)
    items = json.loads(args.inbox.read_text(encoding="utf-8"))["items"] if args.inbox else pull_inbox()
    results = load_results()

    todo = [i for i in items if entry_key(i) not in results]
    print(f"📥 Во «Входящих»: {len(items)}, новых для перевода: {len(todo)}")
    for it in todo:
        print(f"✍️  {it['ru'][:70]}", flush=True)
        res = make_result(it, translate(it, args.level, args.model, args.effort))
        results[entry_key(it)] = res
        save_results(results)
        for p in res["phrases"]:
            print(f"   → {p['es']}", flush=True)

    # Источник правды — телефон: берём его personal.json и дополняем новыми переводами
    # (если кэш на Mac пропал или скрипт запущен с другого компьютера, старые переводы не теряются).
    merged = {f"{r['inboxId']}:{r.get('ru', '')}": r for r in phone_results(args.inbox is None)}
    merged.update({f"{r['inboxId']}:{r.get('ru', '')}": r for r in results.values()})
    payload = {"items": list(merged.values())}
    (WORK / "personal.json").write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")

    if not args.no_audio:
        voice(payload["items"])
    if not args.no_push:
        push()


def phone_results(from_phone: bool) -> list[dict]:
    """Уже доставленные на телефон переводы (personal.json), если он подключён."""
    if not from_phone:
        return []
    dst = WORK / "personal.phone.json"
    if subprocess.run([adb(), "pull", f"{PHONE_DIR}/personal.json", str(dst)], capture_output=True).returncode:
        return []
    try:
        return json.loads(dst.read_text(encoding="utf-8")).get("items", [])
    except json.JSONDecodeError:
        return []


def voice(items: list[dict]) -> None:
    """Озвучка тем же движком, что и книги (в venv audio.py)."""
    clips = clips_for(items)
    (WORK / "clips.json").write_text(json.dumps(clips, ensure_ascii=False), encoding="utf-8")
    subprocess.run([sys.executable, str(HERE / "audio.py"), "--clips", str(WORK / "clips.json"),
                    "--out", str(WORK / "audio")], check=True)


def push() -> None:
    a = adb()
    subprocess.run([a, "shell", "mkdir", "-p", f"{PHONE_DIR}/audio"], check=True)
    subprocess.run([a, "push", str(WORK / "personal.json"), f"{PHONE_DIR}/personal.json"], check=True, capture_output=True)
    audio = WORK / "audio"
    if audio.exists():
        subprocess.run([a, "push", f"{audio}/.", f"{PHONE_DIR}/audio/"], check=True, capture_output=True)
    print(f"📱 На телефоне: {PHONE_DIR} — откройте «Мои фразы» в приложении")


if __name__ == "__main__":
    main()
