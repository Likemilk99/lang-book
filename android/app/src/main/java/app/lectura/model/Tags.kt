package app.lectura.model

/**
 * Справочник тегов книг. Небольшой и сгруппированный, чтобы фильтр оставался удобным.
 * Тот же список (id) — в pipeline/prepare_book.py (TAGS). Добавлять теги можно, менять id — нет.
 */
object Tags {
    data class Tag(val id: String, val label: String)
    data class Group(val title: String, val tags: List<Tag>)

    val groups = listOf(
        Group(
            "Ситуации",
            listOf(
                Tag("cafe", "Кафе и ресторан"),
                Tag("shopping", "Магазин и рынок"),
                Tag("transport", "Транспорт и город"),
                Tag("travel", "Путешествия и отель"),
                Tag("airport", "Аэропорт и вокзал"),
                Tag("health", "Врач и здоровье"),
                Tag("work", "Работа"),
                Tag("study", "Учёба"),
                Tag("home", "Дом и жильё"),
                Tag("money", "Банк и деньги"),
                Tag("officials", "Полиция и документы"),
                Tag("phone", "Телефон и онлайн"),
            ),
        ),
        Group(
            "Общение",
            listOf(
                Tag("meeting", "Знакомство"),
                Tag("plans", "Приглашения и планы"),
                Tag("requests", "Просьбы и вопросы"),
                Tag("opinions", "Мнения и чувства"),
                Tag("family", "Семья и друзья"),
                Tag("smalltalk", "Болтовня и погода"),
                Tag("problems", "Проблемы и жалобы"),
            ),
        ),
        Group(
            "Жанр",
            listOf(
                Tag("everyday", "Повседневность"),
                Tag("adventure", "Приключения"),
                Tag("mystery", "Детектив и тайна"),
                Tag("romance", "Романтика"),
                Tag("humor", "Юмор"),
                Tag("classic", "Классика"),
                Tag("nonfiction", "Нон-фикшн"),
            ),
        ),
    )

    private val byId = groups.flatMap { it.tags }.associateBy { it.id }

    fun label(id: String): String = byId[id]?.label ?: id
    fun groupOf(id: String): Group? = groups.firstOrNull { g -> g.tags.any { it.id == id } }

    val levels = listOf("A0", "A1", "A2", "B1", "B2")
}
