package com.sangam.apkforge

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangam.apkforge.data.ApkSigning
import com.sangam.apkforge.data.FileOps
import com.sangam.apkforge.engine.ApkProject
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

sealed interface ProjectState {
    data object Closed : ProjectState
    data object Opening : ProjectState
    data class Open(
        val title: String,
        val files: List<ApkProject.FileItem>,
        val modified: Boolean,
    ) : ProjectState
    data class Failed(val message: String) : ProjectState
}

data class StringsData(
    val items: List<ApkProject.StringItem>,
    val languages: List<String>,
)

sealed interface EditorState {
    data object Loading : EditorState
    data class Ready(val path: String, val text: String) : EditorState
    data class Failed(val path: String, val message: String) : EditorState
}

/**
 * Full-edit sessions ("projects"). An open project keeps the APK in memory; every change
 * stays there until the user taps Build.
 *
 * All work on the project runs on ONE background thread with a large stack: the engine is
 * not thread safe, and decoding deeply nested XML needs more stack than usual.
 */
class ProjectViewModel(app: Application) : AndroidViewModel(app) {

    private val worker: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "apk-project", 16L * 1024 * 1024)
    }.asCoroutineDispatcher()

    private var project: ApkProject? = null
    private var label: String = ""

    private val _state = MutableStateFlow<ProjectState>(ProjectState.Closed)
    val state: StateFlow<ProjectState> = _state.asStateFlow()

    private val _strings = MutableStateFlow<StringsData?>(null)
    val strings: StateFlow<StringsData?> = _strings.asStateFlow()

    private val _editor = MutableStateFlow<EditorState>(EditorState.Loading)
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    /** Folder shown in the Files tab ("" is the top). */
    private val _folder = MutableStateFlow("")
    val folder: StateFlow<String> = _folder.asStateFlow()

    // Shared with MainViewModel through these callbacks (set by MainActivity).
    var showMessage: (String) -> Unit = {}
    var setBusy: (String?) -> Unit = {}
    var askToSave: (File, String) -> Unit = { _, _ -> }
    var install: (File) -> Unit = {}

    // ---- open / close ----

    fun open(apk: File, title: String) {
        _state.value = ProjectState.Opening
        _strings.value = null
        _folder.value = ""
        label = title
        viewModelScope.launch {
            val result = withContext(worker) {
                runCatching {
                    project?.close()
                    project = null
                    ApkProject.open(apk).also { project = it }
                }
            }
            result.fold(
                onSuccess = { publish() },
                onFailure = { _state.value = ProjectState.Failed(it.message ?: "Could not open this APK.") },
            )
        }
    }

    fun close() {
        _state.value = ProjectState.Closed
        _strings.value = null
        viewModelScope.launch(worker) {
            runCatching { project?.close() }
            project = null
        }
    }

    private suspend fun publish() {
        val snapshot = withContext(worker) {
            val p = project ?: return@withContext null
            Triple(p.listFiles(), p.isModified, label)
        } ?: return
        _state.value = ProjectState.Open(snapshot.third, snapshot.first, snapshot.second)
    }

    fun openFolder(path: String) {
        _folder.value = path
    }

    /** Goes up one folder. Returns false when already at the top. */
    fun folderUp(): Boolean {
        val f = _folder.value
        if (f.isEmpty()) return false
        _folder.value = f.substringBeforeLast('/', missingDelimiterValue = "")
        return true
    }

    // ---- files ----

    fun openEditor(path: String) {
        _editor.value = EditorState.Loading
        viewModelScope.launch {
            _editor.value = withContext(worker) {
                runCatching { requireProject().readText(path) }.fold(
                    onSuccess = { EditorState.Ready(path, it) },
                    onFailure = { EditorState.Failed(path, it.message ?: "Could not open this file.") },
                )
            }
        }
    }

    /** Saves editor text. [onSaved] runs only if the text was valid and stored. */
    fun saveText(path: String, text: String, onSaved: () -> Unit) {
        work("Saving...") {
            requireProject().writeText(path, text)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _editor.value = EditorState.Ready(path, text)
                onSaved()
            }
            showMessage("Saved. Tap Build when you're done editing.")
        }
    }

    suspend fun readBytes(path: String): ByteArray? = withContext(worker) {
        runCatching { requireProject().readBytes(path) }.getOrNull()
    }

    fun replaceFile(path: String, uri: Uri) {
        work("Replacing...") {
            val bytes = readUri(uri)
            requireProject().replaceFile(path, bytes)
            showMessage("Replaced ${path.substringAfterLast('/')}")
        }
    }

    fun addFile(folder: String, uri: Uri) {
        work("Adding file...") {
            val name = displayName(uri) ?: "new_file"
            val path = if (folder.isEmpty()) name else "$folder/$name"
            requireProject().addFile(path, readUri(uri))
            showMessage("Added $path")
        }
    }

    fun deleteFile(path: String) {
        work("Deleting...") {
            requireProject().deleteFile(path)
            showMessage("Deleted ${path.substringAfterLast('/')}")
        }
    }

    fun exportFile(path: String) {
        work("Preparing file...") {
            val bytes = requireProject().readBytes(path)
            val name = FileOps.safeName(path.substringAfterLast('/'))
            val out = File(FileOps.workDir(getApplication(), "export"), name)
            out.writeBytes(bytes)
            askToSave(out, name)
        }
    }

    // ---- strings ----

    fun loadStrings() {
        if (_strings.value != null) return
        viewModelScope.launch {
            val data = withContext(worker) {
                runCatching {
                    val p = requireProject()
                    StringsData(p.listStrings(), p.listStringLanguages())
                }
            }
            data.fold(
                onSuccess = { _strings.value = it },
                onFailure = { showMessage("Could not read strings: ${it.message}") },
            )
        }
    }

    fun setString(item: ApkProject.StringItem, qualifiers: String, value: String) {
        work("Saving...") {
            requireProject().setString(item.id, qualifiers, value)
            // Re-read so the list shows exactly what is stored.
            val p = requireProject()
            _strings.value = StringsData(p.listStrings(), p.listStringLanguages())
        }
    }

    // ---- build ----

    fun build(install: Boolean) {
        work("Building APK...") {
            val p = requireProject()
            val work = FileOps.workDir(getApplication(), "build")
            val unsigned = File(work, "built_unsigned.apk")
            p.build(unsigned)
            setBusy("Signing APK...")
            val name = FileOps.safeName("${label}_edited.apk").replace(' ', '_')
            val signed = File(FileOps.workDir(getApplication(), "signed"), name)
            ApkSigning.sign(getApplication(), unsigned, signed)
            unsigned.delete()
            if (install) withContext(kotlinx.coroutines.Dispatchers.Main) { this@ProjectViewModel.install(signed) }
            else askToSave(signed, name)
        }
    }

    // ---- helpers ----

    private fun requireProject(): ApkProject = project ?: throw IOException("No APK is open for editing.")

    /** Runs [block] on the project thread with a busy indicator, then refreshes the file list. */
    private fun work(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            setBusy(label)
            try {
                withContext(worker) { block() }
                publish()
            } catch (e: OutOfMemoryError) {
                showMessage("This APK is too big for the phone's memory.")
            } catch (e: Exception) {
                showMessage(e.message ?: "Something went wrong.")
            } finally {
                setBusy(null)
            }
        }
    }

    private fun readUri(uri: Uri): ByteArray =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read the chosen file.")

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.let { FileOps.safeName(it) }

    override fun onCleared() {
        runCatching { project?.close() }
        worker.close()
        super.onCleared()
    }
}
