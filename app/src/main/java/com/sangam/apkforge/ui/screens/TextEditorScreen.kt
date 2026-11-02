package com.sangam.apkforge.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sangam.apkforge.EditorState
import com.sangam.apkforge.ProjectViewModel
import com.sangam.apkforge.ui.CodeEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    pvm: ProjectViewModel,
    path: String,
    snackbar: SnackbarHostState,
    busy: Boolean,
    onClose: () -> Unit,
) {
    LaunchedEffect(path) { pvm.openEditor(path) }
    val state by pvm.editor.collectAsState()
    val ready = (state as? EditorState.Ready)?.takeIf { it.path == path }

    // Text being edited, and the last saved text (to know if there are unsaved changes).
    var value by remember(path, ready?.text) { mutableStateOf(TextFieldValue(ready?.text.orEmpty())) }
    var saved by remember(path, ready?.text) { mutableStateOf(ready?.text.orEmpty()) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = ready != null && value.text != saved
    val isXml = path.endsWith(".xml", ignoreCase = true) || path.endsWith(".svg", ignoreCase = true) ||
        path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)

    fun leave() {
        if (dirty) confirmLeave = true else onClose()
    }

    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (dirty) "Unsaved changes" else path,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (dirty) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            if (showSearch) Icons.Filled.SearchOff else Icons.Filled.Search,
                            contentDescription = "Find and replace",
                        )
                    }
                    TextButton(
                        onClick = {
                            val text = value.text
                            pvm.saveText(path, text) { saved = text }
                        },
                        enabled = dirty && !busy,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when (val s = state) {
                is EditorState.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                else -> if (ready == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    CodeEditor(
                        value = value,
                        onValueChange = { value = it },
                        highlightXml = isXml,
                        showSearch = showSearch,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save your changes to this file before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    val text = value.text
                    pvm.saveText(path, text) {
                        saved = text
                        onClose()
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Discard") }
            },
        )
    }
}
