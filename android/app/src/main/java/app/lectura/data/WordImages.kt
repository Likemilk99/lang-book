package app.lectura.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Картинка к слову карточки (Pixabay). Сам файл — в filesDir/word_images/, здесь — откуда он и что
 * ещё можно показать вместо него («Другая картинка»). Это кэш, а не данные пользователя: в резервную
 * копию не входит и при потере просто найдётся заново (кроме выбора «Убрать картинку»).
 */
@Entity(tableName = "word_images")
data class WordImage(
    /** Нормализованное слово (images/WordImageRepository.normalize). */
    @PrimaryKey val word: String,
    /** found / not_found / manual / disabled */
    val status: String,
    val localPath: String? = null,
    val pixabayId: Long? = null,
    val pageUrl: String? = null,
    val author: String? = null,
    /** Все hits последнего поиска (JSON) — для «Другая картинка». */
    val candidatesJson: String? = null,
    /** Когда получены кандидаты: их ссылки на картинки живут ~24 ч. */
    val candidatesFetchedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val candidateIndex: Int = 0,
    val updatedAt: Long,
) {
    companion object {
        const val FOUND = "found"
        const val NOT_FOUND = "not_found"
        const val MANUAL = "manual"
        const val DISABLED = "disabled"
    }
}

@Dao
interface WordImageDao {
    @Query("SELECT * FROM word_images WHERE word = :word")
    suspend fun get(word: String): WordImage?

    @Upsert
    suspend fun upsert(image: WordImage)
}
