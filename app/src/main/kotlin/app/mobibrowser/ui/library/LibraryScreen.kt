package app.mobibrowser.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mobibrowser.R
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.common.EmptyState
import app.mobibrowser.ui.common.SiteMonogram

/**
 * Histórico e favoritos. Uma tela com dois modos em vez de duas telas: a navegação por
 * "onde eu vi aquilo?" é a mesma, muda só a fonte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: MobiViewModel,
    bookmarksOnly: Boolean,
    onDismiss: () -> Unit,
) {
    var showBookmarks by remember { mutableStateOf(bookmarksOnly) }
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<MobiViewModel.HistoryRow>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<MobiViewModel.BrowserDbBookmark>>(emptyList()) }

    LaunchedEffect(query, showBookmarks) {
        if (showBookmarks) {
            bookmarks = vm.listBookmarks()
        } else {
            rows = vm.listHistory(query)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (showBookmarks) stringResource(R.string.library_bookmarks) else stringResource(R.string.library_history),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding()),
        ) {
            if (!bookmarksOnly) {
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    SegmentedButton(
                        selected = !showBookmarks,
                        onClick = { showBookmarks = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.library_history)) }
                    SegmentedButton(
                        selected = showBookmarks,
                        onClick = { showBookmarks = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.library_bookmarks)) }
                }
            }

            if (!showBookmarks) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { },
                    active = false,
                    onActiveChange = { },
                    placeholder = { Text(stringResource(R.string.library_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.find_close))
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.large,
                    colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    content = { },
                )
            }

            if (showBookmarks) {
                if (bookmarks.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.History,
                        title = stringResource(R.string.library_empty),
                        body = "Toque em Compartilhar/Favoritos no menu da página para guardar um endereço aqui.",
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(bookmarks, key = { it.url }) { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.openUrl(item.url)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                SiteMonogram(item.url, size = 26.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        item.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = {
                                    vm.removeBookmark(item.url)
                                    bookmarks = vm.listBookmarks()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.userscripts_delete))
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(rows, key = { "${it.url}-${it.at}" }) { row ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.openUrl(row.url)
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            SiteMonogram(row.url, size = 26.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.title.ifBlank { row.url },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                relativeTime(row.at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = {
                                vm.deleteHistoryEntry(row.url)
                                rows = vm.listHistory(query)
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.userscripts_delete))
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    stringResource(R.string.library_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tempo relativo curto, sem biblioteca de datas: o suficiente para escanear histórico. */
private fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "agora"
        minutes < 60 -> "${minutes}min"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> "${days / 7}sem"
    }
}
