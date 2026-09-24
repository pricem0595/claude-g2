package com.mattprice.claudeg2.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenParserTest {

    @Test
    fun `session list becomes sessions with titles and details`() {
        val screen = ScreenParser.parse(fixture("synthetic-sessions"))
        assertEquals(ScreenKind.SESSIONS, screen.kind)
        assertEquals("Code", screen.title)
        assertEquals(listOf("Fix login redirect", "Add dark mode", "Why are tests flaky?"), screen.sessions.map { it.label })
        assertEquals("my-app · laptop · 2m ago", screen.sessions[0].detail)
        assertNull(screen.prompt)
    }

    @Test
    fun `transcript keeps message text and drops icon buttons and the composer`() {
        val screen = ScreenParser.parse(fixture("synthetic-transcript"))
        assertEquals(ScreenKind.TRANSCRIPT, screen.kind)
        assertEquals("Fix login redirect", screen.title)
        assertEquals(
            listOf(
                "The login page sends people to the old URL. Fix it.",
                "I'll look at the auth handler.",
                "Read src/auth.ts",
                "The redirect uses the old URL. Should I also update the tests?",
            ),
            screen.lines,
        )
    }

    @Test
    fun `a question with only icon buttons next to it is not a prompt`() {
        // The last transcript message ends in "?" and has Copy/Retry beside it.
        assertEquals(ScreenKind.TRANSCRIPT, ScreenParser.parse(fixture("synthetic-transcript")).kind)
    }

    @Test
    fun `permission sheet becomes a prompt with every button, across rows`() {
        val screen = ScreenParser.parse(fixture("synthetic-permission"))
        assertEquals(ScreenKind.PROMPT, screen.kind)
        val prompt = screen.prompt!!
        assertEquals(listOf("Allow", "Deny", "Always allow in this session"), prompt.options.map { it.label })
        assertEquals("Bash\nnpm test", prompt.question)
    }

    @Test
    fun `question inside the transcript becomes a prompt with option details`() {
        val prompt = ScreenParser.parse(fixture("synthetic-question")).prompt!!
        assertEquals("Which database should we use?", prompt.question)
        assertEquals(listOf("Postgres", "SQLite", "Other"), prompt.options.map { it.label })
        assertEquals("Relational, runs as a server", prompt.options[0].detail)
        assertNull(prompt.options[2].detail)
    }

    @Test
    fun `choice ids are stable across parses and distinct within a screen`() {
        val a = ScreenParser.parse(fixture("synthetic-question")).prompt!!.options.map { it.id }
        val b = ScreenParser.parse(fixture("synthetic-question")).prompt!!.options.map { it.id }
        assertEquals(a, b)
        assertEquals(a.size, a.toSet().size)
    }

    // Real captures from the Claude app (conversation text scrubbed to "Text N lorem ipsum").

    @Test
    fun `real transcript reads top to bottom although the app lays its list out bottom-up`() {
        val screen = ScreenParser.parse(fixture("real-transcript-thinking"))
        assertEquals(ScreenKind.TRANSCRIPT, screen.kind)
        // The oldest visible message (partly under the app bar) first, the live status last.
        assertTrue(screen.lines.first(), screen.lines.first().startsWith("Text 7 "))
        assertEquals("Cogitating...", screen.lines.last())
        assertEquals("Using PowerShell", screen.lines[screen.lines.size - 2])
        val tops = listOf("Text 7 ", "Text 6 ", "Text 5 ", "Text 4 ", "Text 2 ", "Text 1 ").map { p -> screen.lines.indexOfFirst { it.startsWith(p) } }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `real title comes from the app bar, not a message scrolled under it`() {
        assertTrue(ScreenParser.parse(fixture("real-transcript-thinking")).title!!.startsWith("Text 8 "))
    }

    @Test
    fun `a busy transcript with many tappable tool rows is still a transcript`() {
        val screen = ScreenParser.parse(fixture("real-transcript-running"))
        assertEquals(ScreenKind.TRANSCRIPT, screen.kind)
        assertTrue(screen.title!!.startsWith("Text 9 "))
        // A tappable row's text pieces become one line.
        assertTrue(screen.lines.toString(), screen.lines.any { it.endsWith("· 1 running task") })
        assertTrue(screen.lines.none { it == "Queue a message…" || it == "Opus 5.5" })
    }

    @Test
    fun `real session list has only sessions, with tidy details`() {
        val screen = ScreenParser.parse(fixture("real-sessions"))
        assertEquals(ScreenKind.SESSIONS, screen.kind)
        assertEquals("Code", screen.title)
        val labels = screen.sessions.map { it.label }
        assertTrue(labels.toString(), "Add device" !in labels && "All" !in labels)
        assertEquals(9, screen.sessions.size)
        assertTrue(labels.contains("Sample session"))
        val weekly = screen.sessions.first { it.label == "Sample session" }
        assertEquals("1d · Disconnected · user/project-b", weekly.detail)
    }

    @Test
    fun `real question prompt has the question text, not the free-text placeholder`() {
        val screen = ScreenParser.parse(fixture("real-question"))
        assertEquals(ScreenKind.PROMPT, screen.kind)
        val prompt = screen.prompt!!
        assertEquals(listOf("3 lines (Recommended)", "Half a screen", "One message", "Skip"), prompt.options.map { it.label })
        assertTrue(prompt.question, prompt.question.startsWith("Text "))
        assertTrue(prompt.question, "Something else" !in prompt.question)
        // Transcript text above the prompt isn't part of the question.
        assertEquals(1, prompt.question.lines().size)
    }

    @Test
    fun `real permission prompt has the question, the command, and allow before deny`() {
        val screen = ScreenParser.parse(fixture("real-permission"))
        assertEquals(ScreenKind.PROMPT, screen.kind)
        val prompt = screen.prompt!!
        assertEquals(listOf("Allow once", "Deny"), prompt.options.map { it.label })
        val question = prompt.question.lines()
        assertEquals("Allow Claude to run?", question[0])
        assertTrue(prompt.question, question[1].startsWith("Text "))
        assertEquals(2, question.size)
    }

    @Test
    fun `xml round-trips`() {
        val root = fixture("synthetic-question")
        assertEquals(root, UiNode.fromXml(root.toXml()))
    }

    @Test
    fun `a screen with no list and no composer is unknown and shows all its text`() {
        val root = UiNode.fromXml(
            """<hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">""" +
                """<node class="android.widget.TextView" text="Sign in" bounds="[0,1000][1080,1100]" />""" +
                """<node class="android.widget.TextView" text="Use your email" bounds="[0,1100][1080,1200]" />""" +
                """</node></hierarchy>""",
        )
        val screen = ScreenParser.parse(root)
        assertEquals(ScreenKind.UNKNOWN, screen.kind)
        assertTrue(screen.lines.containsAll(listOf("Sign in", "Use your email")))
    }
}
