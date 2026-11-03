package com.astraplay.tv.data.iptv

import java.util.Locale

object EpgKeys {
    private val alphabet = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")

    fun normalize(raw: String): String {
        val text = RussianLabels.title(raw).lowercase(Locale.ROOT)
            .replace(Regex("\\.(ru|by|ua|kz)(?=@|$)"), "")
            .replace(Regex("@(hd|sd|fhd|uhd)$"), "")
            .replace(Regex("\\((?:[0-9]{3,4}[pi]|hd|sd|russia)\\)|\\[[^]]*]"), "")
            .replace(Regex("\\b(hd|sd|fhd|uhd)\\b"), "")
        val key = buildString { text.forEach { append(alphabet[it] ?: it.toString()) } }.filter { it.isLetterOrDigit() }
        return when (key) {
            "channelonerussia", "pervyykanal", "perviykanal", "pervijkanal", "1kanal" -> "pervyykanal"
            "russia1", "rossiya1", "rossiya" -> "rossiya1"
            "russia24", "rossiya24" -> "rossiya24"
            "ntvseries", "ntvserial" -> "ntvserial"
            else -> key
        }
    }

    fun channel(channel: Channel): Set<String> = listOfNotNull(channel.epgId, channel.name, channel.displayName)
        .map(::normalize).filter { it.isNotEmpty() }.toSet()
}
