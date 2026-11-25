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
import app.lectura.data.LexiconRepository
import app.lectura.data.LiveChat
import app.lectura.data.MIGRATIONS
import app.lectura.data.MacLink
import app.lectura.data.PersonalRepository
import app.lectura.data.PhrasebookRepository
import app.lectura.data.ScenarioRepository
import app.lectura.data.Prefs
import app.lectura.data.UserData
import app.lectura.images.ImageDownloader
import app.lectura.images.PixabayApi
import app.lectura.images.WordImageRepository
import app.lectura.playback.PlaybackService
import app.lectura.playback.LessonPlayer
import app.lectura.playback.Player
import app.lectura.reminder.Reminder
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
    /** Словарные переводы начальных форм (для карточек слов). */
    lateinit var lexicon: LexiconRepository
    lateinit var phrasebook: PhrasebookRepository
    lateinit var scenarios: ScenarioRepository
    lateinit var personal: PersonalRepository
    /** «Мои фразы» по Wi-Fi: сервер pipeline/server.py на Mac. */
    lateinit var mac: MacLink
    /** «Живой разговор» с персонажем на Mac. */
    lateinit var chat: LiveChat
    lateinit var lessons: LessonPlayer
    lateinit var player: Player
    lateinit var wordImages: WordImageRepository

    /** Для коротких записей, которые должны завершиться, даже если экран уже закрыт. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // Никакого fallbackToDestructiveMigration: при отсутствии миграции лучше упасть, чем стереть прогресс.
        db = Room.databaseBuilder(this, AppDatabase::class.java, "lectura.db")
            .addMigrations(*MIGRATIONS)
            .build()
        curriculum = CurriculumRepository(this)
        lexicon = LexiconRepository(this, curriculum)
        userData = UserData(this, db, lexicon)
        phrasebook = PhrasebookRepository(this)
        scenarios = ScenarioRepository(this)
        personal = PersonalRepository(this, db, userData, phrasebook)
        mac = MacLink(this, prefs, db, personal, applicationScope)
        chat = LiveChat(this, mac, prefs, db, userData)
        applicationScope.launch { runCatching { personal.sync() }.onFailure { Log.w("LecturaApp", "personal sync", it) } }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { userData.onAppStart() }.onFailure { Log.e("LecturaApp", "data upgrade failed", it) }
        }
        books = BookRepository(this, prefs)
        bookAudio = BookAudio(this)
        player = Player(this)
        lessons = LessonPlayer(this)
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
        Reminder.createChannel(this)
        runCatching { Reminder.schedule(this) }.onFailure { Log.w("LecturaApp", "cannot schedule reminder", it) }
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
