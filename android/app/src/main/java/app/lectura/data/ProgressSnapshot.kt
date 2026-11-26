package app.lectura.data

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Snapshot of learning progress for the Mac (POST /progress, pipeline/learner.py). The Mac keeps it as a read-only
 * mirror and uses it to personalize «Живой разговор»: words to review, new frequent words, known words.
 * The phone stays the source of truth: the snapshot is sent whole each time and nothing comes back.
 * Field names are the contract with learner.clean_snapshot (checked by ProgressSnapshotTest).
 */
@Serializable
data class ProgressSnapshot(
    /** No default: a default value would be dropped by a Json without encodeDefaults. */
    val format: Int,
    val at: Long,
    val appVersion: Int,
    val words: List<WordStat>,
    /** Saved phrases (they are not words, only counted). */
    val phrases: Int,
    val grammar: List<GrammarStat>,
    val days: List<DayStat>,
) {
    companion object {
        const val FORMAT = 1
        const val DAYS = 60

        fun of(
            words: List<SavedWord>,
            grammar: List<GrammarProgress>,
            days: List<DailyStats>,
            now: Long,
            appVersion: Int,
        ): ProgressSnapshot = ProgressSnapshot(
            format = FORMAT,
            at = now,
            appVersion = appVersion,
            words = words.filter { !it.isPhrase }.map { w ->
                WordStat(
                    l = w.lemma.ifBlank { w.word }.trim().lowercase(),
                    ru = w.ru,
                    recall = (Memory.recall(w, now) * 100).roundToInt().coerceIn(0, 100),
                    stage = w.stage,
                    reps = w.reps,
                    lapses = w.lapses,
                    interval = w.intervalDays,
                    seen = w.lastReviewed,
                    added = w.createdAt,
                )
            }.distinctBy { it.l },
            phrases = words.count { it.isPhrase },
            grammar = grammar.map { GrammarStat(it.topicId, it.level, it.stage, it.bestPct, it.lastPct, it.sessions) },
            days = days.sortedBy { it.day }.takeLast(DAYS).map {
                DayStat(it.day, it.reviews, it.correct, it.speaking, it.newWords, it.practice, it.memoryPct)
            },
        )

        suspend fun build(db: AppDatabase, now: Long = System.currentTimeMillis()): ProgressSnapshot =
            of(db.words().allOnce(), db.grammar().allOnce(), db.stats().allOnce(), now, AppVersion.code)
    }
}

@Serializable
data class WordStat(
    /** Dictionary form, lowercase (the card key without the phrase/old-card suffixes). */
    val l: String,
    val ru: String,
    /** Recall estimate now, % ([Memory.recall]). */
    val recall: Int,
    val stage: Int,
    val reps: Int,
    val lapses: Int,
    val interval: Int,
    /** Last review, ms (0 — never). */
    val seen: Long,
    val added: Long,
)

@Serializable
data class GrammarStat(val id: String, val level: String, val stage: Int, val best: Int, val last: Int, val sessions: Int)

@Serializable
data class DayStat(
    val day: String,
    val reviews: Int,
    val correct: Int,
    val speaking: Int,
    val newWords: Int,
    val practice: Int,
    val memoryPct: Int,
)
