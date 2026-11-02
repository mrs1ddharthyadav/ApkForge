package com.sangam.apkforge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sangam.apkforge.ui.screens.DetailsScreen
import com.sangam.apkforge.ui.screens.EditScreen
import com.sangam.apkforge.ui.screens.FilesScreen
import com.sangam.apkforge.ui.screens.HomeScreen
import com.sangam.apkforge.ui.theme.ApkForgeTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleViewIntent(intent)
        setContent {
            ApkForgeTheme {
                App(vm, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** Handles "Open with APK Forge" from a file manager. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { vm.openApkUri(it) }
        }
    }
}

@Composable
private fun App(vm: MainViewModel, onExit: () -> Unit) {
    val screen by vm.screen.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val saveRequest by vm.saveRequest.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openApkUri(uri)
    }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.pickIcon(uri)
    }
    val saveApk = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri -> vm.completeSave(uri) }
    val saveOther = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> vm.completeSave(uri) }

    LaunchedEffect(saveRequest) {
        val req = saveRequest ?: return@LaunchedEffect
        vm.saveRequestLaunched()
        if (req is PendingSave.WholeFile) saveApk.launch(req.suggestedName)
        else saveOther.launch(req.suggestedName)
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.clearMessage()
    }

    BackHandler {
        if (!vm.back()) onExit()
    }

    val openPicker = {
        // APKs and bundles (.apks, .xapk, .apkm) get many different labels from file managers,
        // so accept any file and check what it really is after opening it.
        pickApk.launch(arrayOf("*/*"))
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(vm, snackbar, onPickApk = openPicker)
        Screen.Details -> DetailsScreen(vm, snackbar)
        is Screen.Files -> FilesScreen(vm, s.folder, snackbar)
        Screen.Edit -> EditScreen(vm, snackbar, onPickIcon = { pickIcon.launch("image/*") })
    }

    busy?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(label)
                }
            },
        )
    }
}
