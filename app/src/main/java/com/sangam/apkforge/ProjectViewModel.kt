package com.sangam.apkforge

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangam.apkforge.data.ApkSigning
import com.sangam.apkforge.data.FileOps
import com.sangam.apkforge.data.SettingsStore
import com.sangam.apkforge.engine.AiAgent
import com.sangam.apkforge.engine.AiTools
import com.sangam.apkforge.engine.ApkProject
import com.sangam.apkforge.engine.ClaudeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
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

/** One line in the AI chat. */
sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem
    data class Assistant(override val id: Long, val text: String) : ChatItem

    /** Something Claude is doing with the APK, like "Reading res/layout/main.xml". */
    data class Activity(override val id: Long, val text: String, val running: Boolean, val error: Boolean) : ChatItem
    data class Problem(override val id: Long, val text: String) : ChatItem
}

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

    /** Selected tab in the full editor: 0 Files, 1 Strings, 2 AI. Kept here so it survives leaving the screen. */
    val tab = MutableStateFlow(0)

    // ---- AI assistant state ----

    private val settings = SettingsStore(app)
    private var agent: AiAgent? = null
    private var aiTools: AiTools? = null
    private var agentConfig: Triple<String, String, String>? = null
    private val nextId = AtomicLong(1)

    private val _chat = MutableStateFlow<List<ChatItem>>(emptyList())
    val chat: StateFlow<List<ChatItem>> = _chat.asStateFlow()

    private val _aiRunning = MutableStateFlow(false)
    val aiRunning: StateFlow<Boolean> = _aiRunning.asStateFlow()

    /** Tokens used by this chat so far: input to output. */
    private val _tokens = MutableStateFlow(0L to 0L)
    val tokens: StateFlow<Pair<Long, Long>> = _tokens.asStateFlow()

    /** Text typed in the AI box, kept while visiting Settings. */
    val draft = MutableStateFlow("")

    fun hasApiKey(): Boolean = settings.hasApiKey
    fun modelName(): String = SettingsStore.MODELS.firstOrNull { it.id == settings.model }?.title ?: settings.model

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
        tab.value = 0
        resetChat()
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
        agent?.cancel()
        _state.value = ProjectState.Closed
        _strings.value = null
        resetChat()
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

    /** Exports a readable copy of the whole APK (XML decoded) as a ZIP, then asks where to save it. */
    fun exportSource() {
        work("Exporting source...") {
            val p = requireProject()
            val name = FileOps.safeName("${label}_source").replace(' ', '_') + ".zip"
            val out = File(FileOps.workDir(getApplication(), "source"), name)
            val count = p.exportSource(out) { done, total, _ ->
                if (done % 25 == 0) setBusy("Exporting source... ($done of $total files)")
                true
            }
            showMessage("Exported $count files, with ${p.lastExportDecodedCount} XML files made readable.")
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

    // ---- AI assistant ----

    fun sendPrompt(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _aiRunning.value) return
        val key = settings.apiKey
        if (key.isNullOrBlank()) {
            add(ChatItem.Problem(nextId.getAndIncrement(), "Add your Claude API key in Settings first."))
            return
        }
        if (_state.value !is ProjectState.Open) return
        draft.value = ""
        add(ChatItem.User(nextId.getAndIncrement(), prompt))
        _aiRunning.value = true

        viewModelScope.launch {
            var changesBefore = 0
            var tools: AiTools? = null
            try {
                val pair = withContext(worker) { ensureAgent(key) }
                val currentAgent = pair.first
                val currentTools = pair.second
                tools = currentTools
                changesBefore = withContext(worker) { currentTools.changes.size }
                val runner = AiAgent.ToolRunner { name, input ->
                    runBlocking(worker) { currentTools.run(name, input) }
                }
                withContext(Dispatchers.IO) { currentAgent.run(prompt, runner, listener) }
            } catch (e: Exception) {
                val msg = e.message ?: "Something went wrong."
                if (msg == "Stopped.") add(ChatItem.Activity(nextId.getAndIncrement(), "Stopped", running = false, error = false))
                else add(ChatItem.Problem(nextId.getAndIncrement(), msg))
            } finally {
                finishRunningActivities()
                _aiRunning.value = false
                val t = tools
                if (t != null) {
                    val changed = withContext(worker) { t.changes.size } > changesBefore
                    if (changed) {
                        _strings.value = null
                        publish()
                    }
                }
            }
        }
    }

    fun stopAi() {
        agent?.cancel()
    }

    /** Forgets the conversation (the APK keeps any changes already made). */
    fun newChat() {
        if (_aiRunning.value) return
        resetChat()
    }

    private fun resetChat() {
        agent?.cancel()
        agent = null
        aiTools = null
        agentConfig = null
        _chat.value = emptyList()
        _tokens.value = 0L to 0L
        draft.value = ""
    }

    /** Creates the agent, or a new one if the key, model or workspace changed. Runs on [worker]. */
    private fun ensureAgent(key: String): Pair<AiAgent, AiTools> {
        val p = requireProject()
        val config = Triple(key, settings.model, settings.workspaceId)
        val existing = agent
        val tools = aiTools
        if (existing != null && tools != null && agentConfig == config) return existing to tools
        if (existing != null) {
            add(ChatItem.Activity(nextId.getAndIncrement(), "Settings changed, so this is a new conversation", running = false, error = false))
        }
        val client = ClaudeClient(key, settings.workspaceId)
        val created = AiAgent(client, settings.model, AiAgent.summarize(p, label))
        val newTools = AiTools(p)
        agent = created
        aiTools = newTools
        agentConfig = config
        return created to newTools
    }

    private val listener = object : AiAgent.Listener {
        override fun onText(text: String) = add(ChatItem.Assistant(nextId.getAndIncrement(), text))

        override fun onToolStarted(description: String) =
            add(ChatItem.Activity(nextId.getAndIncrement(), description, running = true, error = false))

        override fun onToolFinished(description: String, error: Boolean) {
            _chat.update { list ->
                val i = list.indexOfLast { it is ChatItem.Activity && it.running && it.text == description }
                if (i < 0) list + ChatItem.Activity(nextId.getAndIncrement(), description, running = false, error = error)
                else list.toMutableList().also { it[i] = (it[i] as ChatItem.Activity).copy(running = false, error = error) }
            }
        }

        override fun onUsage(inputTokens: Long, outputTokens: Long) {
            _tokens.value = inputTokens to outputTokens
        }
    }

    private fun add(item: ChatItem) {
        _chat.update { it + item }
    }

    private fun finishRunningActivities() {
        _chat.update { list ->
            list.map { if (it is ChatItem.Activity && it.running) it.copy(running = false) else it }
        }
    }

    // ---- helpers ----

    private fun requireProject(): ApkProject = project ?: throw IOException("No APK is open for editing.")

    /** Runs [block] on the project thread with a busy indicator, then refreshes the file list. */
    private fun work(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            setBusy(label)
            try {
                withContext(worker) {
                    block()
                    // The AI keeps a copy of files it has read; drop it so it sees manual edits.
                    aiTools?.clearCache()
                }
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
        agent?.cancel()
        runCatching { project?.close() }
        worker.close()
        super.onCleared()
    }
}
