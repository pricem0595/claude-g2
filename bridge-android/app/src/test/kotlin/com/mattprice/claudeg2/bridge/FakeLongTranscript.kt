package com.mattprice.claudeg2.bridge

import kotlinx.coroutines.delay

/**
 * A pretend Claude app showing one long session, laid out like the real one (see the real-*
 * fixtures): a full-screen scrollable list laid out bottom-up (newest child first), the title
 * bar and message box outside it, and a live status row at the bottom that changes every second.
 *
 * Scrolling moves the visible window a page at a time and, like a real smooth scroll, reports
 * several in-between positions on the way. The glasses-side scroll behaviour can be triaged
 * against it in the simulator:
 *
 *   gradlew testDebugUnitTest --tests "*DesktopBridge*" --rerun -Dbridge.serve=true -Dbridge.scenario=long
 */
class FakeLongTranscript(
    private val messages: List<String> = sampleMessages(60),
    private val window: Int = 6,
    private val page: Int = (System.getProperty("bridge.page") ?: "5").toInt(),
    private val animationSteps: Int = (System.getProperty("bridge.steps") ?: "3").toInt(),
) : ScreenSource {
    lateinit var controller: MirrorController

    /** Index of the first visible message; the window starts scrolled up [startBack] messages from the end. */
    var first = 0
        private set
    private var tick = 0

    fun start(startBack: Int = 30) {
        first = (messages.size - window - startBack).coerceAtLeast(0)
        publish()
        Thread {
            // The live status row only shows at the bottom, and changes every second.
            while (true) {
                Thread.sleep(1_000)
                tick++
                if (atEnd) publish()
            }
        }.apply { isDaemon = true }.start()
    }

    private val atEnd get() = first >= messages.size - window

    override suspend fun click(node: UiNode) = false

    override suspend fun back() = false

    override suspend fun setText(node: UiNode, text: String) = false

    override suspend fun scroll(up: Boolean): Boolean {
        val target = if (up) (first - page).coerceAtLeast(0) else (first + page).coerceAtMost(messages.size - window)
        if (target == first) return false
        // A smooth scroll: report in-between positions, as the accessibility events do.
        val stepSize = ((target - first) / animationSteps).let { if (it == 0) (if (up) -1 else 1) else it }
        while (first != target) {
            first = if (up) maxOf(target, first + stepSize) else minOf(target, first + stepSize)
            publish()
            delay(40)
        }
        return true
    }

    override suspend fun pageToLatest(maxPages: Int): Int {
        var pages = 0
        while (pages < maxPages && !atEnd) {
            first = minOf(messages.size - window, first + window)
            pages++
            publish()
            delay(40)
        }
        return pages
    }

    private fun publish() = controller.onSnapshot(tree())

    private fun tree(): UiNode {
        val visible = messages.subList(first, first + window)
        val rows = ArrayList<UiNode>()
        var y = 300
        for (text in visible) {
            val height = 60 * (1 + text.length / 45)
            rows += node("android.widget.TextView", text, Bounds(42, y, 1038, y + height))
            y += height + 20
        }
        if (atEnd) rows += node("android.widget.TextView", "${tick}s · thinking…", Bounds(105, y, 900, y + 56))
        // Bottom-up, like the real app: the newest item is the first child.
        val list = UiNode(
            "android.view.View", null, null, null, PKG, false, true, false, Bounds(0, 0, 1080, 2520), rows.reversed(),
            canScrollForward = !atEnd,
        )
        val appBar = UiNode(
            "android.view.View", null, null, null, PKG, false, false, false, Bounds(0, 110, 1080, 278),
            listOf(node("android.widget.TextView", "Long session", Bounds(219, 142, 862, 198))),
        )
        val composer = UiNode("android.widget.EditText", null, null, null, PKG, true, false, true, Bounds(76, 2187, 1004, 2313), emptyList())
        val root = UiNode("android.widget.FrameLayout", null, null, null, PKG, false, false, false, Bounds(0, 0, 1080, 2520), listOf(list, appBar, composer))
        // Rebuild with tree paths, as the real capture does.
        return UiNode.fromXml(root.toXml())
    }

    private fun node(cls: String, text: String, bounds: Bounds) =
        UiNode(cls, text, null, null, PKG, false, false, false, bounds, emptyList())

    companion object {
        private const val PKG = "com.anthropic.claude"

        fun sampleMessages(count: Int): List<String> = (1..count).map { i ->
            val filler = "lorem ipsum dolor sit amet ".repeat(1 + (i * 7) % 4).trim()
            "Message $i: $filler"
        }
    }
}
