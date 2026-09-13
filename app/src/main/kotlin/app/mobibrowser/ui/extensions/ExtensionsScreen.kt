package app.mobibrowser.ui.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobibrowser.R
import app.mobibrowser.core.ext.ChromeWebStore
import app.mobibrowser.core.ext.ExtensionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import app.mobibrowser.ui.common.BitmapIcon
import app.mobibrowser.core.ext.ExtensionRegistry
import app.mobibrowser.core.ext.PermissionCatalog
import app.mobibrowser.core.ext.Risk
import app.mobibrowser.ui.MobiViewModel

/**
 * Central de extensões: o que está instalado, em que modo roda, o que cada uma pode
 * fazer e o interruptor por site. Instalar/remover ficam aqui, sem sair da navegação.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(vm: MobiViewModel, onDismiss: () -> Unit) {
    val extensions by vm.extensionsUi.collectAsStateWithLifecycle()
    val progress by vm.installProgress.collectAsStateWithLifecycle()
    val bridgeStats by vm.bridgeStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var installOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf<ExtensionManager.ExtensionUiState?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument) { uri ->
        uri?.let(vm::installFromUri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extensions_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.resyncExtensions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.extensions_check_updates))
                    }
                    IconButton(onClick = { installOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.extensions_install_store))
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = 28.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
        ) {
            item {
                InstallEntryCard(
                    busy = progress is ExtensionManager.InstallProgress.Working,
                    onOpenStore = {
                        vm.openUrl(ChromeWebStore.STORE_URL)
                        onDismiss()
                    },
                    onPasteId = { installOpen = true },
                    onInstallFile = { filePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                )
            }

            item {
                BridgeStatusCard(
                    enabled = settings.bridgeEnabled,
                    requests = bridgeStats.requests,
                    injections = bridgeStats.injected,
                )
            }

            if (extensions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.extensions_none_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                items(extensions, key = { it.geckoId }) { ext ->
                    ExtensionCard(
                        state = ext,
                        expanded = expanded == ext.geckoId,
                        onToggleExpanded = { expanded = if (expanded == ext.geckoId) null else ext.geckoId },
                        onEnabled = { vm.toggleExtension(ext.geckoId, it) },
                        onPrivate = { vm.setExtensionPrivate(ext.geckoId, it) },
                        onSiteAccess = { access, allow, deny -> vm.setExtensionSiteAccess(ext.geckoId, access, allow, deny) },
                        onOpenOptions = { vm.openExtensionOptions(ext.geckoId) },
                        onOpenStore = ext.storeUrl?.let { { vm.openUrl(it) } },
                        onUpdate = ext.storeId?.let { { vm.updateExtension(ext.geckoId) } },
                        onUninstall = { confirmUninstall = ext },
                        currentUrl = vm.tabState.value?.url,
                        onToggleThisSite = { vm.toggleExtensionOnThisSite(ext.geckoId) },
                    )
                    HorizontalDivider(
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }

    if (installOpen) {
        ExtensionInstallSheet(
            vm = vm,
            onDismiss = { installOpen = false },
        )
    }

    confirmUninstall?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text(stringResource(R.string.extensions_uninstall)) },
            text = { Text("Remover ${target.name}? As permissões concedidas e os dados da extensão são apagados.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.uninstallExtension(target.geckoId)
                        confirmUninstall = null
                    },
                ) { Text(stringResource(R.string.extensions_uninstall)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/* ---------------------------------------------------------------------- */

@Composable
private fun InstallEntryCard(
    busy: Boolean,
    onOpenStore: () -> Unit,
    onPasteId: () -> Unit,
    onInstallFile: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.extensions_install_store),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.extensions_store_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenStore, enabled = !busy) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.extensions_open_store))
                }
                OutlinedButton(onClick = onPasteId, enabled = !busy) {
                    Text(stringResource(R.string.extensions_paste_id))
                }
                OutlinedButton(onClick = onInstallFile, enabled = !busy) {
                    Text(stringResource(R.string.extensions_install_file))
                }
            }
        }
    }
}

@Composable
private fun BridgeStatusCard(
    enabled: Boolean,
    requests: Int,
    injections: Int,
) {
    val settings = enabled
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (settings) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (settings.bridgeEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "MobiBridge",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (settings) "ativa" else "desligada",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "User scripts, estilos e modo compatibilidade usam esta ponte embutida. " +
                    "Páginas consultadas: $requests · injeções: $injections.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtensionCard(
    state: ExtensionManager.ExtensionUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onPrivate: (Boolean) -> Unit,
    onSiteAccess: (ExtensionRegistry.SiteAccess, List<String>, List<String>) -> Unit,
    onOpenOptions: () -> Unit,
    onOpenStore: (() -> Unit)?,
    onUpdate: (() -> Unit)?,
    onUninstall: () -> Unit,
    currentUrl: String?,
    onToggleThisSite: () -> Unit,
) {
    val risk = PermissionCatalog.highestRisk(state.permissions, state.origins)
    Column(
        Modifier
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                BitmapIcon(bitmap = state.icon, fallbackUrl = state.name, size = 32.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(state.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${state.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    ElevatedAssistChip(
                        onClick = onToggleExpanded,
                        leadingIcon = {
                            Icon(
                                if (state.mode == ExtensionRegistry.Mode.NATIVE) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = {
                            Text(
                                if (state.mode == ExtensionRegistry.Mode.NATIVE) {
                                    stringResource(R.string.extensions_source_native)
                                } else {
                                    stringResource(R.string.extensions_source_bridge)
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                if (state.updateAvailable != null) {
                    Text(
                        "Atualização: ${state.updateAvailable}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Switch(checked = state.enabled, onCheckedChange = onEnabled)
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                if (!state.description.isNullOrBlank()) {
                    Text(
                        state.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (state.mode == ExtensionRegistry.Mode.BRIDGE) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Text(
                            stringResource(R.string.extensions_bridge_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    stringResource(R.string.extensions_permissions_title) + " · ${PermissionCatalog.riskLabel(risk)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (state.permissions.isEmpty() && state.origins.isEmpty()) {
                    Text(
                        "Nenhuma permissão declarada no manifest convertido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.permissions.forEach { permission ->
                    val info = PermissionCatalog.describe(permission)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    when (info.risk) {
                                        Risk.LOW -> MaterialTheme.colorScheme.secondary
                                        Risk.MEDIUM -> MaterialTheme.colorScheme.tertiary
                                        Risk.HIGH -> MaterialTheme.colorScheme.error
                                    },
                                    MaterialTheme.shapes.extraSmall,
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(info.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                if (state.origins.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.extensions_origins_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        PermissionCatalog.describeOrigin(state.origins.first()) +
                            if (state.origins.size > 1) " · +${state.origins.size - 1} origem(ns)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    state.warnings.forEach { warning ->
                        Row(Modifier.padding(top = 4.dp)) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (state.droppedKeys.isNotEmpty()) {
                    Text(
                        "Chaves removidas na conversão: ${state.droppedKeys.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.extensions_run_all_sites),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.siteAccess == ExtensionRegistry.SiteAccess.ALL_SITES,
                        onClick = { onSiteAccess(ExtensionRegistry.SiteAccess.ALL_SITES, state.allowList, emptyList()) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Em todos") }
                    SegmentedButton(
                        selected = state.siteAccess == ExtensionRegistry.SiteAccess.ALLOW_LIST,
                        onClick = { onSiteAccess(ExtensionRegistry.SiteAccess.ALLOW_LIST, state.allowList, state.denyList) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Só permitidos") }
                }
                Spacer(Modifier.height(6.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.extensions_run_site)) },
                    supportingContent = {
                        Text(
                            currentUrl?.let {
                                if (state.allowsSite(it)) "Roda em ${it.substringAfter("//").substringBefore("/")}" else "Desligada neste site"
                            } ?: "Abra uma página para alternar",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.allowsSite(currentUrl),
                            onCheckedChange = { onToggleThisSite() },
                        )
                    },
                    modifier = Modifier.clickable(enabled = currentUrl != null) { onToggleThisSite() },
                )

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                    if (state.hasOptionsPage) {
                        AssistChip(onClick = onOpenOptions, label = { Text(stringResource(R.string.extensions_details)) })
                    }
                    if (onOpenStore != null) {
                        AssistChip(
                            onClick = onOpenStore,
                            label = { Text("Chrome Web Store") },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                    if (onUpdate != null) {
                        AssistChip(
                            onClick = onUpdate,
                            label = { Text("Buscar atualização") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                    if (state.mode == ExtensionRegistry.Mode.NATIVE) {
                        AssistChip(
                            onClick = { onPrivate(!state.privateAllowed) },
                            label = { Text(stringResource(R.string.extensions_private)) },
                            leadingIcon = {
                                Icon(
                                    if (state.privateAllowed) Icons.Default.CheckCircle else Icons.Default.SettingsBackupRestore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onUninstall) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.extensions_uninstall))
                    }
                }
            }
        }
    }
}

/**
 * Folha de instalação: colar ID/URL da loja, ver o que a extensão pede e confirmar.
 * Mantida separada da lista para a revisão de permissões poder crescer sem cortar nada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionInstallSheet(
    vm: MobiViewModel,
    onDismiss: () -> Unit,
) {
    val progress by vm.installProgress.collectAsStateWithLifecycle()
    val summary by vm.storeSummary.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(vm.storeInstallInput.value.orEmpty()) }
    val storeId = remember(input) { ChromeWebStore.idFrom(input) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.extensions_install_store)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        vm.previewStoreInstall(it)
                    },
                    label = { Text(stringResource(R.string.extensions_id_or_url)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            if (storeId == null) "Aceita a URL da página do item ou o id de 32 letras." else "id: $storeId",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )

                summary?.takeIf { it.id == storeId }?.let { item ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                if (item.iconUrl != null) {
                                    Icon(Icons.Default.Description, contentDescription = null)
                                } else {
                                    Text(item.name?.take(1)?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name ?: storeId.orEmpty(), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    item.description.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "O MobiBrowser baixa o pacote da Chrome Web Store, converte o manifest e " +
                        "tenta instalar no motor. Se o motor recusar o pacote (assinatura), a " +
                        "extensão é instalada em modo compatibilidade e você é avisado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                progress?.let { p ->
                    Spacer(Modifier.height(10.dp))
                    when (p) {
                        is ExtensionManager.InstallProgress.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(p.step, style = MaterialTheme.typography.bodySmall)
                        }

                        is ExtensionManager.InstallProgress.Failure -> Text(
                            "${p.message}\n${p.detail ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        is ExtensionManager.InstallProgress.Success -> Text(
                            "Instalada: ${p.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (input.isNotBlank()) vm.installFromStore(input) },
                enabled = storeId != null && progress !is ExtensionManager.InstallProgress.Working,
            ) { Text(stringResource(R.string.extensions_install_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Revisão de permissões vinda do motor (PromptDelegate.onInstallPromptRequest). */
@Composable
fun PermissionReviewDialog(
    prompt: ExtensionManager.InstallPrompt,
    onDecision: (grant: Boolean, allowPrivate: Boolean) -> Unit,
) {
    var allowPrivate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDecision(false, false) },
        icon = { Icon(Icons.Default.Sell, contentDescription = null) },
        title = { Text("Permitir “${prompt.name}”?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Esta extensão pede acesso antes de ser habilitada pelo motor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (prompt.permissions.isNotEmpty()) {
                    Text(stringResource(R.string.extensions_permissions_title), style = MaterialTheme.typography.labelLarge)
                    prompt.permissions.forEach { permission ->
                        val info = PermissionCatalog.describe(permission)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(info.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            AssistChip(
                                onClick = {},
                                label = { Text(PermissionCatalog.riskLabel(info.risk), style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                if (prompt.origins.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.extensions_origins_title), style = MaterialTheme.typography.labelLarge)
                    prompt.origins.forEach {
                        Text(PermissionCatalog.describeOrigin(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (prompt.dataCollection.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Coleta de dados declarada", style = MaterialTheme.typography.labelLarge)
                    prompt.dataCollection.forEach {
                        Text(PermissionCatalog.dataCollectionLabel(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = allowPrivate, onCheckedChange = { allowPrivate = it }, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.extensions_private), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDecision(true, allowPrivate) }) { Text(stringResource(R.string.permission_allow)) }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false, false) }) { Text(stringResource(R.string.permission_deny)) }
        },
    )
}
