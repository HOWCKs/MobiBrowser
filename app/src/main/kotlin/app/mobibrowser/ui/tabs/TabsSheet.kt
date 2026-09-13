package app.mobibrowser.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PrivateTestingMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import app.mobibrowser.core.engine.BrowserTab
import app.mobibrowser.core.engine.TabUiState
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.common.SiteMonogram

/**
 * Seletor de abas em folha modal: grade de cartões, segmentado normal/anônimo e
 * "nova aba" como FAB estendido — o gesto fica onde o polegar alcança.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(vm: MobiViewModel, onDismiss: () -> Unit) {
    val tabs by vm.tabList.collectAsStateWithLifecycle()
    val selectedId by vm.selectedTabId.collectAsStateWithLifecycle()
    val startPrivate = vm.selectedTab.value?.isPrivate ?: false
    var showPrivate by remember { mutableStateOf(startPrivate) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 720.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.tabs_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.closeAllTabs(showPrivate) }) {
                    Text(stringResource(R.string.tabs_close_all), style = MaterialTheme.typography.labelLarge)
                }
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SegmentedButton(
                    selected = !showPrivate,
                    onClick = { showPrivate = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.tabs_normal)) }
                SegmentedButton(
                    selected = showPrivate,
                    onClick = { showPrivate = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.tabs_private)) }
            }

            val visible = tabs.filter { it.isPrivate == showPrivate }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.tabs_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                ) {
                    items(visible, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            selected = tab.id == selectedId,
                            onClick = {
                                vm.selectTab(tab.id)
                                onDismiss()
                            },
                            onClose = { vm.closeTab(tab.id) },
                        )
                    }
                }
            }

            ExtendedFloatingActionButton(
                onClick = {
                    vm.newTab(privateMode = showPrivate)
                    onDismiss()
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_new_tab)) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun TabCard(
    tab: BrowserTab,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val state by tab.state.collectAsStateWithLifecycle()
    Surface(
        tonalElevation = if (selected) 3.dp else 0.dp,
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .aspectRatio(0.72f),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp),
            ) {
                SiteMonogram(state.url, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    state.title.ifBlank { state.displayUrl.ifBlank { "Nova aba" } },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close_tab),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // "Miniatura" honesta: não há API pública de snapshot por sessão no 155;
            // mostrar o domínio e o estado de segurança é mais útil que um retângulo cinza.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.host ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (state.isPrivate) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Default.PrivateTestingMode,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.tabs_private),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
