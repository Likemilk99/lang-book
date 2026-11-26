# Learner progress on the Mac

The phone mirrors its learning progress to the home server, so «Живой разговор» ([live-chat.md](live-chat.md)) works with the learner's own words: the ones they are forgetting, frequent ones they don't know yet, and the ones they know. The aim is to speak as soon as possible, so word choice is **frequency first**.

## Rules
- **The phone is the source of truth.** The Mac copy is read-only: every snapshot replaces it as a whole, and nothing goes back to the phone. User data is still written only through `UserData`.
- **Personal data:** the mirror is `books/.work/personal/learner.db` (gitignored), like the rest of «Мои фразы». The selected words go to Claude (or the local model) as prompt context, as «Обо мне» already does.
- **No Room change:** the snapshot is built from existing tables.

## Phone (`data/ProgressSnapshot.kt`)
- `ProgressSnapshot.build(db)`: every word card (not phrases) with its dictionary form, translation, recall now in % (`Memory.recall`), stage, reps, lapses, interval, last review, creation time; the number of phrases; grammar topics (stage, best/last %, sessions); the last 60 days of `daily_stats`.
- Recall is computed on the phone, so the forgetting curve lives in one place (`Memory.kt`).
- **When:** `LiveChat.pushProgress` sends `POST /progress` right before `/chat/start`, including the restart after a Mac restart. An older Mac (404) or an error just means a conversation without it.

## Mac (`pipeline/learner.py`)
- **`POST /progress`** (`server.py`): `clean_snapshot` keeps known fields of sane size (format 1, ≤ 20 000 words), `store` replaces the `words` and `grammar` tables, upserts `days`, writes `meta` and one `coverage` row per day. The log shows «📊 прогресс: слов в карточках N, знаю K; из самых частых знаю a/300, b/1000, c/2000».
- **`brief(level, themes)`** at `/chat/start` (themes = the topic's vocab theme ids, the first one is the topic's own):
  - **review** (10): weak words (recall < 75 %, or 2+ lapses and recall < 90 %). Score = forgetting + lapses + a frequency bonus (top 500 / 1500 / 4000) + 0.3 on topic. Words without a frequency (rare book words like *estrar*) get a penalty.
  - **new** (5): curriculum nouns, verbs, adjectives and adverbs of the chat levels (A0/A1 → A1, A2 → A1–A2, B1 → A1–B1) that are not in the cards. Sorted by frequency rank, verbs count as 0.6 of their rank (core verbs first). 2 from the topic (its first theme first) + 3 general. Words already offered move back by 400 ranks per time (`offered` table), so each conversation brings different ones.
  - **known** (≤ 120): recall ≥ 70 % and stage ≥ 3 or 2+ reps, beyond the level word list the prompt already has (none for B1, which has no list).
- **`prompt_block`** → the «LEARNER'S WORDS» section of the chat system prompt (`chat.build_system(..., learner)`): use 3–5 review words naturally and put them first in hints; introduce 1–2 new words and repeat them; known words may be used freely. Without a snapshot the section is empty. A broken mirror never breaks a conversation (`chat_block` logs and returns "").
- **Coverage:** how many of the 300 / 1000 / 2000 most frequent curriculum content words are known. A plain measure of how much everyday speech the learner can follow.

## Frequency (`pipeline/freq_rank.json`)
- Lemma → rank among all Spanish lemmas (wordfreq «es», lemmatized with simplemma) for every curriculum and book lemma. Committed, so the server needs neither package.
- Summed frequency of all forms only when simplemma keeps the word as its own lemma. It maps *estado* → estar, *sí* → él, *fuera* → ser, so those use their own surface frequency. A reflexive verb gets a third of its base verb (*hacerse* ≪ *hacer*). Multi-word expressions have no rank.
- wordfreq also counts Latin American Spanish and news; only our own (Spain) word lists are ranked, so nothing Latin American gets in.
- **Rebuild** after vocabulary or book changes:
  ```
  uv venv pipeline/.cache/freq-venv && uv pip install --python pipeline/.cache/freq-venv/bin/python wordfreq simplemma
  pipeline/.cache/freq-venv/bin/python pipeline/learner.py --build-freq
  ```

## Check
- `python3 pipeline/learner.py [--level A2] [--themes body-health,time]` prints the coverage and the prompt section the next conversation would get (without counting the new words as offered).
- `python3 pipeline/chat.py --print-prompt` includes it.

## Later
- A pre-conversation brief (useful words and phrases, reopened from the chat header) built from this data.
- A log of the conversation fixes and a short learner profile (typical mistakes) updated by Claude after each conversation.
- Words used correctly in a conversation counted as a review (this changes the schedule on the phone, so it needs `UserData`).

## Tests
`ProgressSnapshotTest`: phrases are not words, recall is filled, 60 days max; field names and the format match `learner.py`, `/progress` exists in `server.py` and `LiveChat`.
