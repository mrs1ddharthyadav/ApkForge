package com.sangam.apkforge.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sangam.apkforge.ProjectState
import com.sangam.apkforge.ProjectViewModel
import com.sangam.apkforge.data.formatSize
import com.sangam.apkforge.engine.ApkProject
import com.sangam.apkforge.ui.Mono
import com.sangam.apkforge.ui.SecondaryAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    pvm: ProjectViewModel,
    snackbar: SnackbarHostState,
    busy: Boolean,
    onOpenEditor: (String) -> Unit,
    onExit: () -> Unit,
) {
    val state by pvm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var confirmExit by remember { mutableStateOf(false) }
    var showBuild by remember { mutableStateOf(false) }
    val open = state as? ProjectState.Open
    val modified = open?.modified == true

    fun tryExit() {
        if (modified) confirmExit = true else onExit()
    }

    // Inside the Files tab, "back" goes up a folder first.
    BackHandler {
        if (tab == 0 && pvm.folderUp()) return@BackHandler
        tryExit()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(open?.title ?: "Full edit", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (modified) "Changed, not built yet" else "No changes yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (modified) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::tryExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close editor")
                    }
                },
                actions = {
                    if (open != null) {
                        TextButton(onClick = { showBuild = true }, enabled = !busy) {
                            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Build")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ProjectState.Closed, ProjectState.Opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Opening APK...", modifier = Modifier.padding(top = 12.dp))
                    }
                }
                is ProjectState.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                is ProjectState.Open -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Files") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Strings") })
                    }
                    if (tab == 0) FilesTab(pvm, s.files, busy, onOpenEditor)
                    else StringsTab(pvm, busy)
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard changes?") },
            text = { Text("You changed this APK but didn't build it. Leaving now throws the changes away.") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onExit() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Keep editing") }
            },
        )
    }

    if (showBuild) {
        AlertDialog(
            onDismissRequest = { showBuild = false },
            title = { Text("Build APK") },
            text = {
                Text(
                    "APK Forge builds a new APK with your changes and signs it. " +
                        "If the original app is installed, uninstall it before installing this one."
                )
            },
            confirmButton = {
                TextButton(onClick = { showBuild = false; pvm.build(install = true) }) { Text("Build & install") }
            },
            dismissButton = {
                TextButton(onClick = { showBuild = false; pvm.build(install = false) }) { Text("Build & save") }
            },
        )
    }
}

// ------------------------------------------------------------------ Files tab

private sealed interface Row2 {
    data class Dir(val name: String, val path: String, val count: Int) : Row2
    data class File(val name: String, val item: ApkProject.FileItem) : Row2
}

private fun rowsFor(files: List<ApkProject.FileItem>, folder: String): List<Row2> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val dirs = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
    val out = mutableListOf<Row2.File>()
    for (f in files) {
        if (!f.path.startsWith(prefix)) continue
        val rest = f.path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash < 0) out += Row2.File(rest, f) else {
            val d = rest.substring(0, slash)
            dirs[d] = (dirs[d] ?: 0) + 1
        }
    }
    return dirs.map { (n, c) -> Row2.Dir(n, prefix + n, c) } + out.sortedBy { it.name.lowercase() }
}

@Composable
private fun FilesTab(
    pvm: ProjectViewModel,
    files: List<ApkProject.FileItem>,
    busy: Boolean,
    onOpenEditor: (String) -> Unit,
) {
    val folder by pvm.folder.collectAsState()
    val rows = remember(files, folder) { rowsFor(files, folder) }
    var selected by remember { mutableStateOf<ApkProject.FileItem?>(null) }
    var replaceTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    val pickReplacement = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = replaceTarget
        replaceTarget = null
        if (uri != null && target != null) pvm.replaceFile(target, uri)
    }
    val pickNewFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pvm.addFile(folder, uri)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "/" + folder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (folder.isNotEmpty()) {
                    item(key = "..") {
                        FileRow(Icons.Filled.Folder, "..", "Up one folder") { pvm.folderUp() }
                    }
                }
                items(rows, key = { r -> if (r is Row2.Dir) "d:" + r.path else "f:" + (r as Row2.File).item.path }) { r ->
                    when (r) {
                        is Row2.Dir -> FileRow(Icons.Filled.Folder, r.name, "${r.count} files") { pvm.openFolder(r.path) }
                        is Row2.File -> FileRow(iconFor(r.item.kind), r.name, sizeText(r.item.size)) {
                            when (r.item.kind) {
                                ApkProject.Kind.BINARY_XML, ApkProject.Kind.TEXT -> onOpenEditor(r.item.path)
                                else -> selected = r.item
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { if (!busy) pickNewFile.launch("*/*") },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add a file to this folder")
        }
    }

    selected?.let { item ->
        FileActionsDialog(
            pvm = pvm,
            item = item,
            onDismiss = { selected = null },
            onReplace = {
                replaceTarget = item.path
                selected = null
                pickReplacement.launch(if (item.kind == ApkProject.Kind.IMAGE) "image/*" else "*/*")
            },
            onExport = { selected = null; pvm.exportFile(item.path) },
            onDelete = { selected = null; confirmDelete = item.path },
        )
    }

    confirmDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = {
                Text(
                    "Delete ${path.substringAfterLast('/')} from the APK? If the app needs this file it will crash. " +
                        "You can still discard all changes before building."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; pvm.deleteFile(path) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileActionsDialog(
    pvm: ProjectViewModel,
    item: ApkProject.FileItem,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val image: ImageBitmap? by produceState<ImageBitmap?>(null, item.path) {
        if (item.kind == ApkProject.Kind.IMAGE) {
            val bytes = pvm.readBytes(item.path)
            value = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    val canReplace = item.kind == ApkProject.Kind.IMAGE || item.kind == ApkProject.Kind.OTHER || item.kind == ApkProject.Kind.DEX
    val canDelete = item.kind != ApkProject.Kind.RESOURCE_TABLE && item.path != "AndroidManifest.xml"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(item.path)
                Text(sizeText(item.size), style = MaterialTheme.typography.bodySmall)
                image?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    )
                    Text("${bmp.width} x ${bmp.height} px", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when (item.kind) {
                        ApkProject.Kind.RESOURCE_TABLE -> "This is the app's resource table. Edit its texts in the Strings tab."
                        ApkProject.Kind.DEX -> "This is compiled code. A code editor comes in the next stage."
                        ApkProject.Kind.IMAGE -> if (item.path.endsWith(".9.png"))
                            "This is a stretchable 9-patch image. Replacing it with a normal picture can make it look wrong."
                        else "Replace it with an image of the same size for the best result."
                        else -> "You can replace, save a copy of, or delete this file."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canReplace) SecondaryAction("Replace", Icons.Filled.Image, onReplace, Modifier.fillMaxWidth())
                if (canDelete) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete from APK", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onExport) { Text("Save a copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun FileRow(icon: ImageVector, name: String, detail: String, onClick: () -> Unit) {
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

private fun iconFor(kind: ApkProject.Kind): ImageVector = when (kind) {
    ApkProject.Kind.BINARY_XML, ApkProject.Kind.TEXT -> Icons.Filled.Code
    ApkProject.Kind.IMAGE -> Icons.Filled.Image
    ApkProject.Kind.DEX -> Icons.Filled.Memory
    ApkProject.Kind.RESOURCE_TABLE -> Icons.Filled.TableChart
    ApkProject.Kind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun sizeText(size: Long) = if (size < 0) "" else formatSize(size)

// ------------------------------------------------------------------ Strings tab

@Composable
private fun StringsTab(pvm: ProjectViewModel, busy: Boolean) {
    val data by pvm.strings.collectAsState()
    LaunchedEffect(Unit) { pvm.loadStrings() }

    val d = data
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (d.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("This APK has no text strings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var lang by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<ApkProject.StringItem?>(null) }

    val visible = remember(d, lang, query) {
        d.items.filter { s ->
            s.values.containsKey(lang) && (query.isBlank() ||
                s.name.contains(query, true) || (s.values[lang]?.contains(query, true) == true))
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(d.languages) { q ->
                FilterChip(
                    selected = lang == q,
                    onClick = { lang = q },
                    label = { Text(ApkProject.describeQualifiers(q)) },
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search strings") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear search") }
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Text(
            "${visible.size} strings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.id }) { s ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { editing = s }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        s.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.values[lang].orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { s ->
        var text by remember(s.id, lang) { mutableStateOf(s.values[lang].orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Language: ${ApkProject.describeQualifiers(lang)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lang.isNotEmpty() && s.values.containsKey("")) {
                        Text(
                            "Default: " + s.values[""],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = MaterialTheme.shapes.small,
                    )
                    Text(
                        "Keep codes like %s, %d or %1\$s as they are. The app fills them in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pvm.setString(s, lang, text)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
