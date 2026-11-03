package com.astraplay.tv.data.iptv

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    @Test fun channelRequestHeadersSurviveParsingAndDoNotLeakToNextStream() {
        val text = """#EXTM3U
#EXTINF:-1,Один
#EXTVLCOPT:http-user-agent=CustomPlayer/1.0
#EXTVLCOPT:http-referrer=https://example.com/
https://example.com/one.m3u8
#EXTINF:-1,Два
https://example.com/two.m3u8
"""
        val rows = M3uParser.parse(text)
        assertEquals("CustomPlayer/1.0", rows[0].userAgent)
        assertEquals("https://example.com/", rows[0].referrer)
        assertNull(rows[1].userAgent)
        assertNull(rows[1].referrer)
    }

    @Test fun extractsEpgUrlFromPlaylistHeader() {
        assertEquals("https://example.com/epg.xml.gz", M3uParser.epgUrl("\uFEFF#EXTM3U x-tvg-url=\"https://example.com/epg.xml.gz,https://example.com/other.xml\"\n"))
        assertNull(M3uParser.epgUrl("#EXTM3U x-tvg-url=\"file:///private\""))
    }

    @Test fun commaInQuotedAttributeDoesNotBecomeTitle() {
        val channels = M3uParser.parse("""#EXTM3U
#EXTINF:-1 group-title="News, World" tvg-id="one",Channel One
https://example.com/one.m3u8
""")
        assertEquals("Channel One", channels.single().name)
        assertEquals("News, World", channels.single().group)
        assertEquals("one", channels.single().epgId)
    }

    @Test fun utf8BomDoesNotBecomeChannel() {
        val channels = M3uParser.parse("\uFEFF#EXTM3U\n#EXTINF:-1,One\nhttps://example.com/one")
        assertEquals(1, channels.size)
        assertEquals("One", channels.single().name)
    }

    @Test fun metadataDoesNotLeakIntoNextChannel() {
        val channels = M3uParser.parse("""#EXTM3U
#EXTINF:-1 tvg-id="one" tvg-logo="https://example.com/logo" group-title="News",One
https://example.com/one
#EXTINF:-1,Two
#EXTGRP:Movies
https://example.com/two
""")
        assertEquals(2, channels.size)
        assertNull(channels[1].epgId)
        assertNull(channels[1].logo)
        assertEquals("Movies", channels[1].group)
    }
}
