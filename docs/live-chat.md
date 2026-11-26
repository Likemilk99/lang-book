# «Живой разговор»

A free conversation with a character in a scene (waiter, neighbour, doctor…) at the learner's level. The learner answers by voice or keyboard. The Mac plays the character with `claude -p` and can voice the replies with Qwen3-TTS. Everything runs locally: the Mac + the Claude subscription, no rented servers or API keys. Without Claude Code the character is a local model in Ollama (`LocalChat`, default gemma4:e4b), and the server also runs on Windows: see [home-server.md](home-server.md).

The card is first on the «Говорить» tab (`LiveChatCard`); route `chat`.

## Mac side
- **`pipeline/chat.py`:**
  - `ChatSession` (base: lock, `[RESUME]`, `ReplyStream`, timings) with two backends picked by `make_session`. `LocalChat` keeps the messages itself and streams from Ollama. `ClaudeChat` is one long-lived `claude -p --input-format stream-json --output-format stream-json --include-partial-messages` process per conversation; its system prompt goes through `--system-prompt-file`. The CLI keeps the history and the prompt cache, so the first token arrives in ~1 s. A new `claude -p` per turn would take 5–10 s.
  - Model: `sonnet`, effort `low` (`server.py --chat-model/--chat-effort`). In a test Haiku mixed tú and usted in one dialogue.
  - `build_system`: role, scene, tú/usted, goals with ids, «Обо мне» facts, level rules (`LEVEL_RULES`), and the level word list from `assets/curriculum/vocab_*.json`: A0/A1 get the A1 list, A2 gets A1+A2, B1 gets no list.
  - «LEARNER'S WORDS»: words to review, new frequent words and known words from the phone's progress mirror (`learner.chat_block`, see [learner-progress.md](learner-progress.md)).
- **Output format.** It is not `--json-schema`, because that returns only at the end and would kill streaming. The model writes:
  ```
  <reply>Spanish line(s)</reply>
  <meta>{"ru", "fix", "hints", "chunks", "sample", "words", "goals_done", "end", "brief"?}</meta>
  ```
  `ReplyStream` parses it on the fly: the text grows on screen, and finished sentences go to TTS at once.
- **Hints:** `hints` are 8–12 single words for the next answer (the words that answer the question plus related alternatives: at the doctor *fiebre, tos, cabeza, garganta, doler…*), so the learner builds the sentence. `chunks` are 3–5 phrase pieces of 2–4 words (*me duele la garganta*). `split_hints` moves any hint longer than two words into `chunks` and drops duplicates. An older app ignores `chunks`.
- **Brief («Перед разговором»):** on the `[START]` turn only, meta also has `brief` = 10–15 key words of the scene (frequent first, including the learner's review and new words that fit) and 4–6 reusable patterns with «…» («Me duele…», «Desde hace…»). `parse_brief` keeps it only when non-empty; `tag_brief` marks words from `learner.chat_context` as `review` / `new`. It makes the first turn ~2 s longer (measured: 6.2 s instead of ~4 s with Sonnet), hidden behind the reading.
- **Fix handling:**
  - `drop_trivial_fix` drops «fixes» that differ only in punctuation, ¿¡, capitals or accents. The learner speaks; the prompt asks for this too, but the model doesn't always follow.
  - Russian words in a reply are allowed: the character answers in Spanish, and `fix` gives the Spanish phrase.
- **Resume:** if the process died (idle close, or Mac restart + phone resend), the next turn starts a new process with a `[RESUME]` transcript.
- **Check in the terminal:** `python3 pipeline/chat.py [--level A0] [--topic "…"]`, text only. `--print-prompt` shows the system prompt.

### Server (`pipeline/server.py`)

| Endpoint | |
|---|---|
| `POST /progress` (snapshot) | The phone's progress before each conversation → `learner.db` ([learner-progress.md](learner-progress.md)) |
| `POST /chat/start` `{chat, level, topic, about, history?, text?, audio, whisper}` | Creates the session (closes an older one with the same id; keeps at most 3 claude processes) and runs the first turn (`[START]`, or `text` with `history` after a Mac restart). Returns the turn |
| `POST /chat/<id>/say` `{text, audio, whisper}` | One learner turn → the full turn JSON (`ChatReply`). 404 `nochat` if the Mac forgot the chat |
| `POST /chat/<id>/end` | Closes the process and deletes its audio; with 2+ learner turns, updates the learner profile in the background |
| `GET /chat/audio/<name>.wav` | A sentence clip, from `books/.work/personal/chat-audio/` |
| WebSocket `/ws` (shared with «Мои фразы») | `chat.delta {chat, turn, text}`, `chat.sentence {turn, seq, text}`, `chat.audio {turn, seq, name, ms, whisper, errors}`, `chat.audio_error {turn, seq}`. `hello` has `chat: true` |

- **Validation:** `chat` id must match `^[a-f0-9]{8,32}$`. `clean_topic` (incl. the vocab theme ids), `clean_about` and `clean_history` keep only known fields of sane length.
- **Mac voice:**
  - Each sentence is voiced on the one MLX thread (`Hub.synth`) as a WAV, without AAC encoding (faster). The voice is f2 or m2 by the topic's gender.
  - Without Whisper: one `Engine.tts`. With Whisper («Проверять озвучку Whisper»): `Engine.segment` does up to 3 attempts and keeps the best.
  - Sentences of a turn the learner has already answered are skipped.
- **MLX:**
  - `clear_mlx_cache()` runs after every clip and after the «Мои фразы» voicing. Without it, TTS and Whisper on 16 GB slow each other 3–5× (measured: 4 s → 18 s per sentence once Whisper has run).
  - `load_engine` warms up f2, m2 and Whisper. The first generation is otherwise 3–4× slower.
  - `chat_warmup`: if TTS has been idle for over 60 s, a chat start voices «Vale.» while the model writes the first line. After idle, especially when the Mac is swapping, the first clip took up to 20 s.
- **Sharing with «Мои фразы»:**
  - Translation now runs in the default thread pool, not on the MLX thread.
  - Their voicing waits while a conversation is active (`CHAT_QUIET` = 120 s after the last chat event; phase «жду конца разговора»).
- **Janitor:** closes claude processes idle for 15 min (the history stays) and forgets chats after 3 h. It clears `chat-audio/` on start.
- **Privacy:** «Обо мне» answers go to Claude as context, just like «Мои фразы» translations do. Nothing is written to git.
- **Journal and profile:** every learner turn (said, reply, fix) goes to `learner.db`, and after the conversation one model call updates the learner profile (typical mistakes), which the next conversations get in their prompt. See [learner-progress.md](learner-progress.md).

### Measured on the M1 Pro 16 GB (2026-11)
- **Text:** first sentence 0.6–1.7 s after sending, full turn with meta 2–4 s.
- **Mac voice, no Whisper:** ~3.5 s per sentence. First audio ~5 s after sending; later sentences follow as each one is generated.
- **Mac voice with Whisper:** ~5–9 s per sentence (one attempt; more when Whisper finds errors).
- **Under memory pressure** (e.g. with the Android emulator running, 12 GB of swap) everything with the Mac voice is 2–4× slower: 8–14 s per sentence.

## Phone side
- **`model/LiveChat.kt`:**
  - `ChatTopic`: scene, role, gender, usted, goals, vocab theme ids. `CHAT_TOPICS` has 13 scenes; «Своя тема» comes from `customChatTopic`.
  - `ChatVoice` (NONE/PHONE/MAC), `ChatReply`, `CHAT_LEVELS` (= `chat.LEVELS`, checked by a test).
- **`data/LiveChat.kt`** (singleton `LecturaApp.chat`, so the chat survives leaving the screen):
  - Sending: `say` → POST, the response finalizes the turn. WebSocket events give live text and audio.
  - Voice is chosen per turn at send time and can be changed mid-chat:
    - NONE: text only;
    - PHONE: `SpeechTts` with the partner's gender, sentence by sentence as `chat.sentence` arrives;
    - MAC: download each `chat.audio` WAV (downloads in parallel, play strictly in order) → `ClipPlayer`.
  - If the Mac has no voice (`hello.audio = false`) or a clip failed, the phone voice reads it.
  - `playTurn` guards against stale audio: a new message, the mic or a replay silences the previous turn.
  - `playLoop` plays the queue one item at a time and survives a failing clip.
    - `ClipPlayer.stop()` makes the waiting `play()` return false, and a clip is awaited at most its duration + 5 s.
    - Before the fix (2026-11), interrupting a Mac clip (mic, send, 🔊 on another reply) left `playLoop` suspended forever. Every later reply and replay of that conversation stayed silent, though the timing line showed the voice was ready.
  - Retry after an error. On 404 it automatically restarts with `history` (the brief already shown is kept).
  - «Перед разговором»: `ChatState.briefOpen` is true at the start. The opening line's audio waits (`held`) until the learner closes the brief (`showBrief(false)`); `showBrief(true)` reopens it.
  - `saveFixes` adds the corrected phrases to review (`UserData.addPhrases`, label «разговор: …»).
  - «Обо мне» answers (latest per question) are sent at start.
- **`ui/LiveChatScreens.kt`:**
  - Setup:
    - level A0–B1;
    - voice and «Проверять озвучку Whisper» (only for the Mac voice; to compare speed and quality);
    - «Голосовой ответ отправлять сразу»;
    - the topics and «Своя тема».
  - «Перед разговором» (`BriefSheet`, a bottom sheet): goals, key words (tap — hear it; «повторить» / «новое» marks), patterns with 🔊, «Начать разговор» / «Вернуться к разговору». While the Mac is preparing it shows a progress bar; an older Mac server sends no brief and the sheet says so.
  - Chat screen:
    - the title with 📋 reopens «Перед разговором»;
    - bubbles: tap a partner bubble for the translation and unknown words; 🔊 replays the reply (Mac clips if there are any). The timing line shows «текст X с · голос Mac a + b с (Whisper)»;
    - your bubble shows «✏️ лучше так» with the explanation; tap it to hear it;
    - goals with ✓;
    - «Звук» changes the voice mid-chat; «Итог» shows the goals and the fixes → «Фразы в повторение».
  - Hint 💡 opens one step per tap (`nextHintLevel`): 1 — single words for the answer (`hints`, tap inserts the word); 2 — + phrase pieces (`chunks`); 3 — + a sample answer; 4 — + all words of the topic's vocab themes at the level (local `vocab_*.json`, works offline). Steps with nothing to show are skipped (an older Mac server sends no `chunks`); after the last step the button shows ✕. Words first, so the learner has to build the sentence.
  - Mic (`ChatMic`): `SpeechInput` with biasing to the hints and the sample. The recognized text goes into the field for editing (or is sent at once if that option is on). Uses the same engine and stop settings as `SpeakButton`. Sending a message (button or auto-send) cancels a running recording without a result (`stopKey`), so it doesn't keep writing into the cleared field or record the partner. The next answer needs a new tap.
- **Prefs:** `chatLevel`, `chatVoice`, `chatWhisper`, `chatAutoSend`, `chatCustomTopic`.
- **User data:** the phone does not store the conversation (memory only); the Mac keeps a journal of the learner's turns in `books/.work/personal/learner.db` ([learner-progress.md](learner-progress.md)). Only `recordSpeaking` for spoken turns and `addPhrases` from «Итог» are written, both through `UserData`.

## Tests
`LiveChatTest`: topic format and limits, vocab theme ids exist, chat id matches the server regex, levels equal `chat.LEVELS`, parsing a `ChatReply` (with and without `chunks` and `brief`), hint steps skip empty ones.
