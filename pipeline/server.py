#!/usr/bin/env python3
"""Сервер «Мои фразы» в домашней сети: телефон сам находит Mac и присылает записи на перевод — без кабеля.

  python3 pipeline/server.py              # запустить (Ctrl+C — остановить)
  python3 pipeline/server.py --level A0   # переводить проще (по умолчанию A1)
  python3 pipeline/server.py --new-code   # сменить код для телефона

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

ЛИЧНЫЕ ДАННЫЕ: всё лежит только в books/.work/personal (не в git) и на телефоне.
"""
from __future__ import annotations

import argparse
import asyncio
import errno
import hmac
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
VENV = HERE / ".cache" / "audio-venv"
sys.path.insert(0, str(HERE))

PORT = 8765
SERVICE = "_lectura._tcp"
NAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
PROTOCOL = 1


def ensure_venv() -> None:
    """Перезапуск в venv озвучки (mlx-audio) — там же ставится aiohttp."""
    if Path(sys.prefix).resolve() == VENV.resolve():
        return
    py = VENV / "bin" / "python"
    if not py.exists():
        print("📦 Готовлю окружение озвучки (один раз, несколько минут)…")
        subprocess.run([sys.executable, str(HERE / "audio.py"), "--voices"], check=True)
    if subprocess.run([str(py), "-c", "import aiohttp"], capture_output=True).returncode:
        print("📦 Ставлю aiohttp в", VENV)
        subprocess.run(["uv", "pip", "install", "-q", "--python", str(py), "aiohttp"], check=True)
    os.execv(str(py), [str(py), __file__, *sys.argv[1:]])


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.168.0.1", 9))  # пакетов не шлёт — только выбирает интерфейс
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def valid_entry(e: object) -> bool:
    return (isinstance(e, dict) and isinstance(e.get("id"), int) and e.get("kind") in ("about", "phrase")
            and isinstance(e.get("ru"), str) and 0 < len(e["ru"].strip()) <= 1000
            and isinstance(e.get("ref", ""), str) and len(e.get("ref", "")) <= 60
            and isinstance(e.get("question", ""), str) and len(e.get("question", "")) <= 300)


class Hub:
    """Очередь записей, один рабочий поток (MLX и claude по очереди) и рассылка статуса."""

    def __init__(self, level: str, model: str, effort: str, audio: bool):
        import personal

        self.p = personal
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
        while True:
            if not self.queue:
                self.wake.clear()
                await self.wake.wait()
                continue
            e = self.current = self.queue.pop(0)
            key = self.p.entry_key(e)
            try:
                if key not in self.results:
                    await self.set_phase("перевожу")
                    log(f"✍️  {e['ru'][:70]}")
                    out = await loop.run_in_executor(
                        self.pool, self.p.translate, e, self.level, self.model, self.effort)
                    self.results[key] = self.p.make_result(e, out)
                    self.p.save_results(self.results)
                    for ph in self.results[key]["phrases"]:
                        log(f"   → {ph['es']}")
                if self.audio:
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

    def voice(self) -> None:
        """Все клипы всех результатов (готовые пропускаются по кэшу, лишние удаляются — как в personal.py)."""
        import audio

        audio.voice_set(self.engine, self.p.clips_for(list(self.results.values())), self.p.AUDIO,
                        self.p.AUDIO.parent.name + "-" + self.p.AUDIO.name)


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
        await ws.send_json({"type": "hello", "protocol": PROTOCOL, "audio": hub.audio})
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

    async def audio_file(request):
        name = request.match_info["name"]
        f = hub.p.AUDIO / f"{name}.m4a"
        if not NAME_RE.match(name) or not f.is_file():
            raise web.HTTPNotFound()
        return web.FileResponse(f, headers={"Content-Type": "audio/mp4"})

    app = web.Application(middlewares=[auth], client_max_size=2 * 1024 * 1024)
    app.add_routes([web.get("/ws", ws_handler), web.post("/sync", sync), web.get("/audio/{name}", audio_file)])
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
    hub = Hub(args.level, args.model, args.effort, not args.no_audio)
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
    bonjour = subprocess.Popen(["dns-sd", "-R", f"Lectura {socket.gethostname().split('.')[0]}", SERVICE,
                                "local", str(args.port)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"""
🟢 Сервер «Мои фразы» запущен. Остановить — Ctrl+C.
   Код для телефона:  {code}
   Адрес (если телефон не найдёт сам):  {local_ip()}:{args.port}
   Данные: {personal.WORK}
""", flush=True)
    worker = asyncio.create_task(hub.run())
    try:
        await asyncio.Event().wait()
    finally:
        worker.cancel()
        bonjour.terminate()
        await runner.cleanup()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--level", default="A1", choices=["A0", "A1"])
    ap.add_argument("--model", default="opus")
    ap.add_argument("--effort", default="medium")
    ap.add_argument("--no-audio", action="store_true", help="без озвучки (в приложении будет системный голос)")
    ap.add_argument("--new-code", action="store_true", help="сменить код для телефона")
    args = ap.parse_args()
    ensure_venv()
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        print("\n🔴 Сервер остановлен.")


if __name__ == "__main__":
    main()
