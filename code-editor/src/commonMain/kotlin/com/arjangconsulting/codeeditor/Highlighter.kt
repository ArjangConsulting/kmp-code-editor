package com.arjangconsulting.codeeditor

/**
 * Incremental, lazy highlighter over [TokenRules].
 *
 * Lines are lexed on demand from the last valid line. After an edit, only lines from the first
 * changed one onward are invalidated; lines after the edit are reused as soon as a re-lexed line
 * ends in the same state the old copy started the next line with. Not thread-safe: use from one
 * thread (the UI thread in the Compose engine, the EDT in the Swing engine).
 */
internal class Highlighter(val rules: TokenRules) {
    private var snapshot: DocumentSnapshot = DocumentSnapshot("", -1)
    private var lines: Array<LexedLine?> = arrayOfNulls(1)

    /**
     * Lines `[0, validUpTo)` are lexed with correct start states. Entries beyond it are candidates
     * carried over from before the last edit and are only trusted once their start state matches.
     */
    private var validUpTo = 0

    /** How many lines were actually lexed since construction; lets tests prove incrementality. */
    var lexedLineCount: Int = 0
        private set

    val version: Long get() = snapshot.version

    fun update(next: DocumentSnapshot) {
        if (next === snapshot || (next.version == snapshot.version && next.text == snapshot.text)) return
        val old = snapshot
        val oldLines = lines
        val oldCount = old.lineCount
        val newCount = next.lineCount
        var prefix = 0
        val maxPrefix = minOf(oldCount, newCount, validUpTo)
        while (prefix < maxPrefix && sameLine(old, prefix, next, prefix)) prefix++
        var suffix = 0
        val maxSuffix = minOf(oldCount, newCount) - prefix
        while (suffix < maxSuffix && sameLine(old, oldCount - 1 - suffix, next, newCount - 1 - suffix)) suffix++
        val newLines = arrayOfNulls<LexedLine>(newCount)
        oldLines.copyInto(newLines, 0, 0, prefix)
        for (k in 0 until suffix) {
            val oldIndex = oldCount - suffix + k
            // Only lines that were valid before can become valid again by state convergence.
            if (oldIndex < validUpTo) newLines[newCount - suffix + k] = oldLines[oldIndex]
        }
        snapshot = next
        lines = newLines
        validUpTo = prefix
    }

    private fun sameLine(a: DocumentSnapshot, aLine: Int, b: DocumentSnapshot, bLine: Int): Boolean {
        val aStart = a.lineStart(aLine)
        val length = a.lineEnd(aLine) - aStart
        val bStart = b.lineStart(bLine)
        if (b.lineEnd(bLine) - bStart != length) return false
        return a.text.regionMatches(aStart, b.text, bStart, length)
    }

    /** Tokens for [line], lexing any earlier lines that are not yet valid. */
    fun line(line: Int): LexedLine {
        val target = line.coerceIn(0, lines.size - 1)
        ensureValid(target)
        return lines[target] ?: EMPTY
    }

    /** The state [line] starts in. */
    fun startState(line: Int): LexerState = line(line).startState

    private fun ensureValid(target: Int) {
        while (validUpTo <= target) {
            val index = validUpTo
            val state = if (index == 0) LexerState.Initial else lines[index - 1]?.endState ?: LexerState.Initial
            val candidate = lines[index]
            if (candidate != null && candidate.startState == state && index > 0) {
                // Converged: every following candidate was valid before the edit and starts from the
                // state its predecessor ends in, so the whole run is valid again.
                var end = index
                while (end < lines.size) {
                    val line = lines[end] ?: break
                    if (end != index && line.startState != lines[end - 1]?.endState) break
                    end++
                }
                validUpTo = end
                continue
            }
            lines[index] = rules.tokenizeLine(snapshot.line(index), state)
            lexedLineCount++
            validUpTo = index + 1
        }
    }

    private companion object {
        val EMPTY = LexedLine(emptyList(), LexerState.Initial, LexerState.Initial)
    }
}
