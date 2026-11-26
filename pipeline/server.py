#!/usr/bin/env python3
"""Сервер «Мои фразы» в домашней сети: телефон сам находит Mac и присылает записи на перевод — без кабеля.

  python3 pipeline/server.py              # запустить (Ctrl+C — остановить)
  python3 pipeline/server.py --level A0   # переводить проще (по умолчанию A1)
  python3 pipeline/server.py --new-code   # сменить код для телефона
  python3 pipeline/server.py --llm local  # писать тексты локальной моделью (Ollama), даже если есть Claude

Runs on macOS and Windows (launchers: `Lectura Server.command`, `Lectura Server.bat`).
- Texts (translations, the conversation partner) come from Claude Code if it is installed and logged in,
  otherwise from a local model in Ollama (pipeline/llm.py).
- The server's own voice (Qwen3-TTS) needs MLX, i.e. a Mac with Apple silicon. Elsewhere the phone reads
  everything with its system voice.

Как устроено:
- Mac объявляет себя в сети через Bonjour (`dns-sd`, встроен в macOS) как _lectura._tcp — телефон
  находит его сам, когда вы дома в той же сети Wi-Fi. Если роутер не пропускает Bonjour, в приложении
  можно указать адрес вручную (он печатается при запуске).
- Телефон держит WebSocket /ws, пока открыта вкладка «Говорить»: сервер присылает статус
  (очередь, что сейчас делает) и «готово» по каждой записи — кружок в приложении зелёный/жёлтый/красный.
- POST /sync — телефон присылает ждущие записи, получает готовые переводы; новые встают в очередь.
  GET /audio/<имя> — озвучка фраз.
- Перевод и озвучка — тот же код и тот же кэш, что у personal.py (books/.work/personal), поэтому
  ничего не переводится дважды и personal.py через adb остаётся запасным путём.
- Без кода (печатается при запуске, хранится в books/.work/personal/server.json) сервер не отвечает:
  иначе любой в той же сети Wi-Fi мог бы тратить вашу подписку Claude.
- «Живой разговор» (pipeline/chat.py): POST /chat/start и /chat/<id>/say — реплика ученика, ответ
  персонажа стримится по тому же WebSocket (chat.delta, chat.sentence), а с голосом Mac каждое
  предложение озвучивается сразу (chat.audio → GET /chat/audio/<имя>.wav). Пока идёт разговор,
  фоновая озвучка «Моих фраз» ждёт: MLX один, и разговор важнее.

ЛИЧНЫЕ ДАННЫЕ: всё лежит только в books/.work/personal (не в git) и на телефоне.
"""
from __future__ import annotations

import argparse
import asyncio
import errno
import hmac
import json
import os
import platform
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
MLX = sys.platform == "darwin" and platform.machine() == "arm64"   # the server voice (Qwen3-TTS) needs MLX
AUDIO_VENV = HERE / ".cache" / "audio-venv"
SERVER_VENV = HERE / ".cache" / "server-venv"
sys.path.insert(0, str(HERE))

import llm  # noqa: E402  (standard library only)

PORT = 8765
SERVICE = "_lectura._tcp"
NAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
PROTOCOL = 1
CHAT_ID_RE = re.compile(r"^[a-f0-9]{8,32}$")
CHAT_IDLE_CLOSE = 15 * 60     # процесс claude простаивающего разговора закрываем (история остаётся)
CHAT_FORGET = 3 * 60 * 60     # совсем забываем разговор
CHAT_QUIET = 120              # столько секунд после реплики фоновая озвучка «Моих фраз» не трогает MLX


def venv_python(venv: Path) -> Path:
    return venv / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def ensure_venv(want_audio: bool) -> None:
    """Restart inside a venv with the server's dependencies.
    Mac with Apple silicon + voice: the audio venv of audio.py (mlx-audio; needs uv) plus aiohttp.
    Otherwise (Windows, Intel Mac, --no-audio without that venv): a small venv with aiohttp (+ zeroconf)."""
    if Path(sys.prefix).resolve() in (AUDIO_VENV.resolve(), SERVER_VENV.resolve()):
        return
    venv, deps = SERVER_VENV, ["aiohttp"] + ([] if sys.platform == "darwin" else ["zeroconf"])
    if MLX and (want_audio or venv_python(AUDIO_VENV).exists()):
        if venv_python(AUDIO_VENV).exists() or shutil.which("uv"):
            venv, deps = AUDIO_VENV, ["aiohttp"]
        else:
            print("⚠️  Для голоса Mac нужен uv (brew install uv) — пока без него, фразы прочитает телефон.")
    py = venv_python(venv)
    if not py.exists():
        if venv == AUDIO_VENV:
            print("📦 Готовлю окружение озвучки (один раз, несколько минут)…", flush=True)
            subprocess.run([sys.executable, str(HERE / "audio.py"), "--voices"], check=True)
        else:
            print("📦 Готовлю окружение сервера (один раз)…", flush=True)
            subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
    imports = ", ".join(d for d in deps)
    if subprocess.run([str(py), "-c", f"import {imports}"], capture_output=True).returncode:
        print("📦 Ставлю", " ".join(deps), flush=True)
        if shutil.which("uv"):
            subprocess.run(["uv", "pip", "install", "-q", "--python", str(py), *deps], check=True)
        else:
            subprocess.run([str(py), "-m", "pip", "install", "-q", "--disable-pip-version-check", *deps], check=True)
    if os.name == "nt":  # no real exec on Windows: run the child and wait for it in the same window
        child = subprocess.Popen([str(py), __file__, *sys.argv[1:]])
        while True:
            try:
                sys.exit(child.wait())
            except KeyboardInterrupt:
                continue  # Ctrl+C reaches the child too; wait for it to stop
    os.execv(str(py), [str(py), __file__, *sys.argv[1:]])


def keep_awake() -> None:
    """Windows: don't let the computer go to sleep while the server runs (on the Mac: caffeinate in the launcher)."""
    if sys.platform == "win32":
        import ctypes

        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)  # ES_CONTINUOUS | ES_SYSTEM_REQUIRED


def drop_stale_dns_sd(port: int) -> None:
    """Stop «dns-sd -R Lectura …» left over from servers killed without cleanup: they keep announcing a dead
    address that the phone may pick. Stale = registered for our port (the port is ours now) or for a port
    nobody listens on."""
    r = subprocess.run(["pgrep", "-f", f"^dns-sd -R Lectura .* {SERVICE} local [0-9]+$"], capture_output=True,
                       text=True)
    for pid in r.stdout.split():
        cmd = subprocess.run(["ps", "-o", "command=", "-p", pid], capture_output=True, text=True).stdout.split()
        if not cmd or not cmd[-1].isdigit():
            continue
        other = int(cmd[-1])
        if other != port:
            with socket.socket() as s:
                s.settimeout(0.5)
                if s.connect_ex(("127.0.0.1", other)) == 0:
                    continue  # another live server: leave its announcement alone
        subprocess.run(["kill", pid], capture_output=True)
        log(f"🧹 убрал старое объявление в сети (порт {other})")


async def advertise(name: str, port: int):
    """Announce _lectura._tcp in the local network (the phone finds the server by it).
    macOS: the built-in dns-sd. Elsewhere: python-zeroconf. Returns a stop() coroutine function."""
    if sys.platform == "darwin":
        drop_stale_dns_sd(port)
        p = subprocess.Popen(["dns-sd", "-R", name, SERVICE, "local", str(port)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        async def stop_dns_sd() -> None:
            p.terminate()
        return stop_dns_sd
    try:
        from zeroconf import IPVersion, ServiceInfo
        from zeroconf.asyncio import AsyncZeroconf

        ip = local_ip()
        info = ServiceInfo(f"{SERVICE}.local.", f"{name}.{SERVICE}.local.", port=port,
                           addresses=[socket.inet_aton(ip)], server=f"{socket.gethostname().split('.')[0]}.local.")
        zc = AsyncZeroconf(ip_version=IPVersion.V4Only, interfaces=[ip])
        await zc.async_register_service(info)
    except Exception as e:  # noqa: BLE001 — the address can still be typed in the app
        log(f"⚠️  объявить сервер в сети не получилось ({e!r}); укажите адрес в приложении вручную")

        async def nothing() -> None:
            pass
        return nothing

    async def stop_zeroconf() -> None:
        await zc.async_unregister_service(info)
        await zc.async_close()
    return stop_zeroconf


def clear_mlx_cache() -> None:
    """Сбросить кэш буферов MLX. TTS и Whisper вместе на 16 ГБ без этого замедляют друг друга в 3–5 раз
    (замер: TTS предложения 4 с → 18 с после первого распознавания Whisper)."""
    try:
        import mlx.core as mx

        mx.clear_cache()
    except Exception:  # noqa: BLE001 — старый mlx: просто без очистки
        pass


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.168.0.1", 9))  # пакетов не шлёт — только выбирает интерфейс
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def _s(x: object, limit: int) -> str:
    return x.strip()[:limit] if isinstance(x, str) else ""


def clean_topic(t: object) -> dict | None:
    """Тема разговора с телефона: только известные поля и разумной длины."""
    if not isinstance(t, dict):
        return None
    goals = []
    for g in t.get("goals") or []:
        if isinstance(g, dict) and _s(g.get("id"), 40) and _s(g.get("ru"), 200):
            goals.append({"id": _s(g["id"], 40), "ru": _s(g["ru"], 200)})
    out = {"title": _s(t.get("title"), 120), "scene": _s(t.get("scene"), 1500), "role": _s(t.get("role"), 300),
           "gender": t.get("gender") if t.get("gender") in ("m", "f") else "f", "usted": bool(t.get("usted")),
           "goals": goals[:8],
           "vocab": [v for v in t.get("vocab") or [] if isinstance(v, str) and re.fullmatch(r"[a-z-]{1,40}", v)][:8]}
    return out if out["title"] or out["scene"] else None


def clean_about(a: object) -> list[dict]:
    out = []
    for x in a if isinstance(a, list) else []:
        if isinstance(x, dict) and _s(x.get("q"), 200) and _s(x.get("a"), 600):
            out.append({"q": _s(x["q"], 200), "a": _s(x["a"], 600)})
    return out[:40]


def clean_history(h: object) -> list[dict]:
    out = []
    for x in h if isinstance(h, list) else []:
        if isinstance(x, dict) and isinstance(x.get("reply"), str):
            out.append({"user": _s(x.get("user"), 1000), "reply": _s(x["reply"], 1000)})
    return out[-40:]


def valid_entry(e: object) -> bool:
    return (isinstance(e, dict) and isinstance(e.get("id"), int) and e.get("kind") in ("about", "phrase")
            and isinstance(e.get("ru"), str) and 0 < len(e["ru"].strip()) <= 1000
            and isinstance(e.get("ref", ""), str) and len(e.get("ref", "")) <= 60
            and isinstance(e.get("question", ""), str) and len(e.get("question", "")) <= 300)


class Hub:
    """Очередь записей, один рабочий поток (MLX и claude по очереди) и рассылка статуса."""

    def __init__(self, level: str, model: str, effort: str, audio: bool, chat_model: str = "sonnet",
                 chat_effort: str = "low", backend: llm.Backend | None = None):
        import personal

        self.p = personal
        self.backend = backend
        self.chats: dict = {}
        self.chat_model, self.chat_effort = chat_model, chat_effort
        self.chat_quiet_until = 0.0
        self.profile_lock = asyncio.Lock()
        self.chat_audio = personal.WORK / "chat-audio"
        self.last_tts = 0.0
        self.level, self.model, self.effort, self.audio = level, model, effort, audio
        self.results: dict[str, dict] = personal.load_results()
        self.queue: list[dict] = []
        self.current: dict | None = None
        self.phase = ""
        self.clients: set = set()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="work")  # один поток: MLX не любит чужие
        self.engine = None
        self.wake = asyncio.Event()

    # ---------------------------------------------------------------- состояние

    def ready(self, e: dict) -> dict | None:
        """Готовый результат: переведено и (если нужна озвучка) все файлы на месте."""
        r = self.results.get(self.p.entry_key(e))
        if r is None:
            return None
        if self.audio and any(not (self.p.AUDIO / f"{name}.m4a").exists() for name, _, _ in self.p.clips_for([r])):
            return None
        return r

    def status(self) -> dict:
        cur = self.current
        return {"type": "status", "queue": len(self.queue) + (1 if cur else 0), "busy": bool(cur or self.phase),
                "phase": self.phase, "current": (cur or {}).get("ru", "")[:80]}

    async def broadcast(self, msg: dict) -> None:
        for ws in list(self.clients):
            try:
                await ws.send_json(msg)
            except Exception:  # noqa: BLE001 — отвалившийся клиент уберёт его обработчик
                self.clients.discard(ws)

    async def set_phase(self, phase: str) -> None:
        self.phase = phase
        await self.broadcast(self.status())

    # ---------------------------------------------------------------- приём записей

    async def submit(self, entries: list[dict]) -> list[dict]:
        """Готовые результаты для присланных записей; недостающие — в очередь."""
        out, added = [], 0
        queued = {self.p.entry_key(e) for e in self.queue} | ({self.p.entry_key(self.current)} if self.current else set())
        for e in entries:
            r = self.ready(e)
            if r is not None:
                out.append(r)
            elif self.p.entry_key(e) not in queued:
                self.queue.append(e)
                queued.add(self.p.entry_key(e))
                added += 1
        if added:
            log(f"📥 новых записей: {added}, в очереди: {len(self.queue)}")
            self.wake.set()
            await self.broadcast(self.status())
        return out

    # ---------------------------------------------------------------- работа

    async def run(self) -> None:
        loop = asyncio.get_running_loop()
        if self.audio:
            await self.set_phase("готовлю голос")
            try:
                await loop.run_in_executor(self.pool, self.load_engine)
                log("🎙️  голос загружен")
            except (Exception, SystemExit) as e:  # noqa: BLE001 — без озвучки сервер всё равно переводит
                log(f"⚠️  озвучка недоступна ({e}); фразы будут читаться системным голосом")
                self.audio = False
            await self.set_phase("")
        if self.backend is not None and self.backend.kind == "local":
            await self.set_phase("загружаю модель")
            await loop.run_in_executor(None, llm.warm_up, self.backend)
            log("🦙 локальная модель загружена")
            await self.set_phase("")
        while True:
            if not self.queue:
                self.wake.clear()
                await self.wake.wait()
                continue
            e = self.current = self.queue.pop(0)
            key = self.p.entry_key(e)
            try:
                if key not in self.results:
                    # A local model answers one request at a time: the conversation goes first.
                    while self.backend is not None and self.backend.kind == "local" and time.time() < self.chat_quiet_until:
                        await self.set_phase("жду конца разговора")
                        await asyncio.sleep(5)
                    await self.set_phase("перевожу")
                    log(f"✍️  {e['ru'][:70]}")
                    # Перевод — это claude, не MLX: в общем пуле потоков, чтобы не задерживать озвучку разговора.
                    out = await loop.run_in_executor(
                        None, self.p.translate, e, self.level, self.model, self.effort, self.backend)
                    self.results[key] = self.p.make_result(e, out)
                    self.p.save_results(self.results)
                    for ph in self.results[key]["phrases"]:
                        log(f"   → {ph['es']}")
                if self.audio:
                    while time.time() < self.chat_quiet_until:  # идёт разговор — MLX нужен ему
                        await self.set_phase("жду конца разговора")
                        await asyncio.sleep(5)
                    await self.set_phase("озвучиваю")
                    await loop.run_in_executor(self.pool, self.voice)
                self.current = None
                self.phase = ""
                await self.broadcast({"type": "done", "inboxId": e["id"]})
                log(f"✅ готово: {e['ru'][:70]}")
            except Exception as err:  # noqa: BLE001 — одна запись не должна ронять сервер
                log(f"❌ не получилось: {e['ru'][:70]} — {err}")
                self.current = None
                self.phase = ""
                await self.broadcast({"type": "error", "inboxId": e["id"], "text": str(err)[:200]})
            await self.broadcast(self.status())

    def load_engine(self) -> None:
        import audio

        audio.make_voices()
        self.engine = audio.Engine()
        # Прогрев: первая генерация каждым голосом и первое распознавание Whisper в разы медленнее
        # (компиляция, загрузка модели) — пусть это будет не реплика разговора.
        for v in ("f2", "m2"):
            wav = self.engine.tts("Hola, ¿qué tal?", v)
        self.engine.stt(wav)
        clear_mlx_cache()
        self.last_tts = time.time()

    def voice(self) -> None:
        """Все клипы всех результатов (готовые пропускаются по кэшу, лишние удаляются — как в personal.py)."""
        import audio

        audio.voice_set(self.engine, self.p.clips_for(list(self.results.values())), self.p.AUDIO,
                        self.p.AUDIO.parent.name + "-" + self.p.AUDIO.name)
        clear_mlx_cache()


    # ---------------------------------------------------------------- «Живой разговор»

    async def chat_event(self, chat: str, ev: dict) -> None:
        await self.broadcast({**ev, "type": "chat." + ev["type"], "chat": chat})

    def chat_turn_events(self, chat, audio: bool, whisper: bool):
        """Колбэк для ChatSession.say: пересылает дельты на телефон и ставит предложения в озвучку."""
        async def on_event(ev: dict) -> None:
            self.chat_quiet_until = time.time() + CHAT_QUIET
            await self.chat_event(chat.id, ev)
            if ev["type"] == "sentence" and audio and self.audio:
                asyncio.create_task(self.chat_tts(chat, ev["turn"], ev["seq"], ev["text"], whisper))
        return on_event

    async def chat_tts(self, chat, turn: int, seq: int, text: str, whisper: bool) -> None:
        """Озвучить одно предложение реплики голосом Mac. Задачи идут в один поток MLX по порядку."""
        loop = asyncio.get_running_loop()
        voice = "m2" if chat.topic.get("gender") == "m" else "f2"
        name = f"{chat.id}-{turn}-{seq}"
        try:
            res = await loop.run_in_executor(self.pool, self.synth, chat, turn, name, text, voice, whisper)
        except Exception as err:  # noqa: BLE001 — телефон прочитает это предложение своим голосом
            log(f"❌ озвучка реплики: {err}")
            await self.chat_event(chat.id, {"type": "audio_error", "turn": turn, "seq": seq, "text": str(err)[:200]})
            return
        if res is None:  # ученик уже ответил — старую реплику не озвучиваем
            return
        ms, errors = res
        log(f"   🔊 {ms} мс{' (Whisper: ошибок ' + str(errors) + ')' if whisper else ''}: {text[:60]}")
        await self.chat_event(chat.id, {"type": "audio", "turn": turn, "seq": seq, "name": name, "ms": ms,
                                        "whisper": whisper, "errors": errors})

    def synth(self, chat, turn: int, name: str, text: str, voice: str, whisper: bool):
        import numpy as np
        import soundfile as sf

        import audio

        if chat.turn != turn or chat.closed:
            return None
        t0 = time.time()
        errors = -1
        if whisper:
            wav, errors, _ = self.engine.segment(text, voice, attempts=3)
        else:
            wav = audio.trim(self.engine.tts(text, voice))
        clear_mlx_cache()
        voiced = wav[np.abs(wav) > 1e-3]
        if len(voiced):
            wav = wav * (0.1 / np.sqrt(np.mean(voiced ** 2)))  # ровная громкость, как в audio.encode
        wav = np.clip(wav, -0.98, 0.98)
        self.chat_audio.mkdir(parents=True, exist_ok=True)
        tmp = self.chat_audio / f"{name}.part"
        sf.write(tmp, wav, audio.SR, subtype="PCM_16", format="WAV")  # WAV: без перекодирования — быстрее
        tmp.rename(self.chat_audio / f"{name}.wav")
        self.last_tts = time.time()
        return int((time.time() - t0) * 1000), errors

    async def chat_warmup(self, voice: str) -> None:
        """Голос давно молчал — прогреть, пока модель пишет первую реплику. После простоя (особенно
        когда память занята другими программами) первая фраза озвучивалась до 20 с вместо 3–4."""
        if not self.audio or self.engine is None or time.time() - self.last_tts < 60:
            return
        self.last_tts = time.time()

        def warm() -> None:
            self.engine.tts("Vale.", voice)
            clear_mlx_cache()

        try:
            await asyncio.get_running_loop().run_in_executor(self.pool, warm)
        except Exception as err:  # noqa: BLE001
            log(f"⚠️  прогрев голоса: {err}")

    async def chat_close(self, cid: str, forget: bool, profile: bool = False) -> None:
        """profile: the conversation is over (the phone ended it, or it was forgotten) — update the learner
        profile from it in the background."""
        chat = self.chats.get(cid)
        if chat is None:
            return
        if profile and forget and sum(1 for t in chat.turns if t.get("user") != "[START]") >= 2:
            asyncio.create_task(self.profile_update(cid))
        await chat.close()
        if forget:
            chat.closed = True
            self.chats.pop(cid, None)
            for f in self.chat_audio.glob(f"{cid}-*"):
                f.unlink(missing_ok=True)

    async def profile_update(self, cid: str) -> None:
        """One model call after a conversation (pipeline/learner.py): typical mistakes, what works, what to push.
        A local model answers one request at a time, so it waits while a conversation is going on."""
        import learner

        async with self.profile_lock:
            while self.backend is not None and self.backend.kind == "local" and time.time() < self.chat_quiet_until:
                await asyncio.sleep(10)
            try:
                prof = await asyncio.to_thread(learner.update_profile, cid, self.backend, self.model, self.effort)
            except Exception as err:  # noqa: BLE001 — the next conversation just keeps the old profile
                log(f"⚠️  профиль ученика не обновлён: {err}")
                return
            if prof:
                log("🧠 профиль ученика обновлён: "
                    + ("; ".join(m["ru"] or m["pattern"] for m in prof["mistakes"]) or "без типичных ошибок"))

    async def chat_janitor(self) -> None:
        for f in self.chat_audio.glob("*"):  # остатки прошлого запуска
            f.unlink(missing_ok=True)
        while True:
            await asyncio.sleep(60)
            now = time.time()
            for cid, chat in list(self.chats.items()):
                if now - chat.used > CHAT_FORGET:
                    await self.chat_close(cid, forget=True, profile=True)
                    log(f"💬 разговор {cid} забыт")
                elif chat.alive and now - chat.used > CHAT_IDLE_CLOSE and not chat.lock.locked():
                    await chat.close()


# -------------------------------------------------------------------- HTTP

def make_app(hub: Hub, code: str):
    from aiohttp import WSMsgType, web

    fails: list[float] = []

    @web.middleware
    async def auth(request, handler):
        given = request.headers.get("X-Lectura-Code", "")
        if not hmac.compare_digest(given.encode(), code.encode()):
            now = time.time()
            fails[:] = [t for t in fails if now - t < 60] + [now]
            await asyncio.sleep(min(len(fails), 10) * 0.5)  # перебор кода — медленно
            log(f"🔒 неверный код от {request.remote}")
            return web.json_response({"error": "code"}, status=401)
        return await handler(request)

    async def ws_handler(request):
        ws = web.WebSocketResponse(heartbeat=15)
        await ws.prepare(request)
        hub.clients.add(ws)
        log(f"📱 телефон подключился ({request.remote})")
        await ws.send_json({"type": "hello", "protocol": PROTOCOL, "audio": hub.audio, "chat": True})
        await ws.send_json(hub.status())
        try:
            async for msg in ws:
                if msg.type == WSMsgType.ERROR:
                    break
        finally:
            hub.clients.discard(ws)
            log("📱 телефон отключился")
        return ws

    async def sync(request):
        try:
            body = await request.json()
        except ValueError:
            return web.json_response({"error": "json"}, status=400)
        items = body.get("items") if isinstance(body, dict) else None
        if not isinstance(items, list) or len(items) > 500 or not all(valid_entry(e) for e in items):
            return web.json_response({"error": "items"}, status=400)
        results = await hub.submit(items)
        return web.json_response({"results": results, **hub.status()})

    async def progress(request):
        """The phone's progress snapshot → learner.db (pipeline/learner.py); sent before every conversation."""
        import learner

        snap = learner.clean_snapshot(await body_of(request))
        if snap is None:
            return web.json_response({"error": "progress"}, status=400)
        cov = await asyncio.to_thread(learner.store, snap)
        log(f"📊 прогресс: {learner.describe(cov)}")
        return web.json_response({"ok": True, **cov})

    async def audio_file(request):
        name = request.match_info["name"]
        f = hub.p.AUDIO / f"{name}.m4a"
        if not NAME_RE.match(name) or not f.is_file():
            raise web.HTTPNotFound()
        return web.FileResponse(f, headers={"Content-Type": "audio/mp4"})

    async def body_of(request) -> dict:
        try:
            body = await request.json()
        except ValueError:
            raise web.HTTPBadRequest(text='{"error": "json"}', content_type="application/json")
        if not isinstance(body, dict):
            raise web.HTTPBadRequest(text='{"error": "json"}', content_type="application/json")
        return body

    async def chat_turn(chat, text: str, body: dict):
        audio = bool(body.get("audio"))
        whisper = bool(body.get("whisper"))
        log(f"💬 {text[:80]}")
        try:
            out = await chat.say(text, hub.chat_turn_events(chat, audio, whisper))
        except Exception as err:  # noqa: BLE001
            log(f"❌ разговор: {err}")
            return web.json_response({"error": "chat", "text": str(err)[:300]}, status=502)
        log(f"🤖 {out['reply'][:80]}  ({out['firstMs']} / {out['totalMs']} мс)")
        import learner

        try:
            await asyncio.to_thread(learner.record_turn, chat.id, chat.topic.get("title") or chat.topic.get("scene", ""),
                                    chat.level, text, out)
        except Exception as err:  # noqa: BLE001 — the journal must not break the conversation
            log(f"⚠️  журнал разговора: {err}")
        return web.json_response({"chat": chat.id, "audio": hub.audio and audio, **out})

    async def chat_start(request):
        from chat import LEVELS, make_session

        body = await body_of(request)
        cid = body.get("chat")
        topic = clean_topic(body.get("topic"))
        level = body.get("level")
        if not isinstance(cid, str) or not CHAT_ID_RE.match(cid) or topic is None or level not in LEVELS:
            return web.json_response({"error": "chat"}, status=400)
        await hub.chat_close(cid, forget=True)
        for old in sorted(hub.chats.values(), key=lambda c: c.used)[:-2]:  # больше трёх процессов не держим
            await old.close()
        import learner

        words, tags = await asyncio.to_thread(learner.chat_context, level, topic["vocab"], True)
        chat = make_session(hub.backend, cid, level, topic, clean_about(body.get("about")), hub.chat_model,
                            hub.chat_effort, words)
        chat.learner_words = tags
        chat.history = clean_history(body.get("history"))
        hub.chats[cid] = chat
        log(f"💬 новый разговор: {topic['title'] or topic['scene'][:60]} · {level}")
        text = _s(body.get("text"), 1000) or "[START]"
        if body.get("audio"):
            asyncio.create_task(hub.chat_warmup("m2" if topic["gender"] == "m" else "f2"))
        return await chat_turn(chat, text, body)

    async def chat_say(request):
        chat = hub.chats.get(request.match_info["cid"])
        if chat is None:
            return web.json_response({"error": "nochat"}, status=404)
        body = await body_of(request)
        text = _s(body.get("text"), 1000)
        if not text:
            return web.json_response({"error": "text"}, status=400)
        return await chat_turn(chat, text, body)

    async def chat_end(request):
        await hub.chat_close(request.match_info["cid"], forget=True, profile=True)
        hub.chat_quiet_until = 0
        return web.json_response({"ok": True})

    async def chat_audio(request):
        name = request.match_info["name"]
        f = hub.chat_audio / f"{name}.wav"
        if not NAME_RE.match(name) or not f.is_file():
            raise web.HTTPNotFound()
        return web.FileResponse(f, headers={"Content-Type": "audio/wav"})

    app = web.Application(middlewares=[auth], client_max_size=4 * 1024 * 1024)  # progress snapshot: ~150 bytes per word
    app.add_routes([web.get("/ws", ws_handler), web.post("/sync", sync), web.post("/progress", progress),
                    web.get("/audio/{name}", audio_file),
                    web.post("/chat/start", chat_start), web.post("/chat/{cid}/say", chat_say),
                    web.post("/chat/{cid}/end", chat_end), web.get("/chat/audio/{name}", chat_audio)])
    return app


def load_code(new: bool) -> str:
    from personal import WORK

    f = WORK / "server.json"
    data = json.loads(f.read_text()) if f.exists() else {}
    if new or not re.fullmatch(r"\d{6}", data.get("code", "")):
        data["code"] = f"{secrets.randbelow(10 ** 6):06d}"
        WORK.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(data))
        os.chmod(f, 0o600)
    return data["code"]


async def serve(args) -> None:
    from aiohttp import web

    import personal

    code = load_code(args.new_code)
    hub = Hub(args.level, args.model, args.effort, not args.no_audio and MLX, args.chat_model, args.chat_effort,
              args.backend)
    runner = web.AppRunner(make_app(hub, code), access_log=None)
    await runner.setup()
    try:
        await web.TCPSite(runner, "0.0.0.0", args.port).start()
    except OSError as e:
        await runner.cleanup()
        if e.errno == errno.EADDRINUSE:
            print(f"\n🟡 Порт {args.port} уже занят — скорее всего, сервер уже запущен в другом окне Терминала.\n"
                  "   Второй не нужен: телефон работает с тем, что уже запущен.")
            return
        raise
    stop_advertising = await advertise(f"Lectura {socket.gethostname().split('.')[0]}", args.port)
    keep_awake()
    voice = "голос Mac (Qwen3-TTS)" if hub.audio else "голос телефона"
    firewall = ("\n   Windows спросит про брандмауэр — разрешите Python доступ к частным сетям."
                if sys.platform == "win32" else "")
    print(f"""
🟢 Сервер «Мои фразы» запущен. Остановить — Ctrl+C.
   Код для телефона:  {code}
   Адрес (если телефон не найдёт сам):  {local_ip()}:{args.port}
   Тексты пишет: {args.backend.label}; озвучка: {voice}
   Данные: {personal.WORK}{firewall}
""", flush=True)
    worker = asyncio.create_task(hub.run())
    janitor = asyncio.create_task(hub.chat_janitor())
    try:
        await asyncio.Event().wait()
    finally:
        worker.cancel()
        janitor.cancel()
        for cid in list(hub.chats):
            await hub.chat_close(cid, forget=True)
        await stop_advertising()
        await runner.cleanup()


def stop_on_term(*_) -> None:
    raise KeyboardInterrupt


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--level", default="A1", choices=["A0", "A1"])
    ap.add_argument("--model", default="opus")
    ap.add_argument("--effort", default="medium")
    ap.add_argument("--no-audio", action="store_true", help="без озвучки (в приложении будет системный голос)")
    ap.add_argument("--chat-model", default="sonnet", help="модель «Живого разговора» (по умолчанию sonnet)")
    ap.add_argument("--chat-effort", default="low")
    ap.add_argument("--llm", default="auto", choices=["auto", "claude", "local"],
                    help="кто пишет тексты: auto — Claude, если Claude Code установлен и выполнен вход, иначе "
                         "локальная модель (Ollama)")
    ap.add_argument("--local-model", default=llm.DEFAULT_LOCAL_MODEL, help="модель Ollama для --llm local/auto")
    ap.add_argument("--mac-voice", action="store_true",
                    help="голос Mac и с локальной моделью (на машине меньше 24 ГБ всё станет медленнее)")
    ap.add_argument("--new-code", action="store_true", help="сменить код для телефона")
    args = ap.parse_args()
    if os.name == "nt":
        for stream in (sys.stdout, sys.stderr):
            stream.reconfigure(encoding="utf-8", errors="replace")
    ensure_venv(not args.no_audio)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, stop_on_term)  # `kill` cleans up like Ctrl+C (no orphaned dns-sd)
    args.backend = llm.choose(args.llm, args.local_model)
    if args.backend.kind == "local" and MLX and not args.no_audio and not args.mac_voice and llm.ram_gb() < 24:
        # Measured on a 16 GB M1 Pro (2026-11): with the voice loaded, gemma4:e4b's first sentence took 4.5–7 s
        # instead of 0.3–0.7 s, and a voiced sentence 17–24 s instead of ~3.5 s.
        print("ℹ️  Локальная модель и голос Mac вместе не помещаются в память — озвучивать будет телефон "
              "(включить голос Mac: --mac-voice).", flush=True)
        args.no_audio = True
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        print("\n🔴 Сервер остановлен.")


if __name__ == "__main__":
    main()
