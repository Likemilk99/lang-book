"""Дикторская озвучка книг на Mac (Qwen3-TTS через MLX), голос у каждого персонажа свой.

  python3 pipeline/audio.py books/a1-02-nuevo-en-madrid.json      # одна книга
  python3 pipeline/audio.py --all                                  # все встроенные книги
  python3 pipeline/audio.py --all --upload                         # + выложить архивы в GitHub Release «audio»
  python3 pipeline/audio.py --voices                               # (пере)создать недостающие голоса набора
  python3 pipeline/audio.py --phrasebook                           # озвучка разговорников (кладётся в APK)
  python3 pipeline/audio.py --scenarios                            # озвучка сценариев (+ их ключевых фраз)
  python3 pipeline/audio.py --clips list.json --out DIR            # список [имя, текст, голос] (pipeline/personal.py)

Как устроено:
- Набор голосов `pipeline/voices/<id>.wav` (+ .txt — что в нём сказано) создан один раз Qwen VoiceDesign по описаниям
  из VOICES и закоммичен: рассказчик и персонажи звучат одинаково во всех книгах. Персонажам голоса раздаются
  по полу в порядке `Book.characters`; рассказчик — всегда `narr`.
- Предложение режется на реплику и авторские слова («—¡Hola! —pregunta Lucía.»), каждый кусок озвучивается
  клоном своего голоса (Qwen Base 1.7B), проверяется Whisper'ом (при ошибке — до 3 попыток, берётся лучшая),
  куски склеиваются в `<глава>_<предложение>.m4a` (AAC, моно 24 кГц).
- Кэш: books/.work/audio/<id>/ — перегенерируются только предложения, у которых изменился текст или голос.
- Архив books/.work/audio/<id>.zip: m4a + manifest.json {версия, хэш токенов каждого предложения}. Приложение
  сверяет хэш с текстом книги и для изменившихся предложений читает системным TTS.
- В книгу (books/ и assets/books/) пишется поле `audio` {url, size, version} — по нему в приложении появляется
  «Скачать озвучку».

Зависимости ставятся сами в pipeline/.cache/audio-venv (uv): mlx-audio, mlx-whisper, soundfile.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
VENV = HERE / ".cache" / "audio-venv"
VOICE_DIR = HERE / "voices"
WORK = ROOT / "books" / ".work" / "audio"
ASSET_BOOKS = ROOT / "android" / "app" / "src" / "main" / "assets" / "books"
ASSET_PHRASEBOOK = ROOT / "android" / "app" / "src" / "main" / "assets" / "phrasebook"
ASSET_SCENARIOS = ROOT / "android" / "app" / "src" / "main" / "assets" / "scenarios"
RELEASE_TAG = "audio"
REPO = "Likemilk99/lang-book"
URL_BASE = f"https://github.com/{REPO}/releases/download/{RELEASE_TAG}/"

DESIGN_MODEL = "mlx-community/Qwen3-TTS-12Hz-1.7B-VoiceDesign-8bit"
CLONE_MODEL = "mlx-community/Qwen3-TTS-12Hz-1.7B-Base-8bit"
STT_MODEL = "mlx-community/whisper-large-v3-turbo"
SR = 24000
SEGMENT_PAUSE = 0.25   # между репликой и авторскими словами внутри предложения
TAIL_PAUSE = 0.35      # тишина в конце каждого предложения
BITRATE = 48000

# Набор голосов. Описание и образец фразы — для VoiceDesign; образец специально с «z/c», чтобы закрепить
# кастильское произношение. Порядок внутри пола = порядок раздачи персонажам.
VOICES = {
    "narr": ("", "Una voz femenina adulta, de unos 40 años, de Madrid, España. Acento castellano peninsular "
             "con distinción de la zeta. Tono cálido, tranquilo y claro, como una narradora de audiolibros.",
             "Era una mañana tranquila de otoño. En la plaza, cerca de la estación, los vecinos tomaban café."),
    "m1": ("m", "Un hombre joven de unos 25 años con voz media, amable y un poco tímida. Habla español de España, "
           "pronunciación castellana clara, ritmo natural de conversación.",
           "Hola, ¿qué tal? Me llamo Iván y hace dos días que vivo en Madrid. ¡Me encanta esta ciudad!"),
    "f1": ("f", "Una chica joven de unos 23 años, voz aguda, alegre y enérgica. Es de Madrid y habla español "
           "de España con acento castellano, muy expresiva y simpática.",
           "¡Hola, chicos! ¿Queréis una cerveza? Hay zumo de naranja en la nevera, y también hace mucho sol."),
    "m2": ("m", "Un hombre de unos 30 años con voz grave y profunda, tranquila y un poco seca, con humor. "
           "Habla español de España, acento castellano de Madrid, despacio y en voz baja.",
           "Bueno, la lavadora es muy vieja, pero funciona. Hacemos la cena a las nueve, ¿vale?"),
    "f2": ("f", "Una mujer de unos 35 años con voz media, clara y segura, amable y profesional. Habla español "
           "de España con acento castellano neutro, ritmo pausado y buena dicción.",
           "Buenos días. Su habitación está en la cuarta planta, al lado del ascensor. ¿Necesita algo más?"),
    "m3": ("m", "Un hombre de unos 55 años, voz madura, cálida y algo ronca, con autoridad tranquila. Habla "
           "español de España, acento castellano, entonación expresiva de narrador de cuentos.",
           "Escucha, muchacho: en esta ciudad hay que tener paciencia. Las cosas buenas llegan despacio."),
    "f3": ("f", "Una mujer de unos 60 años con voz suave, maternal y cariñosa, un poco grave. Habla español de "
           "España con acento castellano, despacio y con mucha ternura.",
           "Cariño, ¿has comido algo? Te he hecho una tortilla y hay cerezas en la cocina."),
    "m4": ("m", "Un hombre mayor de unos 75 años, voz grave, lenta y un poco temblorosa, sabia y amable. "
           "Habla español de España con acento castellano antiguo.",
           "Hace muchos años, cuando yo era joven, en este pueblo no había ni coches ni luz eléctrica."),
}


# ------------------------------------------------------------------ окружение

def ensure_venv() -> None:
    """Перезапускает скрипт в отдельном venv с mlx-audio (системный Python его не трогаем)."""
    if Path(sys.prefix).resolve() == VENV.resolve():
        return
    py = VENV / "bin" / "python"
    if not py.exists():
        print("📦 Ставлю mlx-audio в", VENV)
        subprocess.run(["uv", "venv", "-q", "--python", "3.11", str(VENV)], check=True)
        subprocess.run(["uv", "pip", "install", "-q", "--python", str(py),
                        "mlx-audio", "mlx-whisper", "soundfile", "numpy"], check=True)
    os.execv(str(py), [str(py), __file__, *sys.argv[1:]])


# ------------------------------------------------------------------ текст

sys.path.insert(0, str(HERE))
from prepare_book import sentence_text, write_book  # noqa: E402  (только stdlib)


def token_hash(tokens: list[dict]) -> str:
    """Тот же хэш считает приложение (BookAudio.tokenHash): sha1 от токенов через \\u0001, 10 hex-символов."""
    return hashlib.sha1("\u0001".join(t["t"] for t in tokens).encode()).hexdigest()[:10]


def split_dialogue(text: str, speaker: str | None) -> list[tuple[str | None, str]]:
    """«—¡Hola! —pregunta una chica.» → [(speaker, "¡Hola!"), (None, "pregunta una chica.")]."""
    text = text.strip()
    if speaker is None:
        return [(None, text)]
    if not text.startswith(("—", "–")):
        return [(speaker, text.strip(" —–"))]
    out = []
    for k, p in enumerate(re.split(r"\s*[—–]\s*", text)[1:]):
        p = re.sub(r"^[\s.,;:]+", "", p).strip()
        if not p:
            continue
        if not re.search(r"[.!?…»)]$", p):
            p += "."
        out.append((speaker if k % 2 == 0 else None, p))
    return out or [(speaker, text.strip(" —–"))]


def assign_voices(book: dict) -> dict[str | None, str]:
    """Имя персонажа → id голоса; None (рассказчик) → narr."""
    pools = {"m": [v for v, (g, *_) in VOICES.items() if g == "m"],
             "f": [v for v, (g, *_) in VOICES.items() if g == "f"]}
    every = pools["m"] + pools["f"]
    used = {"m": 0, "f": 0}
    out: dict[str | None, str] = {None: "narr"}
    for c in book.get("characters", []):
        g = c.get("gender", "")
        if g in pools:
            out[c["name"]] = pools[g][used[g] % len(pools[g])]
            used[g] += 1
        else:
            out[c["name"]] = every[int(hashlib.md5(c["name"].encode()).hexdigest(), 16) % len(every)]
    return out


def voice_for(voices: dict, sp: str | None) -> str:
    if sp in voices:
        return voices[sp]
    every = [v for v in VOICES if v != "narr"]
    return every[int(hashlib.md5(sp.encode()).hexdigest(), 16) % len(every)]


_UNITS = ("cero uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince dieciséis "
          "diecisiete dieciocho diecinueve veinte veintiuno veintidós veintitrés veinticuatro veinticinco veintiséis "
          "veintisiete veintiocho veintinueve").split()
_TENS = "_ _ _ treinta cuarenta cincuenta sesenta setenta ochenta noventa".split()
_HUNDREDS = ("_ ciento doscientos trescientos cuatrocientos quinientos seiscientos setecientos ochocientos "
             "novecientos").split()


def spanish_number(n: int) -> str:
    """Цифры → слова, чтобы «25» от Whisper совпадало с «veinticinco» в тексте."""
    if n < 30:
        return _UNITS[n]
    if n < 100:
        return _TENS[n // 10] + ("" if n % 10 == 0 else " y " + _UNITS[n % 10])
    if n < 1000:
        return "cien" if n == 100 else _HUNDREDS[n // 100] + ("" if n % 100 == 0 else " " + spanish_number(n % 100))
    if n < 1_000_000:
        k, r = divmod(n, 1000)
        return ("mil" if k == 1 else spanish_number(k) + " mil") + ("" if r == 0 else " " + spanish_number(r))
    return str(n)


def words(s: str) -> list[str]:
    s = re.sub(r"\d+", lambda m: " " + spanish_number(int(m.group())) + " ", s.lower())
    s = s.replace("š", "sh").replace("ž", "zh").replace("č", "ch")  # Miša ↔ Misha
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.findall(r"\w+", s)


def word_errors(ref: list[str], hyp: list[str]) -> int:
    d = list(range(len(hyp) + 1))
    for i in range(1, len(ref) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(hyp) + 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (ref[i - 1] != hyp[j - 1]))
    return d[-1]


# ------------------------------------------------------------------ синтез

class Engine:
    def __init__(self):
        import mlx_whisper  # noqa: F401
        from mlx_audio.tts.utils import load_model

        self.model = load_model(CLONE_MODEL)
        self.refs = {}
        for v in VOICES:
            wav = VOICE_DIR / f"{v}.wav"
            if not wav.exists():
                sys.exit(f"Нет голоса {wav}. Запустите: python3 pipeline/audio.py --voices")
            self.refs[v] = (str(wav), (VOICE_DIR / f"{v}.txt").read_text().strip())

    def tts(self, text: str, voice: str):
        import numpy as np

        ref_audio, ref_text = self.refs[voice]
        limit = int(12.5 * (3 + 0.12 * len(text)) * 1.6)  # ~1.6× от ожидаемой длины: страховка от зацикливания
        res = list(self.model.generate(text=text, ref_audio=ref_audio, ref_text=ref_text,
                                       lang_code="spanish", max_tokens=limit))
        return np.concatenate([np.array(r.audio, dtype=np.float32).reshape(-1) for r in res])

    @staticmethod
    def stt(wav) -> str:
        import mlx_whisper
        import numpy as np

        x = np.interp(np.arange(0, len(wav), SR / 16000), np.arange(len(wav)), wav).astype(np.float32)
        return mlx_whisper.transcribe(x, path_or_hf_repo=STT_MODEL, language="es",
                                      condition_on_previous_text=False)["text"]

    def segment(self, text: str, voice: str, attempts: int = 3):
        """Лучшая из нескольких попыток по Whisper. Возвращает (wav, ошибок, распознанный текст)."""
        ref = words(text)
        best = None
        for _ in range(attempts):
            wav = trim(self.tts(text, voice))
            heard = self.stt(wav) if len(wav) else ""
            err = word_errors(ref, words(heard))
            too_long = len(wav) / SR > 2.5 + 0.15 * len(text)
            score = err + (len(ref) if too_long or not len(wav) else 0)
            if best is None or score < best[1]:
                best = (wav, score, heard)
            if score <= len(ref) // 5:  # до 4 слов — без ошибок, дальше — до 1 ошибки на 5 слов
                break
        return best


def trim(wav, top_db: float = 40.0):
    import numpy as np

    if not len(wav):
        return wav
    frame = 240
    n = len(wav) // frame
    if n == 0:
        return wav
    rms = np.sqrt(np.mean(wav[: n * frame].reshape(n, frame) ** 2, axis=1) + 1e-12)
    loud = np.where(20 * np.log10(rms / (rms.max() + 1e-12)) > -top_db)[0]
    if not len(loud):
        return wav[:0]
    a, b = max(0, loud[0] - 2) * frame, min(len(wav), (loud[-1] + 3) * frame)
    return wav[a:b]


def encode(wav, dst: Path) -> None:
    import numpy as np
    import soundfile as sf

    voiced = wav[np.abs(wav) > 1e-3]
    if len(voiced):
        wav = wav * (0.1 / np.sqrt(np.mean(voiced ** 2)))  # −20 dBFS RMS: громкость ровная по всей книге
    wav = np.clip(wav, -0.98, 0.98)
    tmp = dst.with_suffix(".wav")
    sf.write(tmp, wav, SR, subtype="PCM_16")
    subprocess.run(["afconvert", "-f", "m4af", "-d", "aac", "-b", str(BITRATE), str(tmp), str(dst)], check=True)
    tmp.unlink()


# ------------------------------------------------------------------ книга

def voice_book(path: Path, engine: Engine) -> Path:
    import numpy as np

    book = json.loads(path.read_text(encoding="utf-8"))
    bid = book["id"]
    out = WORK / bid
    out.mkdir(parents=True, exist_ok=True)
    state_path = out / "state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    voices = assign_voices(book)
    print(f"\n🎙  {book['title']} ({bid}): " + ", ".join(f"{k or 'рассказчик'}={v}" for k, v in voices.items()))

    manifest: dict[str, str] = {}
    problems = []
    total = sum(len(p["sentences"]) for c in book["chapters"] for p in c["paragraphs"])
    done = 0
    t0 = time.time()
    for ci, ch in enumerate(book["chapters"]):
        sents = [s for p in ch["paragraphs"] for s in p["sentences"]]
        for si, s in enumerate(sents):
            done += 1
            name = f"{ci}_{si}"
            parts = [(voice_for(voices, sp), t) for sp, t in split_dialogue(sentence_text(s["tokens"]), s.get("sp"))
                     if words(t)]
            key = hashlib.sha1(json.dumps([CLONE_MODEL, parts], ensure_ascii=False).encode()).hexdigest()[:12]
            dst = out / f"{name}.m4a"
            if not parts:
                continue
            manifest[name] = token_hash(s["tokens"])
            if state.get(name) == key and dst.exists():
                continue
            chunks = []
            for k, (voice, text) in enumerate(parts):
                wav, err, heard = engine.segment(text, voice)
                if err:
                    problems.append({"s": name, "voice": voice, "text": text, "heard": heard.strip(), "errors": err})
                chunks.append(wav)
                pause = SEGMENT_PAUSE if k < len(parts) - 1 else TAIL_PAUSE
                chunks.append(np.zeros(int(pause * SR), np.float32))
            encode(np.concatenate([np.zeros(int(0.05 * SR), np.float32), *chunks]), dst)
            state[name] = key
            state_path.write_text(json.dumps(state, indent=0))
            el = time.time() - t0
            print(f"   [{done}/{total}] {name} {el / 60:5.1f} мин  {'⚠️ ' if problems and problems[-1]['s'] == name else ''}"
                  f"{sentence_text(s['tokens'])[:70]}", flush=True)

    (out / "problems.json").write_text(json.dumps(problems, ensure_ascii=False, indent=1))
    version = hashlib.sha1(json.dumps([state.get(n) for n in sorted(manifest)] + sorted(manifest.items()))
                           .encode()).hexdigest()[:12]
    zpath = WORK / f"{bid}.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
        z.writestr("manifest.json", json.dumps({"bookId": bid, "version": version, "files": manifest}))
        for n in manifest:
            z.write(out / f"{n}.m4a", f"{n}.m4a")
    size = zpath.stat().st_size
    meta = {"url": URL_BASE + zpath.name, "size": size, "version": version}
    for target in (path, ASSET_BOOKS / path.name):
        if target.exists():
            b = json.loads(target.read_text(encoding="utf-8"))
            if b.get("id") == bid and b.get("audio") != meta:
                b["audio"] = meta
                write_book(target, b)
    print(f"✅ {zpath.name}: {len(manifest)} предложений, {size / 1e6:.1f} МБ, версия {version}, "
          f"сомнительных кусков {len(problems)} (см. {out / 'problems.json'})")
    return zpath


def phrase_items(phrases: list[dict], voice: str = "narr") -> list[tuple[str, str, str]]:
    """Фраза → <id> и её варианты шаблона → <id>_<k> (k ≥ 1; вариант 0 — сама фраза)."""
    items = []
    for p in phrases:
        items.append((p["id"], p["es"], voice))
        for k, slot in enumerate(p.get("slots", [])):
            if k > 0 and slot["es"] != p["es"]:
                items.append((f"{p['id']}_{k}", slot["es"], voice))
    return items


def voice_phrasebook(engine: Engine) -> None:
    """Разговорники и ключевые фразы сценариев: assets/phrasebook/audio/<id>.m4a (+ варианты шаблонов).
    Файлы встраиваются в APK, скачивать ничего не нужно."""
    phrases = []
    for f in sorted(ASSET_PHRASEBOOK.glob("phrasebook_*.json")):
        for t in json.loads(f.read_text(encoding="utf-8"))["themes"]:
            phrases += t["phrases"]
    sc = ASSET_SCENARIOS / "scenarios.json"
    if sc.exists():
        seen = {p["id"] for p in phrases}
        for s in json.loads(sc.read_text(encoding="utf-8"))["scenarios"]:
            phrases += [p for p in s["phrases"] if p["id"] not in seen and not seen.add(p["id"])]
    voice_set(engine, phrase_items(phrases), ASSET_PHRASEBOOK / "audio", "phrasebook")


def voice_scenarios(engine: Engine) -> None:
    """Сценарии: реплики <id>_<диалог>_<n>, «понять» <id>_u<n>, вопросы <id>_q<n>, образец ответа <id>_q<n>a.
    Ученик — m1, собеседник — по полу роли (f2 / m2)."""
    items = []
    for s in json.loads((ASSET_SCENARIOS / "scenarios.json").read_text(encoding="utf-8"))["scenarios"]:
        sid, them = s["id"], ("f2" if s["them"].get("gender") == "f" else "m2")
        for d in s["dialogs"]:
            for i, l in enumerate(d["lines"]):
                items.append((f"{sid}_{d['id']}_{i}", l["es"], "m1" if l["who"] == "me" else them))
        for i, u in enumerate(s.get("understand", [])):
            items.append((f"{sid}_u{i}", u["es"], them))
        for i, q in enumerate(s.get("prompts", [])):
            items.append((f"{sid}_q{i}", q["es"], them))
            items.append((f"{sid}_q{i}a", q["accepted"][0], "m1"))
    voice_set(engine, items, ASSET_SCENARIOS / "audio", "scenarios")


def voice_set(engine: Engine, items: list[tuple[str, str, str]], out: Path, work_name: str) -> None:
    """Озвучивает (имя, текст, голос) в out/<имя>.m4a; лишние файлы удаляет, готовые не трогает."""
    import numpy as np

    out.mkdir(parents=True, exist_ok=True)
    work = WORK / work_name
    work.mkdir(parents=True, exist_ok=True)
    state_path = work / "state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    wanted = {name for name, _, _ in items}
    for old in out.glob("*.m4a"):
        if old.stem not in wanted:
            old.unlink()
            state.pop(old.stem, None)
    problems = []
    t0 = time.time()
    for i, (name, text, voice) in enumerate(items, 1):
        key = hashlib.sha1(json.dumps([CLONE_MODEL, voice, text], ensure_ascii=False).encode()).hexdigest()[:12]
        dst = out / f"{name}.m4a"
        if state.get(name) == key and dst.exists():
            continue
        wav, err, heard = engine.segment(text, voice)
        if err:
            problems.append({"s": name, "text": text, "heard": heard.strip(), "errors": err})
        lead, tail = np.zeros(int(0.05 * SR), np.float32), np.zeros(int(0.2 * SR), np.float32)
        encode(np.concatenate([lead, wav, tail]), dst)
        state[name] = key
        state_path.write_text(json.dumps(state, indent=0))
        print(f"   [{i}/{len(items)}] {name} {(time.time() - t0) / 60:5.1f} мин  {'⚠️ ' if err else ''}{text}", flush=True)
    (work / "problems.json").write_text(json.dumps(problems, ensure_ascii=False, indent=1))
    size = sum(f.stat().st_size for f in out.glob("*.m4a"))
    print(f"✅ {work_name}: {len(items)} файлов, {size / 1e6:.1f} МБ, сомнительных {len(problems)} "
          f"(см. {work / 'problems.json'})")


def make_voices(force: bool = False) -> None:
    import numpy as np
    import soundfile as sf
    from mlx_audio.tts.utils import load_model

    VOICE_DIR.mkdir(exist_ok=True)
    todo = [v for v in VOICES if force or not (VOICE_DIR / f"{v}.wav").exists()]
    if not todo:
        print("Все голоса уже есть:", ", ".join(VOICES))
        return
    model = load_model(DESIGN_MODEL)
    for v in todo:
        _, instruct, text = VOICES[v]
        res = list(model.generate_voice_design(text=text, instruct=instruct, language="Spanish"))
        wav = np.concatenate([np.array(r.audio, dtype=np.float32).reshape(-1) for r in res])
        sf.write(VOICE_DIR / f"{v}.wav", wav, model.sample_rate)
        (VOICE_DIR / f"{v}.txt").write_text(text + "\n")
        print(f"🎭 {v}: {len(wav) / model.sample_rate:.1f} с")


def upload(zips: list[Path]) -> None:
    if subprocess.run(["gh", "release", "view", RELEASE_TAG, "-R", REPO], capture_output=True).returncode:
        subprocess.run(["gh", "release", "create", RELEASE_TAG, "-R", REPO, "--prerelease", "--latest=false",
                        "--title", "Озвучка книг",
                        "--notes", "Дикторская озвучка книг (Qwen3-TTS). Приложение скачивает архивы само."],
                       check=True)
    for z in zips:
        subprocess.run(["gh", "release", "upload", RELEASE_TAG, str(z), "--clobber", "-R", REPO], check=True)
        print("☁️  Выложено:", URL_BASE + z.name)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("books", nargs="*", type=Path)
    ap.add_argument("--all", action="store_true", help="все встроенные книги (assets/books)")
    ap.add_argument("--voices", action="store_true", help="создать недостающие голоса набора")
    ap.add_argument("--phrasebook", action="store_true", help="озвучить разговорники (assets/phrasebook)")
    ap.add_argument("--scenarios", action="store_true", help="озвучить сценарии и их ключевые фразы")
    ap.add_argument("--clips", type=Path, help="JSON-список [имя, текст, голос] — озвучить в --out")
    ap.add_argument("--out", type=Path, help="папка для --clips")
    ap.add_argument("--upload", action="store_true", help="выложить архивы в GitHub Release «audio»")
    ap.add_argument("--upload-only", action="store_true", help="только выложить уже готовые архивы")
    args = ap.parse_args()

    books = list(args.books)
    if args.all:
        books += [ROOT / "books" / p.name if (ROOT / "books" / p.name).exists() else p
                  for p in sorted(ASSET_BOOKS.glob("*.json"))]
    if args.upload_only:
        upload([WORK / f"{json.loads(p.read_text())['id']}.zip" for p in books])
        return
    ensure_venv()
    if args.voices:
        make_voices()
    if not books and not args.phrasebook and not args.scenarios and not args.clips:
        return
    make_voices()
    engine = Engine()
    if args.clips:
        voice_set(engine, [tuple(c) for c in json.loads(args.clips.read_text(encoding="utf-8"))], args.out,
                  args.out.parent.name + "-" + args.out.name)
    if args.phrasebook or args.scenarios:
        voice_phrasebook(engine)
    if args.scenarios:
        voice_scenarios(engine)
    for p in books:
        z = voice_book(p, engine)
        if args.upload:
            upload([z])


if __name__ == "__main__":
    main()
