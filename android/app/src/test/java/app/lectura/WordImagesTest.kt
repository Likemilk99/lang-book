package app.lectura

import app.lectura.data.WordImage
import app.lectura.data.WordImageDao
import app.lectura.images.ImageDownloader
import app.lectura.images.PixabayApi
import app.lectura.images.PixabayKeyException
import app.lectura.images.RateLimiter
import app.lectura.images.WordImageRepository
import app.lectura.images.WordImageState
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class WordNormalizeTest {
    @Test
    fun articlesCaseAndPunctuation() {
        assertEquals("manzana", WordImageRepository.normalize("  La Manzana "))
        assertEquals("perros", WordImageRepository.normalize("los perros"))
        assertEquals("casa", WordImageRepository.normalize("una casa"))
        assertEquals("hola", WordImageRepository.normalize("¡Hola!"))
        assertEquals("helado", WordImageRepository.normalize("helado")) // «el» только отдельным словом
        assertEquals("elefante", WordImageRepository.normalize("el elefante"))
    }

    @Test
    fun diacriticsAreKept() {
        assertEquals("año", WordImageRepository.normalize("Año"))
        assertEquals("ano", WordImageRepository.normalize("ano"))
        assertEquals("pingüino", WordImageRepository.normalize("el Pingüino"))
        assertEquals("ano", WordImageRepository.slug("año")) // имя файла — без диакритики, ключ в Room — с ней
    }

    @Test
    fun translationTakesFirstMeaning() {
        assertEquals("есть", WordImageRepository.cleanTranslation("есть, кушать (разг.)"))
        assertEquals("яблоко", WordImageRepository.cleanTranslation("яблоко"))
    }
}

class RateLimiterTest {
    @Test
    fun waitsWhenWindowIsFull() = runBlocking {
        var t = 0L
        val sleeps = mutableListOf<Long>()
        val limiter = RateLimiter(3, now = { t }, sleep = { sleeps += it; t += it })
        repeat(3) { limiter.acquire() }
        assertTrue(sleeps.isEmpty())
        limiter.acquire()
        assertEquals(1, sleeps.size)
        assertTrue(t >= 60_000)
    }
}

private fun json(totalHits: Int, vararg ids: Long) = MockResponse.Builder()
    .code(200)
    .addHeader("Content-Type", "application/json")
    .body(
        """{"total":$totalHits,"totalHits":$totalHits,"hits":[""" + ids.joinToString(",") {
            """{"id":$it,"webformatURL":"https://cdn.example/$it.jpg","pageURL":"https://pixabay.com/photos/$it/","user":"author$it","tags":"t"}"""
        } + "]}",
    )

class PixabayApiTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.close()

    private fun api(key: String = "secret") = PixabayApi(
        OkHttpClient(), key, server.url("/api/"),
        limiter = RateLimiter(1000), sleep = { sleeps += it },
    )

    @Test
    fun successParsesHitsAndSendsParams() = runBlocking {
        server.enqueue(json(2, 1, 2).build())
        val r = api().search("manzana")
        assertEquals(2, r.totalHits)
        assertEquals(listOf(1L, 2L), r.hits.map { it.id })
        val url = server.takeRequest().url
        assertEquals("secret", url.queryParameter("key"))
        assertEquals("manzana", url.queryParameter("q"))
        assertEquals("es", url.queryParameter("lang"))
        assertEquals("photo", url.queryParameter("image_type"))
        assertEquals("true", url.queryParameter("safesearch"))
        assertEquals("10", url.queryParameter("per_page"))
    }

    @Test
    fun emptyResult() = runBlocking {
        server.enqueue(json(0).build())
        assertTrue(api().search("xyzzy").hits.isEmpty())
    }

    @Test
    fun badKeyIsNotRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("[ERROR 400] Invalid or missing API key").build())
        try {
            api().search("manzana")
            fail("ждали PixabayKeyException")
        } catch (_: PixabayKeyException) {
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun blankKeyMakesNoRequest() = runBlocking {
        try {
            api(key = "").search("manzana")
            fail("ждали PixabayKeyException")
        } catch (e: PixabayKeyException) {
            assertEquals(PixabayApi.NO_KEY, e.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rateLimitIsRetriedAfterReset() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("X-RateLimit-Reset", "2").build())
        server.enqueue(json(1, 7).build())
        assertEquals(7L, api().search("manzana").hits.single().id)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun serverErrorsGiveUpAfterThreeRetries() = runBlocking {
        repeat(4) { server.enqueue(MockResponse.Builder().code(503).build()) }
        try {
            api().search("manzana")
            fail("ждали IOException")
        } catch (_: IOException) {
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun keyIsRedactedInLogs() {
        val url = server.url("/api/").newBuilder().addQueryParameter("key", "secret").addQueryParameter("q", "x").build()
        val s = PixabayApi.redact(url)
        assertFalse(s.contains("secret"))
        assertTrue(s.contains("key=***"))
    }
}

/** DAO в памяти — проверяем логику репозитория без Room. */
private class FakeDao : WordImageDao {
    val rows = HashMap<String, WordImage>()
    override suspend fun get(word: String) = rows[word]
    override suspend fun upsert(image: WordImage) {
        rows[image.word] = image
    }
}

class WordImageRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val dao = FakeDao()
    private var now = 1_000_000L

    /** Ответы API по (q, image_type); картинки — /img/<id>.jpg. */
    private val search = HashMap<Pair<String, String>, IntArray>()
    private val apiCalls get() = server.requestCount - imageCalls
    private var imageCalls = 0

    @Before fun setUp() {
        dir = Files.createTempDirectory("img").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                if (url.encodedPath.startsWith("/img/")) {
                    imageCalls++
                    return MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg")
                        .body(Buffer().write(ByteArray(100) { 1 })).build()
                }
                val ids = search[url.queryParameter("q")!! to url.queryParameter("image_type")!!] ?: IntArray(0)
                val base = server.url("/").toString()
                val body = """{"totalHits":${ids.size},"hits":[""" + ids.joinToString(",") {
                    """{"id":$it,"webformatURL":"${base}img/$it.jpg","pageURL":"https://pixabay.com/p/$it/","user":"u$it"}"""
                } + "]}"
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun repo(key: String = "k") = WordImageRepository(
        dao,
        PixabayApi(OkHttpClient(), key, server.url("/api/"), limiter = RateLimiter(1000), sleep = {}),
        ImageDownloader(OkHttpClient(), dir),
        now = { now },
    )

    @Test
    fun manzanaGetsFirstPhotoAndIsStored() = runBlocking {
        search["manzana" to "photo"] = intArrayOf(11, 12, 13)
        val s = repo().resolve("la manzana", "яблоко") as WordImageState.Ready
        assertTrue(s.file.isFile)
        assertEquals("u11", s.author)
        assertEquals(dir, s.file.parentFile)
        val row = dao.rows["manzana"]!!
        assertEquals(WordImage.FOUND, row.status)
        assertEquals(11L, row.pixabayId)
    }

    @Test
    fun secondRequestDoesNotUseNetwork() = runBlocking {
        search["manzana" to "photo"] = intArrayOf(11)
        val r = repo()
        r.resolve("manzana")
        val calls = server.requestCount
        assertTrue(r.resolve("manzana") is WordImageState.Ready)
        assertEquals(calls, server.requestCount)
    }

    @Test
    fun fallbackChainPhotoIllustrationTranslationNotFound() = runBlocking {
        assertEquals(WordImageState.NotFound, repo().resolve("xyzzy", "ничто"))
        assertEquals(3, apiCalls) // photo → illustration → перевод
        assertEquals(WordImage.NOT_FOUND, dao.rows["xyzzy"]!!.status)
        // «не найдено» не ищем повторно 7 дней
        assertEquals(WordImageState.NotFound, repo().resolve("xyzzy", "ничто"))
        assertEquals(3, apiCalls)
    }

    @Test
    fun illustrationAndTranslationFallbacks() = runBlocking {
        search["dragón" to "illustration"] = intArrayOf(21)
        assertTrue(repo().resolve("dragón") is WordImageState.Ready)
        assertEquals(21L, dao.rows["dragón"]!!.pixabayId)
        search["кот" to "photo"] = intArrayOf(31)
        assertTrue(repo().resolve("gatito", "кот") is WordImageState.Ready)
        assertEquals(31L, dao.rows["gatito"]!!.pixabayId)
    }

    @Test
    fun missingFileIsDownloadedAgain() = runBlocking {
        search["manzana" to "photo"] = intArrayOf(11)
        val r = repo()
        val first = r.resolve("manzana") as WordImageState.Ready
        first.file.delete()
        val api = apiCalls
        val again = r.resolve("manzana") as WordImageState.Ready
        assertTrue(again.file.isFile)
        assertEquals(api, apiCalls) // кандидаты свежие — только скачивание, без поиска
    }

    @Test
    fun otherPictureSwitchesCandidateAndDeletesOldFile() = runBlocking {
        search["manzana" to "photo"] = intArrayOf(11, 12)
        val r = repo()
        val first = r.resolve("manzana") as WordImageState.Ready
        val second = r.next("manzana") as WordImageState.Ready
        assertFalse(first.file.exists())
        assertTrue(second.file.isFile)
        assertEquals(12L, dao.rows["manzana"]!!.pixabayId)
        assertEquals(1, dao.rows["manzana"]!!.candidateIndex)
    }

    @Test
    fun disableHidesAndRemovesFile() = runBlocking {
        search["manzana" to "photo"] = intArrayOf(11)
        val r = repo()
        val first = r.resolve("manzana") as WordImageState.Ready
        r.disable("manzana")
        assertFalse(first.file.exists())
        val calls = server.requestCount
        assertEquals(WordImageState.Hidden, r.resolve("manzana"))
        assertEquals(calls, server.requestCount)
    }

    @Test
    fun noKeyGivesMessageWithoutNetwork() = runBlocking {
        val s = repo(key = "").resolve("manzana")
        assertEquals(WordImageState.Error(PixabayApi.NO_KEY), s)
        assertEquals(0, server.requestCount)
    }
}
