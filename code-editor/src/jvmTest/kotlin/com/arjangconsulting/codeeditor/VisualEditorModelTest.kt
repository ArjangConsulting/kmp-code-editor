package com.arjangconsulting.codeeditor

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VisualEditorModelTest {
    @Test
    fun singleLineFindCommandsAreUnhandled() = runTest {
        val state = CodeEditorState("prompt")
        val config = largeDocumentConfig().copy(singleLine = true)
        val controller = LanguageServiceController(this, LanguageService.None, config.serviceOptions, config.language, config.logger)
        val model = EditorModel(state, controller, this, config)
        for (command in listOf(EditorCommand.FIND, EditorCommand.REPLACE, EditorCommand.FIND_NEXT, EditorCommand.FIND_PREVIOUS)) {
            assertEquals(false, model.execute(KeyBinding(command)))
        }
        assertEquals(false, model.find.visible)
        model.dispose()
    }

    @Test
    fun typingReshapesOnlyTheChangedTileAndPreservesShiftedRows() = runComposeUiTest {
        lateinit var measurer: androidx.compose.ui.text.TextMeasurer
        setContent {
            val value = rememberTextMeasurer(cacheSize = 0)
            SideEffect { measurer = value }
        }
        waitForIdle()
        runTest {
            val state = CodeEditorState("one\ntwo\nthree")
            val config = largeDocumentConfig()
            val controller = LanguageServiceController(this, LanguageService.None, config.serviceOptions, config.language, config.logger)
            val model = EditorModel(state, controller, this, config)
            model.textMeasurer = measurer
            model.metrics = EditorMetrics(20f, 10f, 0f, 0f)
            fun shape() {
                model.beginFrame(LineStyleKey(config.textStyle, config.colors, config.options.tabSize))
                repeat(state.snapshot().lineCount) { model.lineLayout(it) }
            }
            shape()
            val before = model.shapedTileCount
            state.select(1)
            model.typeCharacter('x')
            shape()
            assertEquals(before + 1, model.shapedTileCount)
            state.select(0)
            model.insertText("new\n")
            shape()
            assertEquals(before + 2, model.shapedTileCount)
            model.config = config.copy(colors = config.colors.copy(foreground = androidx.compose.ui.graphics.Color.Red))
            model.beginFrame(LineStyleKey(model.config.textStyle, model.config.colors, model.config.options.tabSize))
            model.lineLayout(0)
            assertEquals(before + 3, model.shapedTileCount)
            model.dispose()
        }
    }

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
