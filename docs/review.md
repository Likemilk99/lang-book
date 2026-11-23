# Review and vocabulary

## Items
- **What becomes an item:** saved words and phrases (Room `words`) are review items. `SavedWord.ref` links a phrase to a phrasebook, scenario or «Мои фразы» phrase id, so narrator audio and templates can be found.
- **One card per word** (`data/WordCards.kt`, since 0.14):
  - A word card is keyed by its dictionary form (`Token.l`, lowercased; accents and ñ kept): hablo, hablamos and habla all go to the card **hablar**.
  - Each form the user saved is a `WordExample` in `SavedWord.examples` (form, sentence, its translation, grammar, book). Saving another form of a saved word adds an example instead of a new card (`UserData.saveWord` → `SaveResult.EXAMPLE_ADDED`, toast «Уже в словаре — добавлен пример»).
  - Cards saved before 0.14 have no `examples`; their single example is `sentence`/`sentenceRu` with `word` as the form (`allExamples`).
  - Card translation is the dictionary one (`Lexicon`): curriculum vocab first, then `assets/lemmas.json` (`pipeline/lemmas.py`), otherwise the contextual `Token.ru`.
  - `UserData.consolidateWords()` merges cards with the same dictionary form: the most advanced card (stage, interval, reps) keeps its schedule, all examples move to it. It runs once on upgrade to 0.14 (after a safety copy `Android/data/app.lectura/files/backup-before-0.14.json`) and after every backup import. Phrases are never merged.
- **Matching:** words are matched by both form and lemma (`vocabIndex`).
- **Bulk adding:** «Учить все» (chapter glossary, phrasebook theme) and «Мои фразы» go through `UserData.addPhrases(phrases, label)`.

## Scheduling
- `data/Srs.kt`: SM-2.
- «Знаю» = stage 3, interval 7 days.
- `data/Memory.kt`: recall estimate on the FSRS curve. It is used to pick words for extra practice and for «Память словаря» (`ui/MemoryCard`).
- Extra practice doesn't change the schedule when the answer is right; a wrong answer sends the word back into regular review (`UserData.recordPractice`).

## Session (`review/`)
- **Batch size:** a session is at most 20 words and phrases combined (`SessionBatch`). Reviewed and most overdue items come first, with at least 5 new ones (oldest added first). After it, «Ещё подход» takes the next 20.
- **Ladder:** `ExerciseBuilder` picks the exercise by the item's stage (ladder 0–6, `internal fun ladder(stage, phrase)`) and alternates types.

| Type | When |
|---|---|
| Listen and repeat (`LISTEN_REPEAT`) | Stages 0–2 — speaking starts from the first day |
| Flashcard, listen and choose, fill-in from options, fill-in by typing, sentence building | By stage |
| Say it aloud (`SAY_ES`) | Stage 3+ |
| Say by template (`PATTERN_SAY`, phrases with a `pattern`: «Quería ___.» + slot variant) | Stage 3+ |
| Dialogue reply | Phrases from book dialogues |

- **Speaking weight:** speaking exercises are twice as likely for phrases.
- **Examples rotate:** `ExerciseBuilder.withNextExample` gives each exercise an example other than the one shown last time (it is kept in `sentence`). The cloze blanks the form from the example (`SavedWord.form`: «Nosotros ___ mucho» → hablamos), so one card trains the conjugated forms in context. The flashcard shows the dictionary form with «в тексте: hablamos».
- **Cloze rule:** a cloze is skipped when it would blank out the whole sentence.
- **Exercise views:** `ChoiceExercises.kt`, `InputExercises.kt`, `SpeakExercises.kt` (`ListenRepeatView`, `PatternSayView`), `FlashCard.kt`.
- **Common.kt:** `ListenButtons` and `FeedbackPanel` play via `speakEs`, so phrases get the narrator.
- **ReviewViewModel:** loads the phrase map into the pool and prefetches card pictures for the next 5 words ([word-images.md](word-images.md)).

## Answer matching
- **Typed answers:** `TextMatch` ignores case and punctuation; digits equal words («3» = «tres», «1» = uno/un/una; `SpanishNumbers`). A wrong answer is shown word by word.
- **Spoken answers:** see `SpeechMatch` in [speaking.md](speaking.md).
- **Read-aloud:** Spanish is read aloud wherever possible. An exercise sentence is spoken when it appears, and the right answer after the reply.

## Vocabulary tab
- `VocabScreen` lists saved words and phrases. A word shows its first example; «ещё примеров: N» expands the rest (form in bold).

## Tests
`SessionBatchTest`, `SpacingTest`, `MemoryTest`, `TextMatchTest`, `WordCardsTest` (card from token, merge, examples, rotation).
