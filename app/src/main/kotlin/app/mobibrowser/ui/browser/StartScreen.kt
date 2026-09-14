package app.mobibrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mobibrowser.core.engine.toHost
import app.mobibrowser.data.HistoryEntry
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.common.SiteMonogram
import app.mobibrowser.ui.theme.MobiType

/**
 * Tela de início do navegador — o que aparece antes de qualquer navegação e em toda aba nova.
 *
 * Ela existe por dois motivos, e os dois são de produto, não de enfeite:
 *
 * 1. Um navegador que abre numa página qualquer faz a pessoa digitar antes de saber onde está.
 *    Aqui o campo de busca é o primeiro elemento, os atalhos vêm do histórico real do aparelho e
 *    o que a pessoa deixou aberto volta sozinha na seção "Retomar".
 * 2. É Compose puro: nenhum `GeckoSession` é criado enquanto a pessoa não escolher um destino.
 *    Numa tela que estava morrendo durante a inicialização do motor, ter uma superfície que não
 *    depende dele vale mais que qualquer mensagem de erro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    vm: MobiViewModel,
    /** Muda a cada navegação; é o que faz os atalhos relerem o histórico quando a pessoa volta. */
    refreshKey: String,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.extensionsUi.collectAsStateWithLifecycle()
    val notice by vm.startNotice.collectAsStateWithLifecycle()
    val shortcuts by produceState(initialValue = emptyList<HistoryEntry>(), refreshKey) {
        value = vm.startShortcutsOnIo()
    }
    val recent by produceState(initialValue = emptyList<HistoryEntry>(), refreshKey) {
        value = vm.startRecentOnIo()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 40.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    greeting(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "MobiBrowser",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.2.sp,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Ajustes")
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------------------------------------------------------- campo de busca
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge),
                singleLine = true,
                placeholder = {
                    Text("Endereço ou termo de busca", style = MobiType.address)
                },
                textStyle = MobiType.address,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        Button(
                            onClick = {
                                vm.submit(query)
                                query = ""
                            },
                            shape = MaterialTheme.shapes.extraLarge,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp,
                            ),
                        ) { Text("Ir") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (query.isNotBlank()) {
                            vm.submit(query)
                            query = ""
                        }
                    },
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onOpenSettings,
                leadingIcon = {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Text(
                        if (settings.trackingProtectionDefault) "Bloqueio de rastreamento ligado" else "Sem bloqueio de rastreamento",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
            AssistChip(
                onClick = onOpenExtensions,
                leadingIcon = {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Text(
                        if (installed.isEmpty()) "Instalar extensões" else "${installed.size} extensão(ões) instalada(s)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }

        // ---------------------------------------------------------- aviso de sessão anterior
        if (notice != null) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Ajustamos uma coisa para o app abrir",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = vm::dismissStartNotice) { Text("Entendi") }
                        TextButton(onClick = { vm.showDevScreen() }) { Text("O que foi feito") }
                    }
                }
            }
        }

        // ---------------------------------------------------------- atalhos
        if (shortcuts.isNotEmpty()) {
            StartSection("Seus atalhos", "Os endereços que você mais abre neste aparelho")
            shortcuts.chunked(2).forEach { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    line.forEach { entry ->
                        ShortcutCard(
                            entry = entry,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.openUrl(entry.url) },
                        )
                    }
                    // Linha com um item só: o espaço vazio segura o grid para o cartão não
                    // esticar e virar uma barra esquisita.
                    if (line.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        if (recent.isNotEmpty()) {
            StartSection("Retomar de onde você parou", null)
            recent.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openUrl(entry.url) }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteMonogram(url = entry.url, size = 26.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title.ifBlank { entry.url.toHost().orEmpty() },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.url.toHost().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (shortcuts.isEmpty() && recent.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ainda não há histórico neste aparelho",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Abra um endereço acima: os sites que você mais visita viram atalhos aqui " +
                        "sozinhos, sem você configurar nada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = onOpenExtensions,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Extensões")
            }
            OutlinedButton(
                onClick = { vm.goHome() },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Página inicial")
            }
        }
        if (vm.busyAtStart()) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraLarge),
            )
        }
    }
}

@Composable
private fun StartSection(title: String, supporting: String?) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (supporting != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShortcutCard(
    entry: HistoryEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .heightIn(min = 74.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteMonogram(url = entry.url, size = 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.url.toHost()?.removePrefix("www.").orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.title.ifBlank { "abrir" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Saudação por hora — é o único "bom dia" que um navegador pode dar sem parecer forçado. */
private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }
}
