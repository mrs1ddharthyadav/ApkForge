package com.sangam.apkforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours for XML syntax. Kept separate so light and dark themes can pass their own. */
data class XmlColors(val tag: Color, val attr: Color, val value: Color, val comment: Color)

/** Colours XML text without changing it (so cursor positions stay the same). */
class XmlHighlighter(private val colors: XmlColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text), OffsetMapping.Identity)

    private fun highlight(s: String): AnnotatedString = buildAnnotatedString {
        append(s)
        // Big files: colouring on every keystroke gets slow, so skip it.
        if (s.length > 150_000) return@buildAnnotatedString
        TAG.findAll(s).forEach { addStyle(SpanStyle(color = colors.tag), it.range.first, it.range.last + 1) }
        ATTR.findAll(s).forEach {
            val g = it.groups[1] ?: return@forEach
            addStyle(SpanStyle(color = colors.attr), g.range.first, g.range.last + 1)
        }
        VALUE.findAll(s).forEach { addStyle(SpanStyle(color = colors.value), it.range.first, it.range.last + 1) }
        COMMENT.findAll(s).forEach { addStyle(SpanStyle(color = colors.comment), it.range.first, it.range.last + 1) }
    }

    private companion object {
        val TAG = Regex("</?[A-Za-z_][\\w:.\\-]*|/?>")
        val ATTR = Regex("\\s([A-Za-z_][\\w:.\\-]*)\\s*=")
        val VALUE = Regex("\"[^\"]*\"")
        val COMMENT = Regex("<!--[\\s\\S]*?-->")
    }
}

/**
 * A plain-text code editor with optional XML colouring and a find / replace bar.
 */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    highlightXml: Boolean,
    showSearch: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (dark) {
        XmlColors(Color(0xFF8CCFC7), Color(0xFFF2C27A), Color(0xFFB5D99C), Color(0xFF8A908D))
    } else {
        XmlColors(Color(0xFF1F6F6B), Color(0xFF8A5A12), Color(0xFF3B6E1F), Color(0xFF7A807D))
    }
    val transformation = remember(highlightXml, dark) {
        if (highlightXml) XmlHighlighter(colors) else VisualTransformation.None
    }
    val editorFocus = remember { FocusRequester() }

    Column(modifier) {
        if (showSearch) {
            SearchBar(value, onValueChange, onJump = { runCatching { editorFocus.requestFocus() } })
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
                .focusRequester(editorFocus),
        )
    }
}

@Composable
private fun SearchBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onJump: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    val text = value.text
    val count = if (query.isEmpty()) 0 else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).count()

    fun select(index: Int) {
        if (index >= 0) {
            onValueChange(value.copy(selection = TextRange(index, index + query.length)))
            onJump()
        }
    }

    fun findNext() {
        if (query.isEmpty()) return
        val from = value.selection.max
        val i = text.indexOf(query, from, ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf(query, 0, ignoreCase = true)
        select(i)
    }

    fun findPrevious() {
        if (query.isEmpty()) return
        val from = value.selection.min - 1
        val i = if (from >= 0) text.lastIndexOf(query, from, ignoreCase = true) else -1
        select(if (i >= 0) i else text.lastIndexOf(query, ignoreCase = true))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
            )
            Text(
                if (query.isEmpty()) "" else "$count",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(onClick = ::findPrevious) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = ::findNext) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = replacement,
                onValueChange = { replacement = it },
                placeholder = { Text("Replace with") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
            )
            TextButton(onClick = {
                val sel = value.selection
                val selected = text.substring(sel.min, sel.max)
                if (query.isNotEmpty() && selected.equals(query, ignoreCase = true)) {
                    val newText = text.replaceRange(sel.min, sel.max, replacement)
                    onValueChange(TextFieldValue(newText, TextRange(sel.min + replacement.length)))
                } else {
                    findNext()
                }
            }) { Text("Replace") }
            TextButton(onClick = {
                if (query.isNotEmpty()) {
                    val newText = text.replace(query, replacement, ignoreCase = true)
                    onValueChange(TextFieldValue(newText, TextRange(0)))
                }
            }) { Text("All") }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
