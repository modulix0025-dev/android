package com.modulex.translator.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsParsingTest {
    @Test
    fun iceServersRoundTripAndRejectUnknownSchemes() {
        val text = "stun:stun.example.org:3478\nturn:turn.example.org:3478|alice|s3cret\nhttp://not-ice\n\n"
        val list = SettingsRepository.parseIce(text)
        assertEquals(2, list.size)
        assertEquals(IceServer("turn:turn.example.org:3478", "alice", "s3cret"), list[1])
        assertEquals(list, SettingsRepository.parseIce(SettingsRepository.formatIce(list)))
    }
}
