package app.lectura.images

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Скачивает картинку в [dir] (filesDir/word_images — не cacheDir, чтобы система её не чистила).
 * Только Content-Type «image/…», не больше [maxBytes]; пишет во временный файл и переименовывает — битых файлов не остаётся.
 */
class ImageDownloader(
    private val client: OkHttpClient,
    val dir: File,
    private val maxBytes: Long = 5L * 1024 * 1024,
) {
    suspend fun save(url: String, fileName: String): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("картинка: HTTP ${r.code}")
                val type = r.header("Content-Type").orEmpty()
                if (!type.startsWith("image/")) throw IOException("не картинка: $type")
                if (r.body.contentLength() > maxBytes) throw IOException("картинка больше 5 МБ")
                r.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("картинка больше 5 МБ")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("не удалось сохранить картинку")
            target
        } finally {
            tmp.delete()
        }
    }
}
