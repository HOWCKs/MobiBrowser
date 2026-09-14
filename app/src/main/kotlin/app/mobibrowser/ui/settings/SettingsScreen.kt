package app.mobibrowser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobibrowser.BuildConfig
import app.mobibrowser.R
import app.mobibrowser.data.SearchEngine
import app.mobibrowser.data.ThemeMode
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.core.MobiLog
import app.mobibrowser.core.update.UpdateManager
import app.mobibrowser.ui.common.InfoRow
import app.mobibrowser.ui.common.SectionHeader
import app.mobibrowser.ui.common.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MobiViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var homepageDraft by remember { mutableStateOf(settings.homepage) }

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
                subtitle = "Material You: cores extraídas do papel de parede",
                checked = settings.dynamicColor,
                onCheckedChange = vm::setDynamicColor,
                leadingIcon = Icons.Default.Palette,
            )
            SwitchRow(
                title = stringResource(R.string.settings_expressive),
                subtitle = "Molas e overshoot nas transições de barra e folha",
                checked = settings.expressiveMotion,
                onCheckedChange = vm::setExpressive,
                leadingIcon = Icons.Default.DarkMode,
            )

            SectionHeader(stringResource(R.string.settings_privacy))
            SwitchRow(
                title = stringResource(R.string.settings_tracking_protection),
                subtitle = "Bloqueio de rastreadores por aba (ETP do motor)",
                checked = settings.trackingProtectionDefault,
                onCheckedChange = { vm.setTrackingProtectionDefault(it) },
                leadingIcon = Icons.Default.Shield,
            )
            SwitchRow(
                title = "Enviar sinal de privacidade (GPC)",
                subtitle = "Sucessor do \"Do Not Track\"; aplicado pelo motor no próximo início",
                checked = settings.globalPrivacyControl,
                onCheckedChange = vm::setGlobalPrivacyControl,
            )
            SwitchRow(
                title = "MobiBridge (ponte de extensões)",
                subtitle = "Necessária para user scripts e modo compatibilidade",
                checked = settings.bridgeEnabled,
                onCheckedChange = vm::setBridgeEnabled,
                leadingIcon = Icons.Default.TravelExplore,
            )
            SwitchRow(
                title = stringResource(R.string.userscripts_title),
                subtitle = "Permite injetar seus scripts e estilos nas páginas",
                checked = settings.userscriptsEnabled,
                onCheckedChange = vm::setUserscriptsEnabled,
            )
            SwitchRow(
                title = "Bloqueio por regras (declarativeNetRequest)",
                subtitle = "Regras extraídas de pacotes convertidos, aplicadas pelo motor",
                checked = settings.dnrEnabled,
                onCheckedChange = vm::setDnrEnabled,
            )

            SectionHeader("Busca e início")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.settings_engine), style = MaterialTheme.typography.labelLarge)
                SwitchRow(
                    title = "Desligar o motor (diagnóstico)",
                    subtitle = "Reinicia o app sem criar GeckoRuntime. Se ele continuar fechando " +
                        "sozinho assim, a culpa não é do motor — e é isso que preciso saber.",
                    checked = vm.engineOff,
                    onCheckedChange = { vm.setEngineOff(it) },
                )
                Spacer(Modifier.height(6.dp))
                SearchEngine.entries.forEach { engine ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = settings.searchEngine == engine,
                            onClick = { vm.setSearchEngine(engine) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(engine.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                engine.url.substringBefore("?"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = homepageDraft,
                    onValueChange = { homepageDraft = it },
                    label = { Text("Página inicial") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Usada ao abrir a primeira aba e o botão de início") },
                )
                TextButton(onClick = { vm.setHomepage(homepageDraft) }) {
                    Text(stringResource(R.string.userscripts_save))
                }
            }

            SectionHeader("Dados")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Column(Modifier.padding(vertical = 10.dp)) {
                    InfoRow("Histórico", "${vm.historyCount()} itens")
                    InfoRow("Motor", "GeckoView ${BuildConfig.GECKOVIEW_VERSION}")
                    InfoRow(
                        "Canal",
                        if (BuildConfig.MOBI_ALLOW_UNSIGNED_ADDONS) {
                            "${BuildConfig.GECKOVIEW_CHANNEL} · aceita pacote sem assinatura"
                        } else {
                            "${BuildConfig.GECKOVIEW_CHANNEL} · só pacote assinado"
                        },
                    )
                    InfoRow("Compilação", "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")

                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    // --- atualização do canal instável -------------------------------------
                    // Sem consulta automática no boot: um pedido ao GitHub a cada abertura do
                    // app contaria instalação para um terceiro, o que briga com a proposta do
                    // navegador. O preço é óbvio — o update só é oferecido quando se pede.
                    val update = vm.updateStatus.collectAsStateWithLifecycle().value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when (update) {
                                is UpdateManager.Status.Checking -> "Consultando o canal no GitHub…"
                                is UpdateManager.Status.UpToDate ->
                                    "Este já é o build mais novo do canal (" + BuildConfig.GIT_SHA + ")."
                                is UpdateManager.Status.Available ->
                                    "Novo build " + update.sha + ", de " + update.publishedAt +
                                        " · " + (update.bytes / 1048576) + " MB"
                                is UpdateManager.Status.Downloading ->
                                    "Baixando o instalador… " + update.percent + "%"
                                is UpdateManager.Status.Ready ->
                                    "Instalador pronto (" + (update.bytes / 1048576) +
                                        " MB). O sistema ainda pede confirmação antes de instalar."
                                is UpdateManager.Status.Failed ->
                                    "Não deu para verificar: " + update.message
                                UpdateManager.Status.Idle ->
                                    "Uma chamada ao GitHub, só quando você toca."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        when (update) {
                            is UpdateManager.Status.Available -> Button(onClick = { vm.downloadUpdate() }) {
                                Text("Baixar")
                            }

                            is UpdateManager.Status.Ready -> Button(onClick = {
                                (ctx as? android.app.Activity)?.let { vm.installUpdate(it) }
                            }) {
                                Text("Instalar")
                            }

                            is UpdateManager.Status.Downloading -> Unit

                            else -> TextButton(
                                enabled = update !is UpdateManager.Status.Checking,
                                onClick = { vm.checkUpdate() },
                            ) {
                                Text(if (update is UpdateManager.Status.Failed) "Tentar de novo" else "Verificar")
                            }
                        }
                    }
                    if (update is UpdateManager.Status.Downloading) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { update.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    var copied by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (copied) {
                                "Copiado. Se quiser o texto completo do começo da sessão, ele também" +
                                    " está em Android/data/${ctx.packageName}/files/logs/mobibrowser-log.txt."
                            } else {
                                "Travou ou abriu sem interface? Isto copia a sessão: versão, motor," +
                                    " aparelho e as últimas linhas antes de parar."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(MobiLog.report(ctx)))
                            copied = true
                        }) { Text("Diagnóstico") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { confirmClear = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text(stringResource(R.string.settings_clear_data)) }
                }
            }

            SectionHeader(stringResource(R.string.settings_about))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "MobiBrowser é um experimento: navegador próprio, Kotlin + Compose Material 3, " +
                            "com WebExtensions instaladas e convertidas no aparelho. Artefato instável — " +
                            "abra uma issue no repositório com o que quebrou.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = { vm.openUrl("https://github.com/HOWCKs/MobiBrowser") },
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text("github.com/HOWCKs/MobiBrowser") }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_data)) },
            text = {
                Text("Apaga histórico, cache e cookies do perfil do motor. Extensões instaladas permanecem.")
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

