package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable

/**
 * Pluggable language intelligence, shaped after the Language Server Protocol so a real LSP client or
 * an RPC bridge can implement it with a thin adapter.
 *
 * Every method has a no-op default, so implement only what you support. The editor owns
 * orchestration: it debounces requests, cancels in-flight requests when the document changes, drops
 * results for stale [DocumentSnapshot.version]s, and filters/ranks completions locally while a
 * [CompletionList] is complete. Implementations may be slow, remote, or throw; failures are reported
 * to the editor's [CodeEditorLogger] and otherwise ignored, so they never break editing.
 *
 * Methods are called from [LanguageServiceOptions.dispatcher] (by default `Dispatchers.Default`), so
 * an implementation must be safe to call off the main thread and should support cancellation.
 * Hosts with mutable runtime context should change [CodeEditor]'s `languageServiceRevision` after
 * execution or a session switch so results from the previous context are discarded.
 */
public interface LanguageService {
    /** Characters that open completion immediately, like `.` or `=`. */
    public val triggerCharacters: Set<Char> get() = emptySet()

    /** Completion proposals at [position], or null for none. */
    public suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList? =
        null

    /**
     * Fills in expensive fields (typically [CompletionItem.documentation]) for the selected item,
     * mirroring LSP's `completionItem/resolve`. Returns [item] unchanged by default.
     */
    public suspend fun resolveCompletionItem(item: CompletionItem): CompletionItem = item

    /** Problems in [doc]. Merged with the diagnostics passed to the editor directly. */
    public suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> = emptyList()

    /**
     * Formats [doc] using LSP-style edits in its original coordinates. Null means formatting is
     * unavailable for this document; an empty list means it is already formatted.
     *
     * Called only by an explicit host action through [CodeEditorState.format], never while typing.
     * Implementations can delegate to a local formatter or LSP's `textDocument/formatting`.
     */
    public suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit>? = null

    /** Hover information at [position], or null for none. */
    public suspend fun hover(doc: DocumentSnapshot, position: TextPosition): Hover? = null

    /** Semantic tokens drawn over the lexer's tokens, or null to rely on the lexer only. */
    public suspend fun semanticTokens(doc: DocumentSnapshot): List<SemanticToken>? = null

    /** Folding ranges, or null to let the editor compute them from brackets and comments. */
    public suspend fun foldingRanges(doc: DocumentSnapshot): List<FoldingRange>? = null

    public companion object {
        /** A service that provides nothing. */
        public val None: LanguageService = object : LanguageService {}
    }
}

/**
 * Timing and limits for [LanguageService] orchestration. All durations are in milliseconds.
 *
 * @property completionDebounceMillis Delay after typing an identifier before asking for completions.
 *   Trigger characters and explicit requests use [triggerDebounceMillis].
 * @property triggerDebounceMillis Delay for trigger-character and explicit (Ctrl+Space) requests.
 * @property diagnosticsDebounceMillis Delay after the last edit before asking for diagnostics.
 * @property hoverDelayMillis How long the pointer must rest before hover is requested.
 * @property requestTimeoutMillis Requests running longer are cancelled and logged.
 * @property maxCompletionItems Upper bound on items shown after filtering.
 * @property dispatcher Where service methods run. Null means `Dispatchers.Default`.
 */
@Immutable
public data class LanguageServiceOptions(
    val completionDebounceMillis: Long = 75,
    val triggerDebounceMillis: Long = 0,
    val diagnosticsDebounceMillis: Long = 300,
    val hoverDelayMillis: Long = 400,
    val requestTimeoutMillis: Long = 10_000,
    val maxCompletionItems: Int = 200,
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
) {
    init {
        require(completionDebounceMillis >= 0 && triggerDebounceMillis >= 0 && diagnosticsDebounceMillis >= 0 && hoverDelayMillis >= 0) {
            "Delays must be nonnegative"
        }
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(maxCompletionItems > 0) { "maxCompletionItems must be positive" }
    }
}

/**
 * Receives the editor's diagnostic log output. The editor never logs document text, only versions,
 * positions, sizes, and error types, so it is safe to forward to app logs.
 */
public interface CodeEditorLogger {
    /** Fine-grained tracing (requests started, dropped as stale, cancelled). */
    public fun debug(message: String) {}

    /** A failure that the editor recovered from, such as a throwing or timed-out service. */
    public fun error(message: String, throwable: Throwable? = null) {}

    public companion object {
        /** Discards everything. */
        public val None: CodeEditorLogger = object : CodeEditorLogger {}
    }
}
