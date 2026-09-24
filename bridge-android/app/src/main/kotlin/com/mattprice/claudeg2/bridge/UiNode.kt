package com.mattprice.claudeg2.bridge

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/**
 * One node of the Claude app's accessibility tree, copied out of AccessibilityNodeInfo so the
 * parser can run (and be tested) without Android. The XML form is the same as
 * `adb shell uiautomator dump`, so a dump taken with adb works as a test fixture unchanged.
 */
data class UiNode(
    val className: String,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val packageName: String?,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val bounds: Bounds,
    val children: List<UiNode>,
    /** Position in the tree ("0.3.1"); stable while the screen's structure doesn't change. */
    val path: String = "0",
    /**
     * A scrollable list that can still scroll forward. In the Claude app's (bottom-up) list,
     * forward is toward newer messages, so false means the newest message is showing.
     */
    val canScrollForward: Boolean = false,
) {
    /** What a person reads for this node: its text, else its content description. */
    val label: String? get() = text?.trim()?.takeIf { it.isNotEmpty() } ?: desc?.trim()?.takeIf { it.isNotEmpty() }

    /** Short id for actions: path plus label, so a stale id never lands on a different control. */
    val id: String get() = (path + "|" + className + "|" + label).hashCode().toUInt().toString(36)

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (child in children) yieldAll(child.walk())
    }

    /** Every label in this subtree, in tree order. */
    fun labels(): List<String> = walk().mapNotNull { it.label }.toList()

    fun toXml(): String = StringBuilder()
        .append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">")
        .also { appendXml(it, 0) }
        .append("</hierarchy>")
        .toString()

    private fun appendXml(out: StringBuilder, index: Int) {
        out.append("<node index=\"").append(index).append('"')
        attr(out, "text", text.orEmpty())
        attr(out, "resource-id", viewId.orEmpty())
        attr(out, "class", className)
        attr(out, "package", packageName.orEmpty())
        attr(out, "content-desc", desc.orEmpty())
        attr(out, "clickable", clickable.toString())
        attr(out, "scrollable", scrollable.toString())
        attr(out, "editable", editable.toString())
        if (canScrollForward) attr(out, "scroll-forward", "true")
        attr(out, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        if (children.isEmpty()) {
            out.append(" />")
        } else {
            out.append('>')
            children.forEachIndexed { i, child -> child.appendXml(out, i) }
            out.append("</node>")
        }
    }

    companion object {
        private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        /** Parses a uiautomator-format dump. Returns the first top-level node. */
        fun fromXml(xml: String): UiNode {
            val factory = DocumentBuilderFactory.newInstance()
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val first = doc.documentElement.childElements().firstOrNull()
                ?: throw IllegalArgumentException("Dump has no nodes")
            return fromElement(first, "0")
        }

        private fun fromElement(e: Element, path: String): UiNode {
            val b = BOUNDS.find(e.getAttribute("bounds"))?.destructured
            return UiNode(
                className = e.getAttribute("class"),
                text = e.getAttribute("text").ifEmpty { null },
                desc = e.getAttribute("content-desc").ifEmpty { null },
                viewId = e.getAttribute("resource-id").ifEmpty { null },
                packageName = e.getAttribute("package").ifEmpty { null },
                clickable = e.getAttribute("clickable") == "true",
                scrollable = e.getAttribute("scrollable") == "true",
                // uiautomator has no "editable" attribute; its EditText class name is the tell.
                editable = e.getAttribute("editable") == "true" || e.getAttribute("class").endsWith("EditText"),
                bounds = b?.let { (l, t, r, bt) -> Bounds(l.toInt(), t.toInt(), r.toInt(), bt.toInt()) } ?: Bounds(0, 0, 0, 0),
                children = e.childElements().mapIndexed { i, child -> fromElement(child, "$path.$i") },
                path = path,
                canScrollForward = e.getAttribute("scroll-forward") == "true",
            )
        }

        private fun Element.childElements(): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" }

        private fun attr(out: StringBuilder, name: String, value: String) {
            out.append(' ').append(name).append("=\"")
            for (c in value) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> out.append("&quot;")
                    '\n' -> out.append("&#10;")
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }
}
