package com.sangam.apkforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sangam.apkforge.DetailsState
import com.sangam.apkforge.MainViewModel
import com.sangam.apkforge.data.ApkDetails
import com.sangam.apkforge.data.formatSize
import com.sangam.apkforge.ui.AppIcon
import com.sangam.apkforge.ui.ButtonRow
import com.sangam.apkforge.ui.ExpandableListCard
import com.sangam.apkforge.ui.InfoRow
import com.sangam.apkforge.ui.Mono
import com.sangam.apkforge.ui.PrimaryAction
import com.sangam.apkforge.ui.SecondaryAction
import com.sangam.apkforge.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(vm: MainViewModel, snackbar: SnackbarHostState, onFullEdit: () -> Unit) {
    val state by vm.details.collectAsState()
    val busy by vm.busy.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text((state as? DetailsState.Loaded)?.details?.label ?: "APK details", maxLines = 1)
                },
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
                DetailsState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Reading APK...", modifier = Modifier.padding(top = 12.dp))
                }
                is DetailsState.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                is DetailsState.Loaded -> DetailsContent(vm, s.details, enabled = busy == null, onFullEdit = onFullEdit)
            }
        }
    }
}

@Composable
private fun DetailsContent(vm: MainViewModel, d: ApkDetails, enabled: Boolean, onFullEdit: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val iconKey = if (d.fromInstalledApp) "pkg:${d.packageName}" else "file:${d.file.absolutePath}:${d.file.lastModified()}"
            AppIcon(key = iconKey, size = 64.dp) {
                if (d.fromInstalledApp) vm.repo.iconFor(d.packageName) else vm.repo.iconForApkFile(d.file)
            }
            Column(Modifier.weight(1f)) {
                Text(d.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(d.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Version ${d.versionName ?: "?"} (${d.versionCode})  ·  ${formatSize(d.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (d.splitApks.isNotEmpty()) {
            Notice(
                "This app is installed as ${d.splitApks.size + 1} APK files (a split APK). " +
                    "Only the main (base) APK is shown here, and it won't work on its own. " +
                    "Merge the files into one APK before saving, editing or installing."
            )
            PrimaryAction(
                "Merge into one APK",
                Icons.Filled.CallMerge,
                vm::mergeInstalledSplits,
                Modifier.fillMaxWidth(),
                enabled,
            )
        }

        // Actions
        ButtonRow {
            PrimaryAction("Edit app", Icons.Filled.Edit, vm::openEditor, Modifier.weight(1f), enabled)
            PrimaryAction("Full edit", Icons.Filled.Code, onFullEdit, Modifier.weight(1f), enabled)
        }
        Text(
            "Edit app: name, icon, version, package. Full edit: every file, layout and text inside the APK.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ButtonRow {
            SecondaryAction("Save APK", Icons.Filled.Download, vm::requestSaveOriginal, Modifier.weight(1f), enabled)
            SecondaryAction("Browse files", Icons.Filled.FolderZip, { vm.openFiles() }, Modifier.weight(1f), enabled)
        }
        ButtonRow {
            SecondaryAction("Sign & save", Icons.Filled.Draw, vm::requestSignAndSave, Modifier.weight(1f), enabled)
            SecondaryAction("Sign & install", Icons.Filled.InstallMobile, vm::signAndInstall, Modifier.weight(1f), enabled)
        }
        Text(
            "Signing uses APK Forge's own key. Android won't install a re-signed app over the original, " +
                "so uninstall the original first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard("About this APK") {
            InfoRow("Package", d.packageName)
            InfoRow("Version name", d.versionName ?: "Not set")
            InfoRow("Version code", d.versionCode.toString())
            InfoRow("Min Android", d.minSdk?.let { sdkLabel(it) } ?: "Unknown")
            InfoRow("Target Android", d.targetSdk?.let { sdkLabel(it) } ?: "Unknown")
            InfoRow("File size", formatSize(d.sizeBytes))
            InfoRow("Files inside", d.entries.size.toString())
            InfoRow("Location", d.file.absolutePath)
        }

        SectionCard("Signature") {
            val sig = d.signature
            InfoRow("Status", if (sig.verified) "Valid" else "Not valid or not signed")
            InfoRow("Schemes", sig.schemes.joinToString().ifEmpty { "None" })
            sig.certificates.forEachIndexed { i, c ->
                if (sig.certificates.size > 1) Text("Certificate ${i + 1}", fontWeight = FontWeight.SemiBold)
                InfoRow("Owner", c.subject)
                InfoRow("Issuer", c.issuer)
                InfoRow("Valid until", c.validUntil)
                Text("SHA-256", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Mono(c.sha256)
            }
            if (sig.problems.isNotEmpty()) {
                Text("Problems found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                sig.problems.take(5).forEach { Mono(it) }
            }
        }

        ExpandableListCard("Permissions", d.permissions, "This app asks for no permissions.")
        ExpandableListCard("Activities", d.activities, "No activities declared.")
        ExpandableListCard("Services", d.services, "No services declared.")
        ExpandableListCard("Receivers", d.receivers, "No receivers declared.")
        ExpandableListCard("Providers", d.providers, "No providers declared.")
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val androidVersions = mapOf(
    21 to "5.0", 22 to "5.1", 23 to "6", 24 to "7.0", 25 to "7.1", 26 to "8.0", 27 to "8.1",
    28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14",
    35 to "15", 36 to "16",
)

private fun sdkLabel(api: Int): String =
    androidVersions[api]?.let { "Android $it (API $api)" } ?: "API $api"
