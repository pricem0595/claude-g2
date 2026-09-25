package com.mattprice.claudeg2.bridge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class BridgeException(val httpStatus: Int, message: String) : Exception(message)

// How long after a deliberate scroll a non-overlapping snapshot counts as part of that scroll.
private const val SCROLL_HINT_MS = 1_500L

// After typing a message, how long to wait for the Send button to show, and how often to look.
private const val SEND_WAIT_MS = 3_000L
private const val SEND_POLL_MS = 100L

/** Where screens come from and where taps go: the accessibility service, or a fake in tests. */
interface ScreenSource {
    /** Taps the node with this id in the latest snapshot. Returns false if it couldn't. */
    suspend fun click(node: UiNode): Boolean

    /**
     * Scrolls the Claude app's main list part of a screen (up loads older messages), little
     * enough that the snapshots before and after share messages and the history stitches
     * without a gap. Returns false when it can't scroll further that way.
     */
    suspend fun scroll(up: Boolean): Boolean

    /** Pages the main list forward to the newest message, fast. Returns how many pages. */
    suspend fun pageToLatest(maxPages: Int): Int

    /** Presses Back, while the Claude app is in front. */
    suspend fun back(): Boolean

    /** Replaces the text in an editable node (the message box). Returns false if it couldn't. */
    suspend fun setText(node: UiNode, text: String): Boolean
}

/** What the glasses get from GET /state. `version` goes up whenever anything in it changes. */
data class MirrorState(
    val version: Long,
    /** False when the Claude app isn't the app on screen. The rest is then the last thing seen. */
    val foreground: Boolean,
    val screen: ParsedScreen,
    /** For TRANSCRIPT: the stitched history, oldest first. */
    val history: List<String>,
    /**
     * The phone is locked or its screen is off. The Claude app isn't drawn, so nothing can be
     * read or tapped; the glasses switch to notification-only mode.
     */
    val locked: Boolean = false,
    /** The latest Claude notification since the phone locked, if any. */
    val notice: Notice? = null,
    /** The Claude app's list is at its newest message (it can't scroll further toward newer). */
    val atLatest: Boolean = true,
)

/** A Claude app notification, e.g. title "Claude has a question: Claude", text "Colour". */
data class Notice(val title: String, val text: String?)

/**
 * Turns snapshots of the Claude window into [MirrorState] and carries out the glasses' actions.
 * Thread-safe: snapshots arrive on the accessibility thread, requests on HTTP threads.
 */
class MirrorController(private val source: ScreenSource) {
    private val buffer = TranscriptBuffer()
    private var lastRoot: UiNode? = null
    private var lastTitle: String? = null
    private val _state = MutableStateFlow(MirrorState(0, false, ParsedScreen(ScreenKind.UNKNOWN, null), emptyList()))

    val state: StateFlow<MirrorState> = _state

    /** Latest raw tree, for GET /dump and the fixture capture. */
    val lastSnapshot: UiNode? @Synchronized get() = lastRoot

    /** Called with each new tree of the Claude window, or null when Claude isn't in front. */
    @Synchronized
    fun onSnapshot(root: UiNode?) {
        val current = _state.value
        if (root == null) {
            if (current.foreground) _state.value = current.copy(version = current.version + 1, foreground = false)
            return
        }
        lastRoot = root
        val screen = ScreenParser.parse(root)

        if (screen.kind == ScreenKind.TRANSCRIPT) {
            // A new title means a different session: don't stitch it onto the old one.
            if (screen.title != lastTitle) buffer.clear()
            lastTitle = screen.title
            buffer.merge(screen.lines, currentScrollDirection())
        } else if (screen.kind == ScreenKind.SESSIONS) {
            buffer.clear()
            lastTitle = null
        }
        val history = if (screen.kind == ScreenKind.TRANSCRIPT) buffer.all.toList() else emptyList()
        val list = root.walk().filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        val atLatest = list?.canScrollForward != true

        if (current.foreground && current.screen == screen && current.history == history && current.atLatest == atLatest) return
        _state.value = current.copy(version = current.version + 1, foreground = true, screen = screen, history = history, atLatest = atLatest)
    }

    /** Called when the phone locks or unlocks. Unlocking clears the notice. */
    @Synchronized
    fun onLockState(locked: Boolean) {
        val current = _state.value
        if (current.locked == locked) return
        _state.value = current.copy(version = current.version + 1, locked = locked, notice = if (locked) current.notice else null)
    }

    /**
     * Called for each notification the Claude app posts. Kept only while the phone is locked:
     * unlocked, the mirror shows the app itself.
     */
    @Synchronized
    fun onNotification(title: String, text: String?) {
        val current = _state.value
        val notice = Notice(title, text)
        if (!current.locked || current.notice == notice) return
        _state.value = current.copy(version = current.version + 1, notice = notice)
    }

    /** Waits up to [timeoutMs] for a state newer than [since]; null if nothing changed. */
    suspend fun awaitChange(since: Long, timeoutMs: Long): MirrorState? =
        withTimeoutOrNull(timeoutMs) { _state.first { it.version != since } }

    /**
     * Taps a choice. The id comes from an earlier state, so if the screen has moved on it falls
     * back to a tappable node with the same label.
     */
    suspend fun click(id: String, label: String?) {
        requireForeground()
        val root = lastSnapshot ?: throw BridgeException(409, "Nothing on screen yet")
        val nodes = root.walk().toList()
        val target = nodes.firstOrNull { it.id == id }
            ?: label?.let { l -> nodes.firstOrNull { it.clickable && ScreenParser.choiceLabel(it) == l } }
            ?: throw BridgeException(410, "That option is no longer on screen")
        if (!source.click(target)) throw BridgeException(502, "The tap didn't go through")
    }

    /**
     * Loads messages beyond one end of the history: older ([up]) or newer. The phone's list may
     * be anywhere (after a jump to the latest it sits at the far end from the history's top),
     * so it keeps scrolling that way until the history actually grows at that end, or the list
     * can't scroll further.
     */
    suspend fun scroll(up: Boolean, maxPages: Int = 40) {
        requireForeground()
        val end = { synchronized(this) { if (up) buffer.all.firstOrNull() else buffer.all.getOrNull(buffer.all.size - 2) } }
        val sizeBefore = synchronized(this) { buffer.all.size }
        val endBefore = end()
        repeat(maxPages) {
            expectScroll(toward = if (up) -1 else 1)
            if (!source.scroll(up)) {
                if (it == 0) throw BridgeException(409, if (up) "Nothing older to load" else "Already at the end")
                return
            }
            expectScroll(toward = if (up) -1 else 1)
            // Grown at that end. For newer, the last line is live and changes on its own, so the
            // one before it is the marker.
            if (synchronized(this) { buffer.all.size } > sizeBefore && end() != endBefore) return
        }
    }

    /**
     * Scrolls the Claude app's list to the newest message: forward until it stops moving.
     * Returns how many pages it moved (0 when already there).
     */
    suspend fun scrollToLatest(maxPages: Int = 50): Int {
        requireForeground()
        expectScroll(toward = 1)
        return source.pageToLatest(maxPages).also { expectScroll(toward = 1) }
    }

    /** The way the list was last scrolled on purpose, and until when that explains a jump. */
    @Volatile private var scrollDirection = 0
    @Volatile private var scrollDirectionUntil = 0L

    private fun expectScroll(toward: Int) {
        scrollDirection = toward
        scrollDirectionUntil = System.currentTimeMillis() + SCROLL_HINT_MS
    }

    private fun currentScrollDirection() = if (System.currentTimeMillis() < scrollDirectionUntil) scrollDirection else 0

    suspend fun back() {
        requireForeground()
        if (!source.back()) throw BridgeException(502, "Back didn't go through")
    }

    /**
     * Types [text] into the open session's message box and taps Send. The Send button only
     * appears once there's text, so it waits for a snapshot that shows it.
     */
    suspend fun send(text: String, sendWaitMs: Long = SEND_WAIT_MS) {
        requireForeground()
        if (text.isBlank()) throw BridgeException(400, "Nothing to send")
        if (_state.value.screen.kind != ScreenKind.TRANSCRIPT) throw BridgeException(409, "Open a session to send a message")
        val composer = lastSnapshot?.let(ScreenParser::composer) ?: throw BridgeException(409, "No message box on screen")
        if (!source.setText(composer, text)) throw BridgeException(502, "Couldn't type into the message box")

        val until = System.currentTimeMillis() + sendWaitMs
        while (true) {
            val button = lastSnapshot?.let { root -> ScreenParser.composer(root)?.let { ScreenParser.sendButton(root, it) } }
            if (button != null) {
                if (!source.click(button)) throw BridgeException(502, "The Send tap didn't go through")
                return
            }
            if (System.currentTimeMillis() >= until) throw BridgeException(502, "No Send button: the message is in the box on the phone")
            delay(SEND_POLL_MS)
        }
    }

    private fun requireForeground() {
        if (_state.value.locked) throw BridgeException(409, "Unlock your phone to answer")
        if (!_state.value.foreground) throw BridgeException(409, "Open the Claude app on your phone")
    }
}
