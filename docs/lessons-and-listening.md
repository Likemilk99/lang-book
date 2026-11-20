# Audio lessons and the listening trainer

## Audio lessons («Аудиоуроки», for walks, screen off)
- **Modes** (`playback/Lessons.kt`, `LessonMode`):
  - **«Предугадай» (ANTICIPATE):** a Russian prompt (ru-RU system TTS) → a pause sized by phrase length (`LessonPlayer.pauseFor`, 2–8 s, longer with «🐢 Медленнее») → the narrator clip twice.
  - **«Слушай и повторяй» (REPEAT):** Spanish → pause → Spanish again.
- **Sources** (`data/LessonSources.kt`). Lessons are built on the fly, so there are no extra audio files:
  - «Повторение на сегодня» (up to 25 due items);
  - «Мои фразы»;
  - each scenario (key phrases + your lines);
  - each phrasebook theme.
- **`LessonPlayer`:** state flow (index, phase, playing, mode, slow); start/toggle/pause/resume/next/prev/stop. The Russian TTS is created lazily in `ru()` via `main.post` to avoid recursion during init. If no Russian voice is installed, the screen says so (`ruMissing`).
- **`LessonService`:**
  - Its own foreground service (notification id 3, play/pause/next/prev) and MediaSession `lectura-lesson` for headset buttons.
  - A partial wake lock `lectura:lesson` is held while playing (20-minute timeout, re-armed on every state change), so pauses don't stretch while the CPU sleeps.
- **UI:** `ui/LessonsScreen.kt` (mode chips, sources with an estimated length, `NowPlaying` card).

## Listening trainer («Тренажёр слуха»)
- **Data** (`data/Listening.kt`), 10 items per session (`Listening.SESSION`):
  - **Диктант** (`dictation()`): review phrases with spaces, already studied ones first, topped up from the A0 phrasebook. You hear the phrase and type or say it. Missed words are marked red and underlined via `SpeechMatch.compare`.
  - **«Понять на слух»** (`comprehension()`): the other person's lines and «понять» items from scenarios — what sellers, doctors and clerks will say to you. You pick the meaning in Russian.
  - **Distractors** (`questions()`) come from the same scenario first, so the answer can't be guessed from the topic.
- **UI** (`ui/ListenScreen.kt`, `ListenVm`):
  - Speed 0.8 / 1.0 / 1.2; «один раз» (a single play only).
  - Answers go through `recordFreeExercise`. A miss on a review item goes through `recordPractice` and returns it to review.

## Tests
`LessonTest`, `ListeningTest`.
