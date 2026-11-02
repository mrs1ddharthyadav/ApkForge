package com.sangam.apkforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sangam.apkforge.DetailsState
import com.sangam.apkforge.MainViewModel
import com.sangam.apkforge.data.ZipEntryInfo
import com.sangam.apkforge.data.formatSize
import com.sangam.apkforge.ui.InfoRow
import com.sangam.apkforge.ui.Mono

/** One row in the browser: either a folder or a file inside the APK. */
private sealed interface Node {
    val name: String

    data class Dir(override val name: String, val path: String, val fileCount: Int) : Node
    data class FileNode(override val name: String, val entry: ZipEntryInfo) : Node
}

private fun childrenOf(entries: List<ZipEntryInfo>, folder: String): List<Node> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val dirs = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
    val files = mutableListOf<Node.FileNode>()
    for (e in entries) {
        if (!e.path.startsWith(prefix)) continue
        val rest = e.path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash < 0) files += Node.FileNode(rest, e)
        else {
            val dir = rest.substring(0, slash)
            dirs[dir] = (dirs[dir] ?: 0) + 1
        }
    }
    return dirs.map { (name, count) -> Node.Dir(name, prefix + name, count) } +
        files.sortedBy { it.name.lowercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: MainViewModel, folder: String, snackbar: SnackbarHostState) {
    val state by vm.details.collectAsState()
    val details = (state as? DetailsState.Loaded)?.details
    val nodes = remember(details, folder) { details?.let { childrenOf(it.entries, folder) }.orEmpty() }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(details?.label ?: "Files", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "/" + folder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one folder")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (nodes.isEmpty()) {
                item {
                    Text(
                        "This folder is empty.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(nodes, key = { (it as? Node.Dir)?.path ?: (it as Node.FileNode).entry.path }) { node ->
                when (node) {
                    is Node.Dir -> NodeRow(
                        icon = Icons.Filled.Folder,
                        name = node.name,
                        detail = "${node.fileCount} files",
                    ) { vm.openFiles(node.path) }
                    is Node.FileNode -> NodeRow(
                        icon = iconFor(node.name),
                        name = node.name,
                        detail = formatSize(node.entry.size),
                    ) { selectedPath = node.entry.path }
                }
            }
        }
    }

    val selected = selectedPath?.let { p -> details?.entries?.firstOrNull { it.path == p } }
    if (selected != null) {
        AlertDialog(
            onDismissRequest = { selectedPath = null },
            title = { Text(selected.path.substringAfterLast('/')) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mono(selected.path)
                    InfoRow("Size", formatSize(selected.size))
                    InfoRow("Compressed", formatSize(selected.compressedSize))
                    Text(
                        "Viewing and editing files inside the APK comes in the next stage. " +
                            "For now you can save a copy of this file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.requestSaveEntry(selected.path)
                    selectedPath = null
                }) { Text("Save a copy") }
            },
            dismissButton = {
                TextButton(onClick = { selectedPath = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun NodeRow(icon: ImageVector, name: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun iconFor(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "webp", "gif" -> Icons.Filled.Image
        "xml", "json", "arsc", "smali" -> Icons.Filled.Code
        "dex", "so" -> Icons.Filled.Memory
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
