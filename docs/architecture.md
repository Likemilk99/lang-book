# Architecture

## Principles
- **Content is prepared on the Mac.** Python scripts in `pipeline/` call `claude -p --json-schema` (helper `claude_json` in `prepare_book.py`) on the user's Claude subscription. There is no API key, and the app makes no LLM calls.
- **The app works offline.** Books, phrasebooks, scenarios and the syllabus are JSON with every translation precomputed. Network is used only for:
  - one-time downloads: book narrator packs (GitHub Release `audio`) and card pictures (Pixabay);
  - the optional server on the Mac in the home network: «Мои фразы» ([my-phrases.md](my-phrases.md)) and «Живой разговор» ([live-chat.md](live-chat.md)). The conversation's LLM calls happen on the Mac, not in the app.
- **Audio.**
  - Pre-generated narrator audio (Qwen3-TTS on the Mac) for book text, phrasebooks and scenarios.
  - The Android system TTS (es-ES) for everything else and as a fallback.
  - See [narrator-audio.md](narrator-audio.md).
- **Personal tool, not a product.** No onboarding, paywall, accounts or catalog. Features are prioritised for one user: a native Russian speaker at A0–A1 who lives in Spain and uses a Pixel 8 Pro. The main goal is speaking in everyday situations as soon as possible.

## Stack
- **Android:** Kotlin, Jetpack Compose (Material3), Room, WorkManager, Navigation Compose, kotlinx.serialization, OkHttp. No DI: singletons live in `LecturaApp` (`LecturaApp.instance`).
- **Mac:** Python 3 scripts in `pipeline/`. Audio and the «Мои фразы» server run in their own venv (`pipeline/.cache/audio-venv`, created automatically by `uv`) with mlx-audio, mlx-whisper and aiohttp.

## Package map (`android/app/src/main/java/app/lectura/`)

| Package | Contents |
|---|---|
| `model/` | JSON contracts shared with the pipeline: `Book`, `Curriculum`, `Phrasebook`, `Scenario`, `Personal`, `LiveChat`, `Tags` |
| `data/` | Room (`Db.kt`), `UserData` (all user-data writes), repositories (books, curriculum, phrasebook, scenarios, personal), `DailyPlan`, `Srs`, `Memory`, `BookStats`, `UserProgress`, `Expressions`, `Listening`, `LessonSources`, `MacLink`, `LiveChat`, `Prefs` |
| `playback/` | `Player` (book reading + system TTS), `PlaybackService`, `Lessons` + `LessonService` (audio lessons), `ClipPlayer`, `SpeakEs` |
| `speech/` | Recognition (`Speech.kt`: `SpeakButton`, `SpeechMatch`), `SpeechTts`, `SpanishNumbers` |
| `review/` | Mixed review session: `ExerciseBuilder`, exercise views, `ReviewViewModel`, `SessionBatch`, `TextMatch` |
| `images/` | Pixabay pictures for word cards |
| `reminder/` | Daily reminder (WorkManager) |
| `ui/` | Screens; `ui/grammar/` holds the grammar topics |

## Tabs and navigation
- `MainActivity` has four tabs: **Главная · Говорить · Повторение · Словарь**. The start destination is `home`. The review tab shows a badge with the number of due items.
- Library and Grammar are sections opened from Home and have «Назад».
- Helpers: `NavHostController.openTab(route)` switches tabs; `openRoute(route)` switches to a tab or pushes a screen.
- Routes: `library`, `grammar`, `grammar/{id}`, `grammar/{id}/practice`, `progress`, `bookstats/{id}`, `reader/{id}`, `questions/{id}/{ch}`, `speak/{id}/{ch}/{mode}`, `phrasebook/{level}[/{theme}[/train]]`, `scenario/{id}[/{step}]`, `chat`, `mine`, `mine/train`, `lessons`, `listen`.

## Language
- Spanish is Peninsular only (vosotros, Spain vocabulary).
- The interface and translations are in Russian.
- Content focuses on conversational Spanish and dialogues.
