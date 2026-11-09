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
        assertEquals(listOf("ok"),d.feed(("x".repeat(2049)+"tail\nok\n").toByteArray()))
        assertEquals(listOf("ok"),d.feed("bad\u0000tail\nok\n".toByteArray()))
    }
    @Test fun acceptsLongImuReplyWithSmallFragments() {
        val d=BoardReplyDecoder(); val source="x".repeat(1900)+"\n"
        val frames=source.toByteArray().toList().chunked(20).flatMap { d.feed(it.toByteArray()) }
        assertEquals(listOf("x".repeat(1900)),frames)
    }
    @Test fun reconnectDropsPartialReply() {
        val d=BoardReplyDecoder();d.feed("old".toByteArray());d.reset()
        assertEquals(listOf("new"),d.feed("new\n".toByteArray()))
    }
}
