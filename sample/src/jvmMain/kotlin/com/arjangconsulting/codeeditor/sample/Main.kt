package com.arjangconsulting.codeeditor.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Desktop sample: every editor feature on one screen. Run with `./gradlew :sample:run`. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KMP Code Editor sample",
        state = rememberWindowState(width = 1280.dp, height = 900.dp),
    ) {
        var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
        SampleTheme(themeMode) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Column(Modifier.padding(horizontal = SampleSpacing.large, vertical = SampleSpacing.small)) {
                        ThemeModeSelector(selected = themeMode, onSelect = { themeMode = it })
                    }
                    CodeEditorScreen()
                }
            }
        }
    }
}
