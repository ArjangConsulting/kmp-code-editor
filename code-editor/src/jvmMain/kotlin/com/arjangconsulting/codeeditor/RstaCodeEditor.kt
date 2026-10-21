package com.arjangconsulting.codeeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine

/**
 * The RSyntaxTextArea engine: an [RstaEditorPanel] in a `SwingPanel`, wired to the shared state and
 * controller. The find bar is Compose and sits above the Swing panel (not over it), so it is never
 * hidden by the heavyweight component.
 */
@Composable
internal fun RstaCodeEditor(state: CodeEditorState, controller: LanguageServiceController, config: EditorConfig, modifier: Modifier) {
    var findVisible by remember { mutableStateOf(false) }
    val findFocus = remember { FocusRequester() }
    val panelHolder = remember { arrayOfNulls<RstaEditorPanel>(1) }
    val panel = remember(state) {
        RstaEditorPanel(
            state,
            config,
            object : RstaEditorPanel.Callbacks {
                override fun requestCompletion() = controller.requestCompletion(state.snapshot(), state.selectionInternal.caret)
                override fun closeCompletion() = controller.closeCompletion()
                override fun acceptCompletion(item: CompletionItem) {
                    val session = controller.completion.value ?: return
                    val outcome = completionAcceptOutcome(state.snapshot(), item, session, state.selectionInternal.caret)
                    controller.closeCompletion()
                    if (panelHolder[0]?.config?.readOnly == false) state.apply(outcome)
                    panelHolder[0]?.syncFromState()
                }
                override fun requestHover(offset: Int) {
                    val snapshot = state.snapshot()
                    controller.requestHover(snapshot, snapshot.positionOf(offset))
                }
                override fun cancelHover() = controller.cancelHover()
                override fun openFind() {
                    findVisible = true
                }
                override fun closeFind() {
                    findVisible = false
                }
            },
        ).also { panelHolder[0] = it }
    }
    DisposableEffect(panel) { onDispose { panel.dispose() } }
    LaunchedEffect(panel) {
        snapshotFlow { state.version to state.selectionInternal }.collect { panel.syncFromState() }
    }
    LaunchedEffect(panel) { controller.completion.collect { panel.showCompletion(it) } }
    LaunchedEffect(panel) {
        controller.hover.collect { hover -> if (hover != null) panel.showHover(hover.offset, hover.hover) }
    }
    LaunchedEffect(panel) {
        snapshotFlow { state.revealRequest }.collect { request -> request?.let { panel.reveal(it.range) } }
    }
    LaunchedEffect(findVisible) { if (findVisible) runCatching { findFocus.requestFocus() } }

    val singleLineHeight = if (config.singleLine) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        with(density) { (measurer.measure("M", config.textStyle).size.height * 1.6f).toDp() }
    } else {
        null
    }
    Column(modifier) {
        if (findVisible) {
            RstaFindBar(
                panel = panel,
                config = config,
                focusRequester = findFocus,
                onClose = {
                    findVisible = false
                    panel.focusEditor()
                },
            )
        }
        SwingPanel(
            factory = { panel },
            update = { it.applyConfig(config) },
            modifier = Modifier.fillMaxWidth().let { m -> if (singleLineHeight != null) m.height(singleLineHeight) else m.weight(1f) },
        )
    }
}

@Composable
private fun RstaFindBar(panel: RstaEditorPanel, config: EditorConfig, focusRequester: FocusRequester, onClose: () -> Unit) {
    val query = rememberTextFieldState(panel.textArea.selectedText?.takeIf { '\n' !in it }.orEmpty())
    var matchCase by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    val colors = config.colors
    val labels = config.labels
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val charWidth = with(density) { measurer.measure("M", config.textStyle).size.width.toDp() }
    fun search(forward: Boolean) {
        val text = query.text.toString()
        val context = SearchContext(text).apply {
            this.matchCase = matchCase
            searchForward = forward
            markAll = true
            searchWrap = true
        }
        SearchEngine.find(panel.textArea, context)
        val matches = findMatches(panel.textArea.text, text, matchCase)
        val current = matches.indexOfFirst { it.first == panel.textArea.selectionStart && it.last + 1 == panel.textArea.selectionEnd }
        resultText = when {
            text.isEmpty() -> ""
            matches.isEmpty() -> labels.findNoResults
            else -> labels.findMatchCount(current + 1, matches.size)
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString() to matchCase }.collect { search(forward = true) }
    }
    Surface(
        color = colors.popupBackground,
        contentColor = colors.popupForeground,
        border = BorderStroke(1.dp, colors.popupBorder),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = charWidth, vertical = charWidth / 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(charWidth),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.text.isEmpty()) Text(labels.findPlaceholder, style = config.textStyle, color = colors.popupSecondaryForeground)
                BasicTextField(
                    state = query,
                    textStyle = config.textStyle.copy(color = colors.popupForeground),
                    cursorBrush = SolidColor(colors.caret),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(FIND_FIELD_TAG)
                        .semantics { contentDescription = labels.findPlaceholder }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter, Key.F3 -> true.also { search(!event.isShiftPressed) }
                                Key.Escape -> true.also { onClose() }
                                else -> false
                            }
                        },
                )
            }
            Text(resultText, style = config.textStyle, color = colors.popupSecondaryForeground)
            FindTextButton("Aa", labels.findMatchCase, charWidth) { matchCase = !matchCase }
            FindTextButton("↑", labels.findPrevious, charWidth) { search(forward = false) }
            FindTextButton("↓", labels.findNext, charWidth) { search(forward = true) }
            FindTextButton("✕", labels.findClose, charWidth, onClose)
        }
    }
}

@Composable
private fun FindTextButton(glyph: String, label: String, charWidth: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Text(
        glyph,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = charWidth / 2)
            .width(charWidth * 2)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
    )
}
