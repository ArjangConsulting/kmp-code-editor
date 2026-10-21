package com.arjangconsulting.codeeditor

/** An offset-based selection. [anchor] stays put when extending; [caret] moves. */
internal data class Selection(val anchor: Int, val caret: Int) {
    constructor(offset: Int) : this(offset, offset)

    val start: Int get() = minOf(anchor, caret)
    val end: Int get() = maxOf(anchor, caret)
    val collapsed: Boolean get() = anchor == caret

    fun coerceIn(length: Int): Selection = Selection(anchor.coerceIn(0, length), caret.coerceIn(0, length))
}

/** A replacement in original-text coordinates. */
internal data class Replacement(val start: Int, val end: Int, val text: String)

/** The outcome of an editing command: replacements plus the selection after applying them. */
internal data class EditOutcome(
    val replacements: List<Replacement>,
    val selection: Selection,
    val kind: EditKind = EditKind.OTHER,
)

/** How an edit coalesces in the undo history. */
internal enum class EditKind { TYPING, DELETION, OTHER }

/** Token kinds whose characters are not code, so brackets inside them are ignored. */
internal fun TokenKind.isCode(): Boolean =
    this != TokenKind.STRING && this != TokenKind.COMMENT && this != TokenKind.ESCAPE && this != TokenKind.PROPERTY

/** Looks up the token kind at a document offset. */
internal fun interface TokenLookup {
    fun kindAt(offset: Int): TokenKind

    companion object {
        val AllCode: TokenLookup = TokenLookup { TokenKind.PLAIN }

        /** Lexes [snapshot] with [rules] on demand. */
        fun of(snapshot: DocumentSnapshot, highlighter: Highlighter): TokenLookup = TokenLookup { offset ->
            val position = snapshot.positionOf(offset)
            highlighter.line(position.line).kindAt(position.character)
        }

        fun of(snapshot: DocumentSnapshot, rules: TokenRules): TokenLookup =
            of(snapshot, Highlighter(rules).also { it.update(snapshot) })
    }}

/** A matched bracket pair: offsets of the opening and closing characters. */
internal data class BracketPair(val open: Int, val close: Int)

private const val BRACKET_SCAN_LIMIT = 200_000

/**
 * Finds the bracket pair touching [caret]: a bracket just before the caret wins over one just
 * after it. Brackets inside strings and comments are ignored. Returns null when unmatched.
 */
internal fun findMatchingBracket(text: String, caret: Int, rules: TokenRules, tokens: TokenLookup): BracketPair? {
    fun tryAt(offset: Int): BracketPair? {
        if (offset !in text.indices) return null
        val c = text[offset]
        val pair = rules.brackets.firstOrNull { it.first == c || it.second == c } ?: return null
        if (!tokens.kindAt(offset).isCode()) return null
        return if (c == pair.first) {
            scanForward(text, offset, pair, tokens)?.let { BracketPair(offset, it) }
        } else {
            scanBackward(text, offset, pair, tokens)?.let { BracketPair(it, offset) }
        }
    }
    return tryAt(caret - 1) ?: tryAt(caret)
}

private fun scanForward(text: String, from: Int, pair: Pair<Char, Char>, tokens: TokenLookup): Int? {
    var depth = 0
    val limit = minOf(text.length, from + BRACKET_SCAN_LIMIT)
    for (i in from until limit) {
        val c = text[i]
        if (c != pair.first && c != pair.second) continue
        if (!tokens.kindAt(i).isCode()) continue
        if (c == pair.first) depth++ else depth--
        if (depth == 0) return i
    }
    return null
}

private fun scanBackward(text: String, from: Int, pair: Pair<Char, Char>, tokens: TokenLookup): Int? {
    var depth = 0
    val limit = maxOf(-1, from - BRACKET_SCAN_LIMIT)
    for (i in from downTo limit + 1) {
        val c = text[i]
        if (c != pair.first && c != pair.second) continue
        if (!tokens.kindAt(i).isCode()) continue
        if (c == pair.second) depth++ else depth--
        if (depth == 0) return i
    }
    return null
}

/**
 * Computes folding ranges from code brackets spanning lines and from multi-line block comments.
 * A closing bracket that starts its line stays visible (VS Code style). At most one range is kept
 * per start line, the largest. The result is sorted by start line.
 */
internal fun computeFoldingRanges(snapshot: DocumentSnapshot, rules: TokenRules, highlighter: Highlighter): List<FoldingRange> {
    val byStart = HashMap<Int, FoldingRange>()
    fun offer(range: FoldingRange) {
        val existing = byStart[range.startLine]
        if (existing == null || range.endLine > existing.endLine) byStart[range.startLine] = range
    }
    val openers = rules.brackets.associate { it.first to it.second }
    val closers = rules.brackets.associate { it.second to it.first }
    val stack = ArrayList<Pair<Char, Int>>()
    var commentStart = -1
    for (lineIndex in 0 until snapshot.lineCount) {
        val lexed = highlighter.line(lineIndex)
        val line = snapshot.line(lineIndex)
        for (token in lexed.tokens) {
            if (token.kind != TokenKind.PUNCTUATION || token.end - token.start != 1) continue
            val c = line[token.start]
            if (c in openers) {
                stack += c to lineIndex
            } else if (c in closers) {
                val open = closers.getValue(c)
                val matchIndex = stack.indexOfLast { it.first == open }
                if (matchIndex == -1) continue
                val startLine = stack[matchIndex].second
                while (stack.size > matchIndex) stack.removeAt(stack.size - 1)
                val closesLine = line.substring(0, token.start).isBlank()
                val endLine = if (closesLine) lineIndex - 1 else lineIndex
                if (endLine > startLine) offer(FoldingRange(startLine, endLine))
            }
        }
        val startsInComment = lexed.startState != LexerState.Initial && lexed.tokens.firstOrNull()?.let {
            it.start == 0 && it.kind == TokenKind.COMMENT
        } == true
        val endsInComment = lexed.endState != LexerState.Initial && lexed.tokens.lastOrNull()?.kind == TokenKind.COMMENT
        if (!startsInComment && endsInComment) commentStart = lineIndex
        if (startsInComment && !endsInComment && commentStart >= 0) {
            if (lineIndex > commentStart) offer(FoldingRange(commentStart, lineIndex, FoldingRangeKind.COMMENT))
            commentStart = -1
        }
    }
    return byStart.values.sortedBy { it.startLine }
}

/** Visible lines given [ranges] and the set of collapsed start lines. */
internal class FoldLayout(lineCount: Int, ranges: List<FoldingRange>, collapsed: Set<Int>) {
    /** Visible document lines in order; index = visual row. */
    val visibleLines: IntArray

    /** Collapsed ranges actually applied, keyed by start line. */
    val collapsedRanges: Map<Int, FoldingRange>

    init {
        val applied = ranges.filter { it.startLine in collapsed && it.endLine < lineCount }
        val hidden = BooleanArray(lineCount)
        val appliedMap = HashMap<Int, FoldingRange>()
        for (range in applied.sortedBy { it.startLine }) {
            // A fold whose header is itself hidden by an outer fold is applied, but not shown.
            if (!hidden[range.startLine]) appliedMap[range.startLine] = range
            for (line in range.startLine + 1..range.endLine) hidden[line] = true
        }
        visibleLines = (0 until lineCount).filter { !hidden[it] }.toIntArray()
        collapsedRanges = appliedMap
    }

    val rowCount: Int get() = visibleLines.size

    fun lineAt(row: Int): Int = visibleLines[row.coerceIn(0, visibleLines.size - 1)]

    /** Visual row of [line]; a hidden line maps to the row of the fold header that hides it. */
    fun rowOf(line: Int): Int {
        var low = 0
        var high = visibleLines.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (visibleLines[mid] <= line) low = mid else high = mid - 1
        }
        return low
    }

    fun isHidden(line: Int): Boolean = visibleLines.getOrNull(rowOf(line)) != line

    companion object {
        fun unfolded(lineCount: Int): FoldLayout = FoldLayout(lineCount, emptyList(), emptySet())
    }
}

internal fun leadingWhitespace(line: String): String = line.substring(0, line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it == -1) line.length else it })

/** Inserts a line break at [selection] with auto-indent; splits `{|}` into three lines. */
internal fun newlineOutcome(snapshot: DocumentSnapshot, selection: Selection, rules: TokenRules, tokens: TokenLookup, autoIndent: Boolean): EditOutcome {
    val text = snapshot.text
    val start = selection.start
    val end = selection.end
    if (!autoIndent) return EditOutcome(listOf(Replacement(start, end, "\n")), Selection(start + 1), EditKind.OTHER)
    val position = snapshot.positionOf(start)
    val lineStart = snapshot.lineStart(position.line)
    val before = text.substring(lineStart, start)
    val endPosition = snapshot.positionOf(end)
    val after = text.substring(end, snapshot.lineEnd(endPosition.line))
    val base = leadingWhitespace(before)
    val lastCode = before.trimEnd()
    val opener = lastCode.lastOrNull()
    val pair = rules.brackets.firstOrNull { it.first == opener }
    val openerIsCode = opener != null && tokens.kindAt(lineStart + lastCode.length - 1).isCode()
    // Drop whitespace before the caret so the old line doesn't keep trailing blanks.
    val trimStart = lineStart + before.trimEnd().length.coerceAtLeast(0)
    val replaceStart = if (before.isBlank()) start else trimStart
    return if (pair != null && openerIsCode) {
        val inner = base + rules.indentUnit
        val trimmedAfter = after.trimStart()
        if (trimmedAfter.startsWith(pair.second)) {
            val inserted = "\n" + inner + "\n" + base
            val afterWhitespace = after.length - trimmedAfter.length
            EditOutcome(
                listOf(Replacement(replaceStart, end + afterWhitespace, inserted)),
                Selection(replaceStart + 1 + inner.length),
                EditKind.OTHER,
            )
        } else {
            EditOutcome(listOf(Replacement(replaceStart, end, "\n" + inner)), Selection(replaceStart + 1 + inner.length), EditKind.OTHER)
        }
    } else {
        val indent = if (before.isBlank()) before else base
        EditOutcome(listOf(Replacement(replaceStart, end, "\n" + indent)), Selection(replaceStart + 1 + indent.length), EditKind.OTHER)
    }
}

/**
 * Types [c] at [selection]: wraps a selection in a pair, auto-closes pairs, overtypes an
 * auto-closed character, and re-indents a closing bracket typed on a blank line.
 */
internal fun typeCharacterOutcome(
    snapshot: DocumentSnapshot,
    selection: Selection,
    c: Char,
    rules: TokenRules,
    tokens: TokenLookup,
    autoClose: Boolean,
): EditOutcome {
    val text = snapshot.text
    val start = selection.start
    val end = selection.end
    val typed = EditOutcome(listOf(Replacement(start, end, c.toString())), Selection(start + 1), EditKind.TYPING)
    if (!autoClose) return typed
    val pair = rules.autoClosingPairs.firstOrNull { it.first == c }
    val closingPair = rules.autoClosingPairs.firstOrNull { it.second == c }
    if (!selection.collapsed && pair != null) {
        val wrapped = c + text.substring(start, end) + pair.second
        return EditOutcome(listOf(Replacement(start, end, wrapped)), Selection(start + 1, end + 1), EditKind.OTHER)
    }
    val next = text.getOrNull(end)
    val inCode = start == 0 || tokens.kindAt(start - 1).isCode() || isAtStringEnd(text, start, tokens)
    if (selection.collapsed && closingPair != null && next == c) {
        // Overtype: typing the closing character that is already there just moves past it.
        val isQuote = closingPair.first == closingPair.second
        if (!isQuote || tokens.kindAt(start).let { it == TokenKind.STRING || it == TokenKind.PROPERTY }) {
            return EditOutcome(emptyList(), Selection(start + 1), EditKind.TYPING)
        }
    }
    if (pair != null && selection.collapsed && inCode) {
        val isQuote = pair.first == pair.second
        val nextAllows = next == null || next.isWhitespace() || next in ",;:" || rules.brackets.any { it.second == next }
        val previous = text.getOrNull(start - 1)
        val previousAllows = !isQuote || previous == null || (!rules.isIdentifierPart(previous) && previous != c)
        if (nextAllows && previousAllows) {
            return EditOutcome(listOf(Replacement(start, end, "${pair.first}${pair.second}")), Selection(start + 1), EditKind.TYPING)
        }
    }
    val bracket = rules.brackets.firstOrNull { it.second == c }
    if (bracket != null && selection.collapsed) {
        val position = snapshot.positionOf(start)
        val lineStart = snapshot.lineStart(position.line)
        val before = text.substring(lineStart, start)
        if (before.isNotEmpty() && before.isBlank()) {
            val open = matchingOpenBefore(text, start, bracket, tokens)
            if (open != null) {
                val openLine = snapshot.positionOf(open).line
                val indent = leadingWhitespace(snapshot.line(openLine))
                return EditOutcome(listOf(Replacement(lineStart, start, indent + c)), Selection(lineStart + indent.length + 1), EditKind.OTHER)
            }
        }
    }
    return typed
}

private fun isAtStringEnd(text: String, offset: Int, tokens: TokenLookup): Boolean =
    offset > 0 && tokens.kindAt(offset - 1) == TokenKind.STRING && tokens.kindAt(offset).isCode() && text[offset - 1] in "\"'`"

private fun matchingOpenBefore(text: String, offset: Int, pair: Pair<Char, Char>, tokens: TokenLookup): Int? {
    var depth = 1
    val limit = maxOf(0, offset - BRACKET_SCAN_LIMIT)
    for (i in offset - 1 downTo limit) {
        val c = text[i]
        if (c != pair.first && c != pair.second) continue
        if (!tokens.kindAt(i).isCode()) continue
        if (c == pair.second) depth++ else depth--
        if (depth == 0) return i
    }
    return null
}

/** Backspace: deletes a selection, an empty auto-closed pair, an indent step, or one code point. */
internal fun backspaceOutcome(snapshot: DocumentSnapshot, selection: Selection, rules: TokenRules): EditOutcome? {
    val text = snapshot.text
    if (!selection.collapsed) {
        return EditOutcome(listOf(Replacement(selection.start, selection.end, "")), Selection(selection.start), EditKind.DELETION)
    }
    val caret = selection.caret
    if (caret == 0) return null
    val previous = text[caret - 1]
    val next = text.getOrNull(caret)
    if (rules.autoClosingPairs.any { it.first == previous && it.second == next }) {
        return EditOutcome(listOf(Replacement(caret - 1, caret + 1, "")), Selection(caret - 1), EditKind.DELETION)
    }
    val position = snapshot.positionOf(caret)
    val lineStart = snapshot.lineStart(position.line)
    val before = text.substring(lineStart, caret)
    val unit = rules.indentUnit
    if (before.isNotEmpty() && before.all { it == ' ' } && unit.isNotEmpty() && unit.all { it == ' ' }) {
        val remove = ((before.length - 1) % unit.length) + 1
        return EditOutcome(listOf(Replacement(caret - remove, caret, "")), Selection(caret - remove), EditKind.DELETION)
    }
    val start = previousCodePoint(text, caret)
    // "\r\n" is one line break: delete both characters.
    val adjusted = if (start > 0 && text[start] == '\n' && text[start - 1] == '\r') start - 1 else start
    return EditOutcome(listOf(Replacement(adjusted, caret, "")), Selection(adjusted), EditKind.DELETION)
}

/** Forward delete: deletes a selection or the next code point. */
internal fun deleteForwardOutcome(text: String, selection: Selection): EditOutcome? {
    if (!selection.collapsed) {
        return EditOutcome(listOf(Replacement(selection.start, selection.end, "")), Selection(selection.start), EditKind.DELETION)
    }
    if (selection.caret >= text.length) return null
    var end = nextCodePoint(text, selection.caret)
    if (text[selection.caret] == '\r' && text.getOrNull(end) == '\n') end++
    return EditOutcome(listOf(Replacement(selection.caret, end, "")), selection, EditKind.DELETION)
}

/** Lines touched by [selection]; a selection ending at column 0 doesn't include that line. */
internal fun selectedLines(snapshot: DocumentSnapshot, selection: Selection): IntRange {
    val first = snapshot.positionOf(selection.start).line
    val endPosition = snapshot.positionOf(selection.end)
    val last = if (endPosition.line > first && endPosition.character == 0) endPosition.line - 1 else endPosition.line
    return first..last
}

/** Tab: indents selected lines, or inserts spaces to the next indent stop. */
internal fun indentOutcome(snapshot: DocumentSnapshot, selection: Selection, rules: TokenRules): EditOutcome {
    val unit = rules.indentUnit
    val lines = selectedLines(snapshot, selection)
    if (selection.collapsed || lines.first == lines.last && !spansWholeLine(snapshot, selection)) {
        val column = selection.start - snapshot.lineStart(snapshot.positionOf(selection.start).line)
        val insert = if (unit.all { it == ' ' } && unit.isNotEmpty()) " ".repeat(unit.length - column % unit.length) else unit
        return EditOutcome(listOf(Replacement(selection.start, selection.end, insert)), Selection(selection.start + insert.length), EditKind.OTHER)
    }
    val replacements = lines.filter { snapshot.lineLength(it) > 0 }.map {
        val start = snapshot.lineStart(it)
        Replacement(start, start, unit)
    }
    return EditOutcome(replacements, shiftSelection(selection, replacements, keepLineStart = true), EditKind.OTHER)
}

private fun spansWholeLine(snapshot: DocumentSnapshot, selection: Selection): Boolean {
    val line = snapshot.positionOf(selection.start).line
    return selection.start == snapshot.lineStart(line) && selection.end >= snapshot.lineEnd(line) && snapshot.lineLength(line) > 0
}

/** Shift+Tab: removes one indent level from each selected line. */
internal fun outdentOutcome(snapshot: DocumentSnapshot, selection: Selection, rules: TokenRules): EditOutcome? {
    val unit = rules.indentUnit
    val replacements = selectedLines(snapshot, selection).mapNotNull {
        val line = snapshot.line(it)
        val start = snapshot.lineStart(it)
        val remove = when {
            line.startsWith('\t') -> 1
            else -> line.takeWhile { c -> c == ' ' }.length.coerceAtMost(unit.length.coerceAtLeast(1))
        }
        if (remove == 0) null else Replacement(start, start + remove, "")
    }
    if (replacements.isEmpty()) return null
    return EditOutcome(replacements, shiftSelection(selection, replacements, keepLineStart = true), EditKind.OTHER)
}

/** Toggles [TokenRules.lineComment] on the selected lines. */
internal fun toggleLineCommentOutcome(snapshot: DocumentSnapshot, selection: Selection, rules: TokenRules): EditOutcome? {
    val prefix = rules.lineComment ?: return null
    val lines = selectedLines(snapshot, selection).filter { snapshot.line(it).isNotBlank() }
    if (lines.isEmpty()) return null
    val allCommented = lines.all { snapshot.line(it).trimStart().startsWith(prefix) }
    val replacements = if (allCommented) {
        lines.map {
            val line = snapshot.line(it)
            val column = line.indexOf(prefix)
            val removeSpace = line.getOrNull(column + prefix.length) == ' '
            val start = snapshot.lineStart(it) + column
            Replacement(start, start + prefix.length + if (removeSpace) 1 else 0, "")
        }
    } else {
        val column = lines.minOf { leadingWhitespace(snapshot.line(it)).length }
        lines.map {
            val start = snapshot.lineStart(it) + column
            Replacement(start, start, "$prefix ")
        }
    }
    return EditOutcome(replacements, shiftSelection(selection, replacements, keepLineStart = true), EditKind.OTHER)
}

/** Maps a selection through replacements given in original coordinates. */
internal fun shiftSelection(selection: Selection, replacements: List<Replacement>, keepLineStart: Boolean = false): Selection {
    val sorted = replacements.sortedBy { it.start }
    // Line-based commands keep a selection that starts at a line start anchored there, so the
    // whole lines stay selected after indenting or commenting.
    val sticky = if (keepLineStart && !selection.collapsed) selection.start else -1
    fun map(offset: Int): Int {
        var delta = 0
        for (r in sorted) {
            when {
                offset == sticky && r.start == offset && r.end == offset -> break
                // Ranges ending at or before the offset (including inserts exactly at it) shift it.
                r.end <= offset -> delta += r.text.length - (r.end - r.start)
                // An offset inside a replaced range clamps into the replacement.
                r.start < offset -> return r.start + delta + r.text.length.coerceAtMost(offset - r.start)
                else -> break
            }
        }
        return offset + delta
    }
    return Selection(map(selection.anchor), map(selection.caret))
}

internal enum class CharClass { WORD, SPACE, NEWLINE, OTHER }

internal fun charClass(c: Char, rules: TokenRules): CharClass = when {
    c == '\n' || c == '\r' -> CharClass.NEWLINE
    c.isWhitespace() -> CharClass.SPACE
    rules.isIdentifierPart(c) -> CharClass.WORD
    else -> CharClass.OTHER}

/** The offset Alt/Ctrl+Right moves to: past whitespace, then past one run of the same class. */
internal fun nextWordBoundary(text: String, offset: Int, rules: TokenRules): Int {
    var i = offset
    if (i >= text.length) return text.length
    if (charClass(text[i], rules) == CharClass.NEWLINE) return nextCodePoint(text, i).let { if (text[i] == '\r' && text.getOrNull(it) == '\n') it + 1 else it }
    while (i < text.length && charClass(text[i], rules) == CharClass.SPACE) i++
    if (i >= text.length || charClass(text[i], rules) == CharClass.NEWLINE) return i
    val cls = charClass(text[i], rules)
    while (i < text.length && charClass(text[i], rules) == cls) i++
    return i
}

/** The offset Alt/Ctrl+Left moves to. */
internal fun previousWordBoundary(text: String, offset: Int, rules: TokenRules): Int {
    var i = offset
    if (i <= 0) return 0
    if (charClass(text[i - 1], rules) == CharClass.NEWLINE) return if (text[i - 1] == '\n' && text.getOrNull(i - 2) == '\r') i - 2 else i - 1
    while (i > 0 && charClass(text[i - 1], rules) == CharClass.SPACE) i--
    if (i <= 0 || charClass(text[i - 1], rules) == CharClass.NEWLINE) return i
    val cls = charClass(text[i - 1], rules)
    while (i > 0 && charClass(text[i - 1], rules) == cls) i--
    return i
}

/** The identifier around [offset] as a range, or an empty range at [offset]. */
internal fun wordRangeAt(text: String, offset: Int, rules: TokenRules): IntRange {
    var start = offset.coerceIn(0, text.length)
    var end = start
    while (start > 0 && rules.isIdentifierPart(text[start - 1])) start--
    while (end < text.length && rules.isIdentifierPart(text[end])) end++
    return start until end
}

/** Start of the identifier prefix that ends at [caret] (the completion anchor). */
internal fun completionPrefixStart(text: String, caret: Int, rules: TokenRules): Int {
    var start = caret.coerceIn(0, text.length)
    while (start > 0 && rules.isIdentifierPart(text[start - 1])) start--
    return start
}

/** Smart Home: first non-blank column, or column 0 when already there. */
internal fun smartHome(snapshot: DocumentSnapshot, caret: Int): Int {
    val line = snapshot.positionOf(caret).line
    val start = snapshot.lineStart(line)
    val firstNonBlank = start + leadingWhitespace(snapshot.line(line)).length
    return if (caret == firstNonBlank) start else firstNonBlank
}

/** Offsets of every occurrence of [query] in [text], up to [limit] matches. */
internal fun findMatches(text: String, query: String, caseSensitive: Boolean, limit: Int = 10_000): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<IntRange>()
    var index = text.indexOf(query, 0, ignoreCase = !caseSensitive)
    while (index >= 0 && result.size < limit) {
        result += index until index + query.length
        index = text.indexOf(query, index + maxOf(1, query.length), ignoreCase = !caseSensitive)
    }
    return result
}

/** A parsed LSP snippet: plain [text] and the selection (relative to the text) of the first tab stop. */
internal data class ParsedSnippet(val text: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * Parses the supported snippet subset: `$n`, `${n}`, `${n:placeholder}` (placeholders may nest),
 * `$0`, and `\$`, `\}`, `\\` escapes. The selection covers tab stop 1 if present, else lands on `$0`,
 * else at the end.
 */
internal fun parseSnippet(snippet: String): ParsedSnippet {
    val out = StringBuilder()
    val stops = HashMap<Int, IntRange>()
    fun parse(from: Int, untilBrace: Boolean): Int {
        var i = from
        while (i < snippet.length) {
            val c = snippet[i]
            when {
                c == '\\' && i + 1 < snippet.length && snippet[i + 1] in "\$}\\" -> {
                    out.append(snippet[i + 1])
                    i += 2
                }
                untilBrace && c == '}' -> return i + 1
                c == '$' && i + 1 < snippet.length && snippet[i + 1].isDigit() -> {
                    var j = i + 1
                    while (j < snippet.length && snippet[j].isDigit()) j++
                    val n = snippet.substring(i + 1, j).toInt()
                    stops.getOrPut(n) { out.length until out.length }
                    i = j
                }
                c == '$' && i + 2 < snippet.length && snippet[i + 1] == '{' && snippet[i + 2].isDigit() -> {
                    var j = i + 2
                    while (j < snippet.length && snippet[j].isDigit()) j++
                    val n = snippet.substring(i + 2, j).toInt()
                    val start = out.length
                    i = if (j < snippet.length && snippet[j] == ':') parse(j + 1, untilBrace = true) else if (j < snippet.length && snippet[j] == '}') j + 1 else j
                    if (n !in stops) stops[n] = start until out.length
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return i
    }
    parse(0, untilBrace = false)
    val first = stops.keys.filter { it > 0 }.minOrNull()
    val range = first?.let { stops.getValue(it) } ?: stops[0] ?: (out.length until out.length)
    return ParsedSnippet(out.toString(), range.first, range.last + 1)
}
