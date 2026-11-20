# Build, tests, release

## Build
- **Local build:**
  `cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`
- **Before a release:**
  `./gradlew testDebugUnitTest assembleRelease`
  **CI does not run the unit tests** — run them locally.

## Tests
- **Run:** `./gradlew testDebugUnitTest`. They are JVM unit tests in `android/app/src/test/java/app/lectura/`.
- **Coverage:** each feature doc lists its tests. Several tests read pipeline files to keep contracts in sync:
  - `BookAudioTest`: token hash vs `audio.py`.
  - `MacLinkTest`: port, service and header vs `server.py`.
  - `PhrasebookTest` / `ScenarioTest`: every clip exists.
- **Logging:** Android's `Log` isn't available in unit tests, so code that logs must wrap it in `runCatching`.

## Release
1. Bump `android/version.properties` (`version=X.Y.Z`). versionCode = MAJOR·10000 + MINOR·100 + PATCH.
2. Push to `main`. `.github/workflows/release.yml` builds a signed APK, checks the Room schemas and publishes `vX.Y.Z` with `lectura-X.Y.Z.apk` and `lectura.apk`.
3. `gh run watch <id>`, then `gh release view vX.Y.Z`.

- **Latest APK:** https://github.com/Likemilk99/lang-book/releases/latest/download/lectura.apk
- **Approval:** each release is approved by the user before pushing.

## Keys
- **Signing key:**
  - `~/.android/lectura-release.jks` + `.properties`, plus the `LECTURA_*` secrets on GitHub.
  - **Never regenerate it.** A new key breaks updating the app on the phone.
- **Pixabay key:** see [word-images.md](word-images.md).

## Emulator
- **PATH:** `adb` and `emulator` are not on PATH:
  `export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"`
- **Start:** `emulator -avd Pixel_8_Pro -no-window -no-audio -no-boot-anim`. Stop with `adb emu kill`.
- **Install the release APK:** the installed app is release-signed, so install `app-release.apk`. A debug APK fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- **Screenshot coordinates:** screenshots are scaled down; multiply coordinates by ~1.16 to tap.
- **Typing:** `adb shell input text` can't type Cyrillic.
- **No audio or microphone:** the actual sound and speech recognition must be checked on the phone.
- **Reaching the Mac:**
  - The emulator reaches the Mac at `10.0.2.2`.
  - Bonjour discovery of `server.py` also worked from the emulator.
