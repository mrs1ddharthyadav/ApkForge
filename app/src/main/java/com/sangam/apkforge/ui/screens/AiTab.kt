package com.sangam.apkforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sangam.apkforge.ChatItem
import com.sangam.apkforge.ProjectViewModel
import com.sangam.apkforge.ui.PrimaryAction
import com.sangam.apkforge.ui.SectionCard

private val EXAMPLES = listOf(
    "What screens and texts does this app have?",
    "Change the app's main color to dark green",
    "Translate all the app's texts into Hindi",
    "Make the buttons' text bigger",
)

@Composable
fun AiTab(pvm: ProjectViewModel, onOpenSettings: () -> Unit) {
    val chat by pvm.chat.collectAsState()
    val running by pvm.aiRunning.collectAsState()
    val tokens by pvm.tokens.collectAsState()
    val draft by pvm.draft.collectAsState()

    if (!pvm.hasApiKey()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("AI assistant") {
                Text(
                    "Tell Claude what you want to change in plain words, and it finds the right files and edits them for you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "To start, add your Claude API key in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PrimaryAction("Open Settings", Icons.Filled.Settings, onOpenSettings, Modifier.fillMaxWidth())
            }
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pvm.modelName() + if (tokens.first + tokens.second > 0) "  ·  ${formatTokens(tokens.first + tokens.second)} tokens" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("Settings") }
            TextButton(onClick = pvm::newChat, enabled = !running && chat.isNotEmpty()) { Text("New chat") }
        }
        HorizontalDivider()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("What should I change?", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Describe it in your own words. I'll look through the APK, make the changes, and tell you what I did. " +
                            "Nothing is final until you tap Build.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Try:", style = MaterialTheme.typography.labelLarge)
                    EXAMPLES.forEach { ex ->
                        SuggestionChip(
                            onClick = { pvm.draft.value = ex },
                            label = { Text(ex) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(chat, key = { it.id }) { item -> ChatRow(item) }
                }
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { pvm.draft.value = it },
                placeholder = { Text(if (running) "Working..." else "Tell Claude what to change") },
                enabled = !running,
                maxLines = 5,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                FilledIconButton(onClick = pvm::stopAi, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(
                    onClick = { pvm.sendPrompt(draft) },
                    enabled = draft.isNotBlank(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            SelectionContainer {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        is ChatItem.Assistant -> SelectionContainer {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        is ChatItem.Activity -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 4.dp),
        ) {
            when {
                item.running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                item.error -> Icon(Icons.Filled.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                else -> Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                item.text + if (item.error) " (didn't work)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ChatItem.Problem -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
