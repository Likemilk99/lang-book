package app.lectura.images

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class PixabayHit(
    val id: Long,
    val webformatURL: String,
    val largeImageURL: String = "",
    val pageURL: String = "",
    val user: String = "",
    val tags: String = "",
)

@Serializable
data class PixabayResponse(val totalHits: Int = 0, val hits: List<PixabayHit> = emptyList())

/** Ключ не задан или неверный (HTTP 400) — повторять бессмысленно. */
class PixabayKeyException(message: String) : IOException(message)

/** Не больше [maxPerMinute] запросов за скользящие 60 секунд (у Pixabay лимит 100 / 60 с на ключ). */
class RateLimiter(
    private val maxPerMinute: Int = 90,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val stamps = ArrayDeque<Long>()

    suspend fun acquire() = mutex.withLock {
        while (true) {
            val t = now()
            while (stamps.isNotEmpty() && t - stamps.first() >= WINDOW_MS) stamps.removeFirst()
            if (stamps.size < maxPerMinute) {
                stamps.addLast(t)
                return@withLock
            }
            sleep(WINDOW_MS - (t - stamps.first()) + 1)
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}

/**
 * Поиск картинок Pixabay (GET https://pixabay.com/api/). Ключ — только в query string, в логах маскируется.
 * 400 — [PixabayKeyException] без повторов; 429 — повтор после X-RateLimit-Reset или с экспоненциальной
 * паузой; нет сети / 5xx — до 3 повторов, потом IOException.
 */
class PixabayApi(
    private val client: OkHttpClient,
    private val key: String,
    private val baseUrl: HttpUrl = "https://pixabay.com/api/".toHttpUrl(),
    private val limiter: RateLimiter = RateLimiter(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    val hasKey: Boolean get() = key.isNotBlank()

    suspend fun search(
        query: String,
        lang: String = "es",
        imageType: String = "photo",
        perPage: Int = 10,
    ): PixabayResponse = withContext(Dispatchers.IO) {
        if (!hasKey) throw PixabayKeyException(NO_KEY)
        val url = baseUrl.newBuilder()
            .addQueryParameter("key", key)
            .addQueryParameter("q", query.take(100))
            .addQueryParameter("lang", lang)
            .addQueryParameter("image_type", imageType)
            .addQueryParameter("safesearch", "true")
            .addQueryParameter("orientation", "horizontal")
            .addQueryParameter("per_page", perPage.coerceIn(3, 200).toString())
            .build()
        var failures = 0
        var backoff = 1_000L
        while (true) {
            limiter.acquire()
            val attempt = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    when {
                        r.isSuccessful -> Result.Ok(json.decodeFromString<PixabayResponse>(r.body.string()))
                        r.code == 400 -> throw PixabayKeyException("Pixabay: неверный ключ (${r.body.string().take(80)})")
                        r.code == 429 -> Result.Retry(r.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000))
                        r.code >= 500 -> Result.Fail(IOException("Pixabay: HTTP ${r.code}"))
                        else -> throw IOException("Pixabay: HTTP ${r.code}")
                    }
                }
            }.getOrElse { e -> if (e is PixabayKeyException || e !is IOException) throw e else Result.Fail(e) }
            when (attempt) {
                is Result.Ok -> return@withContext attempt.value
                is Result.Retry -> {
                    // Лимит — не ошибка сети: ждём сброса окна, но не бесконечно.
                    if (++failures > MAX_RATE_RETRIES) throw IOException("Pixabay: превышен лимит запросов")
                    sleep(attempt.waitMs?.coerceIn(500, 60_000) ?: backoff)
                    backoff *= 2
                }
                is Result.Fail -> {
                    runCatching { Log.w(TAG, "search failed: ${redact(url)}: ${attempt.error.message}") } // в unit-тестах Log нет
                    if (++failures > MAX_RETRIES) throw attempt.error
                    sleep(backoff)
                    backoff *= 2
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private sealed interface Result {
        data class Ok(val value: PixabayResponse) : Result
        data class Retry(val waitMs: Long?) : Result
        data class Fail(val error: IOException) : Result
    }

    companion object {
        const val NO_KEY = "Не задан PIXABAY_API_KEY в local.properties"
        private const val TAG = "Pixabay"
        private const val MAX_RETRIES = 3
        private const val MAX_RATE_RETRIES = 5

        /** URL для логов: ключ заменён на ***. */
        fun redact(url: HttpUrl): String = url.newBuilder().setQueryParameter("key", "***").build().toString()
    }
}
