package com.sangam.apkforge.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.reandroid.json.JSONArray
import com.reandroid.json.JSONObject
import com.sangam.apkforge.data.SettingsStore
import com.sangam.apkforge.engine.ClaudeClient
import com.sangam.apkforge.ui.ButtonRow
import com.sangam.apkforge.ui.PrimaryAction
import com.sangam.apkforge.ui.SecondaryAction
import com.sangam.apkforge.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEYS_URL = "https://platform.claude.com/settings/keys"
private const val BILLING_URL = "https://platform.claude.com/settings/billing"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var savedKey by remember { mutableStateOf(store.apiKey) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var workspace by remember { mutableStateOf(store.workspaceId) }
    var model by remember { mutableStateOf(store.model) }
    val isCustom = SettingsStore.MODELS.none { it.id == model }
    var customModel by remember { mutableStateOf(if (isCustom) model else "") }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text to isError
    var testing by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun testKey() {
        val key = savedKey ?: return
        testing = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val body = JSONObject()
                    body.put("model", store.model)
                    body.put("max_tokens", 16)
                    val msg = JSONObject()
                    msg.put("role", "user")
                    msg.put("content", "Reply with the single word OK.")
                    val msgs = JSONArray()
                    msgs.put(msg)
                    body.put("messages", msgs)
                    ClaudeClient(key, store.workspaceId).send(body)
                }
            }
            testing = false
            status = result.fold(
                onSuccess = { "Your key works with ${store.model}. You can use the AI tab in Full edit." to false },
                onFailure = { (it.message ?: "The test failed.") to true },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Claude API key") {
                Text(
                    "The AI assistant uses your own Claude API key. APK Forge stores it encrypted on this phone " +
                        "and sends it only to Anthropic's API.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val current = savedKey
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Key saved: " + SettingsStore.mask(current), fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it.trim() },
                    label = { Text(if (current == null) "Paste your API key" else "Paste a new key to replace it") },
                    placeholder = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key",
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (keyInput.isNotEmpty() && !keyInput.startsWith("sk-ant-")) {
                    Text(
                        "Claude API keys start with sk-ant-. Check that you copied the whole key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ButtonRow {
                    PrimaryAction(
                        "Save key",
                        Icons.Filled.Key,
                        {
                            store.apiKey = keyInput
                            savedKey = store.apiKey
                            keyInput = ""
                            status = "Key saved. Tap Test key to check it." to false
                        },
                        Modifier.weight(1f),
                        enabled = keyInput.length > 20,
                    )
                    SecondaryAction(
                        if (testing) "Testing..." else "Test key",
                        Icons.Filled.NetworkCheck,
                        ::testKey,
                        Modifier.weight(1f),
                        enabled = current != null && !testing,
                    )
                }
                status?.let { (text, isError) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                if (current != null) {
                    TextButton(onClick = {
                        store.apiKey = null
                        savedKey = null
                        status = "Key removed from this phone." to false
                    }) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
                }
            }

            SectionCard("How to get a key") {
                Text(
                    "1. Open the Claude Console and sign in (it's separate from the Claude chat app).\n" +
                        "2. Add credits under Billing. API use is paid per use and isn't included in a Claude Pro plan.\n" +
                        "3. On the API keys page tap Create key. Choose a workspace for the key (Default is fine).\n" +
                        "4. Copy the key (it's shown only once) and paste it above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ButtonRow {
                    SecondaryAction("API keys page", Icons.AutoMirrored.Filled.OpenInNew, { open(KEYS_URL) }, Modifier.weight(1f))
                    SecondaryAction("Billing page", Icons.AutoMirrored.Filled.OpenInNew, { open(BILLING_URL) }, Modifier.weight(1f))
                }
            }

            SectionCard("Model") {
                SettingsStore.MODELS.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                model = m.id
                                store.model = m.id
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == m.id, onClick = { model = m.id; store.model = m.id })
                        Column {
                            Text(m.title, style = MaterialTheme.typography.bodyLarge)
                            Text(m.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { v ->
                        customModel = v.trim()
                        if (customModel.isNotEmpty()) {
                            model = customModel
                            store.model = customModel
                        }
                    },
                    label = { Text("Other model ID (optional)") },
                    placeholder = { Text("e.g. claude-sonnet-5-5") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("In use: $model", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }

            SectionCard("Workspace ID (usually not needed)") {
                Text(
                    "Only fill this in if your key isn't linked to a workspace and the AI says it needs one. " +
                        "Find the ID under Workspaces in the Claude Console. It starts with wrkspc_.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = workspace,
                    onValueChange = {
                        workspace = it.trim()
                        store.workspaceId = workspace
                    },
                    placeholder = { Text("wrkspc_...") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "Privacy: when you use the AI, the parts of the APK it reads (file names, XML and texts) and your " +
                    "messages are sent to Anthropic to get an answer. Each request costs a small amount from your API credits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
