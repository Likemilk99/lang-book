# Lectura

Personal Android app for learning Spanish (Spain, es-ES) by reading adapted books. The user is a native Russian speaker at A0–A1 and reads on a Pixel 8 Pro. It is a personal tool, not a product: no onboarding, paywall, accounts or catalog.

## Architecture
- **Content is prepared on the Mac**: `pipeline/prepare_book.py` calls `claude -p --json-schema` using the user's subscription. There is no API key, and the app makes no LLM calls.
- **The app is fully offline**. A book is a JSON file `books/<id>.json`; every word already carries its translation and grammar.
- **TTS** is the Android system engine, es-ES voice. `Sentence.audio` is reserved for pre-generated narrator audio.

## Structure
- `pipeline/prepare_book.py`: `--topic` writes an original story, `--source` adapts a txt/epub; `--enrich book.json` only refreshes a book's markup. Levels A0–B2. Results are cached in `books/.work/`, and `--push` sends the book to the phone over adb. The enrich step adds characters, the speaker of each line (`sp`), phrases, comprehension questions and dialogue replies.
- `android/`: Kotlin, Compose, Room, no DI. Package `app.lectura`.
  - `model/Book.kt`: book format (Book{characters} → Chapter{phrases,questions,replies} → Paragraph → Sentence{sp} → Token{t,l,ru,g}).
  - `playback/Player.kt`: single source of truth for the current position. Reads sentence by sentence and saves progress.
  - `playback/PlaybackService.kt`: foreground service with MediaSession, for listening with the screen off.
  - `data/`: Room (words, progress, meta), `UserData.kt` (all user-data writes, version stamping, backup), `Srs.kt` (SM-2), `BookRepository` (JSON files in `Android/data/app.lectura/files/books/`).
  - `ui/`: Library (+StatsCard: today's numbers and the day streak), Reader (tap a word → bottom sheet, «Выделить фразу», chapter phrasebook, «Ещё» menu), Vocab, Review, QuestionsScreen, ShadowingScreen (shadowing and role-play).
  - `review/`: mixed session. `ExerciseBuilder` picks the exercise by the item's stage (ladder 0–6) and alternates types. Types: flashcards, listen-and-choose, fill-in from options, fill-in by typing, sentence building, say it aloud, dialogue reply.
  - `speech/Speech.kt`: es-ES recognition (on-device first, cloud as fallback), `SpeechMatch` (LCS over words ignoring accents), `SpeakButton`. `SpeechTts` is TTS with a wait for completion.
- Built-in books (`assets/books/`) refresh on the phone when their hash changes (`BookRepository.installBundled`).
- Tests: `./gradlew testDebugUnitTest` (`BookFormatTest` checks the built-in books).

## Build and release
- Local build: `cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`
- Version lives in `android/version.properties`; versionCode = MAJOR*10000+MINOR*100+PATCH.
- Release: bump the version and push to `main`. `.github/workflows/release.yml` then publishes `vX.Y.Z` with `lectura.apk`.
- Latest version: https://github.com/Likemilk99/lang-book/releases/latest/download/lectura.apk
- Signing key: `~/.android/lectura-release.jks` + `.properties`, plus the `LECTURA_*` secrets on GitHub. **Never regenerate it**: a new key breaks updating the app on the phone.

## User data (must survive updates)
- Write user data **only through `UserData`**: it stamps `appVersion` on the record and updates `meta.lastSaveVersion/lastSaveAt`.
- Room schema change → bump `version` + add an `AutoMigration`/`Migration`; new columns get a `defaultValue`. Never use `fallbackToDestructiveMigration`.
- `android/app/schemas/*.json` is committed; CI fails if the schema doesn't match.
- Data steps between app versions go in `UserData.onAppStart()` (`if (from < X)`).
- Backup format (`Backup`, `FORMAT`): new fields only with defaults; an incompatible change means `FORMAT + 1`.
- Testing an upgrade: install the previous release APK on the emulator (AVD `Pixel_8_Pro`), create data, install the new one with `adb install -r`, open «Данные».

## Rules
- Content focus: conversational Spanish and dialogues (`--style dialog` by default in the pipeline).
- Spanish is Peninsular only (vosotros, Spain vocabulary). The interface and translations are in Russian.
- Book format changes must stay backward compatible (`ignoreUnknownKeys`, new fields optional).
- The project's ancestor is Eppika (reading adapted books). Features are prioritised for personal use.
