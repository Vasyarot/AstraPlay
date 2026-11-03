package com.astraplay.tv.data.iptv

import org.junit.Assert.*
import org.junit.Test

class RussianLabelsTest {
    @Test fun translatesGroupsAndCompositeCategories() {
        assertEquals("Мультфильмы", RussianLabels.group("Animation"))
        assertEquals("Семейные и детские", RussianLabels.group("Family Kids"))
        assertEquals("Мультфильмы / Детские", RussianLabels.group("Animation;Kids"))
        assertEquals("Авторский раздел", RussianLabels.group("Авторский раздел"))
    }

    @Test fun displayAliasesDoNotChangeChannelIdentity() {
        val channel = Channel(name = "Pyat minut tishiny", url = "https://example.com/live", group = "Family Kids")
        val key = channel.key
        assertEquals("Пять минут тишины", channel.displayName)
        assertEquals("Семейные и детские", channel.displayGroup)
        assertEquals(key, channel.key)
        assertEquals("Pyat minut tishiny", channel.name)
    }

    @Test fun usesRussianTvgNameWhenPlaylistTitleIsEnglish() {
        val text = """#EXTM3U
#EXTINF:-1 tvg-name="Пять минут тишины",Five Minutes of Silence
https://example.com/live
"""
        assertEquals("Пять минут тишины", M3uParser.parse(text).single().name)
    }
}
