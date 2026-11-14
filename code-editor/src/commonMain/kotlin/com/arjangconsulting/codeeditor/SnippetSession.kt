package com.arjangconsulting.codeeditor

/** One engine's ephemeral tab-stop session; offsets follow edits inside the active placeholder. */
internal class SnippetSession {
    private var stops = emptyList<Selection>()
    private var index = 0
    private var text = ""
    private var version = -1L
    private var historyGeneration = -1

    fun start(state: CodeEditorState, stops: List<Selection>) {
        this.stops = stops
        index = 0
        text = state.text
        version = state.version
        historyGeneration = state.historyGeneration
        if (stops.size <= 1) cancel()
    }

    fun cancel(): Boolean {
        val active = stops.isNotEmpty()
        stops = emptyList()
        text = ""
        return active
    }

    fun synchronize(state: CodeEditorState): Boolean {
        if (stops.isEmpty()) return false
        if (state.historyGeneration != historyGeneration || state.selectionSet.isMulti) {
            cancel()
            return false
        }
        if (version != state.version) {
            val change = diffText(text, state.text, caretHint = stops[index].start)
            val active = stops[index]
            if (change.start < active.start || change.oldEnd > active.end) {
                cancel()
                return false
            }
            val delta = change.newText.length - (change.oldEnd - change.start)
            val updated = stops.mapIndexedNotNull { stopIndex, stop ->
                when {
                    stopIndex == index -> Selection(stop.start, stop.end + delta)
                    stop.start <= active.start && stop.end >= active.end && stop != active -> Selection(stop.start, stop.end + delta)
                    stop.start >= change.oldEnd -> Selection(stop.start + delta, stop.end + delta)
                    stop.end <= change.start -> stop
                    else -> null
                }?.let { stopIndex to it }
            }
            index = updated.indexOfFirst { it.first == index }
            stops = updated.map { it.second }
            text = state.text
            version = state.version
        }
        val active = stops.getOrNull(index)
        val selection = state.selectionInternal
        if (active == null || selection.start < active.start || selection.end > active.end) {
            cancel()
            return false
        }
        return true
    }

    fun move(state: CodeEditorState, forward: Boolean): Boolean {
        if (!synchronize(state)) return false
        val target = (index + if (forward) 1 else -1).coerceAtLeast(0)
        if (target >= stops.size) {
            cancel()
            return false
        }
        index = target
        val selection = stops[index]
        state.select(selection.start, selection.end)
        if (index == stops.lastIndex) cancel()
        return true
    }
}
