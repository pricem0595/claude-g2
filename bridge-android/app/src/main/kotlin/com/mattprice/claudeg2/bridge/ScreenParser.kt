package com.mattprice.claudeg2.bridge

import java.util.IdentityHashMap

enum class ScreenKind { SESSIONS, TRANSCRIPT, PROMPT, UNKNOWN }

/** Something tappable on the Claude screen: a session row or a prompt option. */
data class Choice(val id: String, val label: String, val detail: String?)

data class Prompt(val question: String, val options: List<Choice>)

/** What the Claude app is showing, reduced to what the glasses need. */
data class ParsedScreen(
    val kind: ScreenKind,
    val title: String?,
    val sessions: List<Choice> = emptyList(),
    /** Visible text blocks in reading order (transcript messages, or everything for UNKNOWN). */
    val lines: List<String> = emptyList(),
    val prompt: Prompt? = null,
)

/**
 * Everything specific to how the Claude app lays out its screens. When a Claude app update
 * breaks the mirror, capture the new screen (the bridge's "Capture" button, or
 * `adb shell uiautomator dump`), add it to src/test/resources/fixtures, and adjust this.
 */
object ClaudeUi {
    const val DEFAULT_PACKAGE = "com.anthropic.claude"

    /** Buttons that only appear on permission and question prompts. */
    val PROMPT_WORDS = Regex(
        """^(allow|always allow|allow once|allow for this session|approve|accept|deny|don't allow|do not allow|reject|decline|yes|no|submit|skip)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The app bar: anything whose top is in this fraction of the screen. */
    const val TOP_BAND = 0.12

    /** A list is the session list when at least this share of its text sits inside tappable rows. */
    const val SESSION_ROW_SHARE = 0.6
}

/** Turns a Claude window's tree into a [ParsedScreen]. Pure, so it runs in JVM tests. */
object ScreenParser {

    fun parse(root: UiNode): ParsedScreen {
        val all = root.walk().toList()
        // Identity, not equality: UiNode is a data class and hashing one hashes its whole subtree.
        val parents = IdentityHashMap<UiNode, UiNode>()
        for (node in all) for (child in node.children) parents[child] = node

        val screenTop = root.bounds.top
        val topBandEnd = screenTop + (root.bounds.height * ClaudeUi.TOP_BAND).toInt()
        val inTopBar = { n: UiNode -> n.bounds.bottom in 1..topBandEnd }

        val composer = all.firstOrNull { it.editable }
        val main = all.filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }

        fun ancestors(n: UiNode) = generateSequence(parents[n]) { parents[it] }
        fun isInside(n: UiNode, container: UiNode) = n === container || ancestors(n).any { it === container }

        // The list scrolls under the app bar, so only text outside the list can be the title.
        val title = all.firstOrNull {
            it.text?.isNotBlank() == true && !it.clickable && !it.editable && inTopBar(it) && (main == null || !isInside(it, main))
        }?.text?.trim()

        // Tappable controls outside the app bar and composer: the outermost clickable of each branch.
        fun choicesIn(container: UiNode): List<UiNode> = container.walk()
            .filter { it.clickable && !it.editable && !inTopBar(it) && (composer == null || !isInside(it, composer)) }
            .filter { n -> ancestors(n).takeWhile { it !== container }.none { it.clickable } }
            .filter { choiceLabel(it) != null }
            .toList()

        findPrompt(all, main, parents, ::choicesIn, ::isInside, inTopBar)?.let { prompt ->
            return ParsedScreen(ScreenKind.PROMPT, title, prompt = prompt)
        }

        // A session has a message box; the session list doesn't. Tool-call rows in a transcript
        // are tappable too, so without this a busy transcript looks like a list of sessions.
        if (main != null && composer == null) {
            val rows = choicesIn(main)
            val labelled = main.walk().filter { it.label != null }.toList()
            val inRows = labelled.count { n -> rows.any { isInside(n, it) } }
            if (rows.size >= 2 && labelled.isNotEmpty() && inRows >= labelled.size * ClaudeUi.SESSION_ROW_SHARE) {
                // A session row has a title and details (time, status, repo). Single-text
                // controls on the same screen ("Add device", the "All" filter) aren't sessions.
                val sessions = sortByPosition(rows).filter { texts(it).size >= 2 }
                return ParsedScreen(ScreenKind.SESSIONS, title, sessions = sessions.map(::toChoice))
            }
        }

        if (main != null || composer != null) {
            val source = main ?: root
            return ParsedScreen(ScreenKind.TRANSCRIPT, title, lines = dedupeAdjacent(blocks(source).filter { it.first !== composer }.map { it.second }))
        }

        return ParsedScreen(ScreenKind.UNKNOWN, title, lines = dedupeAdjacent(blocks(root).map { it.second }))
    }

    /**
     * The readable blocks under [container], top to bottom as drawn. The Claude app's list is
     * laid out bottom-up (newest item first in the tree), so tree order can't be trusted. A
     * tappable row (a tool call, a status line) is one block, however many text pieces it has.
     */
    private fun blocks(container: UiNode): List<Pair<UiNode, String>> {
        val found = ArrayList<Pair<UiNode, String>>()
        fun visit(n: UiNode) {
            if (n.clickable && n !== container) {
                texts(n).joinToString(" ").replace(Regex("\\s+·"), " ·").trim().takeIf { it.isNotEmpty() }?.let { found += n to it }
                return
            }
            // Icon buttons ("Copy", "Retry") only have a description; message text has text.
            n.text?.trim()?.takeIf { it.isNotEmpty() && !n.editable }?.let { found += n to it }
            n.children.forEach(::visit)
        }
        visit(container)
        return found.sortedWith(compareBy({ it.first.bounds.top }, { it.first.bounds.left }))
    }

    private fun sortByPosition(nodes: List<UiNode>) = nodes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

    /**
     * A prompt is a container, other than the main list or anything holding it, with at least two
     * tappable choices, where either a choice reads like a prompt button ("Allow", "Deny") or the
     * container asks a question.
     */
    private fun findPrompt(
        all: List<UiNode>,
        main: UiNode?,
        parents: Map<UiNode, UiNode>,
        choicesIn: (UiNode) -> List<UiNode>,
        isInside: (UiNode, UiNode) -> Boolean,
        inTopBar: (UiNode) -> Boolean,
    ): Prompt? {
        val excluded = { n: UiNode -> main != null && (n === main || isInside(main, n)) }
        val promptLike = { n: UiNode -> ClaudeUi.PROMPT_WORDS.containsMatchIn(choiceLabel(n)!!) }

        // Smallest qualifying container first, so a prompt inside the transcript list isn't
        // swallowed by the list itself.
        val candidates = all.filter { !excluded(it) && it.children.isNotEmpty() }
            .sortedBy { it.bounds.width.toLong() * it.bounds.height }

        for (smallest in candidates) {
            var container = smallest
            var choices = choicesIn(container)
            if (choices.size < 2) continue
            val words = choices.any(promptLike)
            val texts0 = container.walk().filter { n -> n.label != null && !n.clickable && choices.none { isInside(n, it) } }
            if (!words && texts0.none { it.label!!.trimEnd().endsWith("?") }) continue

            // Grow outwards to take in:
            // - the question, when the options sit in their own group below it (the real Claude
            //   app's AskUserQuestion: question text, then a group of options + "Skip");
            // - sibling rows of prompt buttons ("Allow"/"Deny" in one row, "Always allow" next).
            // Stop at anything else tappable, the list itself, or the app bar.
            while (true) {
                val parent = parents[container] ?: break
                if (excluded(parent) || parent.walk().any(inTopBar)) break
                val more = choicesIn(parent)
                if (more.filter { m -> choices.none { it === m } }.any { !promptLike(it) }) break
                container = parent
                choices = more
            }

            // The free-text answer box's placeholder ("Something else") isn't part of the question.
            val inEditable = { n: UiNode -> generateSequence(n) { parents[it] }.any { it.editable } }
            val texts = sortByPosition(
                container.walk().filter { n -> n.label != null && !n.clickable && !inEditable(n) && choices.none { isInside(n, it) } }.toList(),
            ).map { it.label!! }

            // A question outside the container (e.g. the tool call just above the buttons).
            val question = texts.ifEmpty {
                val before = all.takeWhile { it !== container }.filter { it.text?.isNotBlank() == true && !isInside(it, container) }
                before.takeLast(1).map { it.text!!.trim() }
            }
            return Prompt(question.joinToString("\n"), sortByPosition(choices).map(::toChoice))
        }
        return null
    }

    /**
     * The visible text on a tappable control. Icon buttons ("Copy", "Retry") only have a
     * description, so they never count as choices.
     */
    fun choiceLabel(n: UiNode): String? = texts(n).firstOrNull()

    private fun texts(n: UiNode) = n.walk().mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }.toList()

    private fun toChoice(n: UiNode): Choice {
        val texts = texts(n)
        // Separator dots ("Connected • Remote control") are their own text nodes.
        val detail = texts.drop(1).filter { it != "•" && it != "·" }
        return Choice(n.id, texts.first(), detail.joinToString(" · ").ifEmpty { null })
    }

    private fun dedupeAdjacent(lines: List<String>) = lines.filterIndexed { i, s -> i == 0 || lines[i - 1] != s }
}
