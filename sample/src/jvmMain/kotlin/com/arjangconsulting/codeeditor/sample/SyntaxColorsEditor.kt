package com.arjangconsulting.codeeditor.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arjangconsulting.codeeditor.CodeEditorColors
import com.arjangconsulting.codeeditor.CodeEditorSyntaxTheme
import com.arjangconsulting.codeeditor.TokenKind
import com.arjangconsulting.codeeditor.contrastRatio
import com.arjangconsulting.codeeditor.isDarkBackground
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.kmpSpacing

/** Built-in syntax palettes offered by the sample. */
enum class SyntaxPreset(val label: String, val theme: CodeEditorSyntaxTheme) {
    DEFAULT("Default", CodeEditorSyntaxTheme.Default),
    HIGH_CONTRAST("High contrast", CodeEditorSyntaxTheme.HighContrast),
}

private val editableKinds = TokenKind.entries.filter { it != TokenKind.PLAIN }

private val quickColors = listOf(
    Color(0xFFD32F2F), Color(0xFFE65100), Color(0xFFF9A825), Color(0xFF2E7D32),
    Color(0xFF00897B), Color(0xFF0277BD), Color(0xFF3949AB), Color(0xFF8E24AA),
    Color(0xFFFF8A80), Color(0xFFFFB74D), Color(0xFFFFF176), Color(0xFFA5D6A7),
    Color(0xFF80CBC4), Color(0xFF81D4FA), Color(0xFF9FA8DA), Color(0xFFCE93D8),
)

/**
 * Lets a person pick a syntax preset and recolor individual token kinds, the way an app's settings
 * screen would. Edits change only the variant shown on the current background, and colors below
 * 4.5:1 contrast are flagged.
 */
@Composable
fun SyntaxColorsEditor(
    preset: SyntaxPreset,
    theme: CodeEditorSyntaxTheme,
    colors: CodeEditorColors,
    onPresetChange: (SyntaxPreset) -> Unit,
    onThemeChange: (CodeEditorSyntaxTheme) -> Unit,
) {
    var editing by remember { mutableStateOf<TokenKind?>(null) }
    val dark = isDarkBackground(colors.background)
    val variant = if (dark) theme.dark else theme.light
    val lowContrast = variant.lowContrastKinds(colors.background)
    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = "Syntax colors",
        description = "Pick a palette, then click a token kind to recolor it. Edits apply to the " +
            (if (dark) "dark" else "light") + " variant.",
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SyntaxPreset.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = preset == option && theme == option.theme,
                    onClick = { onPresetChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, SyntaxPreset.entries.size),
                ) { Text(option.label) }
            }
        }
        Column(Modifier.fillMaxWidth().background(colors.background)) {
            editableKinds.forEach { kind ->
                TokenRow(
                    kind = kind,
                    color = colors.tokenColor(kind),
                    usesForeground = !variant[kind].isSpecified,
                    lowContrast = kind in lowContrast,
                    selected = editing == kind,
                    onClick = { editing = if (editing == kind) null else kind },
                )
            }
        }
        editing?.let { kind ->
            TokenColorEditor(
                kind = kind,
                current = variant[kind],
                presetColor = (if (dark) preset.theme.dark else preset.theme.light)[kind],
                background = colors.background,
                onColorChange = { color ->
                    val edited = variant.with(kind, color)
                    onThemeChange(if (dark) theme.copy(dark = edited) else theme.copy(light = edited))
                },
            )
        }
    }
}

@Composable
private fun TokenRow(
    kind: TokenKind,
    color: Color,
    usesForeground: Boolean,
    lowContrast: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = kind.label()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = "Edit $label color", onClick = onClick)
            .padding(horizontal = MaterialTheme.kmpSpacing.small, vertical = MaterialTheme.kmpSpacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(color)
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        when {
            lowContrast -> Text(
                "Low contrast",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
            usesForeground -> Text("Text color", color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TokenColorEditor(
    kind: TokenKind,
    current: Color,
    presetColor: Color,
    background: Color,
    onColorChange: (Color) -> Unit,
) {
    var hex by remember(kind, current) { mutableStateOf(if (current.isSpecified) current.toHex() else "") }
    val parsed = parseHexColor(hex)
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
        OutlinedTextField(
            value = hex,
            onValueChange = { text ->
                hex = text
                parseHexColor(text)?.let(onColorChange)
            },
            label = { Text("${kind.label()} color (#RRGGBB)") },
            isError = hex.isNotEmpty() && parsed == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (current.isSpecified) {
            val ratio = contrastRatio(current, background)
            Text(
                "Contrast ${"%.1f".format(ratio)}:1" + if (ratio < 4.5f) " — below the 4.5:1 minimum for text" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (ratio < 4.5f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.extraSmall),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.extraSmall),
        ) {
            quickColors.forEach { color ->
                Swatch(
                    color,
                    Modifier
                        .clickable(role = Role.Button) { onColorChange(color) }
                        .semantics { contentDescription = color.toHex() },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
            TextButton(onClick = { onColorChange(presetColor) }) { Text("Reset") }
            TextButton(onClick = { onColorChange(Color.Unspecified) }) { Text("Use text color") }
        }
    }
}

@Composable
private fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(MaterialTheme.kmpSpacing.medium)
            .background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

private fun TokenKind.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** Parses `#RRGGBB` or `RRGGBB`; returns null for anything else. */
fun parseHexColor(text: String): Color? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}
