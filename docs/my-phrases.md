# «Мои фразы»

The learner writes in Russian. The Mac turns each entry into simple Peninsular Spanish with narrator audio, and the phrases land in review.

**Personal data never goes to git.** It lives only in `books/.work/personal/` (gitignored) and on the phone.

## In the app (`ui/MyPhrasesScreens.kt`)
- **«Обо мне»:** answers to the questions people ask when you meet them and in daily errands (`model/Personal.kt` `ABOUT_QUESTIONS`, 19 questions). Written with `UserData.setAbout`.
- **«Как сказать…»:** free requests such as «у меня течёт кран» (`UserData.addInbox` / `deleteInbox`).
- **Storage:** entries go to Room `inbox` (kind `about` | `phrase`, ref, ru, status pending/done, doneAt).
- **«Расскажите о себе»** (`AboutTrainScreen`): the question is spoken (f2 voice). You answer aloud; your answer or any accepted one passes.

## Translation on the Mac
- **Shared code:** both delivery paths use the same code and cache in `pipeline/personal.py`:
  - `translate()` — `claude -p`, opus, or the local model via `llm.json_call` when there is no Claude ([home-server.md](home-server.md));
  - `entry_key()` — cache key = entry id + sha1 of kind|ref|ru. Ids restart after a reinstall, so an old translation must not attach to a new entry;
  - `make_result()`, `clips_for()`.
- **Cache:** `books/.work/personal/results.json`.
- **«Обо мне»:** a first-person answer in 1–3 sentences, plus 3–5 other accepted answers for speech checking.
- **«Как сказать…»:** 1–2 phrases, with a tú/usted note when it matters.
- **Audio:** phrases in the m1 voice (`me-<inboxId>-<n>.m4a`), «Обо мне» questions in f2 (`q-<ref>.m4a`), in `books/.work/personal/audio/`.

## Delivery 1: Wi-Fi server (main path)
- **Start and stop:** double-click `Lectura Server.command` in the project root (Windows: `Lectura Server.bat`, see [home-server.md](home-server.md)) (opens Terminal, runs the server under `caffeinate -i` so the Mac doesn't idle-sleep; arguments pass through), or `python3 pipeline/server.py` (`--level A0`, `--no-audio`, `--new-code`). It is started by hand; there is no autostart, by the user's choice. Ctrl+C or closing the window stops it. A second start finds the port busy and says the server is already running.
- **Runtime:**
  - aiohttp in the audio venv.
  - Port 8765. Advertised as `_lectura._tcp` via the built-in `dns-sd` (python-zeroconf on Windows). Leftover `dns-sd` registrations of killed servers are removed at start; `kill` (SIGTERM) now cleans up like Ctrl+C.
  - At start it prints the code and the LAN address.
- **Security:**
  - Every request needs the 6-digit code from `books/.work/personal/server.json` in the `X-Lectura-Code` header. Without it, anyone on the same Wi-Fi could spend the Claude subscription.
  - Wrong codes are slowed down.
  - Audio names are checked against `^[A-Za-z0-9_-]{1,80}$`.
- **Endpoints:**

| Endpoint | |
|---|---|
| `GET /ws` (WebSocket, heartbeat 15 s) | `hello` {protocol, audio}, `status` {queue, busy, phase, current}, `done` {inboxId}, `error` {inboxId, text} |
| `POST /sync` `{"items": [InboxEntry]}` | Returns `{"results": [...ready...], ...status}`; entries without a ready result are queued |
| `GET /audio/<name>` | An `.m4a` from `books/.work/personal/audio/` |
| `/chat/…` | «Живой разговор», see [live-chat.md](live-chat.md) |

- **Worker:**
  - One MLX thread (`ThreadPoolExecutor(1)`) does all voicing (here and for «Живой разговор»); MLX must stay on one thread. Translation (`claude -p`) runs in the default thread pool so it doesn't block the conversation's audio.
  - While a conversation is active, voicing waits (phase «жду конца разговора»); `clear_mlx_cache()` after each voicing.
  - The TTS engine is loaded once at start and warmed up (f2, m2, Whisper); the phase is «готовлю голос».
  - A result is «ready» when it is translated and all its clips exist.
  - Voicing calls `voice_set` over all results; finished clips are skipped.

### Phone side
- **Connection: `data/MacLink.kt`.**
  - Runs while «Говорить» or «Мои фразы» is visible (`MacLinkWhileVisible` → `acquire`/`release`, closed 5 s after the last screen).
  - Host search: manual address (`Prefs.macAddress`) → NSD discovery (`registerServiceInfoCallback`, IPv4, 4 s) → last host that worked (`Prefs.macLastHost`).
  - OkHttp WebSocket with a 10 s ping, so a Mac that goes away is noticed within ~10–20 s.
  - Syncs on connect, on every change of pending entries (Room flow), and on `done`.
- **Sync** (`POST /sync`):
  1. Download the audio (`.part` → rename).
  2. `PersonalRepository.addResults` merges into `files/personal/personal.json`.
  3. `sync()` marks entries done and adds phrases to review; a toast shows «+N фраз».
- **Status UI: `ui/MacStatus.kt`.**
  - Dot colours: 🟢 IDLE «Mac на связи», 🟡 BUSY «Mac переводит / озвучивает / готовится / ждёт конца разговора · в работе: N» (pulsing), 🔴 OFFLINE «Mac не найден» or NEED_CODE «нужен код» / «Mac не принял код», ⚪ SEARCHING.
  - Tapping the dot opens a dialog with an explanation, the start command, the code field and an optional address field. Use the address field when the router blocks Bonjour.
- **Manifest:** allows cleartext HTTP (`usesCleartextTraffic`), because the home server has no certificate. External hosts are https anyway.
- **Contract test:** `MacLinkTest` checks that port, service type and header are the same in `server.py`.

## Delivery 2: USB (fallback)
- **Run:** `python3 pipeline/personal.py` with the phone on adb.
- **Flow:**
  - The app writes pending entries to `files/personal/inbox.json` (`/sdcard/Android/data/app.lectura/files/personal/`).
  - The script pulls it, translates, and voices via `audio.py --clips`.
  - It merges with the phone's existing `personal.json`, so a lost Mac cache doesn't erase delivered results.
  - Then it pushes `personal.json` + `audio/`.
- **When the app picks it up:** `PersonalRepository.sync()` runs on app start, on «Говорить» and on «Мои фразы» resume.

## Rules
- A result belongs to an entry only if **id and text** match (`PersonalResult.matches`).
- The backup has `Backup.inbox`. Restored entries come back as pending; `personal.json` itself is not in the backup.
- `PersonalRepository.registerExtra` makes personal phrases playable with their clips (`speakEs(text, ref)`).

## Tests
`PersonalTest`, `MacLinkTest`.
