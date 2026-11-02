package com.sangam.apkforge.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sangam.apkforge.DetailsState
import com.sangam.apkforge.EditForm
import com.sangam.apkforge.EditState
import com.sangam.apkforge.MainViewModel
import com.sangam.apkforge.engine.ApkEditEngine
import com.sangam.apkforge.ui.AppIcon
import com.sangam.apkforge.ui.ButtonRow
import com.sangam.apkforge.ui.PrimaryAction
import com.sangam.apkforge.ui.SecondaryAction
import com.sangam.apkforge.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: MainViewModel, snackbar: SnackbarHostState, onPickIcon: () -> Unit) {
    val state by vm.edit.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit app") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                EditState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is EditState.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                is EditState.Ready -> EditFormContent(vm, s.current, onPickIcon)
            }
        }
    }
}

@Composable
private fun EditFormContent(vm: MainViewModel, cur: ApkEditEngine.CurrentValues, onPickIcon: () -> Unit) {
    val busy by vm.busy.collectAsState()
    val newIcon by vm.newIcon.collectAsState()
    val details = (vm.details.collectAsState().value as? DetailsState.Loaded)?.details

    // Keyed on the package so the fields reset when a different APK is opened.
    var appName by rememberSaveable(cur.packageName) { mutableStateOf(cur.appName.orEmpty()) }
    var pkg by rememberSaveable(cur.packageName) { mutableStateOf(cur.packageName.orEmpty()) }
    var versionName by rememberSaveable(cur.packageName) { mutableStateOf(cur.versionName.orEmpty()) }
    var versionCode by rememberSaveable(cur.packageName) { mutableStateOf(cur.versionCode?.toString().orEmpty()) }

    val form = EditForm(appName, pkg, versionName, versionCode)
    val enabled = busy == null
    val packageChanged = pkg.trim().isNotEmpty() && pkg.trim() != cur.packageName

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (details != null && details.splitApks.isNotEmpty()) {
            SectionCard("Merge first") {
                Text(
                    "This app is split into ${details.splitApks.size + 1} APK files. Go back and tap " +
                        "\"Merge into one APK\" before editing, or the edited app won't install.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard("Icon") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val bmp = newIcon
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "New icon",
                        modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium),
                    )
                } else if (details != null) {
                    val key = if (details.fromInstalledApp) "pkg:${details.packageName}"
                    else "file:${details.file.absolutePath}:${details.file.lastModified()}"
                    AppIcon(key = key, size = 64.dp) {
                        if (details.fromInstalledApp) vm.repo.iconFor(details.packageName)
                        else vm.repo.iconForApkFile(details.file)
                    }
                }
                Column(Modifier.weight(1f)) {
                    SecondaryAction("Choose image", Icons.Filled.Image, onPickIcon, enabled = enabled)
                    if (bmp != null) {
                        TextButton(onClick = vm::clearNewIcon, enabled = enabled) {
                            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Keep original icon")
                        }
                    }
                }
            }
            Text(
                "Square images look best. The image is cropped to a square and resized to 192 x 192.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Name and version") {
            OutlinedTextField(
                value = appName,
                onValueChange = { appName = it },
                label = { Text("App name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            OutlinedTextField(
                value = versionName,
                onValueChange = { versionName = it },
                label = { Text("Version name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            OutlinedTextField(
                value = versionCode,
                onValueChange = { v -> versionCode = v.filter { it.isDigit() } },
                label = { Text("Version code") },
                supportingText = { Text("A whole number. Android only installs updates with a higher code.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
        }

        SectionCard("Package name") {
            OutlinedTextField(
                value = pkg,
                onValueChange = { v -> pkg = v.filter { !it.isWhitespace() } },
                label = { Text("Package name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            Text(
                if (packageChanged)
                    "A new package name makes a separate copy (a clone) that installs next to the original. " +
                        "Some apps check their own name or signature and may close or refuse to log in."
                else
                    "Change this to make a clone that installs next to the original app.",
                style = MaterialTheme.typography.bodySmall,
                color = if (packageChanged) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ButtonRow {
            PrimaryAction(
                "Save APK",
                Icons.Filled.Save,
                { vm.applyEdits(form, install = false) },
                Modifier.weight(1f),
                enabled,
            )
            SecondaryAction(
                "Install",
                Icons.Filled.InstallMobile,
                { vm.applyEdits(form, install = true) },
                Modifier.weight(1f),
                enabled,
            )
        }
        Text(
            "The edited APK is signed with APK Forge's key. If you kept the same package name, " +
                "uninstall the original app before installing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
