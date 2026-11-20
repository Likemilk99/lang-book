# Speaking («Говорить» tab)

## Tab layout (`ui/TalkScreen.kt`), top to bottom
1. **«Мои фразы»** card with the Mac status dot — see [my-phrases.md](my-phrases.md).
2. **«Аудиоуроки»** — see [lessons-and-listening.md](lessons-and-listening.md).
3. **«Тренажёр слуха»** — see [lessons-and-listening.md](lessons-and-listening.md).
4. **Scenarios by level**, with «могу N из M».
5. **Phrasebooks.**
6. **Dialogues from books:** role-play and shadowing by chapter of started books.

## Phrasebooks
- **Content:** `pipeline/phrasebook.py` builds A0 «Выживание» and A1 «Повседневные ситуации», 12 themes each (`THEMES`).
  - Output: `assets/phrasebook/phrasebook_<L>.json`; contract `model/Phrasebook.kt`.
  - Written theme by theme by `claude -p` (opus) and cached in `pipeline/.cache/phrasebook/`. A1 avoids A0 phrases.
- **Templates:**
  - A phrase may have a template `pattern` («Quería ___.», `GAP = "___"`) with `slots`; slot 0 is the phrase itself.
  - `clean()` validates templates.
  - `fold_preposition()` turns «a ___» + «el …» into «al …», so it never writes «a el».
- **Audio:** `audio.py --phrasebook` (narr) → `assets/phrasebook/audio/`, bundled in the APK.
- **Repository:** `PhrasebookRepository` loads both levels. Its `byId` index also includes scenario key phrases and personal phrases (`registerExtra`).
- **UI (`ui/PhrasebookScreens.kt`):** themes → phrases with narrator audio.
  - «Учить все» → `UserData.addPhrases`.
  - «Тренировка» → `PhraseTrainer(title, phrases, onBack, onFinished)`, a reusable listen-and-repeat trainer with `VoiceCompare`.

## Scenarios
- **Content:** `pipeline/scenarios.py` writes 12 scenarios (`SCENARIOS`) to `assets/scenarios/scenarios.json`; contract `model/Scenario.kt`.
  - A0: café, pharmacy, shop, train, directions, neighbour.
  - A1: doctor, flat viewing, repair call, bank, police report, restaurant.
- **Each scenario has:**
  - a situation, a «Могу …» goal, and the other person's role and gender;
  - 8–10 key phrases (phrasebook phrases are reused by exact text → same id → one card in review);
  - 2 dialogues (normal + twist);
  - 5 «понять ответ» items;
  - 4–5 questions with several accepted answers.
- **Audio:** `audio.py --scenarios`. The learner is m1; the other person is f2 or m2; key phrases are narr into `assets/phrasebook/audio`.
- **Logic (`data/Scenarios.kt`):**
  - `ScenarioStep` has 7 steps: PHRASES, LISTEN, ROLE_ME, ROLE_THEM, TWIST, UNDERSTAND, ANSWER. The keys are stored in the DB.
  - `ScenarioState`: a step counts from 60 % (`DONE_PCT`); `next`; `canDo` = all steps done → shown as «Могу» on Home.
  - Progress goes to Room `scenario_progress` (best % per step) via `UserData.recordScenarioStep`.
- **UI (`ui/ScenarioScreens.kt`):**
  - An overview of the 7 steps.
  - Steps: phrases (`PhraseTrainer`) → listen (text hidden) → your role → the other person's role → twist → understand (shuffled options) → answer in your own words.
  - The answer step uses `SpeakButton(accepted = …)` (passes on any accepted answer) and has «засчитать».
  - `ScenarioVm` plays clips via `ClipPlayer` and has a slow mode.

## Dialogues from books
`ShadowingScreen`: shadowing (repeat after the narrator) and role-play (you say one character's lines) per chapter.

## Speech recognition (`speech/Speech.kt`)
- **Recognizer:** es-ES, on-device first, cloud as fallback.
- **`SpeakButton(expected, modifier, accepted, onResult)`:** «Нажмите и скажите вслух». Recording ends by button or on silence (`Prefs.speechManualStop`).
- **`SpeechMatch`:** LCS over words, ignoring accents and punctuation.
  - `SpeechMatch.best(accepted, alternatives)` takes the best over several accepted answers.
  - `SpeechMatch.withoutNames` makes proper names inside a sentence optional, so «Me llamo Iván» accepts any name.
  - `SpeechMatch.compare` marks missed words.
- **`SpeechTts`:** TTS with a wait for completion.

## «Записать себя» (`ui/VoiceCompare.kt`)
- `VoiceRecorder` records with MediaRecorder to `cacheDir/my_voice.m4a`. This is separate from recognition.
- «▶ Диктор → вы» plays the narrator clip, then your take, so you hear the difference.
- It is shown in the phrase trainer.

## Tests
`PhrasebookTest` (format, every clip exists) and `ScenarioTest` (format, id reuse, audio, logic, free answer, names).
