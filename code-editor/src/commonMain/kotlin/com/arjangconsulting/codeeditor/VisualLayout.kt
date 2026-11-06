package com.arjangconsulting.codeeditor

import kotlin.math.abs

internal const val RENDER_TILE_CHARS = 256

/** Sparse advance checkpoints keep geometry independent of native paragraph size. */
internal class LineGeometry(
    private val text: String,
    val start: Int,
    val end: Int,
    private val tabSize: Int,
    private val charWidth: Float,
    private val advance: (String) -> Float,
    val wrapWidth: Float,
    private val asciiWidths: FloatArray? = null,
    reuse: LineGeometry? = null,
) {
    private val offsets: IntArray
    private val advances: DoubleArray
    val rowStarts: IntArray
    private val rowAdvances: DoubleArray
    val width: Float
    val maxRowWidth: Float

    init {
        if (reuse != null) {
            offsets = reuse.offsets
            advances = reuse.advances
            rowStarts = reuse.rowStarts
            rowAdvances = reuse.rowAdvances
            width = reuse.width
            maxRowWidth = reuse.maxRowWidth
        } else {
            val checkpoints = ArrayList<Int>()
            val positions = ArrayList<Double>()
            val rows = ArrayList<Int>()
            val rowPositions = ArrayList<Double>()
            checkpoints.add(0)
            positions.add(0.0)
            rows.add(0)
            rowPositions.add(0.0)
            var offset = start
            var x = 0.0
            var rowX = 0.0
            var widestRow = 0.0
            var checkpoint = start
            while (offset < end) {
                val next = clusterEnd(text, offset, end)
                val step = step(offset, next, x)
                if (wrapWidth > 0f && x > rowX && x - rowX + step > wrapWidth) {
                    widestRow = maxOf(widestRow, x - rowX)
                    rows.add(offset - start)
                    rowPositions.add(x)
                    rowX = x
                }
                if (offset - checkpoint >= RENDER_TILE_CHARS) {
                    checkpoints.add(offset - start)
                    positions.add(x)
                    checkpoint = offset
                }
                x += step
                offset = next
            }
            checkpoints.add(end - start)
            positions.add(x)
            offsets = checkpoints.toIntArray()
            advances = positions.toDoubleArray()
            rowStarts = rows.toIntArray()
            rowAdvances = rowPositions.toDoubleArray()
            width = x.toFloat()
            maxRowWidth = maxOf(widestRow, x - rowX).toFloat()
        }
    }

    private fun step(offset: Int, next: Int, x: Double): Double = when {
        text[offset] == '\t' -> {
            val stop = tabSize * charWidth.toDouble()
            stop - x % stop
        }
        next == offset + 1 && text[offset] in ' '..'~' -> (asciiWidths?.get(text[offset].code - 32) ?: charWidth).toDouble()
        else -> advance(text.substring(offset, next)).toDouble()
    }

    fun xAt(offset: Int): Double {
        val target = offset.coerceIn(start, end)
        val index = floorIndex(offsets, target - start)
        var current = start + offsets[index]
        var x = advances[index]
        while (current < target) {
            val next = clusterEnd(text, current, end)
            if (next > target) break
            x += step(current, next, x)
            current = next
        }
        return x
    }

    fun offsetAt(x: Double): Int {
        val index = floorIndex(advances, x)
        var offset = start + offsets[index]
        var currentX = advances[index]
        while (offset < end) {
            val next = clusterEnd(text, offset, end)
            val nextX = currentX + step(offset, next, currentX)
            if (x < nextX) return if (abs(x - currentX) <= abs(nextX - x)) offset else next
            currentX = nextX
            offset = next
        }
        return end
    }

    fun rowOf(offset: Int): Int = floorIndex(rowStarts, offset.coerceIn(start, end) - start)

    fun rowEnd(row: Int): Int = start + rowStarts.getOrElse(row + 1) { end - start }

    fun rowX(row: Int): Double = rowAdvances[row]

    fun rowWidth(row: Int): Float = (xAt(rowEnd(row)) - rowX(row)).toFloat()

    fun matches(source: String, from: Int, to: Int): Boolean = to - from == end - start && text.regionMatches(start, source, from, to - from)

    fun tileEnd(offset: Int, end: Int): Int {
        if (text.getOrNull(offset) == '\t') return minOf(offset + 1, end)
        var result = minOf(offset + RENDER_TILE_CHARS, end)
        result = snapToCodePointBoundary(text, result)
        while (result < end && result - offset < RENDER_TILE_CHARS + 32 && isClusterContinuation(text, result)) result = nextCodePoint(text, result)
        for (index in offset until result) if (text[index] == '\t') return index
        return result.coerceAtLeast(nextCodePoint(text, offset)).coerceAtMost(end)
    }

    fun tileStart(offset: Int): Int {
        var result = snapToCodePointBoundary(text, offset)
        while (result > start && offset - result < RENDER_TILE_CHARS && isClusterContinuation(text, result)) result = previousCodePoint(text, result)
        return result
    }

    fun display(start: Int, end: Int): DisplayLine {
        val builder = StringBuilder(end - start)
        val map = IntArray(end - start + 1)
        var offset = start
        var x = xAt(start)
        while (offset < end) {
            val next = clusterEnd(text, offset, end)
            val step = step(offset, next, x)
            if (text[offset] == '\t') {
                map[offset - start] = builder.length
            } else {
                for (i in offset until next) {
                    map[i - start] = builder.length
                    builder.append(text[i])
                }
            }
            x += step
            offset = next
        }
        map[end - start] = builder.length
        return DisplayLine(builder.toString(), map)
    }
}

/** Logical source lines mapped onto visual rows without rewriting source or language ranges. */
internal class VisualLayout(
    val snapshot: DocumentSnapshot,
    private val fold: FoldLayout,
    tabSize: Int,
    charWidth: Float,
    wrapWidth: Float,
    advance: (String) -> Float,
    asciiWidths: FloatArray? = null,
    previous: VisualLayout? = null,
    placeholderWidth: Float = 0f,
) {
    private val lines = arrayOfNulls<LineGeometry>(snapshot.lineCount)
    private val rowStarts = IntArray(fold.rowCount + 1)
    var width: Float = 0f
        private set

    init {
        for (index in 0 until fold.rowCount) {
            val line = fold.lineAt(index)
            val start = snapshot.lineStart(line)
            val end = snapshot.lineEnd(line)
            val shifted = line + (previous?.snapshot?.lineCount ?: snapshot.lineCount) - snapshot.lineCount
            val folded = line in fold.collapsedRanges
            val available = if (wrapWidth > 0f && folded) (wrapWidth - placeholderWidth).coerceAtLeast(charWidth) else wrapWidth
            val reuse = previous?.lines?.getOrNull(line)?.takeIf { it.wrapWidth == available && it.matches(snapshot.text, start, end) }
                ?: previous?.lines?.getOrNull(shifted)?.takeIf { it.wrapWidth == available && it.matches(snapshot.text, start, end) }
            val geometry = LineGeometry(snapshot.text, start, end, tabSize, charWidth, advance, available, asciiWidths, reuse)
            lines[line] = geometry
            rowStarts[index + 1] = rowStarts[index] + geometry.rowStarts.size
            width = maxOf(width, (if (wrapWidth > 0) geometry.maxRowWidth else geometry.width) + if (folded) placeholderWidth else 0f)
        }
    }

    val rowCount: Int get() = rowStarts.last()
    val collapsedRanges: Map<Int, FoldingRange> get() = fold.collapsedRanges

    fun lineAt(row: Int): Int = fold.lineAt(floorIndex(rowStarts, row.coerceIn(0, rowCount - 1)))

    fun geometry(line: Int): LineGeometry = lines[line] ?: lines[fold.lineAt(fold.rowOf(line))]!!

    fun rowOf(line: Int, column: Int = 0): Int {
        val visible = fold.rowOf(line)
        val geometry = geometry(line)
        return rowStarts[visible] + if (fold.isHidden(line)) 0 else geometry.rowOf(geometry.start + column)
    }

    fun localRow(row: Int): Int = row - rowStarts[floorIndex(rowStarts, row.coerceIn(0, rowCount - 1))]

    fun start(row: Int): Int = geometry(lineAt(row)).let { it.start + it.rowStarts[localRow(row)] }

    fun end(row: Int): Int = geometry(lineAt(row)).rowEnd(localRow(row))

    fun xAt(row: Int, offset: Int): Float {
        val geometry = geometry(lineAt(row))
        return (geometry.xAt(offset.coerceIn(start(row), end(row))) - geometry.rowX(localRow(row))).toFloat()
    }

    fun offsetAt(row: Int, x: Float): Int {
        val geometry = geometry(lineAt(row))
        return geometry.offsetAt(x.toDouble() + geometry.rowX(localRow(row))).coerceIn(start(row), end(row))
    }

    fun isFirstRow(row: Int): Boolean = localRow(row) == 0

    fun isLastRow(row: Int): Boolean = end(row) == geometry(lineAt(row)).end
}

internal fun floorIndex(values: IntArray, value: Int): Int {
    var low = 0
    var high = values.size - 1
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (values[mid] <= value) low = mid else high = mid - 1
    }
    return low
}

private fun floorIndex(values: DoubleArray, value: Double): Int {
    var low = 0
    var high = values.size - 1
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (values[mid] <= value) low = mid else high = mid - 1
    }
    return low
}

private fun isClusterContinuation(text: String, offset: Int): Boolean =
    text[offset].category.let { it == CharCategory.NON_SPACING_MARK || it == CharCategory.COMBINING_SPACING_MARK || it == CharCategory.ENCLOSING_MARK } ||
        text[offset] == '\u200D' || text[offset - 1] == '\u200D' || text[offset].isLowSurrogate()

internal fun clusterEnd(text: String, start: Int, end: Int): Int {
    var next = nextCodePoint(text, start).coerceAtMost(end)
    if (text[start] in ' '..'~' && (next >= end || text[next] in ' '..'~' || text[next] == '\t')) return next
    while (next < end && next - start < RENDER_TILE_CHARS && isClusterContinuation(text, next)) next = nextCodePoint(text, next).coerceAtMost(end)
    return next
}
