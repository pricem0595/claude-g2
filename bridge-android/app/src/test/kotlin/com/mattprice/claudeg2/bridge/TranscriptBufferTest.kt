package com.mattprice.claudeg2.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller's load-older/newer against the long pretend transcript, with its real stitching. */
class LoadMoreTest {
    private fun setUp(page: Int): Pair<FakeLongTranscript, MirrorController> {
        System.setProperty("bridge.page", "$page")
        System.setProperty("bridge.steps", "1")
        val fake = FakeLongTranscript()
        val controller = MirrorController(fake)
        fake.controller = controller
        fake.start(startBack = 0)
        return fake to controller
    }

    @Test
    fun `after a jump to the latest, loading older reaches past the top of the history`() = kotlinx.coroutines.runBlocking {
        val (_, controller) = setUp(page = 3)
        controller.scroll(up = true) // history now spans two windows
        controller.scrollToLatest() // phone back at the newest message
        val oldest = controller.state.value.history.first()
        val size = controller.state.value.history.size

        controller.scroll(up = true)

        val history = controller.state.value.history
        assertTrue("history grew at the top: $history", history.size > size && history.first() != oldest)
        assertTrue("newest messages kept", history.last().contains("thinking") || history.last().startsWith("Message 60"))
    }
}

class TranscriptBufferTest {
    private val buffer = TranscriptBuffer(cap = 10)

    @Test
    fun `scrolling down appends the new lines`() {
        buffer.merge(listOf("a", "b", "c"))
        buffer.merge(listOf("b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c", "d", "e"), buffer.all)
    }

    @Test
    fun `a window inside the history keeps everything below it`() {
        buffer.merge(listOf("a", "b", "c", "d", "e", "f"))
        assertFalse(buffer.merge(listOf("b", "c")))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), buffer.all)
    }

    @Test
    fun `a message still streaming grows in place`() {
        buffer.merge(listOf("a", "b", "Working on"))
        buffer.merge(listOf("b", "Working on it now.", "c"))
        assertEquals(listOf("a", "b", "Working on it now.", "c"), buffer.all)
    }

    @Test
    fun `a live status row that changes every second replaces in place`() {
        buffer.merge(listOf("a", "b", "1m 46s · thinking…"))
        buffer.merge(listOf("a", "b", "1m 47s · thinking…"))
        assertEquals(listOf("a", "b", "1m 47s · thinking…"), buffer.all)
    }

    @Test
    fun `an older window is not mistaken for the live line changing`() {
        buffer.merge(listOf("c", "d", "live"))
        buffer.merge(listOf("x", "y"))
        assertEquals(listOf("x", "y"), buffer.all)
    }

    @Test
    fun `scrolling up prepends older lines`() {
        buffer.merge(listOf("c", "d", "e"))
        buffer.merge(listOf("a", "b", "c", "d"))
        assertEquals(listOf("a", "b", "c", "d", "e"), buffer.all)
    }

    @Test
    fun `a window with no overlap replaces the history`() {
        buffer.merge(listOf("a", "b"))
        buffer.merge(listOf("x", "y"))
        assertEquals(listOf("x", "y"), buffer.all)
    }

    @Test
    fun `after a deliberate scroll, a window with no overlap is placed on that side`() {
        buffer.merge(listOf("a", "b", "c"))
        buffer.merge(listOf("g", "h"), direction = 1)
        assertEquals(listOf("a", "b", "c", "g", "h"), buffer.all)
        buffer.merge(listOf("x", "y"), direction = -1)
        assertEquals(listOf("x", "y", "a", "b", "c", "g", "h"), buffer.all)
    }

    @Test
    fun `the same window twice is not a change`() {
        assertTrue(buffer.merge(listOf("a", "b")))
        assertFalse(buffer.merge(listOf("a", "b")))
        assertFalse(buffer.merge(emptyList()))
    }

    @Test
    fun `oldest lines drop off past the cap`() {
        buffer.merge((1..8).map { "$it" })
        buffer.merge((5..14).map { "$it" })
        assertEquals((5..14).map { "$it" }, buffer.all)
    }
}
