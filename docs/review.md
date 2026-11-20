# Review and vocabulary

## Items
- **What becomes an item:** saved words and phrases (Room `words`) are review items. `SavedWord.ref` links a phrase to a phrasebook, scenario or «Мои фразы» phrase id, so narrator audio and templates can be found.
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
- **Cloze rule:** a cloze is skipped when it would blank out the whole sentence.
- **Exercise views:** `ChoiceExercises.kt`, `InputExercises.kt`, `SpeakExercises.kt` (`ListenRepeatView`, `PatternSayView`), `FlashCard.kt`.
- **Common.kt:** `ListenButtons` and `FeedbackPanel` play via `speakEs`, so phrases get the narrator.
- **ReviewViewModel:** loads the phrase map into the pool and prefetches card pictures for the next 5 words ([word-images.md](word-images.md)).

## Answer matching
- **Typed answers:** `TextMatch` ignores case and punctuation; digits equal words («3» = «tres», «1» = uno/un/una; `SpanishNumbers`). A wrong answer is shown word by word.
- **Spoken answers:** see `SpeechMatch` in [speaking.md](speaking.md).
- **Read-aloud:** Spanish is read aloud wherever possible. An exercise sentence is spoken when it appears, and the right answer after the reply.

## Vocabulary tab
- `VocabScreen` lists saved words and phrases.

## Tests
`SessionBatchTest`, `SpacingTest`, `MemoryTest`, `TextMatchTest`.
