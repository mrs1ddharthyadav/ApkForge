package com.sangam.apkforge

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangam.apkforge.data.ApkDetails
import com.sangam.apkforge.data.ApkInspector
import com.sangam.apkforge.data.ApkSigning
import com.sangam.apkforge.data.AppRepository
import com.sangam.apkforge.data.FileOps
import com.sangam.apkforge.data.InstalledApp
import com.sangam.apkforge.engine.ApkEditEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Details : Screen
    data class Files(val folder: String) : Screen
    data object Edit : Screen
}

sealed interface DetailsState {
    data object Loading : DetailsState
    data class Loaded(val details: ApkDetails) : DetailsState
    data class Failed(val message: String) : DetailsState
}

sealed interface EditState {
    data object Loading : EditState
    data class Ready(val current: ApkEditEngine.CurrentValues) : EditState
    data class Failed(val message: String) : EditState
}

/** What the user typed on the edit screen. Blank text means "keep as it is". */
data class EditForm(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
)

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

    private val _edit = MutableStateFlow<EditState>(EditState.Loading)
    val edit: StateFlow<EditState> = _edit.asStateFlow()

    /** New icon chosen on the edit screen, already converted to a 192 x 192 PNG. */
    private val _newIcon = MutableStateFlow<Bitmap?>(null)
    val newIcon: StateFlow<Bitmap?> = _newIcon.asStateFlow()
    private var newIconPng: ByteArray? = null

    /** Short text shown at the bottom of the screen ("Saved", errors, etc.). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Non-null while a long job runs; the text explains what is happening. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** One-shot request for the UI to open the "Save as" screen. The UI clears it once launched. */
    private val _saveRequest = MutableStateFlow<PendingSave?>(null)
    val saveRequest: StateFlow<PendingSave?> = _saveRequest.asStateFlow()

    /** What to write once the user has picked a location. */
    @Volatile
    private var awaitingSave: PendingSave? = null

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

    // ---- opening things ----

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
            if (FileOps.isBundle(file)) openBundle(file)
            else load(file, fromInstalledApp = false, splits = emptyList())
        }
    }

    /** .apks / .xapk / .apkm files hold several APKs. Merge them into one first. */
    private suspend fun openBundle(bundle: File) {
        _busy.value = "Merging the APKs inside ${bundle.name}..."
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val work = FileOps.workDir(getApplication(), "merge")
                val unsigned = File(work, "merged_unsigned.apk")
                ApkEditEngine.mergeBundleFile(bundle, work, unsigned)
                val signed = File(FileOps.workDir(getApplication(), "merged"), bundle.nameWithoutExtension + "_merged.apk")
                ApkSigning.sign(getApplication(), unsigned, signed)
                unsigned.delete()
                signed
            }
        }
        _busy.value = null
        result.fold(
            onSuccess = {
                _message.value = "Merged into one APK. You can now save, edit or install it."
                load(it, fromInstalledApp = false, splits = emptyList())
            },
            onFailure = { _details.value = DetailsState.Failed("Could not open this bundle: ${it.message}") },
        )
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
            Screen.Edit -> _screen.value = Screen.Details
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
            val out = signedCopy(d.file, "${baseName(d)}_signed.apk")
            askWhereToSave(PendingSave.WholeFile(out, out.name))
        }
    }

    /** Sign the APK, then open Android's installer. */
    fun signAndInstall() {
        val d = current() ?: return
        runBusy("Signing APK...") {
            install(signedCopy(d.file, "${baseName(d)}_signed.apk"))
        }
    }

    /** Merges an installed split app (base + splits) into one APK and opens the result. */
    fun mergeInstalledSplits() {
        val d = current() ?: return
        if (d.splitApks.isEmpty()) return
        runBusy("Merging ${d.splitApks.size + 1} APK files...") {
            val work = FileOps.workDir(getApplication(), "merge")
            val unsigned = File(work, "merged_unsigned.apk")
            val parts = listOf(d.file) + d.splitApks.map(::File)
            ApkEditEngine.mergeSplits(parts, work, unsigned)
            val signed = signedCopy(unsigned, "${baseName(d)}_merged.apk", subDir = "merged")
            unsigned.delete()
            withContext(Dispatchers.Main) {
                _message.value = "Merged into one APK. You can now save, edit or install it."
                load(signed, fromInstalledApp = false, splits = emptyList())
            }
        }
    }

    fun requestSaveEntry(entryPath: String) {
        val d = current() ?: return
        askWhereToSave(PendingSave.ZipEntry(d.file, entryPath, entryPath.substringAfterLast('/')))
    }

    // ---- quick edit ----

    fun openEditor() {
        val d = current() ?: return
        _screen.value = Screen.Edit
        _edit.value = EditState.Loading
        clearNewIcon()
        viewModelScope.launch {
            _edit.value = withContext(Dispatchers.IO) {
                runCatching { ApkEditEngine.readCurrent(d.file) }.fold(
                    onSuccess = { cur ->
                        // Android's own label is more reliable for display when the APK has many languages.
                        if (cur.appName.isNullOrBlank()) cur.appName = d.label
                        EditState.Ready(cur)
                    },
                    onFailure = { EditState.Failed(it.message ?: "Could not read this APK.") },
                )
            }
        }
    }

    /** Loads a picked image, crops it to a square and turns it into a 192 x 192 PNG. */
    fun pickIcon(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val src = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { BitmapFactory.decodeStream(it) }
                        ?: error("Could not read that image.")
                    val side = minOf(src.width, src.height)
                    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    val scaled = Bitmap.createScaledBitmap(square, 192, 192, true)
                    val png = ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    scaled to png
                }
            }
            result.fold(
                onSuccess = { (bmp, png) ->
                    _newIcon.value = bmp
                    newIconPng = png
                },
                onFailure = { _message.value = it.message ?: "Could not read that image." },
            )
        }
    }

    fun clearNewIcon() {
        _newIcon.value = null
        newIconPng = null
    }

    /**
     * Applies the edits, signs the result, then either opens the installer or asks where to save.
     */
    fun applyEdits(form: EditForm, install: Boolean) {
        val d = current() ?: return
        val cur = (_edit.value as? EditState.Ready)?.current ?: return

        val versionCode = form.versionCode.trim().takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: run {
                _message.value = "Version code must be a whole number, like 42."
                return
            }
        }
        val edits = ApkEditEngine.Edits().apply {
            appName = form.appName.trim().takeIf { it.isNotEmpty() && it != cur.appName }
            packageName = form.packageName.trim().takeIf { it.isNotEmpty() && it != cur.packageName }
            versionName = form.versionName.trim().takeIf { it.isNotEmpty() && it != cur.versionName }
            this.versionCode = versionCode?.takeIf { it != cur.versionCode }
            iconPng = newIconPng
        }
        val nothingChanged = edits.appName == null && edits.packageName == null &&
            edits.versionName == null && edits.versionCode == null && edits.iconPng == null
        if (nothingChanged) {
            _message.value = "Nothing was changed."
            return
        }

        runBusy("Building edited APK...") {
            val work = FileOps.workDir(getApplication(), "edit")
            val unsigned = File(work, "edited_unsigned.apk")
            ApkEditEngine.applyEdits(d.file, unsigned, edits)
            val name = FileOps.safeName("${edits.appName ?: d.label}_edited.apk").replace(' ', '_')
            val signed = signedCopy(unsigned, name)
            unsigned.delete()
            if (install) install(signed)
            else askWhereToSave(PendingSave.WholeFile(signed, signed.name))
        }
    }

    // ---- saving ----

    private fun askWhereToSave(save: PendingSave) {
        awaitingSave = save
        _saveRequest.value = save
    }

    fun saveRequestLaunched() {
        _saveRequest.value = null
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

    // ---- helpers ----

    private suspend fun install(apk: File) {
        val opened = withContext(Dispatchers.Main) { FileOps.openInstaller(getApplication(), apk) }
        if (!opened) {
            _message.value = "Allow \"Install unknown apps\" for APK Forge, then try again."
        }
    }

    private suspend fun signedCopy(input: File, name: String, subDir: String = "signed"): File =
        withContext(Dispatchers.IO) {
            val out = File(FileOps.workDir(getApplication(), subDir), FileOps.safeName(name))
            ApkSigning.sign(getApplication(), input, out)
            out
        }

    private fun runBusy(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = label
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: OutOfMemoryError) {
                _message.value = "This APK is too big for the phone's memory."
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = null
            }
        }
    }
}
