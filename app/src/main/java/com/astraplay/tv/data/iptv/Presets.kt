package com.astraplay.tv.data.iptv

/**
 * Готовые IPTV-подборки проекта iptv-org (открытый каталог свободных
 * публичных плейлистов на GitHub). URL стабильные и поддерживаются сообществом.
 */
object IptvPresets {

    data class Preset(val title: String, val url: String)

    val list: List<Preset> = listOf(
        Preset("🇷🇺 Россия", "https://iptv-org.github.io/iptv/countries/ru.m3u"),
        Preset("Русскоязычные каналы", "https://iptv-org.github.io/iptv/languages/rus.m3u"),
        Preset("Русскоязычные • музыка", "https://iptv-org.github.io/iptv/languages/rus.m3u#astra-category=music"),
        Preset("Русскоязычные • кино", "https://iptv-org.github.io/iptv/languages/rus.m3u#astra-category=movies"),
        Preset("Русскоязычные • сериалы", "https://iptv-org.github.io/iptv/languages/rus.m3u#astra-category=series"),
        Preset("Русскоязычные • детские", "https://iptv-org.github.io/iptv/languages/rus.m3u#astra-category=kids"),
        // Зарубежные каналы с русской локализацией (рус. soundtrack/дорожки)
        Preset("🇧🇾 Беларусь", "https://iptv-org.github.io/iptv/countries/by.m3u"),
        Preset("🇰🇿 Казахстан", "https://iptv-org.github.io/iptv/countries/kz.m3u"),
        Preset("🇺🇿 Узбекистан", "https://iptv-org.github.io/iptv/countries/uz.m3u"),
        // Музыкальные каналы
        Preset("Музыка", "https://iptv-org.github.io/iptv/categories/music.m3u"),
        Preset("Классика (ретро-каналы)", "https://iptv-org.github.io/iptv/categories/classic.m3u"),
        // Сериалы и фильмы
        Preset("Сериалы (зарубежные)", "https://iptv-org.github.io/iptv/categories/series.m3u"),
        Preset("Фильмы и сериалы", "https://iptv-org.github.io/iptv/categories/movies.m3u"),
        Preset("Развлекательные", "https://iptv-org.github.io/iptv/categories/entertainment.m3u"),
        // Прочее
        Preset("Новости", "https://iptv-org.github.io/iptv/categories/news.m3u"),
        Preset("Спорт", "https://iptv-org.github.io/iptv/categories/sports.m3u"),
        Preset("Детские", "https://iptv-org.github.io/iptv/categories/kids.m3u"),
        Preset("Документальные", "https://iptv-org.github.io/iptv/categories/documentary.m3u"),
        Preset("🌍 Все страны (большой список)", "https://iptv-org.github.io/iptv/index.country.m3u"),
    )
}
