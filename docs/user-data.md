# User data (must survive updates)

## Writes
- **Write user data only through `UserData`** (`data/UserData.kt`). It stamps `appVersion` on the record and updates `meta.lastSaveVersion/lastSaveAt`.
- **Main entry points:**
  - `markKnown`, `addPhrases`, `recordPractice`, `recordFreeExercise`, `recordChapterActivity`, `recordScenarioStep`;
  - `setAbout`, `addInbox`, `deleteInbox`, `markInboxDone`.
- **Data steps between app versions** go in `UserData.onAppStart()` (`if (from < X)`).

## Room
- **Version: v8.** Tables: words, progress, meta, daily_stats, chapter_activity, grammar_progress, word_images, scenario_progress, inbox.

| Version | Change | Backup |
|---|---|---|
| v5 | `word_images` — card picture cache | not in the backup |
| v6 | `words.ref` — phrasebook/scenario/personal phrase id | `WordDto.ref` |
| v7 | `scenario_progress` — best % per scenario step | `Backup.scenarios` |
| v8 | `inbox` — «Мои фразы» entries | `Backup.inbox` (restored as pending; `personal.json` itself is not backed up) |

- **Schema changes:** bump `version` and add an `AutoMigration`/`Migration`. New columns get a `defaultValue`.
- **Never use `fallbackToDestructiveMigration`.** Without a migration it is better to crash than to erase progress.
- **Committed schemas:** `android/app/schemas/*.json` is committed. CI fails if the schema doesn't match ("Check Room schemas are committed").

## Backup
- **Where:** `DataDialog` (backup/restore), opened from Home and the Library.
- **Format:** `Backup`, `FORMAT`. New fields only with defaults; an incompatible change means `FORMAT + 1`.

## Files outside Room
- `files/books/` — book JSON.
- `files/audio/<id>/` — narrator packs.
- `filesDir/word_images/` — picture cache.
- `files/personal/` — `personal.json`, `inbox.json`, `audio/`.
- Shared preferences `prefs` (`data/Prefs.kt`): reader font, review settings, reminder, Mac code and address, etc.

## Testing an upgrade
1. Install the previous release APK on the emulator (AVD `Pixel_8_Pro`).
2. Create data.
3. Install the new one with `adb install -r`.
4. Open «Данные».
