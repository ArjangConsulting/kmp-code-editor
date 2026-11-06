package com.arjangconsulting.codeeditor

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualEditorModelTest {
    @Test
    fun wrappedBoundaryAffinityAndResizeKeepSourcePositions() = runTest {
        val state = CodeEditorState("abcdefghijklmnopqrstuvwxyz")
        val config = largeDocumentConfig(CodeEditorOptions(softWrap = true))
        val controller = LanguageServiceController(this, LanguageService.None, config.serviceOptions, config.language, config.logger)
        val model = EditorModel(state, controller, this, config)
        model.metrics = EditorMetrics(20f, 10f, 0f, 0f)
        model.resize(120f, 100f)
        assertEquals(0f, model.maxScrollX)
        model.moveCaret(11, false, upstream = true)
        assertEquals(0f, model.caretRect().top)
        assertEquals(110f, model.caretRect().left)
        model.moveCaret(11, false)
        assertEquals(20f, model.caretRect().top)
        assertEquals(0f, model.caretRect().left)
        assertEquals(11, model.offsetAt(Offset(0f, 21f)))
        model.execute(KeyBinding(EditorCommand.LINE_END, extend = true))
        assertEquals("lmnopqrstuv", state.selectedText)
        assertEquals(20f, model.caretRect().top)
        model.focused = true
        model.resize(70f, 60f)
        assertEquals(22, state.selectionEnd)
        assertTrue(model.caretRect().top >= 0f)
        assertTrue(model.caretRect().bottom <= model.viewportHeight)
        assertEquals(0f, model.maxScrollX)
        assertEquals("abcdefghijklmnopqrstuvwxyz", state.text)
        model.dispose()
    }

    @Test
    fun detachedLargeSelectionReplacesAndUndoesTheWholeDocument() = runTest {
        val original = "x".repeat(50_000) + "😀\r\nend"
        val state = CodeEditorState(original)
        val config = largeDocumentConfig()
        val controller = LanguageServiceController(this, LanguageService.None, config.serviceOptions, config.language, config.logger)
        val model = EditorModel(state, controller, this, config)
        state.selectAll()
        model.bridge.sync()
        assertTrue(model.bridge.field.text.length <= 4098)
        model.insertText("日本")
        assertEquals("日本", state.text)
        assertTrue(state.undo())
        assertEquals(original, state.text)
        model.bridge.sync()
        assertTrue(model.bridge.field.text.length <= 4098)
        assertTrue(state.redo())
        assertEquals("日本", state.text)
        model.dispose()
    }
}
