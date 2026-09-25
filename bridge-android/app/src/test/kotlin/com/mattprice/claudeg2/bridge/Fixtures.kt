package com.mattprice.claudeg2.bridge

/** Loads a screen dump from src/test/resources/fixtures. */
fun fixture(name: String): UiNode {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/fixtures/$name.xml")) { "No fixture $name" }
    return UiNode.fromXml(stream.bufferedReader().use { it.readText() })
}

/**
 * A pretend Claude app that moves between the fixture screens the way the real one would:
 * tap a session to open it, Back to return, tap a prompt option to answer it. The desktop bridge
 * and the HTTP tests drive it.
 */
class FakeClaude : ScreenSource {
    lateinit var controller: MirrorController
    var screen = "synthetic-sessions"
        private set
    val clicks = mutableListOf<String>()

    /** What's typed in the message box, and every message sent from it. */
    var typed = ""
        private set
    val sent = mutableListOf<String>()

    fun show(name: String) {
        screen = name
        controller.onSnapshot(fixture(name))
    }

    override suspend fun setText(node: UiNode, text: String): Boolean {
        if (!node.editable) return false
        typed = text
        return true
    }

    override suspend fun click(node: UiNode): Boolean {
        if (node.label?.let { ClaudeUi.SEND_WORDS.containsMatchIn(it) } == true) {
            clicks += "Send"
            sent += typed
            typed = ""
            return true
        }
        val label = ScreenParser.choiceLabel(node) ?: return false
        clicks += label
        show(
            when (screen) {
                // Each session opens on a different screen, so the simulator can reach them all.
                "synthetic-sessions" -> when (label) {
                    "Add dark mode" -> "synthetic-permission"
                    "Why are tests flaky?" -> "synthetic-question"
                    else -> "synthetic-transcript"
                }
                else -> "synthetic-transcript"
            },
        )
        return true
    }

    /** How many pages the pretend list can still scroll forward before reaching the newest message. */
    var pagesBelow = 0

    override suspend fun scroll(up: Boolean): Boolean {
        if (up) return true
        if (pagesBelow == 0) return false
        pagesBelow--
        return true
    }

    override suspend fun pageToLatest(maxPages: Int): Int = minOf(pagesBelow, maxPages).also { pagesBelow -= it }

    override suspend fun back(): Boolean {
        show("synthetic-sessions")
        return true
    }
}
