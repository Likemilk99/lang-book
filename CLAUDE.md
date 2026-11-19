# Lectura

Personal Android app for learning Spanish (Spain, es-ES) by reading adapted books. The user is a native Russian speaker at A0–A1 and reads on a Pixel 8 Pro. It is a personal tool, not a product: no onboarding, paywall, accounts or catalog.

## Architecture
- **Content is prepared on the Mac**: `pipeline/prepare_book.py` calls `claude -p --json-schema` using the user's subscription. There is no API key, and the app makes no LLM calls.
- **The app works offline**. A book is a JSON file `books/<id>.json`; every word already carries its translation and grammar. The only network use is a one-time download of a book's narrator pack from GitHub (`INTERNET` permission exists for that alone).
- **Audio**: book text is read from pre-generated narrator audio (Qwen3-TTS on the Mac, a voice per character, dialogue tags like «—dice Lucía» in the narrator's voice) once the book's pack is downloaded; otherwise, and always for words, phrases and exercises, the Android system TTS (es-ES). `Sentence.audio` is unused (reserved).
- Qwen3-TTS was chosen by the user after listening to one chapter voiced by Piper, Supertonic 3, Kokoro, Qwen3-TTS and Chatterbox (scripts and files in `books/.work/tts-compare/`, not in git). Don't re-open that choice without a reason; on-device models (Piper/Supertonic via sherpa-onnx) are the fallback idea if system TTS for words ever needs replacing.

## Structure
- `pipeline/prepare_book.py`: `--topic` writes an original story, `--source` adapts a txt/epub; `--enrich book.json` only refreshes a book's markup. Levels A0–B2. Results are cached in `books/.work/`, and `--push` sends the book to the phone over adb. The enrich step adds characters, the speaker of each line (`sp`), phrases, comprehension questions and dialogue replies, and tags (`--tags book.json` re-tags only). The tag catalog is `TAGS` in the pipeline = `model/Tags.kt` in the app (3 groups; ids are never changed).
- `pipeline/audio.py`: narrator audio (`--all`, `--upload`, `--voices`). Qwen3-TTS via MLX in its own venv (`pipeline/.cache/audio-venv`, created automatically). Voice bank `pipeline/voices/` (committed; `narr` + 4 male + 3 female, handed out by gender in `Book.characters` order). Each sentence is split into speech and dialogue tags, every piece is checked by Whisper (best of 3), and the result is `<chapter>_<sentence>.m4a`. Packs `books/.work/audio/<id>.zip` (+ `manifest.json` with a token hash per sentence) go to the pre-release GitHub Release `audio` (never mark it latest). The book gets `audio{url,size,version}`. Only changed sentences are regenerated; ~1× real time on an M1 Pro, all books ≈ 7 h.
- `android/`: Kotlin, Compose, Room, no DI. Package `app.lectura`.
  - `model/Book.kt`: book format (Book{characters,audio} → Chapter{phrases,questions,replies,grammar,glossary} → Paragraph → Sentence{sp} → Token{t,l,ru,g,r}).
  - `playback/Player.kt`: single source of truth for the current position. Reads sentence by sentence (narrator file via MediaPlayer, otherwise TTS) and saves progress.
  - `playback/PlaybackService.kt`: foreground service with MediaSession, for listening with the screen off.
  - `data/`: Room v4 (words, progress, meta, daily_stats, chapter_activity, grammar_progress), `UserData.kt` (all user-data writes, version stamping, backup, `markKnown`, `recordPractice`, `recordChapterActivity`), `Srs.kt` (SM-2), `Memory.kt` (recall estimate on the FSRS curve, picking words for extra practice), `BookStats.kt` (book vocabulary coverage by lemma, chapter exercises), `BookRepository` (JSON files in `Android/data/app.lectura/files/books/`), `CurriculumRepository` (syllabus from assets), `BookAudio` (download/unzip narrator packs to `files/audio/<id>/`; a sentence whose token hash doesn't match the manifest falls back to TTS — the hash must equal `token_hash` in `pipeline/audio.py`), `Expressions.kt` (multi-word expressions, see Rules), `Prefs`.
  - `ui/`: Library (StatsCard, level and topic filters, book progress), BookStatsScreen (per-chapter table), MemoryCard («Память словаря»), Reader (tap a word → bottom sheet, «Выделить фразу», chapter phrasebook, chapter glossary with «Учить все», «Ещё» menu with «Скачать/Обновить/Удалить озвучку», 🎙 in the player bar while the narrator reads), Vocab, Review, ProgressScreen («мой прогресс»), DataDialog (backup/restore, «Озвучка книг»: download all packs), QuestionsScreen, ShadowingScreen (shadowing and role-play). `ui/grammar/`: topic list, theory and practice per grammar topic (`GrammarLogic` checks answers and builds the spoken prompt).
  - `review/`: mixed session. `ExerciseBuilder` picks the exercise by the item's stage (ladder 0–6) and alternates types. Types: flashcards, listen-and-choose, fill-in from options, fill-in by typing, sentence building, say it aloud, dialogue reply. `SessionBatch` caps a session; `TextMatch` compares answers.
  - `speech/Speech.kt`: es-ES recognition (on-device first, cloud as fallback), `SpeechMatch` (LCS over words ignoring accents), `SpeakButton`. `SpeechTts` is TTS with a wait for completion. `SpanishNumbers`: digits → Spanish words.
- `pipeline/prepare_book.py --original`: public-domain originals with no text changes (authors who died before 1945, published before 1930; sources in `pipeline/sources/README.md`). Words are flagged rare/old (`Token.r`), each chapter gets a glossary, and versions of one work are linked through `workId`.
- `pipeline/curriculum.py`: the syllabus (`assets/curriculum/vocab_*.json`, `grammar_*.json`, contract in `model/Curriculum.kt`) following the structure of the Plan Curricular del Instituto Cervantes (own wording, not a copy). A1–B2: about 4,800 words and 84 grammar topics with about 3,000 exercises.
- `pipeline/library.py`: generates an adapted library per level so that it covers ≥ 90% of the level's vocabulary and every grammar topic (`Chapter.grammar`). Coverage reports are in `pipeline/coverage_*.md`.
- `UserProgress` (`data/UserProgress.kt`, `ProgressService.compute`) is the single aggregate of user statistics, computed from Room. Level = 0.5·vocabulary + 0.5·grammar; 80% counts as covered.
- Built-in books (`assets/books/`) refresh on the phone when their hash changes (`BookRepository.installBundled`).
- Tests: `./gradlew testDebugUnitTest` (`BookFormatTest` checks the built-in books; `BookAudioTest` checks that the Kotlin token hash equals the pipeline's and that `audio` packs point to the release).

## Build and release
- Local build: `cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`
- Version lives in `android/version.properties`; versionCode = MAJOR*10000+MINOR*100+PATCH.
- Release: bump the version and push to `main`. `.github/workflows/release.yml` then publishes `vX.Y.Z` with `lectura.apk`.
- Latest version: https://github.com/Likemilk99/lang-book/releases/latest/download/lectura.apk
- Before a release: `./gradlew testDebugUnitTest assembleRelease`. After the push: `gh run watch <id>`, then `gh release view vX.Y.Z`.
- Emulator: `adb`/`emulator` are not on PATH — `export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"`; `emulator -avd Pixel_8_Pro -no-window -no-audio -no-boot-anim`, stop with `adb emu kill`. The installed app is release-signed, so install `app-release.apk` (a debug APK fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE).
- Narrator audio: `python3 pipeline/audio.py books/<id>.json --upload` (system `python3`; the script re-executes itself in its venv). For a long run use `nohup caffeinate -is python3 pipeline/audio.py --all --upload > books/.work/audio/all.log 2>&1 &`. Doubtful pieces (Whisper mismatch after 3 tries) are listed in `books/.work/audio/<id>/problems.json`; most are spelling noise, ≥3 wrong words is a real glitch.
- `--upload` publishes to a public repo — it is outward-facing, confirm with the user before running it.
- After a book's text changes (re-enrich, new book): rerun `audio.py` for it with `--upload`, then release the app, because the book JSON gets a new `audio.version`. Until then the changed sentences fall back to TTS.
- Signing key: `~/.android/lectura-release.jks` + `.properties`, plus the `LECTURA_*` secrets on GitHub. **Never regenerate it**: a new key breaks updating the app on the phone.

## User data (must survive updates)
- Write user data **only through `UserData`**: it stamps `appVersion` on the record and updates `meta.lastSaveVersion/lastSaveAt`.
- Room schema change → bump `version` + add an `AutoMigration`/`Migration`; new columns get a `defaultValue`. Never use `fallbackToDestructiveMigration`.
- `android/app/schemas/*.json` is committed; CI fails if the schema doesn't match.
- Data steps between app versions go in `UserData.onAppStart()` (`if (from < X)`).
- Backup format (`Backup`, `FORMAT`): new fields only with defaults; an incompatible change means `FORMAT + 1`.
- Testing an upgrade: install the previous release APK on the emulator (AVD `Pixel_8_Pro`), create data, install the new one with `adb install -r`, open «Данные».

## Rules
- Typed and spoken answers are compared ignoring case and punctuation; digits equal words («3» = «tres», «1» = uno/un/una; `TextMatch`, `SpanishNumbers`). A wrong answer is shown word by word.
- Multi-word expressions (~1000 from the curriculum vocab) are recognised in the reader (`data/Expressions.kt`, `ExpressionIndex`): tapping a word shows the whole expression with its translation; verb + preposition counts only before an infinitive; accents matter. Saved phrases/expressions are highlighted in the text as a whole (`savedRanges`, `phraseIndex`); the expression card shows each word separately (curriculum level, other expressions with it).
- Spanish is read aloud wherever possible: an exercise sentence is spoken on appearance with a pause at the gap (`Player.speakWithPauses`), and the right answer after the reply.
- A review session is at most 20 words and phrases combined (`SessionBatch`): reviewed and most overdue first, at least 5 new ones (oldest added first). After it, «Ещё подход» takes the next 20.
- Extra practice does not change the schedule when the answer is right; a wrong answer sends the word back into regular review.
- «Знаю» = stage 3, interval 7 days. Words are matched by both form and lemma (`vocabIndex`).
- Content focus: conversational Spanish and dialogues (`--style dialog` by default in the pipeline).
- Spanish is Peninsular only (vosotros, Spain vocabulary). The interface and translations are in Russian.
- Book format changes must stay backward compatible (`ignoreUnknownKeys`, new fields optional).
- The project's ancestor is Eppika (reading adapted books). Features are prioritised for personal use.
