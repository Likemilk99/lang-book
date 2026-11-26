# «Живой разговор» as the main speaking practice: status and plan

Status of the multi-session effort to make «Живой разговор» ([live-chat.md](live-chat.md)) the main speaking practice, personalized by the learner's progress ([learner-progress.md](learner-progress.md)). **Read this first when continuing the work.**

Last updated: 2026-11-26.

## Goal (the user's words, condensed)
- Speak and understand everyday speech in Spain as soon as possible. Practical language only.
- **Frequency first:** the most frequent words, key verbs, nouns and adjectives before anything topical or literary.
- The Mac keeps a mirror of the learner's progress. A process compresses it, and the result goes into the conversation prompt: words they forget get repeated, frequent new words get introduced, typical mistakes get practised.
- Make the learner think: hints start with single words, phrase pieces only on request.

## Done

| Commit | What | Needs |
|---|---|---|
| `042b5b5` | Dictionary translation audit: 230 vocab + 119 book-lemma fixes (*rico* «богатый; вкусный», false friends, Spain usage). Saved cards pick up dictionary fixes on every app update | release |
| `e4c44e1` | Hints: 💡 step 1 = 8–12 single words, 2 = phrase pieces (`chunks`), 3 = sample answer, 4 = all topic words; empty steps skipped | server restart (more words) + release (the steps) |
| `85919ed` | Progress mirror: the phone sends `POST /progress` before each chat → `learner.db`. `learner.brief` picks review / new / known words by frequency (`pipeline/freq_rank.json`) for the «LEARNER'S WORDS» prompt section. Coverage of the top 300/1000/2000 words is logged | server restart + release |
| `98da2e1` | «Перед разговором»: on the `[START]` turn the model sends a brief (10–15 key words, 4–6 patterns with «…»). Words are tagged «повторить» / «новое». A bottom sheet shows it at the start, and the opening line waits until it is closed. 📋 next to the chat title reopens it | server restart + release |
| `8d3dacb` | Journal of every learner turn (said, reply, fix) + a learner profile updated by one model call after each conversation (typical mistakes, can do, focus, note in Russian). It goes into every next prompt as «LEARNER PROFILE» | server restart only |

## Where it stands now
- **Nothing of this is released yet.** The phone runs 0.16.1. The release goes through `/release` when the user asks.
- **The Mac server must be restarted** to run the new code. The journal and profile work right away even with the old app. The progress snapshot, the brief sheet and the hint steps need the new app.
- **Verified:**
  - unit tests (incl. `ProgressSnapshotTest`, `LiveChatTest`) and the release build;
  - `/progress` and the chat end-to-end in-process (aiohttp test client) with real Claude turns, on temporary databases;
  - the doctor scene: review words appeared in the character's lines and first in the hints; the brief tagged them; the profile caught *ser/estar*, *desde hace* and noun gender from 4 turns in ~10 s (Opus).
- **Not verified yet:**
  - **The brief sheet UI was never seen on a device or the emulator.** Check the layout, the spinner and that the opening line really waits.
  - Real progress data: no snapshot from the user's phone has reached the Mac yet, so the thresholds below are untuned.
  - The local model (gemma4:e4b) with the longer prompt, the brief and the profile call.
  - The first turn is ~2 s longer because of the brief (6.2 s vs ~4 s measured).

## How to check after a few conversations
- **Server log:**
  - «📊 прогресс: слов в карточках N, знаю K; из самых частых знаю a/300, b/1000, c/2000» when a chat starts;
  - «🧠 профиль ученика обновлён: …» after a chat ends;
  - «⚠️ …» lines for any failure of the mirror, journal or profile.
- **`python3 pipeline/learner.py --themes body-health`:** coverage, the profile, and the prompt sections the next conversation would get.
- **The journal:** `sqlite3 books/.work/personal/learner.db "select topic, said, fix_es from turns order by rowid desc limit 30"`.
- **Rebuild the profile from the last conversation:** `python3 pipeline/learner.py --update-profile`.

## Questions for the user next session
0. ❓: is 4–6 s fast enough? Are the answers short and usable? Is tapping a word of a line + «＋ учить» convenient?
1. Did the brief help before speaking? Too many or too few words? Were the patterns useful?
2. Do the 💡 words give enough to build a sentence without giving it away? Is step 2 (phrase pieces) the right next step?
3. Did the character bring back «повторить» words naturally, or did it feel forced?
4. Were the new words useful and frequent enough? Too basic? (They come from the A1 list by frequency, so *día, tiempo, salir* are typical.)
5. Does the profile (`learner.py`) match the mistakes they feel they make?
6. Is the extra ~2 s on the first turn acceptable?

## Tuning knobs (`pipeline/learner.py`)
- `REVIEW_N` 10, `NEW_TOPIC_N` 2, `NEW_GENERAL_N` 3, `KNOWN_N` 120.
- **Weak word:** recall < 75 %, or 2+ lapses and recall < 90 %.
- **Known:** recall ≥ 70 % and (stage ≥ 3 or 2+ reps).
- **Frequency bonus:** top 500 / 1500 / 4000. Verbs count as 0.6 of their rank for new words. Rotation: +400 ranks per time a word was already offered.
- **freq_rank.json:** wordfreq includes news, so *gobierno, nacional* rank high. Only our own word lists are ranked, but a manual stop-list or a spoken-corpus weight may help.

## Done after the roadmap was written (not released, needs the server restart + release)
- **Save from the chat:** «＋ учить» on unknown words, any tapped word of a line (offline dictionary or the Mac), the whole line, fixes, brief words, ❓ answers; long press on 💡 words.
- **❓ «Как сказать…»:** `POST /chat/<id>/ask`, measured 4–6 s (Sonnet one-shot); the answer's example fits the current moment. Questions feed the profile.
- **Fix:** 🔊 in the brief played only after closing it (the hold now applies only to the character's lines).

## Backlog (candidates, in a suggested order)
1. ~~**Save words and sentences from the chat to review (asked by the user 2026-11-26, top priority).**~~ Done, see above. Today nothing in the chat can be saved except «Итог» → «Фразы в повторение» (all fixes at once).
   - **Words:** the unknown words of a partner bubble (shown on tap, `ChatMsg.words`), the brief words, and the 💡 words get a «＋ учить» action. Save through `UserData.saveWord` with a card built like `WordCards.fromGlossary`: dictionary translation from `Lexicon` if known, else the chat `ru`; the chat line (`reply` + `ru`) as the example. On a word already saved, add the example (`SaveResult.EXAMPLE_ADDED` toast, as in the reader).
   - **Sentences:** a partner reply or one sentence of it, and a single «✏️ лучше так» fix → `UserData.addPhrases(label = "разговор: <topic>")`. The reply's `ru` is for the whole reply. For one sentence, either save the whole short reply or ask the Mac for per-sentence translations (e.g. a `sentences_ru` list in meta).
   - **UI:** a long press or a small «＋» in the expanded bubble; a check mark once saved. Words that are already saved are marked.
   - This also covers «add the new words to review in one tap» (the brief's «новое» words).
2. ~~**❓ «Как сказать…» during the chat (asked by the user 2026-11-26).**~~ Done (one-shot; a persistent tutor process only if 4–6 s feels slow). A ❓ button next to 💡 opens a sheet where the learner asks, in Russian or mixed, how to say or ask something («как спросить про скидку», «как сказать, что я бы хотел перенести»). The Mac answers right away, short, with an example they can use in the next reply. The aim: not knowing a word or a structure must not limit the conversation.
   - **Context:** the answer must know the dialogue. The Mac already has it: the session keeps the turns in memory (`chat.turns`, `chat.history`), and the journal has every learner turn of the chat (`turns` table, step 3). Add the topic, the level and the learner profile.
   - **Server:** `POST /chat/<id>/ask {q}`. A separate call, NOT through the character's process: the role-play must not see the question, and the turn counter must not move. One-shot `llm.json_call` (sonnet, low) is simplest, but a fresh `claude -p` takes ~5–10 s. If that feels slow, keep a second long-lived «tutor» process per chat (the `ClaudeChat` machinery with another system prompt, ~1–2 s).
   - **Answer (JSON):** 1–2 Spanish ways to say it (simplest first, at the level, Spain) with Russian, a one-line note in Russian (tú/usted, when to use), and one example that fits the current scene.
   - **UI:**
     - the answer appears in the sheet; tap → insert into the input field; 🔊; «＋ учить» (item 1);
     - the session's questions and answers stay in the sheet;
     - while waiting, the sheet can stay open or be closed (the answer then appears under ❓ with a badge).
   - **Journal:** store questions and answers (`asks` table: chat, at, q, answer). They show what the learner wanted to say but could not, which is valuable input for the profile update (gaps → `focus`).
   - **Prompt for the character:** optionally tell it the learner just asked about X, so it gives a chance to use it. Simplest: nothing, since the learner uses it in the next reply anyway.
3. **Profile in the app.** Show `note_ru` and the mistakes in Russian in «Итог» or on the chat setup screen. The server can return the profile in the `/chat/<id>/end` response, or via `GET /profile`.
4. **Count correctly used words as a review.** The model reports `meta.used` = review/new words the learner used correctly. The phone credits them through `UserData` (like a practice answer, not a full review). This changes SRS schedules, so be careful.
5. **Coverage on Home.** `/progress` already returns `top300/top1000/top2000`. The phone can keep the last value and show «из 1000 самых частых слов знаю N».
6. **Send the snapshot on every Mac connect**, not only at chat start, so the mirror stays fresh for other uses.
7. **Tune** the thresholds and the frequency list after real data.

## Files
- **Mac:**
  - `pipeline/learner.py` — mirror, frequency, brief, journal, profile;
  - `pipeline/chat.py` — prompt: hints/chunks, brief, «LEARNER'S WORDS», «LEARNER PROFILE»;
  - `pipeline/server.py` — `/progress`, journal on each turn, profile after `/chat/<id>/end`;
  - `pipeline/freq_rank.json`.
- **Phone:**
  - `data/ProgressSnapshot.kt`;
  - `data/LiveChat.kt` (`pushProgress`, `showBrief`, `held`);
  - `model/LiveChat.kt` (`ChatBrief`, `chunks`);
  - `ui/LiveChatScreens.kt` (`HintPanel`, `nextHintLevel`, `BriefSheet`, 📋 title).
- **Personal data:** `books/.work/personal/learner.db` (gitignored). Never commit it.
