#!/usr/bin/env python3
"""Who writes the texts for the home server: Claude Code (the user's subscription) or a local model.

  python3 pipeline/llm.py                 # which backend would be used, and is it ready
  python3 pipeline/llm.py --local         # prepare the local model (installs nothing but the model itself)

- Claude: `claude -p` from Claude Code, logged in with a claude.ai account (`claude auth status`).
- Local: a model in Ollama (https://ollama.com), reached over its HTTP API on 127.0.0.1:11434.
  Works on macOS and Windows; a missing model is downloaded on the first start.
  The default model was chosen by a comparison on a 16 GB machine, see docs/live-chat.md.

Only the standard library is used here, so personal.py and chat.py can import it from any Python.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

DEFAULT_LOCAL_MODEL = "gemma4:e4b"
MIN_RAM_GB = 16
CHAT_CTX = 16384          # system prompt with the level word list (~6–7k tokens) + a long conversation.
                          # The same value for every call: a different num_ctx makes Ollama reload the model.
KEEP_ALIVE = "20m"        # keep the model in memory between turns


def ollama_url() -> str:
    host = os.environ.get("OLLAMA_HOST", "").strip() or "127.0.0.1:11434"
    if host.startswith("0.0.0.0"):
        host = "127.0.0.1" + host[len("0.0.0.0"):]
    return (host if "://" in host else "http://" + host).rstrip("/")


@dataclass
class Backend:
    kind: str                      # "claude" | "local"
    model: str = ""                # Ollama tag for "local"
    think: bool = False            # the local model has a thinking mode (it is switched off)
    info: dict = field(default_factory=dict)

    @property
    def label(self) -> str:
        return "Claude (подписка Claude Code)" if self.kind == "claude" else f"локальная модель {self.model} (Ollama)"


# ------------------------------------------------------------------ Claude

def claude_exe() -> str | None:
    return shutil.which("claude")


def claude_ready() -> tuple[bool, str]:
    exe = claude_exe()
    if not exe:
        return False, "Claude Code не установлен"
    try:
        r = subprocess.run([exe, "auth", "status"], capture_output=True, text=True, timeout=30)
        st = json.loads(r.stdout)
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
        return False, "не удалось проверить вход в Claude Code"
    if not st.get("loggedIn"):
        return False, "в Claude Code не выполнен вход"
    return True, ""


# ------------------------------------------------------------------ Ollama

def _req(path: str, body: dict | None = None, timeout: float = 30):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(ollama_url() + path, data=data,
                                 headers={"Content-Type": "application/json"} if data else {})
    return urllib.request.urlopen(req, timeout=timeout)


def _get_json(path: str, body: dict | None = None, timeout: float = 30) -> dict:
    with _req(path, body, timeout) as r:
        return json.loads(r.read())


def ollama_up() -> bool:
    try:
        _get_json("/api/version", timeout=3)
        return True
    except (OSError, ValueError):
        return False


def install_hint() -> str:
    if sys.platform == "win32":
        return "Установите Ollama: https://ollama.com/download (или в PowerShell: winget install Ollama.Ollama)"
    if sys.platform == "darwin":
        return "Установите Ollama: https://ollama.com/download (или: brew install --cask ollama)"
    return "Установите Ollama: https://ollama.com/download"


def start_ollama() -> None:
    """Start `ollama serve` in the background if the app is installed but not running."""
    if ollama_up():
        return
    exe = shutil.which("ollama")
    if not exe and sys.platform == "win32":
        guess = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "Ollama", "ollama.exe")
        exe = guess if os.path.exists(guess) else None
    if not exe:
        sys.exit("❌ Нет ни Claude Code, ни Ollama — тексты писать некому.\n   " + install_hint()
                 + "\n   Или установите Claude Code и войдите в аккаунт: https://claude.com/claude-code")
    print("🦙 Запускаю Ollama…", flush=True)
    flags = 0x08000000 if sys.platform == "win32" else 0  # CREATE_NO_WINDOW
    subprocess.Popen([exe, "serve"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     stdin=subprocess.DEVNULL, creationflags=flags, start_new_session=sys.platform != "win32")
    for _ in range(60):
        if ollama_up():
            return
        time.sleep(0.5)
    sys.exit("❌ Ollama не запустилась. Откройте приложение Ollama вручную и запустите сервер ещё раз.")


def has_model(model: str) -> bool:
    names = {m.get("name") for m in _get_json("/api/tags").get("models", [])}
    return model in names or (":" not in model and f"{model}:latest" in names)


def pull(model: str) -> None:
    print(f"📦 Скачиваю модель {model} (один раз, несколько ГБ)…", flush=True)
    last = 0.0
    with _req("/api/pull", {"model": model, "stream": True}, timeout=3600) as r:
        for line in r:
            ev = json.loads(line)
            if ev.get("error"):
                sys.exit(f"❌ Не удалось скачать {model}: {ev['error']}")
            total, done = ev.get("total"), ev.get("completed")
            if total and done and time.time() - last > 5:
                last = time.time()
                print(f"   {done / total:5.0%}  {done / 1e9:.1f} / {total / 1e9:.1f} ГБ", flush=True)
    print("   ✅ модель скачана", flush=True)


def ram_gb() -> float:
    try:
        if sys.platform == "win32":
            import ctypes

            class MEM(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

            m = MEM()
            m.dwLength = ctypes.sizeof(MEM)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
            return m.ullTotalPhys / 2**30
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 2**30
    except (OSError, ValueError, AttributeError):
        return 0.0


def prepare_local(model: str) -> Backend:
    start_ollama()
    if not has_model(model):
        pull(model)
    caps = _get_json("/api/show", {"model": model}).get("capabilities", [])
    ram = ram_gb()
    if 0 < ram < MIN_RAM_GB - 0.5:
        print(f"⚠️  Памяти {ram:.0f} ГБ, а модели {model} нужно от {MIN_RAM_GB} ГБ — может работать очень медленно.",
              flush=True)
    return Backend("local", model, think="thinking" in caps)


def warm_up(b: Backend) -> None:
    """Load the model into memory now, so the first chat turn doesn't wait for it."""
    if b.kind == "local":
        try:
            with _req("/api/generate", {"model": b.model, "prompt": "", "keep_alive": KEEP_ALIVE}, timeout=300):
                pass
        except OSError:
            pass


def choose(pref: str, local_model: str = DEFAULT_LOCAL_MODEL) -> Backend:
    """pref: auto (Claude if it is installed and logged in, else local) | claude | local."""
    if pref in ("auto", "claude"):
        ok, why = claude_ready()
        if ok:
            return Backend("claude")
        if pref == "claude":
            sys.exit(f"❌ {why}. Установите Claude Code и войдите в аккаунт или запустите с --llm local.")
        print(f"ℹ️  {why} — тексты будет писать локальная модель.", flush=True)
    return prepare_local(local_model)


# ------------------------------------------------------------------ calls

def _options(b: Backend, extra: dict | None = None) -> dict:
    body: dict = {"model": b.model, "keep_alive": KEEP_ALIVE}
    if b.think:
        body["think"] = False  # thinking would add seconds before the first word
    return {**body, **(extra or {})}


def parse_json_object(text: str) -> dict | None:
    """The first {...} object in a model answer (tolerates ```json fences and text around it)."""
    a, b = text.find("{"), text.rfind("}")
    if a < 0 or b <= a:
        return None
    try:
        out = json.loads(text[a: b + 1])
    except json.JSONDecodeError:
        return None
    return out if isinstance(out, dict) else None


def local_json(b: Backend, prompt: str, system: str, schema: dict, retries: int = 2) -> dict:
    """One structured answer. The schema goes into the prompt instead of Ollama's `format`:
    Ollama's MLX runner (used for some models on Apple silicon) hangs on `format` (2026-11, Ollama 0.40)."""
    task = (prompt + "\n\nAnswer with ONE JSON object and nothing else. It must match this JSON schema "
            "(all required keys present):\n" + json.dumps(schema, ensure_ascii=False))
    body = _options(b, {"messages": [{"role": "system", "content": system}, {"role": "user", "content": task}],
                        "stream": False,
                        "options": {"temperature": 0.3, "num_ctx": CHAT_CTX, "num_predict": 1500}})
    err = ""
    for attempt in range(retries + 1):
        try:
            out = parse_json_object(_get_json("/api/chat", body, timeout=300)["message"]["content"])
            if out is not None and all(k in out for k in schema.get("required", [])):
                return out
            err = "ответ не по схеме"
        except (OSError, ValueError, KeyError) as e:
            err = f"{type(e).__name__}: {e}"[:300]
        print(f"   ! попытка {attempt + 1} не удалась: {err}", file=sys.stderr)
    raise RuntimeError(f"local model failed: {err}")


def json_call(b: Backend | None, prompt: str, system: str, schema: dict, model: str, effort: str) -> dict:
    """Structured answer from whichever backend is active (None = Claude, as before)."""
    if b is not None and b.kind == "local":
        return local_json(b, prompt, system, schema)
    from prepare_book import claude_json

    return claude_json(prompt, system, schema, model, effort)


def stream_chat(b: Backend, messages: list[dict], stop: list[bool]):
    """Yield text chunks of one chat answer (blocking; run it in a thread). stop[0] = True aborts."""
    body = _options(b, {"messages": messages, "stream": True,
                        "options": {"temperature": 0.7, "num_ctx": CHAT_CTX, "num_predict": 1200}})
    with _req("/api/chat", body, timeout=300) as r:
        for line in r:
            if stop[0]:
                return
            ev = json.loads(line)
            if ev.get("error"):
                raise RuntimeError(str(ev["error"])[:300])
            text = (ev.get("message") or {}).get("content") or ""
            if text:
                yield text
            if ev.get("done"):
                return


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--local", action="store_true", help="prepare the local model even if Claude is available")
    ap.add_argument("--local-model", default=DEFAULT_LOCAL_MODEL)
    args = ap.parse_args()
    b = choose("local" if args.local else "auto", args.local_model)
    print(f"✅ Тексты пишет: {b.label}")


if __name__ == "__main__":
    main()
