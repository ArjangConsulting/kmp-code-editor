package com.arjangconsulting.codeeditor

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

class LargeDocumentFormattingBenchmarkTest {
    @Test
    fun fiveMegabyteFormatting() {
        val output = System.getenv("KMP_EDITOR_FORMAT_BENCHMARK") ?: return
        val file = File(output).apply {
            parentFile.mkdirs()
            writeText("shape,bytes,iteration,operation,milliseconds\n")
        }
        for (shape in listOf("pretty", "minified", "string")) {
            runBlocking { CodeEditorState(largeJsonFixture(shape, 100_000)).format(JsonLanguageService()) }
            val source = largeJsonFixture(shape, 5_000_000)
            val state = CodeEditorState(source)
            val elapsed = measureTime {
                val result = runBlocking { state.format(JsonLanguageService(), serviceOptions = LanguageServiceOptions(requestTimeoutMillis = 120_000)) }
                assertEquals(FormatResult.APPLIED, result)
            }.inWholeNanoseconds / 1_000_000.0
            file.appendText("$shape,${source.length},1,format,$elapsed\n")
        }
    }
}
