package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/** Everything an engine needs besides the state and controller. Internal; built by [CodeEditor]. */
internal data class EditorConfig(
    val language: TokenRules,
    val readOnly: Boolean,
    val singleLine: Boolean,
    val onSubmit: ((String) -> Unit)?,
    val onHistory: ((Int) -> Unit)?,
    val showLineNumbers: Boolean,
    val folding: Boolean,
    val lineHighlights: List<LineHighlight>,
    val diagnostics: List<Diagnostic>,
    val onCaretChange: ((TextPosition) -> Unit)?,
    val onLineClick: ((Int) -> Unit)?,
    val tabBehavior: TabBehavior,
    val colors: CodeEditorColors,
    val textStyle: TextStyle,
    val labels: CodeEditorLabels,
    val options: CodeEditorOptions,
    val serviceOptions: LanguageServiceOptions,
    val logger: CodeEditorLogger,
)

/**
 * A code editor with syntax highlighting, LSP-shaped language intelligence, folding, find, and
 * diagnostics, rendered by the chosen [engine].
 *
 * **State.** Hoist [state] with [rememberCodeEditorState]. For a plain `String` value use the other
 * overload.
 *
 * **Language.** [language] drives highlighting, brackets, comments, and indentation. [languageService]
 * adds completion, diagnostics, hover, semantic tokens, and folding ranges; the editor debounces,
 * cancels, and drops stale results (tune with [serviceOptions]), and logs failures to [logger].
 * [diagnostics] are merged with the service's.
 * Change [languageServiceRevision] after execution, a session reset, or a device switch to cancel
 * pending requests and clear service results even when the document is unchanged. Analysis is
 * refreshed; completion opens again on typing or Ctrl+Space. Host-supplied [diagnostics] are unaffected.
 *
 * **Single-line (REPL) mode.** With [singleLine], Enter calls [onSubmit] with the text (Enter still
 * accepts an open completion first), and Up/Down call [onHistory] with -1 (older) or +1 (newer)
 * when no completion popup is open. Pasted line breaks become spaces.
 *
 * **Decorations.** [lineHighlights] tint whole lines; [revealLine] / [revealRange] scroll into view
 * whenever their value changes (use [CodeEditorState.revealLine] to repeat a reveal). [onLineClick]
 * is called with the 0-based line when a line number is clicked.
 *
 * **Keyboard.** Arrows, word and line movement, Page Up/Down, selection with Shift, undo/redo,
 * copy/cut/paste (whole line when nothing is selected), Cmd/Ctrl+F find with F3 / Cmd+G for the next
 * match, Ctrl+Space completion, Cmd/Ctrl+/ toggle comment. [tabBehavior] picks indent vs. focus
 * traversal for Tab. The Compose engine folds/unfolds the innermost region at the caret with
 * Cmd+Option+[/] on Apple platforms or Ctrl+Shift+[/] elsewhere, including read-only editors.
 *
 * **Accessibility.** The editor exposes [CodeEditorLabels.editorDescription] and the caret position
 * to screen readers. Text is presented through a hidden platform text field that mirrors the lines
 * around the caret, so screen readers read the current context rather than the whole document.
 * The Compose input field also exposes localized fold/unfold actions for the current region and
 * the whole document. Collapsing moves a hidden selection to the region's header.
 *
 * **Sizing.** Give the editor a bounded height to get virtualized rendering. With unbounded height
 * (e.g. inside a `verticalScroll`) it measures to fit every line.
 */
@Composable
public fun CodeEditor(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    language: TokenRules = PlainText,
    languageService: LanguageService = LanguageService.None,
    engine: CodeEditorEngine = CodeEditorEngine.DEFAULT,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    onSubmit: ((String) -> Unit)? = null,
    onHistory: ((Int) -> Unit)? = null,
    showLineNumbers: Boolean = !singleLine,
    folding: Boolean = !singleLine,
    lineHighlights: List<LineHighlight> = emptyList(),
    diagnostics: List<Diagnostic> = emptyList(),
    revealLine: Int? = null,
    revealRange: TextRange? = null,
    onCaretChange: ((TextPosition) -> Unit)? = null,
    onLineClick: ((Int) -> Unit)? = null,
    tabBehavior: TabBehavior = if (singleLine) TabBehavior.MOVE_FOCUS else TabBehavior.INSERT_INDENT,
    colors: CodeEditorColors = CodeEditorDefaults.colors(),
    textStyle: TextStyle = CodeEditorDefaults.textStyle(),
    labels: CodeEditorLabels = CodeEditorLabels(),
    options: CodeEditorOptions = CodeEditorOptions(),
    serviceOptions: LanguageServiceOptions = LanguageServiceOptions(),
    logger: CodeEditorLogger = CodeEditorLogger.None,
    languageServiceRevision: Long = 0,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(state) { LanguageServiceController(scope, languageService, serviceOptions, language, logger) }
    SideEffect {
        controller.options = serviceOptions
        controller.rules = language
        controller.service = languageService
        controller.serviceRevision = languageServiceRevision
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }

    val serviceDiagnostics by controller.diagnostics.collectAsState()
    val currentVersion = state.version
    val merged = remember(diagnostics, serviceDiagnostics, currentVersion) {
        if (serviceDiagnostics.version == currentVersion) diagnostics + serviceDiagnostics.value else diagnostics
    }
    val config = EditorConfig(
        language = language,
        readOnly = readOnly,
        singleLine = singleLine,
        onSubmit = onSubmit,
        onHistory = onHistory,
        showLineNumbers = showLineNumbers,
        folding = folding,
        lineHighlights = lineHighlights,
        diagnostics = merged,
        onCaretChange = onCaretChange,
        onLineClick = onLineClick,
        tabBehavior = tabBehavior,
        colors = colors,
        textStyle = textStyle,
        labels = labels,
        options = options,
        serviceOptions = serviceOptions,
        logger = logger,
    )

    DocumentObserver(state, controller, config)
    FoldingRangeUpdater(state, controller, language, folding)

    LaunchedEffect(state, revealLine) { revealLine?.let(state::revealLine) }
    LaunchedEffect(state, revealRange) { revealRange?.let(state::revealRange) }

    val resolved = if (engine == CodeEditorEngine.RSYNTAX_TEXT_AREA && rSyntaxTextAreaAvailable) {
        CodeEditorEngine.RSYNTAX_TEXT_AREA
    } else {
        CodeEditorEngine.COMPOSE
    }
    when (resolved) {
        CodeEditorEngine.RSYNTAX_TEXT_AREA -> RSyntaxTextAreaEditor(state, controller, config, modifier)
        else -> ComposeCodeEditor(state, controller, config, modifier)
    }
}

/**
 * A [CodeEditor] for a plain [value]. Edits are reported through [onValueChange]; when [value]
 * changes from outside, the editor text is replaced (as one undoable change) and the caret moves to
 * the end. Selection, folding, and undo history live inside and survive recomposition.
 * [languageServiceRevision] invalidates service results as in the state-based overload.
 */
@Composable
public fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    language: TokenRules = PlainText,
    languageService: LanguageService = LanguageService.None,
    engine: CodeEditorEngine = CodeEditorEngine.DEFAULT,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    onSubmit: ((String) -> Unit)? = null,
    onHistory: ((Int) -> Unit)? = null,
    showLineNumbers: Boolean = !singleLine,
    folding: Boolean = !singleLine,
    lineHighlights: List<LineHighlight> = emptyList(),
    diagnostics: List<Diagnostic> = emptyList(),
    revealLine: Int? = null,
    revealRange: TextRange? = null,
    onCaretChange: ((TextPosition) -> Unit)? = null,
    onLineClick: ((Int) -> Unit)? = null,
    tabBehavior: TabBehavior = if (singleLine) TabBehavior.MOVE_FOCUS else TabBehavior.INSERT_INDENT,
    colors: CodeEditorColors = CodeEditorDefaults.colors(),
    textStyle: TextStyle = CodeEditorDefaults.textStyle(),
    labels: CodeEditorLabels = CodeEditorLabels(),
    options: CodeEditorOptions = CodeEditorOptions(),
    serviceOptions: LanguageServiceOptions = LanguageServiceOptions(),
    logger: CodeEditorLogger = CodeEditorLogger.None,
    languageServiceRevision: Long = 0,
) {
    val state = remember { CodeEditorState(value) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    // Texts we reported but the caller may not have echoed back yet. A late echo of one of them must
    // not overwrite newer typing, so only values outside this set count as external changes.
    val reported = remember { ArrayDeque<String>() }
    DisposableEffect(state) {
        val listener: (String) -> Unit = { text ->
            reported.addLast(text)
            while (reported.size > 32) reported.removeFirst()
            currentOnValueChange(text)
        }
        state.addTextListener(listener)
        onDispose { state.removeTextListener(listener) }
    }
    LaunchedEffect(value) {
        if (value != state.text && value !in reported) state.setText(value)
        if (value == state.text) reported.clear()
    }
    CodeEditor(
        state = state,
        modifier = modifier,
        language = language,
        languageService = languageService,
        engine = engine,
        readOnly = readOnly,
        singleLine = singleLine,
        onSubmit = onSubmit,
        onHistory = onHistory,
        showLineNumbers = showLineNumbers,
        folding = folding,
        lineHighlights = lineHighlights,
        diagnostics = diagnostics,
        revealLine = revealLine,
        revealRange = revealRange,
        onCaretChange = onCaretChange,
        onLineClick = onLineClick,
        tabBehavior = tabBehavior,
        colors = colors,
        textStyle = textStyle,
        labels = labels,
        options = options,
        serviceOptions = serviceOptions,
        logger = logger,
        languageServiceRevision = languageServiceRevision,
    )
}

/** Feeds document and caret changes to the controller and [EditorConfig.onCaretChange]. */
@Composable
private fun DocumentObserver(state: CodeEditorState, controller: LanguageServiceController, config: EditorConfig) {
    val onCaretChange by rememberUpdatedState(config.onCaretChange)
    val completionOnTyping by rememberUpdatedState(config.options.completionOnTyping && !config.readOnly)
    LaunchedEffect(state, controller) {
        var lastVersion = -1L
        snapshotFlow { state.version to state.selectionInternal }.collect { (version, selection) ->
            val snapshot = state.snapshot()
            if (version != lastVersion) {
                lastVersion = version
                val typed = state.lastTypedCharacter?.takeIf { completionOnTyping || it in controller.service.triggerCharacters }
                controller.documentChanged(snapshot, selection.caret, typed)
            } else {
                controller.caretMoved(snapshot, selection.caret)
            }
            onCaretChange?.invoke(snapshot.positionOf(selection.caret))
        }
    }
}

/** Keeps [CodeEditorState.foldingRanges] current: service ranges when provided, else computed. */
@Composable
private fun FoldingRangeUpdater(state: CodeEditorState, controller: LanguageServiceController, language: TokenRules, folding: Boolean) {
    val serviceRanges by controller.foldingRanges.collectAsState()
    LaunchedEffect(state, language, folding, serviceRanges) {
        if (!folding) {
            state.foldingRanges = emptyList()
            return@LaunchedEffect
        }
        val highlighter = Highlighter(language)
        snapshotFlow { state.version }.collect {
            // Folding needs every line lexed; debounce so typing in large files stays cheap.
            if (state.foldingRanges.isNotEmpty()) delay(FOLDING_DEBOUNCE_MILLIS)
            val snapshot = state.snapshot()
            val fromService = serviceRanges?.takeIf { it.version == snapshot.version }?.value
            state.foldingRanges = fromService?.filter { it.endLine < snapshot.lineCount }
                ?: computeFoldingRanges(snapshot, language, highlighter.also { h -> h.update(snapshot) })
        }
    }
}

private const val FOLDING_DEBOUNCE_MILLIS = 150L
