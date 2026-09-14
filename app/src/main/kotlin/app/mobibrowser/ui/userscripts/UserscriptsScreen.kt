package app.mobibrowser.ui.userscripts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mobibrowser.R
import app.mobibrowser.core.ext.MatchPattern
import app.mobibrowser.data.ScriptKind
import app.mobibrowser.data.UserScript
import app.mobibrowser.ui.MobiViewModel
import java.util.UUID

/**
 * User scripts e estilos próprios — o "e outras que a maioria dos navegadores possui" do
 * pedido, resolvido sem depender de loja nenhuma: o app injeta via MobiBridge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserscriptsScreen(vm: MobiViewModel, onDismiss: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var scripts by remember { mutableStateOf(emptyList<UserScript>()) }
    var editing by remember { mutableStateOf<UserScript?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) { scripts = vm.scripts() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importUserscript(uri)
            refresh++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.userscripts_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = { importPicker.launch(arrayOf("text/plain", "text/css", "*/*")) }) {
                        Icon(Icons.Default.UploadFile, contentDescription = stringResource(R.string.userscripts_import))
                    }
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.userscripts_add_script))
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            contentPadding = PaddingValues(top = insets.calculateTopPadding() + 8.dp, bottom = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    stringResource(R.string.userscripts_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (scripts.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.userscripts_none), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { creating = true }) {
                                    Text(stringResource(R.string.userscripts_add_script))
                                }
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { importPicker.launch(arrayOf("*/*", "text/plain")) },
                                ) { Text(stringResource(R.string.userscripts_import)) }
                            }
                        }
                    }
                }
            } else {
                items(scripts, key = { it.id }) { script ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = script }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(script.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                script.pattern.lineSequence().firstOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(
                                    onClick = { editing = script },
                                    label = { Text(if (script.kind == ScriptKind.CSS) "CSS" else "JS") },
                                )
                                if (script.sourceExtension != null) {
                                    AssistChip(
                                        onClick = { editing = script },
                                        label = { Text("de extensão") },
                                    )
                                }
                            }
                        }
                        Switch(
                            checked = script.enabled,
                            onCheckedChange = {
                                vm.setScriptEnabled(script.id, it)
                                refresh++
                            },
                        )
                        IconButton(onClick = {
                            vm.deleteScript(script.id)
                            refresh++
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.userscripts_delete))
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ScriptEditor(
            initial = editing,
            onSave = { script ->
                vm.saveScript(script)
                creating = false
                editing = null
                refresh++
            },
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun ScriptEditor(
    initial: UserScript?,
    onSave: (UserScript) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty().ifBlank { "Novo script" }) }
    var pattern by remember { mutableStateOf(initial?.pattern ?: "<all_urls>") }
    var code by remember { mutableStateOf(initial?.code.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: ScriptKind.JS) }
    var runAtIdle by remember { mutableStateOf(initial?.runAtIdle ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initial) {
        if (initial != null) {
            name = initial.name
            pattern = initial.pattern
            code = initial.code
            kind = initial.kind
            runAtIdle = initial.runAtIdle
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.userscripts_add_script) else name) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 520.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.userscripts_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.userscripts_pattern)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val bad = pattern.lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .filterNot { it == "<all_urls>" || MatchPattern.matches(it, "https://exemplo.com/pagina") }
                        Text(
                            if (bad.any()) "Padrão inválido: ${bad.first()}" else "Casa com https://exemplo.com/pagina",
                            color = if (bad.any()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = kind == ScriptKind.JS,
                        onClick = { kind = ScriptKind.JS },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("JavaScript") }
                    SegmentedButton(
                        selected = kind == ScriptKind.CSS,
                        onClick = { kind = ScriptKind.CSS },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("CSS") }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = runAtIdle, onCheckedChange = { runAtIdle = it })
                    Spacer(Modifier.width(10.dp))
                    Text("Depois do DOM pronto (idle)", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.userscripts_code)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (code.isBlank()) {
                        error = "Escreva algo primeiro."
                        return@Button
                    }
                    onSave(
                        UserScript(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.ifBlank { "Sem nome" },
                            code = code,
                            pattern = pattern.ifBlank { "<all_urls>" },
                            kind = kind,
                            enabled = initial?.enabled ?: true,
                            sourceExtension = initial?.sourceExtension,
                            runAtIdle = runAtIdle,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.userscripts_save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(
                        onClick = {
                            onSave(initial.copy(enabled = false, code = ""))
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.userscripts_delete)) }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
