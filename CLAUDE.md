# Lectura

Personal Android app for learning Spanish (Spain, es-ES). The user is a native Russian speaker at A0–A1 who lives in Spain and uses a Pixel 8 Pro. The main goal is speaking in everyday situations as soon as possible. Adapted books stay as the reading/listening part.

It is a personal tool, not a product: no onboarding, paywall, accounts or catalog.

## Docs
Feature docs live in [`docs/`](docs/README.md), one file per feature. **Read the relevant doc before changing a feature, and update it in the same change.** When a new feature appears, add a file and list it in `docs/README.md`.

| Area | Doc |
|---|---|
| Principles, stack, package map, tabs | [docs/architecture.md](docs/architecture.md) |
| Books, `prepare_book.py`, library, reader | [docs/books.md](docs/books.md) |
| Qwen3-TTS narrator audio, `audio.py`, packs | [docs/narrator-audio.md](docs/narrator-audio.md) |
| Review, SRS, exercises, answer matching | [docs/review.md](docs/review.md) |
| Pixabay word pictures | [docs/word-images.md](docs/word-images.md) |
| «Говорить»: phrasebooks, scenarios, recognition | [docs/speaking.md](docs/speaking.md) |
| «Живой разговор»: character on the Mac, `chat.py`, voices | [docs/live-chat.md](docs/live-chat.md) |
| Progress mirror on the Mac, `learner.py`, frequency ranks | [docs/learner-progress.md](docs/learner-progress.md) |
| «Мои фразы»: `server.py` (Wi-Fi), `personal.py` (USB) | [docs/my-phrases.md](docs/my-phrases.md) |
| Home server on macOS/Windows, Claude vs local model (`llm.py`) | [docs/home-server.md](docs/home-server.md) |
| Audio lessons, listening trainer | [docs/lessons-and-listening.md](docs/lessons-and-listening.md) |
| Home, daily plan, reminder, progress | [docs/home-and-progress.md](docs/home-and-progress.md) |
| Syllabus and grammar | [docs/grammar-curriculum.md](docs/grammar-curriculum.md) |
| Room, `UserData`, backup, migrations | [docs/user-data.md](docs/user-data.md) |
| Build, tests, release, emulator | [docs/build-and-release.md](docs/build-and-release.md) |

## Core architecture (details in docs/architecture.md)
- **Content is prepared on the Mac** by `pipeline/*.py` via `claude -p --json-schema` on the user's subscription. No API key; the app makes no LLM calls.
- **The app works offline** (Kotlin, Compose, Room, no DI; package `app.lectura`). The network is used only for:
  - narrator packs (GitHub);
  - card pictures (Pixabay);
  - the optional Mac server in the home Wi-Fi («Мои фразы», «Живой разговор»).
- **Audio:** pre-generated Qwen3-TTS narrator audio where available, otherwise Android system TTS (es-ES). The Qwen3-TTS choice was made by the user after a comparison; don't re-open it without a reason.

## Rules that must not be broken
- **User data survives updates:**
  - Write it only through `UserData`.
  - Never use `fallbackToDestructiveMigration`.
  - A Room schema change needs a version bump, a migration and the committed `android/app/schemas/*.json`.
  - Backup fields are added only with defaults.
  - Details: [docs/user-data.md](docs/user-data.md).
- **Signing key** (`~/.android/lectura-release.jks` + `LECTURA_*` GitHub secrets): **never regenerate it.** A new key breaks updating the app on the phone.
- **Personal data («Мои фразы») never goes to git.** It lives only in `books/.work/personal/` (gitignored) and on the phone. The repo is public.
- **Outward-facing actions need the user's confirmation:**
  - `audio.py --upload` (publishes to a public GitHub Release);
  - commits, pushes and releases (the user approves each release).
- **Never bump `android/version.properties` on your own.** Releases go only through the `/release` skill (`.claude/skills/release/`) when the user invokes it: every release build runs on GitHub Actions (triggered only by a change to `android/version.properties` on `main`), and the user is on the free plan with limits.
- **Book format stays backward compatible:** `ignoreUnknownKeys`, new fields optional. Tag ids (`TAGS` = `model/Tags.kt`) are never changed.
- **Spanish is Peninsular only** (vosotros, Spain vocabulary). The interface and translations are in Russian. Content focus is conversational Spanish and dialogues.
- **Answer comparison** ignores case, punctuation and accents. Digits equal words (`TextMatch`, `SpeechMatch`, `SpanishNumbers`). Spanish is read aloud wherever possible.

## Quick commands
- **Tests + release build** (CI doesn't run tests):
  `cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleRelease`
- **Release:** only via `/release` (bumps `android/version.properties`, runs tests, one commit and one push to `main`, then `gh run watch <id>` and `gh release view vX.Y.Z`). Latest APK: https://github.com/Likemilk99/lang-book/releases/latest/download/lectura.apk
- **Emulator:**
  - `export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"`
  - `emulator -avd Pixel_8_Pro -no-window -no-audio -no-boot-anim`
  - Install `app-release.apk` (release-signed).
- **«Мои фразы» server:** double-click `Lectura Server.command` on macOS or `Lectura Server.bat` on Windows (or `python3 pipeline/server.py`). Without Claude Code it uses a local model in Ollama (`--llm local`).
- **Ancestor:** the project's ancestor is Eppika (reading adapted books).
