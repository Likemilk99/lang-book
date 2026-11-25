# Home server: platforms and text backends

The server (`pipeline/server.py`) serves «Мои фразы» ([my-phrases.md](my-phrases.md)) and «Живой разговор» ([live-chat.md](live-chat.md)) over the home Wi-Fi. It runs on macOS and Windows. Texts come from Claude Code when it is available, otherwise from a local model.

## Start
- **macOS:** double-click `Lectura Server.command`. It checks for Python 3.10+ and runs the server under `caffeinate -i`.
- **Windows:** double-click `Lectura Server.bat`.
  - It switches the console to UTF-8 and finds Python 3.10+ (`py -3`, then `python`). If there is none, it prints how to install it.
  - The file must keep CRLF line endings (`.gitattributes`).
  - On the first start Windows asks about the firewall: allow Python on private networks, or the phone can't reach the server.
  - The server keeps the PC awake while it runs (`SetThreadExecutionState`).
- Arguments pass through on both: `--llm`, `--local-model`, `--level`, `--no-audio`, `--new-code`.

## Environment (`ensure_venv`)
- **Mac with Apple silicon, voice on:** the audio venv `pipeline/.cache/audio-venv`. `audio.py` creates it with `uv` and installs mlx-audio, mlx-whisper and aiohttp. Without `uv` the server warns and runs without the Mac voice.
- **Everything else** (Windows, Intel Mac, no `uv`): `pipeline/.cache/server-venv`.
  - It is created with the standard `venv` and gets aiohttp, plus zeroconf outside macOS.
  - Dependencies are installed with `uv pip` if `uv` exists, else with `pip`.
- **Restart into the venv:** `os.execv` on Unix. Windows has no real exec, so the parent starts the child and waits for it in the same window.

## Discovery (`advertise`)
- **macOS:** the built-in `dns-sd -R`.
  - Before registering, the server kills leftover `dns-sd -R Lectura … _lectura._tcp` processes. The port is already ours by then, so they come from servers that were killed without cleanup. They kept announcing dead addresses (seen 2026-11: four stale ones, two on an unused port).
- **Windows:** python-zeroconf (`AsyncZeroconf`, IPv4, the LAN interface). It was checked from a Mac with `dns-sd -B/-L`.
- If the announcement fails, the address can still be typed in the app.

## Voice
- The server voice (Qwen3-TTS + Whisper) needs MLX, so it only works on a Mac with Apple silicon (`MLX` in `server.py`).
- Elsewhere `hello.audio` is false and the phone reads everything with its system voice. «Мои фразы» results are ready without clips.

## Text backend (`pipeline/llm.py`)
- **Choice:** `--llm auto` (the default) uses Claude if `claude` is on PATH and `claude auth status` reports `loggedIn`. Otherwise it uses the local model. `--llm claude` or `--llm local` forces one. The startup banner says which one runs.
- **Claude:**
  - Unchanged: `claude -p` (`ClaudeChat` in `chat.py`, `claude_json` in `prepare_book.py`). The executable is resolved with `shutil.which`, so `claude.exe` works on Windows.
  - The chat system prompt goes through `--system-prompt-file`. The A2 prompt is ~22k characters, and a Windows command line is limited to 32 767.
  - The working directory is `tempfile.gettempdir()`, not `/tmp`.
- **Local: Ollama** (macOS/Windows, HTTP API on `127.0.0.1:11434`, `OLLAMA_HOST` respected).
  - **Start:** `start_ollama` runs `ollama serve` if the app is installed but not running. Without Ollama (and without Claude) the server exits with install instructions (`winget install Ollama.Ollama` / `brew install --cask ollama`).
  - **Model:** a missing model is pulled on the first start, with progress. `warm_up` loads it into memory at server start, after the voice.
  - **Memory:** the server warns if the machine has less than 16 GB of RAM.
  - **Thinking:** switched off (`think: false`) for models that have it, because it delays the first word.
  - **Chat** (`LocalChat`): the history lives in the session and is sent with every turn. Ollama's prefix cache reuses the long system prompt, so only the new turn is processed. After a server restart the phone's `history` goes in as the usual `[RESUME]` message. The timeout is 300 s, because the first turn also reads the system prompt.
  - **«Мои фразы»** (`local_json`): the JSON schema goes into the prompt instead of Ollama's `format`. Ollama's MLX runner, used for some models on Apple silicon, hung on `format` (Ollama 0.40.18). The answer is parsed leniently (`parse_json_object`), and `make_result` tolerates missing keys.
  - **Order:** a local model answers one request at a time. Translation waits while a conversation is active (`CHAT_QUIET`), as voicing already did.
  - **Settings:** one `num_ctx` (16384) for every call, because a different value makes Ollama reload the model. `keep_alive` is 20 min; there are `num_predict` limits.

## Local model and the Mac voice
- A local model and the Mac voice don't fit together in 16 GB.
- On the M1 Pro 16 GB, gemma4:e4b with the voice loaded:
  - first sentence 4.5–7 s instead of 0.3–0.7 s;
  - a whole turn 9–12 s;
  - a voiced sentence 17–24 s instead of ~3.5 s.
- So with a local model on a machine under 24 GB the server turns the Mac voice off and the phone reads. `--mac-voice` forces it on.

## Choosing the local model
- **Default:** `llm.DEFAULT_LOCAL_MODEL` = **`gemma4:e4b`**. Another model: `--local-model <ollama tag>`.
- **Method:** compared 2026-11-25 on the M1 Pro 16 GB, Ollama 0.40.18, with memory already under pressure (swap ~13 GB, the realistic worst case).
  - The real prompts: the A1 café scene from `chat.py` (6 turns, with a gender error «un tostada» and a turn in Russian), the first turns of A2 (the long word list), and 3 «Мои фразы» translations.
  - The script was a one-off and is not kept in the repo.
- **Timings:** «first» is the time until the first sentence of a turn after the first one. The first turn also reads the system prompt.

| Model | Memory | First / whole turn | First turn (A1 / A2) | Meta JSON | Quality |
|---|---|---|---|---|---|
| Claude Sonnet (reference) | — | 0.8–0.9 s / 2–3 s | 1.4 s / 1.6 s | 6/6 | Fixes every error, tracks goals, idiomatic Spain Spanish |
| **gemma4:e4b** | 8.9 GB | **0.3–0.7 s / 3–5 s** | 4.5 s / 11 s | 6/6 | Peninsular («¿qué te pongo?», bollería, grifo, fontanero), keeps tú. Misses some fixes («un tostada»), weak at goals_done, sometimes invents facts in «Обо мне» |
| gemma4:12b | 7.5 GB | 21–25 s / 29–35 s | 21 s / 42 s | 5/6 (one empty turn) | Best of the local ones: caught «un tostada», tracked goals. Too slow: Ollama's MLX runner missed the prompt cache on almost every turn |
| qwen3.5:9b | 7.6 GB | 1.5–2 s / 7–9 s (16 s with the Russian turn) | 16 s / 29 s | 6/6, but A2 broke the format (meta inside the reply) | Loses the scene logic (asks the learner for the bill), misses fixes |
| qwen3:14b | 11.4 GB | 3.7–6.4 s / 19–57 s | 37 s / 76 s | 5/6 | usted instead of tú, Latin «llave» for the tap, no fixes |
| ministral-3:8b | 6.9 GB | 1.9–3 s / 8–17 s | 19 s / 34 s | 1/6 | Broken meta JSON, usted, stage directions and HTML in replies |

- **Why e4b:** it is the only local model whose speed feels like a conversation, and its Spanish is good. Its weak spots are the fixes and goals: hints and the sample answer still work, but «✏️ лучше так» appears less often than with Claude.
- **Elsewhere:**
  - On Windows Ollama runs a GGUF build of the same tag. With a GPU it should be similar.
  - CPU-only with 16 GB RAM will be noticeably slower (not measured; no Windows machine was available). The text then still streams, the phone voice starts on the first sentence.
  - The Windows path itself has not been run on Windows yet. Things to check on the first real start: the `.bat`, the venv, the firewall prompt, zeroconf discovery from the phone, and `claude.exe` if Claude Code is installed.
- **Later:** worth re-checking gemma4:12b when Ollama's MLX runner reuses the prompt cache reliably.
