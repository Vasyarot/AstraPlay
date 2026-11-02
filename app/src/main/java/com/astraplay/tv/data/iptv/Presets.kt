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
        Preset("Новости", "https://iptv-org.github.io/iptv/categories/news.m3u"),
        Preset("Музыка", "https://iptv-org.github.io/iptv/categories/music.m3u"),
        Preset("Спорт", "https://iptv-org.github.io/iptv/categories/sports.m3u"),
        Preset("Детские", "https://iptv-org.github.io/iptv/categories/kids.m3u"),
        Preset("Фильмы и сериалы", "https://iptv-org.github.io/iptv/categories/movies.m3u"),
        Preset("Документальные", "https://iptv-org.github.io/iptv/categories/documentary.m3u"),
        Preset("🌍 Все страны (большой список)", "https://iptv-org.github.io/iptv/index.country.m3u"),
    )
}
