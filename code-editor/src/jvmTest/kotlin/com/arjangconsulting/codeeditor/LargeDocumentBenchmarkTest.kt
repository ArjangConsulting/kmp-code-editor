package com.arjangconsulting.codeeditor

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

internal fun largeDocumentConfig(options: CodeEditorOptions = CodeEditorOptions()): EditorConfig = EditorConfig(
    language = JsonRules,
    readOnly = false,
    singleLine = false,
    onSubmit = null,
    onHistory = null,
    showLineNumbers = true,
    folding = true,
    lineHighlights = emptyList(),
    diagnostics = emptyList(),
    onCaretChange = null,
    onLineClick = null,
    tabBehavior = TabBehavior.MOVE_FOCUS,
    colors = CodeEditorDefaults.colorsFrom(lightColorScheme()),
    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
    labels = CodeEditorLabels(),
    options = options,
    serviceOptions = LanguageServiceOptions(),
    logger = CodeEditorLogger.None,
)

internal fun largeJsonFixture(shape: String, size: Int): String {
    val item = if (shape == "pretty") "  {\"id\":123,\"name\":\"fixture value\",\"ok\":true}" else "{\"id\":123,\"name\":\"fixture value\",\"ok\":true}"
    return if (shape == "string") "{\"value\":\"" + "x".repeat(size - 12) + "\"}" else {
        val separator = if (shape == "pretty") ",\n" else ","
        "[" + List((size - 2) / (item.length + separator.length)) { item }.joinToString(separator) + "]"
    }
}

@OptIn(ExperimentalTestApi::class)
class LargeDocumentBenchmarkTest {
    @Test
    fun benchmark() {
        val output = System.getenv("KMP_EDITOR_BENCHMARK") ?: return
        runComposeUiTest(testTimeout = 10.minutes) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            lateinit var measurer: androidx.compose.ui.text.TextMeasurer
            setContent {
                val value = rememberTextMeasurer(cacheSize = 0)
                SideEffect { measurer = value }
            }
            waitForIdle()
            val resultFile = File(output).apply {
                parentFile.mkdirs()
                writeText("shape,bytes,iteration,operation,milliseconds\n")
            }
            val config = largeDocumentConfig()
            fun model(state: CodeEditorState): EditorModel = EditorModel(
                state, LanguageServiceController(scope, LanguageService.None, config.serviceOptions, config.language, config.logger), scope, config,
            ).also {
                it.textMeasurer = measurer
                it.metrics = EditorMetrics(18f, measurer.measure("M", config.textStyle).getHorizontalPosition(1, true), 4f, 6f)
                it.viewportWidth = 800f
                it.viewportHeight = 400f
            }
            for (size in listOf(100_000, 1_000_000, 5_000_000)) {
                for (shape in listOf("pretty", "minified", "string")) {
                    val source = largeJsonFixture(shape, size)
                    repeat(4) { iteration ->
                        val state = CodeEditorState(source)
                        lateinit var editor: EditorModel
                        fun record(operation: String, block: () -> Unit) {
                            val elapsed = measureTime(block).inWholeNanoseconds / 1_000_000.0
                            resultFile.appendText("$shape,${source.length},$iteration,$operation,$elapsed\n")
                        }
                        fun display() {
                            editor.beginFrame(LineStyleKey(config.textStyle, config.colors, config.options.tabSize))
                            val snapshot = state.snapshot()
                            val first = (state.caret.line - 8).coerceAtLeast(0)
                            for (line in first..minOf(snapshot.lineCount - 1, first + 22)) editor.lineLayout(line)
                            editor.maxColumns()
                            editor.bridge.sync()
                        }
                        record("initial") { editor = model(state); display() }
                        for ((name, offset) in listOf("type_start" to 10, "type_middle" to source.length / 2, "type_end" to source.length - 3)) {
                            state.select(offset)
                            record(name) { editor.typeCharacter('x'); display() }
                            state.undo()
                        }
                        state.select(source.length / 2)
                        record("paste_16KB") { editor.insertText("p".repeat(16_384)); display() }
                        record("undo") { state.undo(); display() }
                        record("redo") { state.redo(); display() }
                        state.resetText(source)
                        record("find") { findMatches(state.text, "fixture", true) }
                        if (size < 5_000_000 || System.getenv("KMP_EDITOR_BASELINE") != "true") record("format") { runBlocking { state.format(JsonLanguageService(), serviceOptions = LanguageServiceOptions(requestTimeoutMillis = 120_000)) } }
                        if (shape != "pretty" && state.canUndo) {
                            state.undo()
                            assertEquals(source, state.text)
                        }
                    }
                }
            }
            scope.cancel()
        }
    }
}
