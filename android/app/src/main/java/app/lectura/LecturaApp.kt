package app.lectura

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.room.Room
import app.lectura.data.AppDatabase
import app.lectura.data.BookAudio
import app.lectura.data.BookRepository
import app.lectura.data.CurriculumRepository
import app.lectura.data.MIGRATIONS
import app.lectura.data.Prefs
import app.lectura.data.UserData
import app.lectura.images.ImageDownloader
import app.lectura.images.PixabayApi
import app.lectura.images.WordImageRepository
import app.lectura.playback.PlaybackService
import app.lectura.playback.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LecturaApp : Application() {
    lateinit var prefs: Prefs
    lateinit var db: AppDatabase
    lateinit var books: BookRepository
    lateinit var bookAudio: BookAudio
    lateinit var userData: UserData
    lateinit var curriculum: CurriculumRepository
    lateinit var player: Player
    lateinit var wordImages: WordImageRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // Никакого fallbackToDestructiveMigration: при отсутствии миграции лучше упасть, чем стереть прогресс.
        db = Room.databaseBuilder(this, AppDatabase::class.java, "lectura.db")
            .addMigrations(*MIGRATIONS)
            .build()
        userData = UserData(this, db)
        curriculum = CurriculumRepository(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { userData.onAppStart() }.onFailure { Log.e("LecturaApp", "data upgrade failed", it) }
        }
        books = BookRepository(this, prefs)
        bookAudio = BookAudio(this)
        player = Player(this)
        val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        wordImages = WordImageRepository(
            db.wordImages(),
            PixabayApi(http, BuildConfig.PIXABAY_API_KEY),
            ImageDownloader(http, java.io.File(filesDir, "word_images")),
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "Чтение вслух", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun startPlaybackService() {
        try {
            startForegroundService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
            // Например, запуск из фона запрещён системой — чтение всё равно идёт, просто без уведомления.
            Log.w("LecturaApp", "cannot start playback service", e)
        }
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        lateinit var instance: LecturaApp
            private set
    }
}
