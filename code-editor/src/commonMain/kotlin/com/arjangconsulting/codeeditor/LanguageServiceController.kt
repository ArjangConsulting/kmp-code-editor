package com.arjangconsulting.codeeditor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** An open completion popup. Immutable; the controller publishes a new instance on every change. */
internal data class CompletionSession(
    /** Offset where the identifier prefix starts; the default replacement range is `[anchor, caret)`. */
    val anchor: Int,
    /** Position the last request was made at; a `textEdit` ending here is extended to the caret. */
    val requestPosition: TextPosition,
    val requestVersion: Long,
    val rawItems: List<CompletionItem>,
    val items: List<RankedCompletion>,
    val selectedIndex: Int,
    val isIncomplete: Boolean,
    val explicit: Boolean,
    val prefix: String,
    val resolved: Map<CompletionItem, CompletionItem> = emptyMap(),
    val requestSnapshot: DocumentSnapshot? = null,
) {
    val selected: CompletionItem? get() = items.getOrNull(selectedIndex)?.item?.let { resolved[it] ?: it }
}

/** A value computed for a specific document version. */
internal data class Versioned<T>(val version: Long, val value: T)

/** Hover published for [position] in document [version]. */
internal data class HoverState(val position: TextPosition, val offset: Int, val version: Long, val hover: Hover?)

/**
 * Orchestrates [LanguageService] calls for one editor: debouncing, cancellation on edit, dropping
 * stale versions, client-side completion filtering, and re-querying incomplete lists. All public
 * methods must be called from [scope]'s thread (the UI thread); service calls run on
 * [LanguageServiceOptions.dispatcher]. Failures and timeouts are logged and ignored.
 */
internal class LanguageServiceController(
    private val scope: CoroutineScope,
    service: LanguageService,
    options: LanguageServiceOptions,
    rules: TokenRules,
    private val logger: CodeEditorLogger,
) {
    var service: LanguageService = service
        set(value) {
            if (field === value) return
            field = value
            closeCompletion()
            cancelHover()
            _diagnostics.value = Versioned(-1L, emptyList())
            _semanticTokens.value = null
            _foldingRanges.value = null
            lastSnapshot?.let { scheduleAnalysis(it) }
        }
    var options: LanguageServiceOptions = options
    var rules: TokenRules = rules

    private val _completion = MutableStateFlow<CompletionSession?>(null)
    val completion: StateFlow<CompletionSession?> = _completion.asStateFlow()

    private val _diagnostics = MutableStateFlow(Versioned(-1L, emptyList<Diagnostic>()))
    val diagnostics: StateFlow<Versioned<List<Diagnostic>>> = _diagnostics.asStateFlow()

    private val _semanticTokens = MutableStateFlow<Versioned<List<SemanticToken>>?>(null)
    val semanticTokens: StateFlow<Versioned<List<SemanticToken>>?> = _semanticTokens.asStateFlow()

    private val _foldingRanges = MutableStateFlow<Versioned<List<FoldingRange>>?>(null)
    val foldingRanges: StateFlow<Versioned<List<FoldingRange>>?> = _foldingRanges.asStateFlow()

    private val _hover = MutableStateFlow<HoverState?>(null)
    val hover: StateFlow<HoverState?> = _hover.asStateFlow()

    private var completionJob: Job? = null
    private var resolveJob: Job? = null
    private var analysisJob: Job? = null
    private var hoverJob: Job? = null
    private var lastSnapshot: DocumentSnapshot? = null
    private var lastCaret: Int = 0

    /** Number of `complete` calls issued; lets tests verify debouncing. */
    var completionRequests: Int = 0
        private set

    private val serviceContext get() = options.dispatcher ?: Dispatchers.Default

    /**
     * The document changed to [snapshot] with the caret at [caret]. [typed] is the character the user
     * just typed, if the edit was a single typed character; it drives trigger characters and
     * identifier-typing completion.
     */
    fun documentChanged(snapshot: DocumentSnapshot, caret: Int, typed: Char? = null) {
        val previous = lastSnapshot
        if (previous != null && previous.version == snapshot.version && previous.text == snapshot.text) {
            caretMoved(snapshot, caret)
            return
        }
        val previousCaret = lastCaret
        lastSnapshot = snapshot
        lastCaret = caret
        cancelHover()
        scheduleAnalysis(snapshot)
        val session = _completion.value
        val service = service
        when {
            session != null && sessionStillValid(session, snapshot, caret) &&
                previous != null && prefixEditOnly(previous, previousCaret, snapshot, caret, session.anchor) -> {
                if (typed != null && typed in service.triggerCharacters) {
                    request(snapshot, caret, CompletionTrigger.character(typed), explicit = false)
                } else {
                    refilter(session, snapshot, caret)
                    if (session.isIncomplete) request(snapshot, caret, CompletionTrigger.Incomplete, explicit = session.explicit)
                }
            }
            typed != null && typed in service.triggerCharacters -> {
                closeCompletion()
                request(snapshot, caret, CompletionTrigger.character(typed), explicit = false)
            }
            typed != null && rules.isIdentifierPart(typed) && completionPrefixStart(snapshot.text, caret, rules) < caret -> {
                closeCompletion()
                request(snapshot, caret, CompletionTrigger.Invoked, explicit = false)
            }
            else -> closeCompletion()
        }
    }

    /** The caret moved without an edit. Closes completion when it leaves the prefix; hides hover. */
    fun caretMoved(snapshot: DocumentSnapshot, caret: Int) {
        if (caret != lastCaret) {
            completionJob?.cancel()
            completionJob = null
            cancelHover()
        }
        lastCaret = caret
        val session = _completion.value ?: return
        if (!sessionStillValid(session, snapshot, caret)) closeCompletion() else refilter(session, snapshot, caret)
    }

    /** Explicit request (Ctrl+Space): shows the popup even with an empty prefix or no results. */
    fun requestCompletion(snapshot: DocumentSnapshot, caret: Int) {
        lastSnapshot = snapshot
        lastCaret = caret
        request(snapshot, caret, CompletionTrigger.Invoked, explicit = true)
    }

    fun closeCompletion() {
        completionJob?.cancel()
        completionJob = null
        resolveJob?.cancel()
        resolveJob = null
        _completion.value = null
    }

    /** Moves the selection by [delta] items, clamped to the list (no wrap for page moves). */
    fun moveSelection(delta: Int, wrap: Boolean = true) {
        val session = _completion.value ?: return
        if (session.items.isEmpty()) return
        val size = session.items.size
        val target = session.selectedIndex + delta
        val index = if (wrap && (delta == 1 || delta == -1)) ((target % size) + size) % size else target.coerceIn(0, size - 1)
        select(index)
    }

    fun select(index: Int) {
        val session = _completion.value ?: return
        if (index !in session.items.indices) return
        _completion.value = session.copy(selectedIndex = index)
        resolveSelected()
    }

    private fun resolveSelected() {
        val session = _completion.value ?: return
        val item = session.items.getOrNull(session.selectedIndex)?.item ?: return
        if (item in session.resolved) return
        resolveJob?.cancel()
        val service = service
        resolveJob = scope.launch {
            val resolved = call("resolveCompletionItem") { service.resolveCompletionItem(item) } ?: return@launch
            val current = _completion.value ?: return@launch
            if (current.rawItems === session.rawItems) _completion.value = current.copy(resolved = current.resolved + (item to resolved))
        }
    }

    /** Requests hover at [position] after the hover delay; replaces any pending request. */
    fun requestHover(snapshot: DocumentSnapshot, position: TextPosition) {
        val current = _hover.value
        if (current != null && current.position == position && current.version == snapshot.version) return
        hoverJob?.cancel()
        _hover.value = null
        val service = service
        val offset = snapshot.offsetOf(position)
        hoverJob = scope.launch {
            delay(options.hoverDelayMillis)
            val hover = call("hover") { service.hover(snapshot, position) }
            if (lastSnapshot?.version == snapshot.version) {
                _hover.value = HoverState(position, offset, snapshot.version, hover)
            } else {
                logger.debug("Dropped stale hover for version ${snapshot.version}")
            }
        }
    }

    fun cancelHover() {
        hoverJob?.cancel()
        hoverJob = null
        _hover.value = null
    }

    fun dispose() {
        closeCompletion()
        cancelHover()
        analysisJob?.cancel()
    }

    private fun prefixEditOnly(previous: DocumentSnapshot, previousCaret: Int, snapshot: DocumentSnapshot, caret: Int, anchor: Int): Boolean {
        if (caret < anchor || previousCaret < anchor) return false
        return previous.text.substring(0, anchor) == snapshot.text.substring(0, anchor) &&
            previous.text.substring(previousCaret) == snapshot.text.substring(caret)
    }

    private fun sessionStillValid(session: CompletionSession, snapshot: DocumentSnapshot, caret: Int): Boolean {
        if (caret < session.anchor || caret > snapshot.text.length) return false
        if (snapshot.positionOf(caret).line != snapshot.positionOf(session.anchor).line) return false
        for (i in session.anchor until caret) if (!rules.isIdentifierPart(snapshot.text[i])) return false
        return true
    }

    private fun refilter(session: CompletionSession, snapshot: DocumentSnapshot, caret: Int) {
        val prefix = snapshot.text.substring(session.anchor, caret)
        if (prefix == session.prefix) return
        val items = filterAndRankCompletions(session.rawItems, prefix, options.maxCompletionItems)
        if (items.isEmpty() && !session.isIncomplete && !session.explicit) {
            closeCompletion()
            return
        }
        _completion.value = session.copy(items = items, prefix = prefix, selectedIndex = preselectedIndex(items))
        resolveSelected()
    }

    private fun request(snapshot: DocumentSnapshot, caret: Int, trigger: CompletionTrigger, explicit: Boolean) {
        completionJob?.cancel()
        val service = service
        val position = snapshot.positionOf(caret)
        val debounce = if (trigger.kind == CompletionTriggerKind.INVOKED && !explicit) options.completionDebounceMillis else options.triggerDebounceMillis
        completionJob = scope.launch {
            if (debounce > 0) delay(debounce)
            completionRequests++
            logger.debug("Completion requested at $position for version ${snapshot.version} (${trigger.kind})")
            val list = call("complete") { service.complete(snapshot, position, trigger) }
            val current = lastSnapshot
            if (current == null || current.version != snapshot.version || lastCaret != caret) {
                logger.debug("Dropped stale completion for version ${snapshot.version}")
                return@launch
            }
            publishCompletion(list ?: CompletionList.Empty, current, lastCaret, position, explicit)
        }
    }

    private fun publishCompletion(list: CompletionList, snapshot: DocumentSnapshot, caret: Int, position: TextPosition, explicit: Boolean) {
        val anchor = completionPrefixStart(snapshot.text, caret, rules)
        val prefix = snapshot.text.substring(anchor, caret)
        val items = filterAndRankCompletions(list.items, prefix, options.maxCompletionItems)
        val previous = _completion.value
        if (items.isEmpty() && !explicit && !(list.isIncomplete && previous != null)) {
            _completion.value = null
            return
        }
        val keepSelection = previous?.selected?.let { selected -> items.indexOfFirst { it.item.label == selected.label } }?.takeIf { it >= 0 }
        _completion.value = CompletionSession(
            anchor = anchor,
            requestPosition = position,
            requestVersion = snapshot.version,
            rawItems = list.items,
            items = items,
            selectedIndex = keepSelection ?: preselectedIndex(items),
            isIncomplete = list.isIncomplete,
            explicit = explicit || previous?.explicit == true,
            prefix = prefix,
            requestSnapshot = snapshot,
        )
        resolveSelected()
    }

    private fun preselectedIndex(items: List<RankedCompletion>): Int = items.indexOfFirst { it.item.preselect }.coerceAtLeast(0)

    private fun scheduleAnalysis(snapshot: DocumentSnapshot) {
        analysisJob?.cancel()
        val service = service
        analysisJob = scope.launch {
            delay(options.diagnosticsDebounceMillis)
            launch {
                val diagnostics = call("diagnostics") { service.diagnostics(snapshot) } ?: return@launch
                if (isCurrent(snapshot, "diagnostics")) _diagnostics.value = Versioned(snapshot.version, diagnostics)
            }
            launch {
                val tokens = call("semanticTokens") { service.semanticTokens(snapshot) }
                if (isCurrent(snapshot, "semanticTokens")) _semanticTokens.value = tokens?.let { Versioned(snapshot.version, it) }
            }
            launch {
                val ranges = call("foldingRanges") { service.foldingRanges(snapshot) }
                if (isCurrent(snapshot, "foldingRanges")) _foldingRanges.value = ranges?.let { Versioned(snapshot.version, it) }
            }
        }
    }

    private fun isCurrent(snapshot: DocumentSnapshot, what: String): Boolean {
        val current = lastSnapshot?.version == snapshot.version
        if (!current) logger.debug("Dropped stale $what for version ${snapshot.version}")
        return current
    }

    /** Runs a service call off the UI thread with a timeout; returns null on failure or timeout. */
    private suspend fun <T> call(name: String, block: suspend () -> T): T? = try {
        withTimeout(options.requestTimeoutMillis) { withContext(serviceContext) { block() } }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        logger.error("LanguageService.$name timed out after ${options.requestTimeoutMillis} ms", e)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.error("LanguageService.$name failed: ${e::class.simpleName}", e)
        null
    }
}

/**
 * The edit that accepting [item] makes, given the [session] it came from and the current [caret].
 * Maps service edits through changes to the completion prefix. A `textEdit` ending at the request
 * position follows the caret; otherwise replaces the identifier prefix with `insertText ?: label`. Snippets are expanded and select the first stop.
 */
internal fun completionAcceptOutcome(snapshot: DocumentSnapshot, item: CompletionItem, session: CompletionSession, caret: Int): EditOutcome {
    val original = session.requestSnapshot ?: snapshot
    val diff = diffText(original.text, snapshot.text)
    val delta = diff.newText.length - (diff.oldEnd - diff.start)
    fun mapOffset(offset: Int, end: Boolean): Int = when {
        offset < diff.start || offset == diff.start && !end -> offset
        offset >= diff.oldEnd -> offset + delta
        end -> diff.start + diff.newText.length
        else -> diff.start
    }
    fun replacement(edit: TextEdit): Replacement = Replacement(
        mapOffset(original.offsetOf(edit.range.start), end = false),
        mapOffset(original.offsetOf(edit.range.end), end = true),
        edit.newText,
    )
    val (start, end, rawText) = item.textEdit?.let { edit ->
        val mapped = replacement(edit)
        val editEnd = if (edit.range.end == session.requestPosition) caret else mapped.end
        Triple(mapped.start, editEnd, edit.newText)
    } ?: Triple(session.anchor.coerceAtMost(caret), caret, item.insertText ?: item.label)
    val parsed = if (item.insertTextFormat == InsertTextFormat.SNIPPET) parseSnippet(rawText) else ParsedSnippet(rawText, rawText.length, rawText.length)
    val main = Replacement(start, end, parsed.text)
    val additional = item.additionalTextEdits.map { edit ->
        if (edit.range.isEmpty) {
            val offset = original.offsetOf(edit.range.start)
            val mapped = mapOffset(offset, end = offset != session.anchor)
            Replacement(mapped, mapped, edit.newText)
        } else {
            replacement(edit)
        }
    }.filter { it.end <= start || it.start >= end }
    val shiftBefore = additional.filter { it.end <= start }.sumOf { it.text.length - (it.end - it.start) }
    val selection = Selection(start + shiftBefore + parsed.selectionStart, start + shiftBefore + parsed.selectionEnd)
    return EditOutcome(listOf(main) + additional, selection, EditKind.OTHER)
}
