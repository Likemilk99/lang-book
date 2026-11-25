# Syllabus and grammar

## Syllabus (`pipeline/curriculum.py`)
- **Output:** `assets/curriculum/vocab_*.json` and `grammar_*.json`. The contract is `model/Curriculum.kt`, loaded by `CurriculumRepository`.
- **Structure:** follows the Plan Curricular del Instituto Cervantes, in our own wording (not a copy).
- **Size:** A1–B2, about 5,100 words (A1 909, A2 1082, B1 1464, B2 1633) and 84 grammar topics with about 3,000 exercises.
- **Planning file:** `pipeline/curriculum_plan.json`.
- **Manual fixes (`pipeline/vocab_fixes.json`):** the generated lists missed frequent everyday words (*interesante, normal, elegir, apetecer, ustedes, qué tal…*) and placed some at B1/B2 (*faltar, perfecto, soler…*). The B1/B2 audit added ~170 words (Spain colloquial *flipar, mogollón, rollo, cutre*; *despacio, interesar, marcharse, averiguar, sugerir…*), moved frequent spoken words down to A2 (*realmente, echar, pena, culpa, justo…*), fixed core translations (*superar, sentido, falta, natural, casero*) and removed near-duplicates, Latin Americanisms and too-narrow words.
  - `words` puts a word on a level and theme, moving it if it already exists elsewhere (ru/pos/g come from the fix). `"a0": true` marks words for the A0 books. A `words` entry for a word already at its level just replaces its translation. `remove` drops duplicates (*zapatos* next to *zapato*, *quejarse de* next to *quejarse* with the same meaning), Latin Americanisms (*enojarse*), outdated (*minusválido*) and too-narrow words (*cóncavo, nanotecnología*).
  - Applied by `python3 pipeline/curriculum.py --steps fix`, and automatically after every `vocab` step, so a regenerated list keeps them. Idempotent.
- **Frequency audit:** `python3 pipeline/curriculum.py --audit [TOP]` (needs `pip install wordfreq simplemma`, best in a separate venv) writes `pipeline/vocab_audit.md`: frequent lemmas missing from every level, and frequent lemmas sitting at B1/B2. The frequency list includes news, Wikipedia and Latin American Spanish, so it is a list for manual review; decisions go into `vocab_fixes.json`.
- **Uses elsewhere:**
  - Books are generated to cover the syllabus (`pipeline/library.py`, see [books.md](books.md)).
  - Multi-word expressions come from the vocab ([books.md](books.md)).

## Grammar in the app (`ui/grammar/`)
- **Screens:** topic list (`GrammarListScreen`; level is remembered in `Prefs.grammarLevel`) → topic theory (`GrammarTopicContent`) → practice (`GrammarPracticeContent`, `GrammarPracticeVm`).
- **`GrammarLogic`:** checks answers and builds the spoken prompt. With `Prefs.grammarAutoSpeak`, the sentence is read on appearance and the answer after.
- **Progress:** Room `grammar_progress`. Due grammar reviews feed the «План на сегодня» grammar step.

## Tests
`CurriculumFormatTest`.
