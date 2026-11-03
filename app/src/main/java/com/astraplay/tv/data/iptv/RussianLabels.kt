package com.astraplay.tv.data.iptv

import java.util.Locale

/** Display aliases only: URLs, EPG IDs and saved channel keys keep their original values. */
object RussianLabels {
    private val categories = mapOf(
        "animation" to "Мультфильмы", "family" to "Семейные", "kids" to "Детские",
        "children" to "Детские", "family kids" to "Семейные и детские",
        "family & kids" to "Семейные и детские", "movies" to "Фильмы", "movie" to "Фильмы",
        "series" to "Сериалы", "entertainment" to "Развлекательные", "general" to "Общие",
        "news" to "Новости", "music" to "Музыка", "sports" to "Спорт", "sport" to "Спорт",
        "documentary" to "Документальные", "documentaries" to "Документальные",
        "education" to "Образование", "educational" to "Образование", "science" to "Наука",
        "culture" to "Культура", "comedy" to "Комедии", "classic" to "Классика",
        "classics" to "Классика", "religious" to "Религиозные", "religion" to "Религиозные",
        "business" to "Бизнес", "lifestyle" to "Образ жизни", "travel" to "Путешествия",
        "outdoor" to "Природа и отдых", "cooking" to "Кулинария", "food" to "Кулинария",
        "auto" to "Авто", "automotive" to "Авто", "shop" to "Телемагазины",
        "shopping" to "Телемагазины", "legislative" to "Парламентские", "weather" to "Погода",
        "public" to "Общественные", "international" to "Международные", "local" to "Региональные",
        "russia" to "Россия", "russian" to "Русскоязычные", "russian federation" to "Россия",
        "ukraine" to "Украина", "belarus" to "Беларусь", "kazakhstan" to "Казахстан",
        "undefined" to "Без группы", "other" to "Другие", "xxx" to "Для взрослых"
    )

    fun group(value: String?): String {
        if (value.isNullOrBlank()) return "Без группы"
        val text = value.trim()
        categories[text.lowercase(Locale.ROOT)]?.let { return it }
        return text.split(Regex("\\s*[;/|]\\s*")).joinToString(" / ") { part ->
            categories[part.lowercase(Locale.ROOT)] ?: part
        }
    }

    private val titles = mapOf(
        "pyat minut tishiny" to "Пять минут тишины", "pyat' minut tishiny" to "Пять минут тишины",
        "five minutes of silence" to "Пять минут тишины", "5 minut tishiny" to "Пять минут тишины",
        "balabol" to "Балабол", "nevskiy" to "Невский", "perviy otdel" to "Первый отдел",
        "pes" to "Пёс", "shef" to "Шеф", "skoraya pomoshch" to "Скорая помощь",
        "ntv law" to "НТВ Право", "ntv series" to "НТВ Сериал", "ntv style" to "НТВ Стиль",
        "ntv sledstviye veli" to "НТВ Следствие вели", "ntv svoya igra" to "НТВ Своя игра",
        "ntv vitrina" to "НТВ Витрина", "ntv-hit" to "НТВ Хит",
        "pervyy kanal" to "Первый канал", "perviy kanal" to "Первый канал", "channel one russia" to "Первый канал",
        "rossiya 1" to "Россия 1", "rossiya 24" to "Россия 24", "rossiya k" to "Россия К",
        "karusel" to "Карусель", "karusel international" to "Карусель Международная",
        "pyatnitsa" to "Пятница!", "zvezda" to "Звезда", "domashniy" to "Домашний",
        "kultura" to "Культура", "mir" to "Мир", "mir 24" to "Мир 24", "pobeda" to "Победа"
    )

    fun title(value: String): String {
        if (value.startsWith("NTV (Russia)", ignoreCase = true)) return "НТВ" + value.substring("NTV (Russia)".length)
        val suffix = Regex("\\s*(\\([^)]*\\)|\\[[^]]*])\\s*$").find(value)
        val base = if (suffix == null) value.trim() else value.substring(0, suffix.range.first).trim()
        val translated = titles[base.lowercase(Locale.ROOT)] ?: return value
        return translated + (suffix?.let { " ${it.value.trim()}" } ?: "")
    }
}

val Channel.displayName: String get() = RussianLabels.title(name)
val Channel.displayGroup: String get() = RussianLabels.group(group)
