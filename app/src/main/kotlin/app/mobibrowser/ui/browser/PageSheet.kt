package app.mobibrowser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobibrowser.R
import app.mobibrowser.core.ext.ExtensionRegistry
import app.mobibrowser.core.ext.Risk
import androidx.compose.foundation.background
import app.mobibrowser.core.ext.PermissionCatalog
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.Overlay

/**
 * Menu da página. O bloco do meio é o que diferencia um navegador com extensões:
 * "o que roda neste site?" responde-se aqui, com um toque, sem sair da página.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageSheet(vm: MobiViewModel, onDismiss: () -> Unit) {
    val state by vm.tabState.collectAsStateWithLifecycle()
    val extensions by vm.extensionsUi.collectAsStateWithLifecycle()
    val bookmarked by vm.bookmarkActive.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::installFromUri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            state?.let { tab ->
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(tab.title.ifBlank { tab.displayUrl }, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            SheetRow(Icons.Default.Share, stringResource(R.string.action_share)) {
                vm.shareCurrentUrl()
                onDismiss()
            }
            SheetRow(
                if (bookmarked) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                stringResource(if (bookmarked) R.string.action_bookmark_remove else R.string.action_bookmark_add),
            ) {
                vm.toggleBookmark()
                onDismiss()
            }
            SheetRow(Icons.Default.Search, stringResource(R.string.action_find_in_page)) {
                vm.showFind()
                onDismiss()
            }
            SheetRow(
                Icons.Default.Computer,
                stringResource(
                    if (state?.desktopMode == true) R.string.action_mobile_site else R.string.action_desktop_site,
                ),
                trailing = { Switch(checked = state?.desktopMode == true, onCheckedChange = { vm.toggleDesktopMode() }) },
            ) {
                vm.toggleDesktopMode()
            }
            SheetRow(
                Icons.Default.Psychology,
                stringResource(R.string.settings_js),
                trailing = { Switch(checked = state?.javascript != false, onCheckedChange = { vm.toggleJavascript() }) },
            ) {
                vm.toggleJavascript()
            }
            SheetRow(
                Icons.Default.Shield,
                stringResource(R.string.settings_tracking_protection),
                trailing = {
                    Switch(checked = state?.trackingProtection != false, onCheckedChange = { vm.toggleTrackingProtection() })
                },
            ) {
                vm.toggleTrackingProtection()
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 20.dp))

            // ---- extensões neste site ----
            if (extensions.isEmpty()) {
                SheetRow(Icons.Default.Extension, stringResource(R.string.extensions_install_store)) {
                    vm.openExtensions()
                    onDismiss()
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.extensions_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.extensions_run_site),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                extensions.forEach { ext ->
                    val allowedHere = ext.allowsSite(state?.url)
                    ListItem(
                        headlineContent = { Text(ext.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Extension,
                                contentDescription = null,
                                tint = if (allowedHere) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        },
                        supportingContent = {
                            if (ext.mode == ExtensionRegistry.Mode.BRIDGE && !allowedHere) {
                                Text(
                                    "Bloqueada neste site pela lista de acesso",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = allowedHere,
                                onCheckedChange = { vm.toggleExtensionOnThisSite(ext.geckoId) },
                            )
                        },
                        modifier = Modifier.clickable {
                            vm.toggleExtensionOnThisSite(ext.geckoId)
                        },
                    )
                }
                SheetRow(Icons.Default.Settings, stringResource(R.string.extensions_title)) {
                    vm.openExtensions()
                    onDismiss()
                }
            }
            SheetRow(Icons.Default.Download, stringResource(R.string.extensions_install_file)) {
                filePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                onDismiss()
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 20.dp))

            SheetRow(Icons.Default.History, stringResource(R.string.library_history)) {
                vm.show(Overlay.HISTORY)
                onDismiss()
            }
            SheetRow(Icons.Default.BookmarkRemove, stringResource(R.string.library_bookmarks)) {
                vm.show(Overlay.BOOKMARKS)
                onDismiss()
            }
            SheetRow(Icons.Default.Psychology, stringResource(R.string.userscripts_title)) {
                vm.show(Overlay.USERSCRIPTS)
                onDismiss()
            }
            SheetRow(Icons.Default.Settings, stringResource(R.string.settings_title)) {
                vm.show(Overlay.SETTINGS)
                onDismiss()
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Resumo compacto de permissões reutilizado na folha da página e na tela de extensões. */
@Composable
fun PermissionSummary(permissions: List<String>, origins: List<String>) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            stringResource(R.string.extensions_permissions_title),
            style = MaterialTheme.typography.labelLarge,
        )
        permissions.take(6).forEach { permission ->
            val info = PermissionCatalog.describe(permission)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(info.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                RiskDot(info.risk)
            }
        }
        if (origins.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                PermissionCatalog.describeOrigin(origins.first()) +
                    if (origins.size > 1) " (+${origins.size - 1})" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RiskDot(risk: Risk) {
    val color = when (risk) {
        Risk.LOW -> MaterialTheme.colorScheme.secondary
        Risk.MEDIUM -> androidx.compose.material3.MaterialTheme.colorScheme.tertiary
        Risk.HIGH -> MaterialTheme.colorScheme.error
    }
    Spacer(Modifier.width(8.dp))
    Spacer(
        Modifier
            .size(8.dp)
            .background(color, MaterialTheme.shapes.extraSmall),
    )
}
