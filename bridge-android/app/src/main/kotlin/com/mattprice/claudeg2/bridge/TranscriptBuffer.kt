package com.mattprice.claudeg2.bridge

/**
 * The Claude app's transcript is a lazy list: only the messages on screen exist in the tree.
 * This stitches each visible window into a longer history, so the glasses can scroll back
 * further than one phone screen, and the phone can be scrolled to fetch older messages.
 */
class TranscriptBuffer(private val cap: Int = 200) {
    private val lines = ArrayList<String>()

    val all: List<String> get() = lines

    fun clear() = lines.clear()

    /**
     * Merges the currently visible blocks. Returns true if the history changed.
     *
     * [direction] is the way the list was just scrolled on purpose: 1 toward newer messages, -1
     * toward older, 0 unknown. A window that shares nothing with the history is then placed on
     * that side of it (a jump that skipped some messages) instead of replacing the history (a
     * different session). Replacing would move everything the glasses are showing.
     */
    fun merge(visible: List<String>, direction: Int = 0): Boolean {
        if (visible.isEmpty()) return false
        val before = ArrayList(lines)

        val forward = forwardOverlap(visible)
        if (forward != null) {
            if (forward + visible.size >= lines.size) {
                // Reaches the end (scrolled down, or new output): keep what's above, take the
                // window from there on, which also updates a last message still changing.
                while (lines.size > forward) lines.removeAt(lines.size - 1)
                lines.addAll(visible)
            }
            // Otherwise the window lies inside the history (the phone scrolled back through
            // messages already seen): nothing new, and what's below it must be kept.
        } else {
            val backward = backwardOverlap(visible)
            if (backward != null) {
                // Scrolled up: the window ends where the history starts.
                lines.addAll(0, visible.subList(0, visible.size - backward))
            } else if (direction > 0 && lines.isNotEmpty()) {
                lines.addAll(visible)
            } else if (direction < 0 && lines.isNotEmpty()) {
                lines.addAll(0, visible)
            } else {
                // A different session, or a jump with no overlap.
                lines.clear()
                lines.addAll(visible)
            }
        }
        while (lines.size > cap) lines.removeAt(0)
        return lines != before
    }

    /**
     * The index in the history where [visible] starts, if the window continues the history.
     * The last history line is live (a message still streaming, or a status row like
     * "1m 46s · thinking…" that changes every second), so it may differ.
     */
    private fun forwardOverlap(visible: List<String>): Int? {
        for (start in lines.indices.reversed()) {
            if (!sameOrGrown(lines[start], visible[0], start == lines.lastIndex)) continue
            val overlap = minOf(lines.size - start, visible.size)
            val matches = (0 until overlap).all { k ->
                sameOrGrown(lines[start + k], visible[k], start + k == lines.lastIndex)
            }
            // At least one line must match exactly, or any window would "continue" the live line.
            val anchored = (0 until overlap).any { k -> lines[start + k] == visible[k] }
            if (matches && anchored) return start
        }
        return null
    }

    /** How many of [visible]'s last lines equal the history's first lines, if any do. */
    private fun backwardOverlap(visible: List<String>): Int? {
        for (overlap in minOf(visible.size, lines.size) downTo 1) {
            val tail = visible.subList(visible.size - overlap, visible.size)
            if (tail == lines.subList(0, overlap)) return overlap
        }
        return null
    }

    private fun sameOrGrown(old: String, new: String, isLast: Boolean) = old == new || isLast
}
