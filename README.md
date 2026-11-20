# Lectura

A personal Android app for learning Spanish (Spain) from zero to everyday conversation. It is built for a Russian speaker living in Spain.

## What the app does
- **Говорить:**
  - phrasebooks (A0/A1) with narrator audio;
  - 12 real-life scenarios, from key phrases to answering in your own words;
  - «Мои фразы» — your own sentences translated on the Mac;
  - audio lessons for walks;
  - the listening trainer.
- **Повторение:** spaced repetition with a mixed exercise ladder, where speaking starts on day one.
- **Книги:** adapted and original books with tap-to-translate, narrator audio, shadowing and role-play.
- **Грамматика:** syllabus following the Instituto Cervantes curriculum, with theory and practice.
- **Offline:** all content is prepared on the Mac ahead of time.

Documentation by feature: [`docs/`](docs/README.md).

## Install
Latest APK: https://github.com/Likemilk99/lang-book/releases/latest/download/lectura.apk

## Releases
1. Change `version=` in `android/version.properties`.
2. Push to `main`.
3. GitHub Actions builds a signed APK and publishes the release.

The signing key lives in `~/.android/lectura-release.jks` (not in the repository) and in the GitHub secrets. **Don't lose it.** Without it, a new APK won't install over the old one.

## Local build
```bash
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleRelease
```
