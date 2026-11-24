# Books and the reader

## Book format (`model/Book.kt`)
- **Hierarchy:**
  - `Book{characters, audio}`
  - → `Chapter{phrases, questions, replies, grammar, glossary}`
  - → `Paragraph`
  - → `Sentence{sp}`
  - → `Token{t, l, ru, g, r}`
- **Token fields:** surface form, lemma, Russian translation in context, grammar note, rare/old flag.
- `Sentence.sp` is the speaker (a character id). `Sentence.audio` is unused (reserved).
- **Compatibility:** changes must stay backward compatible: `ignoreUnknownKeys`, new fields optional.
- **Tags:** the catalog is `TAGS` in the pipeline = `model/Tags.kt` in the app (3 groups). Tag ids are never changed.

## Making books (`pipeline/prepare_book.py`)
- **Modes:**
  - `--topic "..."` writes an original story.
  - `--source file.txt|epub` adapts an existing text.
  - `--enrich book.json` only refreshes the markup.
  - `--tags book.json` only re-tags.
- **Options:** levels A0–B2. `--style dialog` is the default (conversation focus). The model is chosen with `--model`.
- **Output and caching:** results are cached in `books/.work/`. `--push` copies the book to the phone over adb; otherwise use «Импорт» in the app.
- **The enrich step adds** characters, the speaker of each line (`sp`), phrases, comprehension questions, dialogue replies and tags.
- **`--original`:** public-domain originals with no text changes (authors who died before 1945, published before 1930; sources in `pipeline/sources/README.md`). Words are flagged rare/old (`Token.r`), each chapter gets a glossary, and versions of one work are linked through `workId`.
- **`pipeline/lemmas.py`:** dictionary translations of book lemmas that are not in the curriculum vocab → `assets/lemmas.json` (used for word cards: «mano — рука», not «руку»). Run it after adding books; only new lemmas are translated (cache in `books/.work/lemmas/`).
- **`pipeline/library.py`:** generates an adapted library per level that covers ≥ 90 % of the level's vocabulary and every grammar topic (`Chapter.grammar`). Coverage reports are in `pipeline/coverage_*.md`.
  - **`--focus` (supplement books):** targets only the words from `pipeline/vocab_fixes.json` ([grammar-curriculum.md](grammar-curriculum.md)). Levels A0 (words marked `a0`, A1 vocabulary and the first 10 A1 grammar topics), A1 and A2. Modern original stories in Spain, mostly dialogue (`FOCUS_SEEDS`). At least two books per level, so the second one repeats the words. Books are numbered after the existing ones (`a1-11-…`, `a0-01-…`); cache `pipeline/.cache/library/<L>-focus/`; report `pipeline/coverage_<L>_focus.md`.
  - `python3 pipeline/library.py --focus --level A0 --jobs 3`
- **After a book's text changes,** re-voice it (see [narrator-audio.md](narrator-audio.md)) and release the app.

## On the phone
- **Bundled books:** `assets/books/` refresh on the phone when their hash changes (`BookRepository.installBundled`).
- **Storage:** books live as JSON files in `Android/data/app.lectura/files/books/`.
- **Library screen:** level and topic filters, book progress. `BookStatsScreen` shows a per-chapter table.
- **Book stats:** `BookStats.kt` computes book vocabulary coverage by lemma and chapter exercises (`ChapterStat.DONE_PCT`).

## Reader (`ui/ReaderScreen.kt`)
- **Tapping a word** opens a bottom sheet with its translation in context, dictionary form and grammar. From there you can hear it and save it.
  - «Учить» saves the card of the dictionary form (see «One card per word» in [review.md](review.md)). On another form of a saved word the button is «Добавить пример»; on a sentence already in the card it is «В словаре».
  - The button never deletes: «Убрать» under it removes the card.
- **«Выделить фразу»** saves a multi-word phrase.
- **Chapter extras:** chapter phrasebook; chapter glossary with «Учить все».
- **«Ещё» menu:** «Скачать / Обновить / Удалить озвучку».
- **Player:**
  - `playback/Player.kt` is the single source of truth for the current position.
  - It reads sentence by sentence: the narrator file via MediaPlayer when the pack is downloaded, otherwise TTS. It saves progress.
  - `PlaybackService` (foreground + MediaSession) keeps reading with the screen off and responds to headset buttons.
  - 🎙 in the player bar while the narrator reads.
- **Multi-word expressions** (~1000 from the curriculum vocab; `data/Expressions.kt`, `ExpressionIndex`):
  - Tapping a word shows the whole expression with its translation.
  - Verb + preposition counts only before an infinitive. Accents matter.
  - Saved phrases and expressions are highlighted as a whole (`savedRanges`, `phraseIndex`).
  - The expression card shows each word separately (curriculum level, other expressions with it).
- **Per chapter:** comprehension questions (`QuestionsScreen`), shadowing and role-play (`ShadowingScreen`, see [speaking.md](speaking.md)).

## Tests
- `BookFormatTest` checks the built-in books.
- `ExpressionsTest` checks expression matching.
