package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class LargeDocumentDisplayBenchmarkTest {
    private fun benchmark(shape: String, size: Int) {
        val output = System.getenv("KMP_EDITOR_DISPLAY_BENCHMARK") ?: return
        val resultFile = File(output)
        if (!resultFile.exists()) {
            resultFile.parentFile.mkdirs()
            resultFile.writeText("shape,bytes,iteration,operation,milliseconds,status\n")
        }
        val source = largeJsonFixture(shape, size)
        repeat(4) { iteration ->
            var elapsed = 0.0
            var status = "ok"
            try {
                runComposeUiTest(testTimeout = 2.minutes) {
                    val state = CodeEditorState(source, 0 until 0)
                    val started = System.nanoTime()
                    setContent {
                        MaterialTheme {
                            CodeEditor(state, Modifier.size(800.dp, 400.dp).testTag("benchmark-editor"), language = JsonRules)
                        }
                    }
                    waitForIdle()
                    onNodeWithTag("benchmark-editor").captureToImage()
                    elapsed = (System.nanoTime() - started) / 1_000_000.0
                }
            } catch (failure: Throwable) {
                status = (failure::class.simpleName + ": " + failure.message).replace(',', ';').replace('\n', ' ').take(250)
            }
            resultFile.appendText("$shape,${source.length},$iteration,first_frame,$elapsed,$status\n")
        }
    }

    @Test fun pretty100KB(): Unit = benchmark("pretty", 100_000)
    @Test fun pretty1MB(): Unit = benchmark("pretty", 1_000_000)
    @Test fun pretty5MB(): Unit = benchmark("pretty", 5_000_000)
    @Test fun minified100KB(): Unit = benchmark("minified", 100_000)
    @Test fun minified1MB(): Unit = benchmark("minified", 1_000_000)
    @Test fun minified5MB(): Unit = benchmark("minified", 5_000_000)
    @Test fun string100KB(): Unit = benchmark("string", 100_000)
    @Test fun string1MB(): Unit = benchmark("string", 1_000_000)
    @Test fun string5MB(): Unit = benchmark("string", 5_000_000)
}
