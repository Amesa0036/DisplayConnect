package com.example.displayconnect.network
import org.junit.Assert.*
import org.junit.Test
class BoardReplyDecoderTest {
    @Test fun fragmentsAndMultipleFrames() {
        val d=BoardReplyDecoder()
        assertTrue(d.feed("{\"type\":\"boa".toByteArray()).isEmpty())
        assertEquals(listOf("{\"type\":\"board_reply\"}","second"),d.feed("rd_reply\"}\r\nsecond\n".toByteArray()))
    }
    @Test fun rejectsOversizedAndNonAsciiFramesInFull() {
        val d=BoardReplyDecoder()
        assertEquals(listOf("ok"),d.feed(("x".repeat(1025)+"tail\nok\n").toByteArray()))
        assertEquals(listOf("ok"),d.feed("bad\u0000tail\nok\n".toByteArray()))
    }
    @Test fun reconnectDropsPartialReply() {
        val d=BoardReplyDecoder();d.feed("old".toByteArray());d.reset()
        assertEquals(listOf("new"),d.feed("new\n".toByteArray()))
    }
}
