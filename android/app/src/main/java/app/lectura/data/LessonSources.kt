package app.lectura.data

import app.lectura.LecturaApp
import app.lectura.model.Phrase
import app.lectura.model.ScenarioLine
import app.lectura.playback.LessonItem

/** Откуда собрать аудиоурок. */
data class LessonSource(val id: String, val group: String, val title: String, val items: List<LessonItem>)

/**
 * Аудиоуроки из того, что уже есть: фразы к повторению на сегодня, «Мои фразы», темы разговорников,
 * сценарии (ключевые фразы + ваши реплики из разговора). Озвучка — диктор, если есть, иначе системный голос.
 */
object LessonSources {
    const val TODAY = "today"
    private const val MAX_TODAY = 25

    suspend fun all(app: LecturaApp = LecturaApp.instance, now: Long = System.currentTimeMillis()): List<LessonSource> {
        val pb = app.phrasebook
        val out = ArrayList<LessonSource>()

        // На сегодня: фразы и слова, которые пора повторять (сначала фразы — их учим говорить).
        pb.all()
        val due = app.db.words().due(now).sortedBy { if (it.isPhrase) 0 else 1 }.take(MAX_TODAY)
        out += LessonSource(TODAY, "Сегодня", "Повторение на сегодня", due.filter { it.ru.isNotBlank() }.map {
            LessonItem(it.word, it.ru, if (it.ref.isBlank()) null else pb.clipFor(it.ref, it.word))
        })

        val mine = app.personal.results.value.flatMap { it.phrases }
        out += LessonSource("mine", "Сегодня", "Мои фразы", mine.map { item(it) })

        for (s in app.scenarios.all()) {
            val lines = s.dialogs.firstOrNull()?.let { d ->
                d.lines.mapIndexedNotNull { i, l ->
                    if (l.who != ScenarioLine.ME) return@mapIndexedNotNull null
                    LessonItem(l.es, l.ru, app.scenarios.clip(ScenarioRepository.lineClip(s.id, d.id, i)))
                }
            }.orEmpty()
            val phrases = s.phrases.map { item(it) }
            val seen = HashSet<String>()
            out += LessonSource(
                "sc-${s.id}", "Сценарии ${s.level}", "${s.emoji} ${s.title}",
                (phrases + lines).filter { seen.add(it.es.lowercase()) },
            )
        }
        for (b in pb.all()) for (t in b.themes) {
            out += LessonSource("pb-${t.id}", "Разговорник ${b.level}", "${t.emoji} ${t.title}", t.phrases.map { item(it) })
        }
        return out.filter { it.items.isNotEmpty() }
    }

    private fun item(p: Phrase) = LessonItem(p.es, p.ru, LecturaApp.instance.phrasebook.clip(p.id))
}
