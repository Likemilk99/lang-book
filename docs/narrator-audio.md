# Narrator audio

## Engine choice
- **Qwen3-TTS** via MLX on the Mac, with **Whisper** (mlx-whisper) as the checker.
- The user chose it after listening to one chapter voiced by Piper, Supertonic 3, Kokoro, Qwen3-TTS and Chatterbox. Scripts and files are in `books/.work/tts-compare/` (not in git).
- Don't re-open that choice without a reason.
- If system TTS for words ever needs replacing, on-device models (Piper/Supertonic via sherpa-onnx) are the fallback idea.

## `pipeline/audio.py`
- **Run with the system `python3`.** The script re-executes itself in `pipeline/.cache/audio-venv`, which `uv` creates automatically.
- **Voice bank** `pipeline/voices/` (committed):
  - `narr`, plus `m1`–`m4` (male) and `f1`–`f3` (female).
  - Created once with `--voices` (VoiceDesign); then cloned with the Base model.
- **Engine:**
  - `Engine.segment` makes up to 3 attempts per piece and keeps the best by Whisper word errors.
  - Pieces that are still doubtful go to `problems.json`. Most of them are spelling noise; ≥ 3 wrong words is a real glitch.
- **Modes:**

| Mode | What it voices | Output |
|---|---|---|
| `books/<id>.json`, `--all` | Book sentences. Each sentence is split into speech and dialogue tags («—dice Lucía» in the narrator voice). Characters get voices by gender in `Book.characters` order. | `<chapter>_<sentence>.m4a` in a pack `books/.work/audio/<id>.zip` + `manifest.json` (token hash per sentence) |
| `--upload` | Uploads the packs to the **pre-release** GitHub Release `audio` (never mark it latest); the book gets `audio{url,size,version}` | — |
| `--phrasebook` | Phrasebook phrases + slot variants, plus scenario-only key phrases (narr) | `assets/phrasebook/audio/<id>.m4a`, `<id>_<k>.m4a` (bundled in the APK) |
| `--scenarios` | Scenario lines (learner m1, other person f2/m2), «понять» items, questions and first accepted answers | `assets/scenarios/audio/` (bundled) |
| `--clips list.json --out DIR` | Arbitrary `[name, text, voice]` list (used by «Мои фразы») | `DIR/<name>.m4a` |

- **Shared helper:** `voice_set(engine, items, out, work_name)` regenerates only changed items (state keyed by model + voice + text in `books/.work/audio/<work_name>/state.json`) and deletes files that are no longer wanted.
- **Speed:** ~1× real time on an M1 Pro; all books ≈ 7 h. For a long run:
  `nohup caffeinate -is python3 pipeline/audio.py --all --upload > books/.work/audio/all.log 2>&1 &`
- **`--upload` publishes to a public repo.** It is outward-facing, so confirm with the user before running it.
- **After a book's text changes** (re-enrich, new book): rerun `audio.py` for it with `--upload`, then release the app, because the book JSON gets a new `audio.version`. Until then the changed sentences fall back to TTS.

## In the app
- **Book packs: `data/BookAudio`.**
  - Downloads and unzips the pack to `files/audio/<id>/`.
  - A sentence whose token hash doesn't match the manifest falls back to TTS. The Kotlin hash must equal `token_hash` in `audio.py`; `BookAudioTest` checks this and that packs point to the release.
  - «Данные» → «Озвучка книг» downloads all packs.
- **Bundled clips** (phrasebooks, scenarios):
  - `PhrasebookRepository.clip(id, variant)` / `clipFor(ref, es)`.
  - `playback/SpeakEs.kt` `speakEs(text, ref)` plays the narrator clip only when the text matches the phrase or a variant exactly; otherwise it uses TTS.
- **`playback/ClipPlayer.kt`:** plays an APK asset or an absolute file path and suspends until it ends. Speed is set after subscribing to completion; TTS is the fallback via `SpeechTts`.
- **Words, exercises and anything without a clip** use system TTS (es-ES). `Player.speakWithPauses` reads an exercise sentence with a pause at the gap.
