package com.sangam.apkforge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangam.apkforge.data.ApkDetails
import com.sangam.apkforge.data.ApkInspector
import com.sangam.apkforge.data.ApkSigning
import com.sangam.apkforge.data.AppRepository
import com.sangam.apkforge.data.FileOps
import com.sangam.apkforge.data.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Details : Screen
    data class Files(val folder: String) : Screen
}

sealed interface DetailsState {
    data object Loading : DetailsState
    data class Loaded(val details: ApkDetails) : DetailsState
    data class Failed(val message: String) : DetailsState
}

/** A file waiting for the user to choose where to save it. */
sealed interface PendingSave {
    val suggestedName: String

    data class WholeFile(val file: File, override val suggestedName: String) : PendingSave
    data class ZipEntry(val apk: File, val entryPath: String, override val suggestedName: String) : PendingSave
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repo = AppRepository(app)
    private val inspector = ApkInspector(repo)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    private val _details = MutableStateFlow<DetailsState>(DetailsState.Loading)
    val details: StateFlow<DetailsState> = _details.asStateFlow()

    /** Short text shown at the bottom of the screen ("Saved", errors, etc.). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Non-null while a long job (signing) runs; the text explains what is happening. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** One-shot request for the UI to open the "Save as" screen. The UI clears it once launched. */
    private val _saveRequest = MutableStateFlow<PendingSave?>(null)
    val saveRequest: StateFlow<PendingSave?> = _saveRequest.asStateFlow()

    /** What to write once the user has picked a location. */
    @Volatile
    private var awaitingSave: PendingSave? = null

    private fun askWhereToSave(save: PendingSave) {
        awaitingSave = save
        _saveRequest.value = save
    }

    fun saveRequestLaunched() {
        _saveRequest.value = null
    }

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = null
            _apps.value = runCatching { repo.installedApps() }.getOrElse {
                _message.value = "Could not list apps: ${it.message}"
                emptyList()
            }
        }
    }

    fun openInstalledApp(app: InstalledApp) {
        val splits = repo.splitPathsFor(app.packageName)
        load(File(app.sourceDir), fromInstalledApp = true, splits = splits)
    }

    fun openApkUri(uri: Uri) {
        _screen.value = Screen.Details
        _details.value = DetailsState.Loading
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { FileOps.copyUriToCache(getApplication(), uri) }
            }.getOrElse {
                _details.value = DetailsState.Failed(it.message ?: "Could not open the file.")
                return@launch
            }
            load(file, fromInstalledApp = false, splits = emptyList())
        }
    }

    private fun load(file: File, fromInstalledApp: Boolean, splits: List<String>) {
        _screen.value = Screen.Details
        _details.value = DetailsState.Loading
        viewModelScope.launch {
            _details.value = withContext(Dispatchers.IO) {
                runCatching { inspector.inspect(file, fromInstalledApp, splits) }
                    .fold(
                        onSuccess = { DetailsState.Loaded(it) },
                        onFailure = { DetailsState.Failed(it.message ?: "Could not read this APK.") },
                    )
            }
        }
    }

    // ---- navigation ----

    fun openFiles(folder: String = "") {
        _screen.value = Screen.Files(folder)
    }

    /** Returns false when there is nowhere to go back to (the app should close). */
    fun back(): Boolean {
        when (val s = _screen.value) {
            Screen.Home -> return false
            Screen.Details -> _screen.value = Screen.Home
            is Screen.Files -> {
                _screen.value = if (s.folder.isEmpty()) Screen.Details
                else Screen.Files(s.folder.substringBeforeLast('/', missingDelimiterValue = ""))
            }
        }
        return true
    }

    // ---- actions on the current APK ----

    private fun current(): ApkDetails? = (_details.value as? DetailsState.Loaded)?.details

    private fun baseName(d: ApkDetails) =
        FileOps.safeName("${d.label}_${d.versionName ?: d.versionCode}").replace(' ', '_')

    /** Save the APK exactly as it is. */
    fun requestSaveOriginal() {
        val d = current() ?: return
        askWhereToSave(PendingSave.WholeFile(d.file, "${baseName(d)}.apk"))
    }

    /** Sign the APK with APK Forge's key, then ask where to save it. */
    fun requestSignAndSave() {
        val d = current() ?: return
        runBusy("Signing APK...") {
            val out = signedCopy(d)
            askWhereToSave(PendingSave.WholeFile(out, "${baseName(d)}_signed.apk"))
        }
    }

    /** Sign the APK, then open Android's installer. */
    fun signAndInstall() {
        val d = current() ?: return
        runBusy("Signing APK...") {
            val out = signedCopy(d)
            val opened = withContext(Dispatchers.Main) { FileOps.openInstaller(getApplication(), out) }
            if (!opened) {
                _message.value = "Allow \"Install unknown apps\" for APK Forge, then tap Install again."
            }
        }
    }

    fun requestSaveEntry(entryPath: String) {
        val d = current() ?: return
        askWhereToSave(PendingSave.ZipEntry(d.file, entryPath, entryPath.substringAfterLast('/')))
    }

    /** Called with the location the user picked (or null if they cancelled). */
    fun completeSave(uri: Uri?) {
        val pending = awaitingSave ?: return
        awaitingSave = null
        if (uri == null) return
        runBusy("Saving...") {
            when (pending) {
                is PendingSave.WholeFile -> FileOps.copyFileToUri(getApplication(), pending.file, uri)
                is PendingSave.ZipEntry ->
                    FileOps.copyZipEntryToUri(getApplication(), pending.apk, pending.entryPath, uri)
            }
            _message.value = "Saved ${pending.suggestedName}"
        }
    }

    fun clearMessage() = _message.update { null }

    private suspend fun signedCopy(d: ApkDetails): File = withContext(Dispatchers.IO) {
        val out = File(FileOps.workDir(getApplication(), "signed"), "${baseName(d)}_signed.apk")
        ApkSigning.sign(getApplication(), d.file, out)
        out
    }

    private fun runBusy(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = label
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = null
            }
        }
    }
}
