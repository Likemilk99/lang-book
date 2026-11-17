# Lectura

A personal Android app for learning Spanish (Spain variant) by reading adapted books, in the style of Eppika.

- **Reader**: tap any word to see its translation in context, dictionary form and grammar (tense, person), hear it pronounced, and save it.
- **Listening**: text-to-speech in es-ES, reading sentence by sentence with the current sentence highlighted. Speed from 0.6× to 1.2×. Plays in the background with the screen off, and responds to headset buttons.
- **Dictionary + review**: saved words become flashcards with spaced repetition (SM-2), shown in their original sentence.
- **Offline**: all translations are prepared ahead of time and stored in the book's JSON file.

## Preparing books (on the Mac, via Claude Code)

```bash
# Original A1 story on a topic
python3 pipeline/prepare_book.py --topic "a cat who lives in Madrid" --level A1 --chapters 5 --push

# Adapt an existing book (txt/epub, e.g. from Project Gutenberg)
python3 pipeline/prepare_book.py --source marianela.txt --title "Marianela" --author "Benito Pérez Galdós" --level A1 --max-chunks 3 --push
```

The result goes to `books/<id>.json`. With `--push`, the file is copied to the phone over adb. Otherwise, use the "Import" button in the app.
Levels: A0, A1, A2, B1, B2. The `--model` flag selects the model (sonnet by default).

## Releases

1. Change `version=` in `android/version.properties`, for example `0.1.0` → `0.2.0`.
2. Push to `main`.
3. GitHub Actions builds a signed APK and publishes the release `v0.2.0`.

The latest version on your phone is always here: https://github.com/Likemilk99/lang-book/releases/latest/download/lectura.apk

The signing key lives in `~/.android/lectura-release.jks` (not in the repository) and in the repository's GitHub secrets.
**Don't lose it.** Without it, a new APK won't install over the old one.

## Local build

```bash
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installRelease
```
