# Syllabus and grammar

## Syllabus (`pipeline/curriculum.py`)
- **Output:** `assets/curriculum/vocab_*.json` and `grammar_*.json`. The contract is `model/Curriculum.kt`, loaded by `CurriculumRepository`.
- **Structure:** follows the Plan Curricular del Instituto Cervantes, in our own wording (not a copy).
- **Size:** A1–B2, about 4,800 words and 84 grammar topics with about 3,000 exercises.
- **Planning file:** `pipeline/curriculum_plan.json`.
- **Uses elsewhere:**
  - Books are generated to cover the syllabus (`pipeline/library.py`, see [books.md](books.md)).
  - Multi-word expressions come from the vocab ([books.md](books.md)).

## Grammar in the app (`ui/grammar/`)
- **Screens:** topic list (`GrammarListScreen`; level is remembered in `Prefs.grammarLevel`) → topic theory (`GrammarTopicContent`) → practice (`GrammarPracticeContent`, `GrammarPracticeVm`).
- **`GrammarLogic`:** checks answers and builds the spoken prompt. With `Prefs.grammarAutoSpeak`, the sentence is read on appearance and the answer after.
- **Progress:** Room `grammar_progress`. Due grammar reviews feed the «План на сегодня» grammar step.

## Tests
`CurriculumFormatTest`.
