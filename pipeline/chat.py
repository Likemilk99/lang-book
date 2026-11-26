#!/usr/bin/env python3
"""«Живой разговор»: собеседник-персонаж для ученика, реплика за репликой (claude -p в постоянном процессе).

  python3 pipeline/chat.py                         # проверить в терминале (кафе, A1), только текст
  python3 pipeline/chat.py --level A0 --topic "В аптеке: у меня болит голова"
  python3 pipeline/chat.py --llm local             # the same with a local model (Ollama) instead of Claude

Обычно этим пользуется pipeline/server.py: телефон присылает тему, уровень и реплики, сервер
стримит ответ по WebSocket и озвучивает его по предложениям.

Как устроено:
- На разговор — один процесс `claude -p --input-format stream-json --output-format stream-json`:
  история и кэш промпта живут в нём, поэтому ответ начинается через ~1 с (новый `claude -p` на каждую
  реплику — это 5–10 с).
- Системный промпт: роль и сцена, уровень и список слов уровня (assets/curriculum/vocab_*.json),
  факты «Обо мне», цели сцены, слова ученика (pipeline/learner.py: повторить / новые / знает),
  правила (коротко, вопрос в конце, мягкие исправления).
- Ответ модели — размеченный текст, а не --json-schema (та отдаёт результат только в конце):
      <reply>испанская реплика</reply>
      <meta>{"ru": …, "fix": …, "hints": […], "chunks": […], "sample": …, "goals_done": […], "end": false}</meta>
  [ReplyStream] разбирает поток на лету: готовые предложения реплики сразу уходят в озвучку.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import shutil
import sys
import tempfile
import time
import unicodedata
from pathlib import Path
from typing import Awaitable, Callable

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CURRICULUM = ROOT / "android" / "app" / "src" / "main" / "assets" / "curriculum"

LEVELS = ("A0", "A1", "A2", "B1")

LEVEL_RULES = {
    "A0": ("absolute beginner (A0). Your turn: ONE very short sentence plus a very simple question "
           "(at most ~10 words in total). Present tense only. Only the most frequent words. "
           "Repeat key words the learner already used. Speak like to someone who knows 100 words."),
    "A1": ("beginner (A1). Your turn: 1–2 short sentences (at most ~18 words in total). Present tense, "
           "«ir a» + infinitive and fixed polite chunks (quería, me gustaría, ¿me pone…?, ¿cuánto es?). "
           "No subjunctive, no long subordinate clauses."),
    "A2": ("elementary (A2). Your turn: 1–3 sentences (at most ~30 words). Present, pretérito perfecto, "
           "indefinido, imperfecto for descriptions, simple «porque / cuando / que» clauses."),
    "B1": ("intermediate (B1). Natural but clear speech, 1–4 sentences, any common tense, "
           "everyday idioms of Spain are welcome."),
}

# Словарь уровня для промпта: A0/A1 — слова A1, A2 — A1+A2, B1 — без списка (слишком длинный).
VOCAB_FILES = {"A0": ["A1"], "A1": ["A1"], "A2": ["A1", "A2"], "B1": []}

SYSTEM = """You are a role-play conversation partner for a Spanish learner.

THE LEARNER: a native Russian speaker who lives in Spain and learns the Spanish of Spain to speak in everyday \
situations. Level: {level_rule}

THE SCENE: {scene}
YOU PLAY: {role}. Stay in this role the whole time: you are a real person in Spain, not a teacher, not an AI.
FORM OF ADDRESS: {address} — keep it consistent in every turn.
{goals}{facts}{learner}
LANGUAGE RULES
- Speak only Spanish of Spain: vosotros, Spain vocabulary (móvil, coche, zumo, vale, ordenador), euros.
- Write numbers, prices and times in words («un euro con ochenta», «a las cinco y media») — the reply is \
read aloud.
- {vocab_rule}
- Keep turns short; one idea per turn. Most turns end with a question or a clear prompt, so the learner \
always knows what to answer. Steer gently toward the scene goals that are not done yet.
- Never correct the learner inside the reply. Instead, naturally reuse the correct form in your answer \
(recast). Corrections go to meta.fix.
- The learner's messages are often dictated by voice: ignore missing punctuation, capitals and accents, \
and obvious speech-recognition slips (do not «fix» those).
- If the learner writes Russian or mixes in Russian words, understand them, answer in character in simple \
Spanish, and put the Spanish way to say what they meant in meta.fix.
- If the learner goes off-topic, react briefly like a real person and come back to the scene.
- If the learner is stuck («no sé», «?», silence), rephrase your last question more simply or offer \
two options to choose from.

OUTPUT FORMAT — exactly this, nothing before or after:
<reply>your Spanish line(s)</reply>
<meta>{{"ru": "Russian translation of your reply",
"fix": null or {{"es": "the learner's LAST message said correctly and naturally (Spain)", \
"ru": "very short explanation in Russian of what was wrong"}},
"hints": [{{"es": "one word", "ru": "перевод"}}, ...8–12 SINGLE words (no phrases) the learner can combine \
into their NEXT answer: the words for answering you plus related alternatives, so they can build several different \
answers themselves (e.g. at the doctor: fiebre, tos, cansancio, cabeza, garganta, oído, doler, desde, ayer). \
Nouns without article, verbs in the infinitive, mostly from the allowed words],
"chunks": [{{"es": "short phrase piece", "ru": "перевод"}}, ...3–5 ready pieces of 2–4 words for the NEXT answer \
(e.g. tengo fiebre, me duele la garganta, desde ayer) — shown only if single words are not enough],
"sample": {{"es": "one natural answer the learner could say next, at their level", "ru": "перевод"}},
"words": [{{"es": "word from your reply", "ru": "перевод"}}, ...0–4 words of your reply the learner \
probably does not know],
"goals_done": ["ids of ALL scene goals the learner has achieved so far"],
"end": false}}</meta>

meta is one valid JSON object (double quotes, no comments). fix is null when the learner's message was \
fine or is the [START] message. fix is ALSO null when the only differences are punctuation, ¿¡, capitals \
or accent marks — the learner speaks, they do not write. Naturalness tips only when they really help. \
Set "end": true only when the scene has naturally finished (goodbye said).

The first user message is [START]: open the scene with your first line (greeting + an opening question). \
A message starting with [RESUME] gives the conversation so far: continue it from the learner's new message."""


def load_lemmas(level: str) -> list[str]:
    out: list[str] = []
    for lv in VOCAB_FILES.get(level, []):
        f = CURRICULUM / f"vocab_{lv}.json"
        if not f.exists():
            continue
        for t in json.loads(f.read_text(encoding="utf-8"))["themes"]:
            out += [w["l"] for w in t["words"]]
    return list(dict.fromkeys(out))


def build_system(level: str, topic: dict, about: list[dict], learner: str = "") -> str:
    """learner: the LEARNER'S WORDS section from learner.chat_block (their progress mirrored on the Mac)."""
    level = level if level in LEVEL_RULES else "A1"
    lemmas = load_lemmas(level)
    if lemmas:
        vocab_rule = ("Use words from the ALLOWED WORDS list below (any inflected form), names, numbers, and only "
                      "the few extra words the scene really needs. Prefer the simplest, most frequent everyday word.\n"
                      f"ALLOWED WORDS ({len(lemmas)}): " + ", ".join(lemmas))
    else:
        vocab_rule = "Use common everyday vocabulary; avoid rare and regional words."
    goals = topic.get("goals") or []
    goals_txt = ""
    if goals:
        goals_txt = ("SCENE GOALS for the learner (ids for meta.goals_done):\n"
                     + "\n".join(f"- {g['id']}: {g['ru']}" for g in goals) + "\n")
    facts_txt = ""
    if about:
        facts_txt = ("\nFACTS ABOUT THE LEARNER (written by them in Russian; use naturally when it fits, never "
                     "recite; if they say something different in the conversation, believe the conversation):\n"
                     + "\n".join(f"- {a['q']} — {a['a']}" for a in about) + "\n")
    gender = {"f": "a woman", "m": "a man"}.get(topic.get("gender", ""), "a person")
    return SYSTEM.format(
        level_rule=LEVEL_RULES[level],
        scene=topic.get("scene") or topic.get("title") or "an everyday conversation in Spain",
        role=f"{topic.get('role') or 'a friendly local'} ({gender})",
        address="usted (formal)" if topic.get("usted") else "tú (informal, normal in Spain)",
        goals=goals_txt,
        facts=facts_txt,
        learner=learner,
        vocab_rule=vocab_rule,
    )


# ------------------------------------------------------------------ потоковый разбор ответа

_SENT_END = re.compile(r"[.!?…]+[»\"')]*(?=\s)")


class ReplyStream:
    """Собирает текст ответа по кусочкам: отдаёт реплику по мере роста и готовые предложения.

    feed(chunk) -> (reply_so_far | None, [новые законченные предложения]).
    Предложение считается законченным, когда после знака конца пошёл пробел (значит, началось следующее)
    или закрылся </reply>.
    """

    def __init__(self):
        self.raw = ""
        self.sent_upto = 0  # сколько символов реплики уже отдано предложениями
        self.closed = False

    def _reply(self) -> str:
        a = self.raw.find("<reply>")
        if a < 0:
            return ""
        a += len("<reply>")
        b = self.raw.find("</reply>", a)
        if b >= 0:
            self.closed = True
            return self.raw[a:b]
        # хвост может быть началом «</reply>» — не показываем его
        tail = self.raw[a:]
        for k in range(min(len(tail), 8), 0, -1):
            if "</reply>".startswith(tail[-k:]):
                return tail[:-k]
        return tail

    def feed(self, chunk: str) -> tuple[str | None, list[str]]:
        was_closed = self.closed
        before = self._reply()
        self.raw += chunk
        reply = self._reply()
        if was_closed:
            return None, []
        sentences: list[str] = []
        while True:
            rest = reply[self.sent_upto:]
            m = _SENT_END.search(rest)
            if not m:
                break
            s = rest[: m.end()].strip()
            self.sent_upto += m.end()
            if s:
                sentences.append(s)
        if self.closed:
            s = reply[self.sent_upto:].strip()
            self.sent_upto = len(reply)
            if s:
                sentences.append(s)
        return (reply.strip() if reply != before or self.closed else None), sentences

    def reply(self) -> str:
        return self._reply().strip()

    def meta(self) -> dict:
        m = re.search(r"<meta>(.*?)(</meta>|$)", self.raw, re.S)
        return parse_meta(m.group(1) if m else "")


def _pair(x) -> dict | None:
    if isinstance(x, dict) and isinstance(x.get("es"), str) and x["es"].strip():
        return {"es": x["es"].strip()[:300], "ru": str(x.get("ru") or "").strip()[:300]}
    return None


def split_hints(hints, chunks) -> dict:
    """hints are single words (1–2 tokens: «cabeza», «de repente»), chunks are phrase pieces.
    A longer item the model put into hints moves to chunks."""
    def pairs(xs) -> list[dict]:
        return [p for p in (_pair(x) for x in (xs if isinstance(xs, list) else [])[:16]) if p]
    words, pieces = [], []
    for p in pairs(hints):
        (words if len(p["es"].split()) <= 2 else pieces).append(p)
    pieces += pairs(chunks)
    seen: set[str] = set()
    pieces = [p for p in pieces if not (norm_words(p["es"]) in seen or seen.add(norm_words(p["es"])))]
    return {"hints": words[:12], "chunks": pieces[:6]}


def norm_words(s: str) -> str:
    """Для сравнения реплик: без регистра, ударений и знаков препинания."""
    s = "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")
    return " ".join(re.findall(r"[a-z0-9]+", s))


def drop_trivial_fix(meta: dict, said: str) -> dict:
    """Исправление, где отличаются только знаки, ¿¡, заглавные или ударения, — не исправление:
    ученик говорит голосом, а не пишет."""
    fix = meta.get("fix")
    if fix and norm_words(fix["es"]) == norm_words(said):
        meta["fix"] = None
    return meta


def parse_meta(text: str) -> dict:
    """JSON из <meta>, терпимо к мусору вокруг; недостающее — пустое."""
    data: dict = {}
    a, b = text.find("{"), text.rfind("}")
    if a >= 0 and b > a:
        try:
            data = json.loads(text[a: b + 1])
        except json.JSONDecodeError:
            data = {}
    if not isinstance(data, dict):
        data = {}
    return {
        "ru": str(data.get("ru") or "").strip()[:1000],
        "fix": _pair(data.get("fix")),
        **split_hints(data.get("hints"), data.get("chunks")),
        "sample": _pair(data.get("sample")),
        "words": [p for p in (_pair(w) for w in (data.get("words") or [])[:6]) if p],
        "goals_done": [str(g) for g in (data.get("goals_done") or []) if isinstance(g, (str, int))][:20],
        "end": bool(data.get("end")),
    }


# ------------------------------------------------------------------ сессия

Event = Callable[[dict], Awaitable[None]]


class ChatSession:
    """One conversation. Turns go one at a time ([turn] under the lock).
    Subclasses only stream the model's raw answer: ClaudeChat (a long-lived `claude -p`) and
    LocalChat (a local model in Ollama)."""

    timeout = 120.0

    def __init__(self, cid: str, level: str, topic: dict, about: list[dict], learner: str = ""):
        self.id = cid
        self.level = level
        self.topic = topic
        self.system = build_system(level, topic, about, learner)
        self.lock = asyncio.Lock()
        self.turn = 0
        self.turns: list[dict] = []
        # Turns said before the session (re)started: the phone sends them if the server restarted.
        self.history: list[dict] = []
        self.closed = False  # the conversation is over: its audio is no longer made
        self.used = time.time()

    @property
    def alive(self) -> bool:
        """Holds an idle resource (a process) that the janitor may close."""
        return False

    async def open(self) -> bool:
        """Prepare for a turn; True if the model starts from scratch and needs a [RESUME] reminder."""
        raise NotImplementedError

    def stream(self, text: str):
        """Async iterator over chunks of the model's raw answer to `text`."""
        raise NotImplementedError

    async def close(self) -> None:
        pass

    async def say(self, text: str, on_event: Event, timeout: float | None = None) -> dict:
        """Send the learner's turn and wait for the answer. on_event gets
        {"type": "delta", "text"} and {"type": "sentence", "seq", "text"} while the answer streams."""
        async with self.lock:
            said = text
            if await self.open():
                past = self.history + [{"user": t["user"], "reply": t["reply"]} for t in self.turns]
                if past:  # fresh model: remind it of the conversation, or the character starts over
                    lines = [f"Learner: {p['user']}\nYou: {p['reply']}" for p in past if p.get("user") != "[START]"]
                    opening = next((p["reply"] for p in past if p.get("user") == "[START]"), "")
                    text = ("[RESUME] The conversation so far (continue it, do not greet again):\n"
                            + (f"You: {opening}\n" if opening else "") + "\n".join(lines)
                            + f"\n\nThe learner now says: {text}")
            self.turn += 1
            self.used = time.time()
            turn = self.turn
            stream = ReplyStream()
            seq = 0
            t0 = time.time()
            first = None

            async def read() -> None:
                nonlocal seq, first
                async for chunk in self.stream(text):
                    if first is None:
                        first = time.time() - t0
                    reply, sentences = stream.feed(chunk)
                    if reply is not None:
                        await on_event({"type": "delta", "turn": turn, "text": reply})
                    for s in sentences:
                        await on_event({"type": "sentence", "turn": turn, "seq": seq, "text": s})
                        seq += 1

            try:
                await asyncio.wait_for(read(), timeout or self.timeout)
            except Exception:
                await self.close()  # the stream may have stopped mid-answer: the next turn starts afresh
                raise
            reply = stream.reply()
            if not reply:
                raise RuntimeError("пустой ответ")
            out = {"turn": turn, "reply": reply, "sentences": seq, **drop_trivial_fix(stream.meta(), said),
                   "firstMs": int((first or 0) * 1000), "totalMs": int((time.time() - t0) * 1000)}
            self.turns.append({"user": said, **out})
            self.used = time.time()
            return out


class ClaudeChat(ChatSession):
    """One conversation = one `claude -p` process: it keeps the history and the prompt cache."""

    def __init__(self, cid: str, level: str, topic: dict, about: list[dict], model: str, effort: str,
                 learner: str = ""):
        super().__init__(cid, level, topic, about, learner)
        self.model, self.effort = model, effort
        self.proc: asyncio.subprocess.Process | None = None
        self.prompt_file: Path | None = None

    @property
    def alive(self) -> bool:
        return self.proc is not None and self.proc.returncode is None

    async def open(self) -> bool:
        if self.alive:
            return False
        # The prompt goes through a file: on Windows a command line is limited to 32 767 characters.
        fd, name = tempfile.mkstemp(prefix="lectura-chat-", suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(self.system)
        self.prompt_file = Path(name)
        self.proc = await asyncio.create_subprocess_exec(
            shutil.which("claude") or "claude", "-p",
            "--model", self.model,
            "--effort", self.effort,
            "--tools", "",
            "--strict-mcp-config",
            "--no-session-persistence",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--include-partial-messages",
            "--verbose",
            "--system-prompt-file", name,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            cwd=tempfile.gettempdir(),
            limit=16 * 1024 * 1024,
        )
        return True

    async def stream(self, text: str):
        msg = {"type": "user", "message": {"role": "user", "content": text}}
        self.proc.stdin.write((json.dumps(msg, ensure_ascii=False) + "\n").encode())
        await self.proc.stdin.drain()
        got = False
        while True:
            line = await self.proc.stdout.readline()
            if not line:
                raise RuntimeError("claude завершился")
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            kind = ev.get("type")
            if kind == "stream_event":
                e = ev.get("event") or {}
                if e.get("type") == "content_block_delta" and (e.get("delta") or {}).get("type") == "text_delta":
                    got = True
                    yield e["delta"].get("text", "")
            elif kind == "result":
                if ev.get("is_error"):
                    raise RuntimeError(str(ev.get("result") or "ошибка claude")[:300])
                if not got and ev.get("result"):  # no partial messages: take the whole result
                    yield ev["result"]
                return

    async def close(self) -> None:
        p, self.proc = self.proc, None
        if p and p.returncode is None:
            try:
                p.stdin.close()
                await asyncio.wait_for(p.wait(), 3)
            except Exception:  # noqa: BLE001
                p.kill()
        if self.prompt_file:
            self.prompt_file.unlink(missing_ok=True)
            self.prompt_file = None


class LocalChat(ChatSession):
    """A local model in Ollama: the history is kept here and sent with every turn
    (Ollama reuses the cached prompt prefix, so only the new turn is processed)."""

    timeout = 300.0  # the first turn also reads the long system prompt

    def __init__(self, cid: str, level: str, topic: dict, about: list[dict], backend, learner: str = ""):
        super().__init__(cid, level, topic, about, learner)
        self.backend = backend
        self.messages: list[dict] = []

    async def open(self) -> bool:
        return not self.messages

    async def stream(self, text: str):
        import llm

        loop = asyncio.get_running_loop()
        queue: asyncio.Queue = asyncio.Queue()
        stop = [False]
        msgs = [{"role": "system", "content": self.system}, *self.messages, {"role": "user", "content": text}]

        def work() -> None:
            try:
                for chunk in llm.stream_chat(self.backend, msgs, stop):
                    loop.call_soon_threadsafe(queue.put_nowait, chunk)
                loop.call_soon_threadsafe(queue.put_nowait, None)
            except Exception as e:  # noqa: BLE001 — passed to the reader below
                loop.call_soon_threadsafe(queue.put_nowait, e)

        loop.run_in_executor(None, work)
        raw = ""
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                if isinstance(item, Exception):
                    raise RuntimeError(f"локальная модель: {item}")
                raw += item
                yield item
        finally:
            stop[0] = True
        self.messages += [{"role": "user", "content": text}, {"role": "assistant", "content": raw}]


def make_session(backend, cid: str, level: str, topic: dict, about: list[dict], model: str,
                 effort: str, learner: str = "") -> ChatSession:
    if backend is not None and backend.kind == "local":
        return LocalChat(cid, level, topic, about, backend, learner)
    return ClaudeChat(cid, level, topic, about, model, effort, learner)


# ------------------------------------------------------------------ проверка в терминале

DEMO_TOPIC = {
    "title": "В кафе",
    "scene": "A small bar-café in Madrid in the morning. The learner comes in for breakfast.",
    "role": "Javi, the waiter",
    "gender": "m",
    "usted": False,
    "vocab": ["food", "numbers", "shopping"],
    "goals": [{"id": "order", "ru": "заказать напиток"}, {"id": "food", "ru": "заказать что-то поесть"},
              {"id": "pay", "ru": "спросить, сколько стоит, и заплатить"}],
}


async def demo(args) -> None:
    topic = dict(DEMO_TOPIC)
    if args.topic:
        topic = {"title": args.topic, "scene": args.topic, "role": "a local person in this situation",
                 "gender": "f", "usted": False, "goals": []}
    import learner
    import llm

    s = make_session(llm.choose(args.llm, args.local_model), "demo", args.level, topic, [], args.model, args.effort,
                     learner.chat_block(args.level, topic.get("vocab") or []))

    async def show(ev: dict) -> None:
        if ev["type"] == "sentence":
            print(f"   🔊 [{ev['seq']}] {ev['text']}", flush=True)

    text = "[START]"
    try:
        while True:
            out = await s.say(text, show)
            print(f"🤖 {out['reply']}\n   ({out['ru']})  — первый токен {out['firstMs']} мс, всего {out['totalMs']} мс")
            if out["fix"]:
                print(f"   ✏️  {out['fix']['es']} — {out['fix']['ru']}")
            print("   💡 " + " · ".join(f"{h['es']} ({h['ru']})" for h in out["hints"]))
            if out["chunks"]:
                print("   🧩 " + " · ".join(f"{h['es']} ({h['ru']})" for h in out["chunks"]))
            if out["sample"]:
                print(f"   ↪ {out['sample']['es']}")
            if out["goals_done"]:
                print(f"   ✓ {', '.join(out['goals_done'])}")
            text = input("🙂 ").strip()
            if not text:
                break
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        await s.close()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--level", default="A1", choices=LEVELS)
    ap.add_argument("--topic", default="")
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--effort", default="low")
    ap.add_argument("--llm", default="auto", choices=["auto", "claude", "local"],
                    help="auto: Claude if Claude Code is logged in, else a local model in Ollama")
    ap.add_argument("--local-model", default=None)
    ap.add_argument("--print-prompt", action="store_true")
    args = ap.parse_args()
    if args.local_model is None:
        import llm

        args.local_model = llm.DEFAULT_LOCAL_MODEL
    if args.print_prompt:
        import learner

        print(build_system(args.level, DEMO_TOPIC, [], learner.chat_block(args.level, DEMO_TOPIC["vocab"])))
        return
    asyncio.run(demo(args))


if __name__ == "__main__":
    sys.exit(main())
