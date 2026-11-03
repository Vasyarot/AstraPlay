package com.astraplay.tv.data.iptv

import org.junit.Assert.*
import org.junit.Test

class EpgKeysTest {
    @Test fun matchesChannelNamesToRussianXmltvNames() {
        assertEquals(EpgKeys.normalize("НТВ"), EpgKeys.normalize("NTV.ru@HD"))
        assertEquals(EpgKeys.normalize("НТВ +4"), EpgKeys.normalize("NTV (Russia) +4 (576p)"))
        assertEquals(EpgKeys.normalize("Первый канал"), EpgKeys.normalize("PerviyKanal.ru@SD"))
        assertNotEquals(EpgKeys.normalize("НТВ +4"), EpgKeys.normalize("НТВ"))
    }
}
