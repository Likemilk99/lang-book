# Home, daily plan, reminder, progress

## Home (`ui/HomeScreen.kt`)
- **StatsCard:** 🔥 streak and today's numbers.
- **TodayCard:** «План на сегодня».
- **Tiles:** Library and Grammar, opened as sections with «Назад».
- **CanDoCard:** «Могу по-испански N из 12» — scenarios with all steps done.
- **ReminderDialog:** on/off switch and a TimePicker dial.

## «План на сегодня» (`data/DailyPlan.kt`)
- **Steps** (`StepKind`):
  - REVIEW (goal 20 reviews);
  - LISTEN / read (goal 30 sentences);
  - SPEAK (goal 10);
  - GRAMMAR.
  Each step leads to a tab or a screen.
- **Speaking step:** the next unfinished scenario step (`scenario/{id}/{step}`), else a book role-play, else `phrasebook/A0`.
- **Grammar step** (`nextGrammar`): due grammar reviews first, then the first unstarted topic.
- **Input:** `PlanInput(today, due, book, grammar, grammarDoneToday, scenario)` is built by `load()`. `currentBook()` refreshes the book list if it is still empty (it loads lazily).
- **Active day:** a day counts as active when `DailyStats.isActive()`.

## Reminder (`reminder/Reminder.kt`)
- **Schedule:** WorkManager periodic work, once a day. The first run is delayed by `delayUntil(minutes, now)`. Settings: `Prefs.reminderEnabled` (default on) and `Prefs.reminderMinutes` (default 20:00).
- **Rule:** a notification is shown only if the day has no activity. `message(today, due, streak)` returns null otherwise.
- **Notification channel:** `reminder`.

## Progress
- **`UserProgress`** (`data/UserProgress.kt`, `ProgressService.compute`) is the single aggregate of user statistics, computed from Room.
  - Level = 0.5 · vocabulary + 0.5 · grammar.
  - 80 % counts as covered.
- **`ProgressScreen`** («мой прогресс»), `BookStatsScreen` (per book), `MemoryCard` («Память словаря»).
- **Activity tracking:**
  - `UserData.recordChapterActivity`.
  - `daily_stats` / `chapter_activity` tables.

## Tests
`DailyPlanTest`.
