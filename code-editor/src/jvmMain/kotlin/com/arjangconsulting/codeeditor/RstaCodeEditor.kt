package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * The RSyntaxTextArea engine: an [RstaEditorPanel] in a `SwingPanel`, wired to the shared state and
 * controller. The find bar is the shared Compose [FindReplaceBar] and sits above the Swing panel (not
 * over it), so it is never hidden by the heavyweight component.
 */
@Composable
internal fun RstaCodeEditor(state: CodeEditorState, controller: LanguageServiceController, config: EditorConfig, modifier: Modifier) {
    val panelHolder = remember { arrayOfNulls<RstaEditorPanel>(1) }
    val find = remember(state) {
        FindSession(
            object : FindSession.Host {
                override val state: CodeEditorState get() = state
                override val readOnly: Boolean get() = panelHolder[0]?.config?.readOnly ?: config.readOnly

                override fun select(start: Int, end: Int) {
                    state.selectionInternal = Selection(start, end)
                    val snapshot = state.snapshot()
                    state.revealRange(TextRange(snapshot.positionOf(start), snapshot.positionOf(end)))
                }

                override fun apply(outcome: EditOutcome): Boolean {
                    if (readOnly) return false
                    return state.apply(outcome).also { panelHolder[0]?.syncFromState() }
                }

                override fun focusEditor() {
                    panelHolder[0]?.focusEditor()
                }
            },
        )
    }
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
                    if (outcome == null) {
                        config.logger.logRejectedCompletion(item)
                        return
                    }
                    if (panelHolder[0]?.config?.readOnly == false) state.apply(outcome)
                    panelHolder[0]?.syncFromState()
                }
                override fun requestHover(offset: Int) {
                    val snapshot = state.snapshot()
                    controller.requestHover(snapshot, snapshot.positionOf(offset))
                }
                override fun cancelHover() = controller.cancelHover()
                override fun openFind() = find.open()
                override fun openReplace() = find.open(replace = true)
                override fun closeFind() {
                    if (find.visible) find.close()
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
    LaunchedEffect(panel, find) {
        snapshotFlow { find.matches() }.collect { panel.markAll(it) }
    }

    val measurer = rememberTextMeasurer()
    val glyph = remember(config.textStyle, measurer) { measurer.measure("M", config.textStyle).size }
    val singleLineHeight = if (config.singleLine) {
        with(LocalDensity.current) { (glyph.height * 1.6f).toDp() }
    } else {
        null
    }
    Column(modifier) {
        if (find.visible) {
            FindReplaceBar(
                session = find,
                config = config,
                charWidth = glyph.width.toFloat(),
                lineHeight = glyph.height.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SwingPanel(
            factory = { panel },
            update = { it.applyConfig(config) },
            modifier = Modifier.fillMaxWidth().let { m -> if (singleLineHeight != null) m.height(singleLineHeight) else m.weight(1f) },
        )
    }
}
