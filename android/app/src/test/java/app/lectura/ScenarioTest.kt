package app.lectura

import app.lectura.data.CurrentBook
import app.lectura.data.DailyPlan
import app.lectura.data.DailyStats
import app.lectura.data.PlanInput
import app.lectura.data.ScenarioNext
import app.lectura.data.ScenarioProgress
import app.lectura.data.ScenarioRepository
import app.lectura.data.ScenarioState
import app.lectura.data.ScenarioStep
import app.lectura.data.StepKind
import app.lectura.model.AnswerPrompt
import app.lectura.model.Phrase
import app.lectura.model.Phrasebook
import app.lectura.model.Scenario
import app.lectura.model.ScenarioDialog
import app.lectura.model.ScenarioFile
import app.lectura.model.ScenarioLine
import app.lectura.model.ScenarioRole
import app.lectura.speech.SpeechMatch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Сценарии: файл из assets (формат, озвучка), шаги и «могу», выбор сценария для плана, свободный ответ. */
class ScenarioTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val assets = File("src/main/assets")

    private fun scenarios(): List<Scenario> {
        val f = File(assets, ScenarioRepository.FILE)
        assertTrue("нет ${f.absolutePath}", f.exists())
        return json.decodeFromString(ScenarioFile.serializer(), f.readText()).scenarios
    }

    @Test
    fun scenariosAreConsistent() {
        val errors = mutableListOf<String>()
        val list = scenarios()
        if (list.size < 10) errors += "мало сценариев: ${list.size}"
        if (list.map { it.id }.toSet().size != list.size) errors += "повтор id сценария"
        for (s in list) {
            if (s.level !in setOf("A0", "A1")) errors += "${s.id}: уровень ${s.level}"
            if (s.phrases.size < 6) errors += "${s.id}: мало фраз"
            if (s.dialogs.size != 2) errors += "${s.id}: диалогов ${s.dialogs.size}"
            s.dialogs.forEach { d ->
                if (d.lines.count { it.isMe } < 3) errors += "${s.id}/${d.id}: мало реплик ученика"
                if (d.lines.none { !it.isMe }) errors += "${s.id}/${d.id}: нет собеседника"
                d.lines.forEach { if (it.who !in setOf(ScenarioLine.ME, ScenarioLine.THEM) || it.es.isBlank() || it.ru.isBlank()) errors += "${s.id}/${d.id}: плохая реплика $it" }
            }
            s.understand.forEach { if (it.answer !in it.options.indices) errors += "${s.id}: неверный ответ в «понять»" }
            s.prompts.forEach { if (it.accepted.size < 2) errors += "${s.id}: мало вариантов ответа на «${it.es}»" }
            if (!s.canDo.startsWith("Могу")) errors += "${s.id}: canDo «${s.canDo}»"
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    /** Ключевые фразы с тем же текстом, что в разговорнике, — та же фраза (один id, одна карточка в повторении). */
    @Test
    fun scenarioPhrasesReusePhrasebookIds() {
        val pb = File(assets, "phrasebook").listFiles { f -> f.name.startsWith("phrasebook_") }.orEmpty()
            .flatMap { json.decodeFromString(Phrasebook.serializer(), it.readText()).themes.flatMap { t -> t.phrases } }
        val byText = pb.associateBy { it.es.lowercase() }
        val ids = pb.map { it.id }.toMutableSet()
        for (s in scenarios()) for (p in s.phrases) {
            byText[p.es.lowercase()]?.let { assertEquals("${s.id}: «${p.es}»", it.id, p.id) }
            if (p.id.startsWith("sc-")) assertTrue("повтор id ${p.id}", ids.add(p.id))
        }
    }

    @Test
    fun everyLineQuestionAndPhraseIsVoiced() {
        val dir = File(assets, ScenarioRepository.AUDIO_DIR)
        val pbAudio = File(assets, "phrasebook/audio")
        val missing = mutableListOf<String>()
        for (s in scenarios()) {
            s.dialogs.forEach { d -> d.lines.indices.forEach { i -> ScenarioRepository.lineClip(s.id, d.id, i).let { if (!File(dir, "$it.m4a").exists()) missing += it } } }
            s.understand.indices.forEach { i -> ScenarioRepository.understandClip(s.id, i).let { if (!File(dir, "$it.m4a").exists()) missing += it } }
            s.prompts.indices.forEach { i ->
                ScenarioRepository.promptClip(s.id, i).let { if (!File(dir, "$it.m4a").exists()) missing += it }
                ScenarioRepository.answerClip(s.id, i).let { if (!File(dir, "$it.m4a").exists()) missing += it }
            }
            s.phrases.forEach { if (!File(pbAudio, "${it.id}.m4a").exists()) missing += it.id }
        }
        if (missing.isNotEmpty()) fail("нет озвучки (${missing.size}): ${missing.take(20)}")
    }

    // ------------------------------------------------------------ логика

    private fun sc(id: String, prompts: Boolean = true) = Scenario(
        id = id, level = "A0", title = id, situation = "", canDo = "Могу $id", them = ScenarioRole("Официант", "m"),
        phrases = listOf(Phrase("p", "Hola.", "Привет.")),
        dialogs = listOf(
            ScenarioDialog("a", "A", listOf(ScenarioLine("me", "Hola.", "Привет."))),
            ScenarioDialog("b", "B", listOf(ScenarioLine("me", "Hola.", "Привет."))),
        ),
        prompts = if (prompts) listOf(AnswerPrompt("¿Qué?", "Что?", accepted = listOf("Un café.", "Una caña."))) else emptyList(),
    )

    private fun p(id: String, step: ScenarioStep, best: Int) = ScenarioProgress(id, step.key, best, 1, 0)

    @Test
    fun stepsAndCanDo() {
        val s = sc("cafe", prompts = false)
        val steps = ScenarioState.stepsOf(s)
        assertFalse("нет вопросов — нет шага «ответить»", ScenarioStep.ANSWER in steps)
        assertFalse("нет «понять» — нет шага", ScenarioStep.UNDERSTAND in steps)
        val half = ScenarioState.of(s, listOf(p("cafe", ScenarioStep.PHRASES, 100), p("cafe", ScenarioStep.LISTEN, 40)))
        assertEquals(1, half.doneCount)
        assertEquals(ScenarioStep.LISTEN, half.next)
        assertFalse(half.canDo)
        val all = ScenarioState.of(s, steps.map { p("cafe", it, 60) } + p("other", ScenarioStep.PHRASES, 0))
        assertTrue(all.canDo)
        assertNull(all.next)
    }

    @Test
    fun planSpeaksWithNextScenario() {
        val a = sc("cafe")
        val b = sc("pharmacy")
        val doneA = ScenarioState.stepsOf(a).map { p("cafe", it, 100) }
        assertEquals(ScenarioNext("pharmacy", "pharmacy", ScenarioStep.PHRASES), DailyPlan.nextScenario(listOf(a, b), doneA))
        assertNull(DailyPlan.nextScenario(listOf(a), doneA))

        val book = CurrentBook("b", "Libro", 0, "", 0, "", true)
        val input = PlanInput(DailyStats("d"), 0, book, null, false, ScenarioNext("cafe", "В кафе", ScenarioStep.ROLE_ME))
        val speak = DailyPlan.build(input).first { it.kind == StepKind.SPEAK }
        assertEquals("scenario/cafe/role_me", speak.route)
        assertTrue(speak.title.contains("В кафе"))
        // Без сценария — по-прежнему ролевая игра по книге.
        assertEquals("speak/b/0/role", DailyPlan.build(input.copy(scenario = null)).first { it.kind == StepKind.SPEAK }.route)
    }

    @Test
    fun freeAnswerMatchesAnyAccepted() {
        val accepted = listOf("Me duele la cabeza.", "Tengo fiebre.", "Me duele la garganta.")
        assertTrue(SpeechMatch.best(accepted, listOf("tengo fiebre desde ayer")).passed)
        assertTrue(SpeechMatch.best(accepted, listOf("me duele la garganta")).passed)
        assertFalse(SpeechMatch.best(accepted, listOf("quiero un café")).passed)
    }

    @Test
    fun ownNameInsteadOfTheExampleName() {
        val accepted = listOf("Me llamo Iván.", "Soy Iván.", "Iván. ¿Y tú?")
        assertTrue(SpeechMatch.best(accepted, listOf("me llamo Sergei")).passed)
        assertTrue(SpeechMatch.best(listOf("Quiero ir a Toledo."), listOf("quiero ir a Sevilla")).passed)
        assertEquals("Me llamo", SpeechMatch.withoutNames("Me llamo Iván"))
        assertEquals("Hola. ¿Eres de aquí?", SpeechMatch.withoutNames("Hola. ¿Eres de aquí?"))
        assertFalse(SpeechMatch.best(accepted, listOf("tengo hambre")).passed)
    }
}
