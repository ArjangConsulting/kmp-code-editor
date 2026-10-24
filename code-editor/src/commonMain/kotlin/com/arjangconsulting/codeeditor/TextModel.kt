package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A position in a document, mirroring LSP's `Position`.
 *
 * Both fields are 0-based. [character] counts UTF-16 code units from the start of the line, which
 * is what Kotlin `String` indices already are, so an emoji outside the Basic Multilingual Plane
 * occupies two characters. A [character] past the end of the line means the end of the line.
 */
@Immutable
@Serializable
public data class TextPosition(val line: Int, val character: Int) : Comparable<TextPosition> {
    init {
        require(line >= 0) { "line must be >= 0, was $line" }
        require(character >= 0) { "character must be >= 0, was $character" }
    }

    override fun compareTo(other: TextPosition): Int =
        if (line != other.line) line.compareTo(other.line) else character.compareTo(other.character)

    public companion object {
        /** The start of the document. */
        public val Zero: TextPosition = TextPosition(0, 0)
    }
}

/**
 * A range between two [TextPosition]s, mirroring LSP's `Range`. [end] is exclusive.
 *
 * The constructor normalizes the order, so `start <= end` always holds. Note that this is not
 * `androidx.compose.ui.text.TextRange`, which is offset based; import one of them with an alias
 * when a file needs both.
 */
@Immutable
@Serializable(with = TextRangeSerializer::class)
public class TextRange(start: TextPosition, end: TextPosition) {
    /** The inclusive start. */
    public val start: TextPosition = minOf(start, end)

    /** The exclusive end. */
    public val end: TextPosition = maxOf(start, end)

    /** True when the range selects no text. */
    public val isEmpty: Boolean get() = start == end

    /** True when [position] lies within the range, treating [end] as exclusive unless empty. */
    public operator fun contains(position: TextPosition): Boolean =
        position >= start && (position < end || (isEmpty && position == end))

    /** True when this range and [other] share at least one character, or touch when either is empty. */
    public fun intersects(other: TextRange): Boolean =
        if (isEmpty || other.isEmpty) start <= other.end && other.start <= end else start < other.end && other.start < end

    override fun equals(other: Any?): Boolean = other is TextRange && start == other.start && end == other.end

    override fun hashCode(): Int = 31 * start.hashCode() + end.hashCode()

    override fun toString(): String = "TextRange($start, $end)"

    /** Returns a copy with the given bounds. */
    public fun copy(start: TextPosition = this.start, end: TextPosition = this.end): TextRange = TextRange(start, end)

    public companion object {
        /** An empty range at [position]. */
        public fun at(position: TextPosition): TextRange = TextRange(position, position)

        /** The range covering whole lines [firstLine]..[lastLine] including the last line's content. */
        public fun lines(firstLine: Int, lastLine: Int, lastLineLength: Int): TextRange =
            TextRange(TextPosition(firstLine, 0), TextPosition(lastLine, lastLineLength))
    }
}

/**
 * A replacement of [range] with [newText], mirroring LSP's `TextEdit`. An empty [range] inserts and
 * an empty [newText] deletes.
 */
@Immutable
@Serializable
public data class TextEdit(val range: TextRange, val newText: String) {
    public companion object {
        /** Inserts [text] at [position]. */
        public fun insert(position: TextPosition, text: String): TextEdit = TextEdit(TextRange.at(position), text)

        /** Deletes [range]. */
        public fun delete(range: TextRange): TextEdit = TextEdit(range, "")
    }
}

/**
 * An immutable view of a document at one [version], as handed to a [LanguageService].
 *
 * [version] increases monotonically with every change made through a [CodeEditorState], so a service
 * (or the editor) can tell whether a result still applies. Line splitting follows LSP: `\n`, `\r\n`,
 * and `\r` all end a line. The line index is built lazily on first use and then cached.
 */
public class DocumentSnapshot(public val text: String, public val version: Long) {
    private val index: LineIndex by lazy(LazyThreadSafetyMode.PUBLICATION) { LineIndex(text) }

    /** Number of lines; an empty document has one empty line. */
    public val lineCount: Int get() = index.lineCount

    /** The text of [line] without its terminator. */
    public fun line(line: Int): String = index.lineText(text, line)

    /** Offset of the first character of [line]. */
    public fun lineStart(line: Int): Int = index.lineStart(line)

    /** Offset just past the last character of [line], before its terminator. */
    public fun lineEnd(line: Int): Int = index.lineEnd(line)

    /** Length of [line] in UTF-16 code units, excluding its terminator. */
    public fun lineLength(line: Int): Int = index.lineEnd(line) - index.lineStart(line)

    /** Converts [position] to a string offset, clamping out-of-range lines and characters like LSP. */
    public fun offsetOf(position: TextPosition): Int = index.offsetOf(position)

    /** Converts a string [offset] (clamped to the text) to a position. */
    public fun positionOf(offset: Int): TextPosition = index.positionOf(offset)

    /** The text inside [range]. */
    public fun textIn(range: TextRange): String = text.substring(offsetOf(range.start), offsetOf(range.end))

    /** The position just past the last character. */
    public val endPosition: TextPosition get() = positionOf(text.length)

    /** The range covering the whole document. */
    public val fullRange: TextRange get() = TextRange(TextPosition.Zero, endPosition)

    override fun toString(): String = "DocumentSnapshot(version=$version, length=${text.length})"
}

/**
 * Line start offsets of a text. Pure and allocation-light; rebuilt per document version.
 */
internal class LineIndex(text: String) {
    private val starts: IntArray
    private val ends: IntArray
    private val length: Int = text.length

    init {
        val startList = ArrayList<Int>()
        val endList = ArrayList<Int>()
        startList.add(0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                endList.add(i)
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                startList.add(i + 1)
            }
            i++
        }
        endList.add(text.length)
        starts = startList.toIntArray()
        ends = endList.toIntArray()
    }

    val lineCount: Int get() = starts.size

    fun lineStart(line: Int): Int = starts[line.coerceIn(0, starts.size - 1)]

    fun lineEnd(line: Int): Int = ends[line.coerceIn(0, ends.size - 1)]

    fun lineText(text: String, line: Int): String = text.substring(lineStart(line), lineEnd(line))

    fun offsetOf(position: TextPosition): Int {
        if (position.line >= starts.size) return length
        val start = starts[position.line]
        return start + position.character.coerceAtMost(ends[position.line] - start)
    }

    fun positionOf(offset: Int): TextPosition {
        val clamped = offset.coerceIn(0, length)
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= clamped) low = mid else high = mid - 1
        }
        // An offset inside a "\r\n" terminator belongs to the end of the line.
        return TextPosition(low, (clamped - starts[low]).coerceAtMost(ends[low] - starts[low]))
    }
}

/**
 * Applies LSP-style [edits] to [text] and returns the result.
 *
 * Every edit's range refers to the *original* text, as in LSP. Edits must not overlap; inserts at
 * the same position are applied in list order. Throws [IllegalArgumentException] for overlapping
 * edits so a buggy service fails loudly instead of corrupting the document.
 */
public fun applyTextEdits(text: String, edits: List<TextEdit>): String = applyTextEditsWithMapping(text, edits).text

/** The result of [applyTextEditsWithMapping]. */
internal class AppliedEdits(val text: String, private val resolved: List<ResolvedEdit>) {
    /**
     * Maps an offset in the original text to the new text. Offsets inside a replaced range move to
     * the end of its replacement.
     */
    fun mapOffset(offset: Int): Int {
        var delta = 0
        for (edit in resolved) {
            if (edit.end <= offset && !(edit.start == edit.end && edit.start == offset)) {
                delta += edit.newText.length - (edit.end - edit.start)
            } else if (edit.start <= offset && offset < edit.end) {
                return edit.start + delta + edit.newText.length
            } else if (edit.start > offset) {
                break
            }
        }
        return offset + delta
    }

    /** End offset, in the new text, of the replacement made by [edit]. */
    fun endOf(edit: TextEdit): Int {
        var delta = 0
        for (r in resolved) {
            if (r.source === edit) return r.start + delta + r.newText.length
            delta += r.newText.length - (r.end - r.start)
        }
        return text.length
    }
}

internal class ResolvedEdit(val start: Int, val end: Int, val newText: String, val order: Int, val source: TextEdit)

internal fun applyTextEditsWithMapping(text: String, edits: List<TextEdit>): AppliedEdits {
    if (edits.isEmpty()) return AppliedEdits(text, emptyList())
    val index = LineIndex(text)
    val resolved = edits.mapIndexed { i, edit ->
        ResolvedEdit(index.offsetOf(edit.range.start), index.offsetOf(edit.range.end), edit.newText, i, edit)
    }.sortedWith(compareBy<ResolvedEdit>({ it.start }, { it.end }, { it.order }))
    for (i in 1 until resolved.size) {
        val previous = resolved[i - 1]
        val current = resolved[i]
        require(current.start >= previous.end) { "Overlapping text edits at offsets ${previous.start}..${current.end}" }
    }
    val builder = StringBuilder(text.length + resolved.sumOf { it.newText.length })
    var cursor = 0
    for (edit in resolved) {
        builder.append(text, cursor, edit.start)
        builder.append(edit.newText)
        cursor = edit.end
    }
    builder.append(text, cursor, text.length)
    return AppliedEdits(builder.toString(), resolved)
}

/** Moves [offset] off the low half of a surrogate pair so a caret never splits a code point. */
internal fun snapToCodePointBoundary(text: CharSequence, offset: Int, forward: Boolean = false): Int {
    if (offset <= 0 || offset >= text.length) return offset.coerceIn(0, text.length)
    return if (text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) {
        if (forward) offset + 1 else offset - 1
    } else {
        offset
    }
}

/** Offset of the previous code point boundary before [offset]. */
internal fun previousCodePoint(text: CharSequence, offset: Int): Int {
    if (offset <= 0) return 0
    val candidate = offset - 1
    return if (candidate > 0 && text[candidate].isLowSurrogate() && text[candidate - 1].isHighSurrogate()) {
        candidate - 1
    } else {
        candidate
    }
}

/** Offset of the next code point boundary after [offset]. */
internal fun nextCodePoint(text: CharSequence, offset: Int): Int {
    if (offset >= text.length) return text.length
    return if (text[offset].isHighSurrogate() && offset + 1 < text.length && text[offset + 1].isLowSurrogate()) {
        offset + 2
    } else {
        offset + 1
    }
}

/** A minimal single replacement turning [old] into [new]: common prefix and suffix are kept. */
internal data class TextDiff(val start: Int, val oldEnd: Int, val newText: String) {
    val isNoOp: Boolean get() = start == oldEnd && newText.isEmpty()
}

internal fun diffText(old: CharSequence, new: CharSequence, caretHint: Int = -1): TextDiff {
    val minLength = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < minLength && old[prefix] == new[prefix]) prefix++
    // When a hint is given, don't let the prefix extend past it: typing "a" after "a" should insert
    // at the caret, not at the earliest equivalent position.
    if (caretHint in 0 until prefix) prefix = caretHint
    var suffix = 0
    while (suffix < minLength - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return TextDiff(prefix, old.length - suffix, new.subSequence(prefix, new.length - suffix).toString())
}
