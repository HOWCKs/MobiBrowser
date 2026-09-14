package app.mobibrowser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobibrowser.R
import app.mobibrowser.data.SearchEngine
import app.mobibrowser.data.ThemeMode
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.common.SectionHeader
import app.mobibrowser.ui.common.SwitchRow

/**
 * Ajustes, do ponto de vista de quem usa o navegador: aparência, privacidade, busca e dados.
 *
 * Nada aqui fala de motor, canal, assinatura ou YAML — a versão anterior desta tela tinha
 * subtítulo técnico embaixo de cada interruptor e um cartão de diagnóstico no meio da tela. O que
 * é de desenvolvimento mudou para [DevScreen], alcançada pelo ícone no fim da lista: continua
 * acessível para quem precisa, some para quem não precisa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MobiViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var homepageDraft by remember { mutableStateOf(settings.homepage) }
    var homepageSaved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding(), bottom = 32.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { vm.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    ) {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = "Cores do app tiradas do papel de parede",
                checked = settings.dynamicColor,
                onCheckedChange = vm::setDynamicColor,
                leadingIcon = Icons.Default.Palette,
            )
            SwitchRow(
                title = stringResource(R.string.settings_expressive),
                subtitle = "Animações com mola ao abrir telas e esconder barras",
                checked = settings.expressiveMotion,
                onCheckedChange = vm::setExpressive,
                leadingIcon = Icons.Default.DarkMode,
            )

            SectionHeader(stringResource(R.string.settings_privacy))
            SwitchRow(
                title = stringResource(R.string.settings_tracking_protection),
                subtitle = "Bloqueia rastreadores em todas as abas novas",
                checked = settings.trackingProtectionDefault,
                onCheckedChange = { vm.setTrackingProtectionDefault(it) },
                leadingIcon = Icons.Default.Shield,
            )
            SwitchRow(
                title = "Pedir para não vender meus dados",
                subtitle = "Aviso enviado aos sites (o antigo \"não me rastreie\"). Vale para as " +
                    "próximas páginas que você abrir.",
                checked = settings.globalPrivacyControl,
                onCheckedChange = vm::setGlobalPrivacyControl,
                leadingIcon = Icons.Default.Visibility,
            )
            SwitchRow(
                title = stringResource(R.string.userscripts_title),
                subtitle = "Permite que seus próprios scripts e estilos entrem nas páginas",
                checked = settings.userscriptsEnabled,
                onCheckedChange = vm::setUserscriptsEnabled,
            )

            SectionHeader("Busca e início")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_engine), style = MaterialTheme.typography.labelLarge)
                }
                SearchEngine.entries.forEach { engine ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { vm.setSearchEngine(engine) }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(
                            selected = settings.searchEngine == engine,
                            onClick = { vm.setSearchEngine(engine) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(engine.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Pesquisa por: ${engine.url.substringBefore("?").removePrefix("https://")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = homepageDraft,
                    onValueChange = {
                        homepageDraft = it
                        homepageSaved = false
                    },
                    label = { Text("Endereço do botão de início") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        vm.setHomepage(homepageDraft)
                        homepageSaved = true
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                ) { Text("Salvar endereço") }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (homepageSaved) {
                        "Anotado. Este endereço abre no botão de início; a tela de início continua " +
                            "a primeira coisa que você vê."
                    } else {
                        "Endereço aberto quando você toca na casinha. O que aparece ao abrir o app " +
                            "é a tela de início, com a busca e os seus atalhos."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Dados")
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(vertical = 10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Histórico neste aparelho",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${vm.historyCount()} itens",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) { Text(stringResource(R.string.settings_clear_data)) }
                }
            }

            // Sem texto: quem precisa de número de versão e de copiar log procura o ícone, e quem
            // não precisa não tropeça nele. É a exigência da rodada: informação de desenvolvimento
            // não é enfeite de vitrine.
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { vm.showDevScreen() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.DeveloperMode,
                        contentDescription = "Modo avançado",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { vm.showDevScreen() }) {
                    Text("Modo avançado")
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
            title = { Text(stringResource(R.string.settings_clear_data)) },
            text = {
                Text(
                    "Apaga o histórico, as páginas guardadas e os cookies. As extensões instaladas " +
                        "continuam aqui.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearEverything()
                        confirmClear = false
                    },
                ) { Text("Limpar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
