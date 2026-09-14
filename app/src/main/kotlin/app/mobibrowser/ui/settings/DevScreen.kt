package app.mobibrowser.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobibrowser.BuildConfig
import app.mobibrowser.core.Diag
import app.mobibrowser.core.EngineGuard
import app.mobibrowser.core.MobiLog
import app.mobibrowser.core.update.UpdateManager
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.common.InfoRow
import app.mobibrowser.ui.common.SectionHeader
import app.mobibrowser.ui.common.SwitchRow

/**
 * Tela de desenvolvimento. Chega-se a ela por um ícone em Ajustes, não por um cartão de texto:
 * quem usa o navegador não precisa ler "GeckoRuntime", "canal release" e "YAML de assinatura"
 * para resolver o próprio problema, e tudo o que existe aqui é justamente para quando a tela de
 * início não bastou.
 *
 * O conteúdo é intencionalmente cru (versão, motor, estado do guarda, o `.txt` dos coletores)
 * porque é isso que se copia para uma issue. Nada aqui é decorativo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevScreen(vm: MobiViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    // `update` é lido em `when (update) { is … -> update.sha }`, e smart cast não funciona em
    // propriedade delegada — por isso o bloco original em Ajustes usava `.value` numa val local.
    // Mantém-se a local: o `by` aqui seria 14 erros de compilação para ganhar uma letra.
    val update = vm.updateStatus.collectAsStateWithLifecycle().value
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modo avançado") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding(), bottom = 40.dp),
        ) {
            SectionHeader(
                "O que o aparelho registrou",
                supporting = "É o texto que resolve o mistério de um fechamento sem aviso. Copie e " +
                    "cole na issue.",
            )
            DevCard {
                val guard = remember { EngineGuard.debugDump() }
                val exit = remember { Diag.lastExit }
                Text(
                    "Diag.lastExit (o que a abertura atual leu do sistema):",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                MonoBlock(exit ?: "nada lido nesta abertura")
                Spacer(Modifier.height(10.dp))
                Text("Guarda do motor:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                MonoBlock(guard)
                Spacer(Modifier.height(10.dp))
                val dir = remember { MobiLog.logsDir(ctx) }
                Text(
                    "Arquivos no aparelho — pasta ${dir.parentFile?.name ?: "Android/data/<pacote>/files"}/, " +
                        "aberta por qualquer gerenciador ou pelo Code on the Go:",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                MonoBlock(
                    listOf(
                        "diag/exits.txt — motivo de cada fechamento, com o relatório do sistema",
                        "diag/live.txt — em que ponto do início o app estava, atualizado a cada meio segundo",
                        "diag/logcat.txt — o log da nossa própria processo",
                        "logs/mobibrowser-log.txt — a sessão inteira",
                        "logs/mobibrowser-errors.txt — só erros, através das aberturas",
                        "logs/mobibrowser-crash.txt — a pilha, quando existe uma",
                    ).joinToString("\n"),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(Diag.export(ctx)))
                        copied = true
                    }) { Text(if (copied) "Copiado" else "Copiar tudo") }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(dir.absolutePath))
                    }) { Text("Copiar caminho") }
                }
            }

            SectionHeader("Estado do navegador")
            DevCard {
                InfoRow("Versão", "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
                InfoRow("Motor", "GeckoView ${BuildConfig.GECKOVIEW_VERSION}")
                InfoRow("Canal do motor", BuildConfig.GECKOVIEW_CHANNEL)
                InfoRow("Pacote sem assinatura", yesNo(BuildConfig.MOBI_ALLOW_UNSIGNED_ADDONS))
                InfoRow("Registro de depuração", yesNo(BuildConfig.MOBI_DEBUG_LOGS))
                InfoRow("Histórico local", "${vm.historyCount()} itens")
                InfoRow("Última fase do início", Diag.lastPhase)
                InfoRow("Aberturas mortas no berço", "${EngineGuard.debugStreak()}")
                InfoRow("Motor de extensões pausado", yesNo(EngineGuard.extensionsPaused))
            }

            SectionHeader(
                "Reproduzir o fechamento",
                supporting = "Desliga o processo do motor de páginas no próximo início. Se o app " +
                    "continuar fechando assim, quem fecha não é o motor.",
            )
            SwitchRow(
                title = "Desligar o motor de páginas (teste)",
                subtitle = "Reinicia o app agora, sem criar o GeckoRuntime.",
                checked = vm.engineOff,
                onCheckedChange = { vm.setEngineOff(it) },
            )

            SectionHeader(
                "Canal instável",
                supporting = "Uma chamada ao GitHub, só quando você toca. Nada é consultado " +
                    "sozinho na abertura.",
            )
            DevCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
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

                            UpdateManager.Status.Idle -> "Pronto para verificar quando você quiser."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (update is UpdateManager.Status.Downloading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { update.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (update) {
                        is UpdateManager.Status.Available -> Button(onClick = { vm.downloadUpdate() }) {
                            Text("Baixar")
                        }

                        is UpdateManager.Status.Ready -> Button(onClick = {
                            (ctx as? android.app.Activity)?.let { vm.installUpdate(it) }
                        }) { Text("Instalar") }

                        else -> Unit
                    }
                    TextButton(
                        enabled = update !is UpdateManager.Status.Checking,
                        onClick = { vm.checkUpdate() },
                    ) {
                        Text(if (update is UpdateManager.Status.Failed) "Tentar de novo" else "Verificar")
                    }
                }
            }

            SectionHeader("Peças que tocam o motor")
            SwitchRow(
                title = "Ponte de extensões",
                subtitle = "Sem ela, extensões instaladas em modo compatível e scripts injetados não rodam.",
                checked = settings.bridgeEnabled,
                onCheckedChange = vm::setBridgeEnabled,
            )
            SwitchRow(
                title = "Regras de bloqueio das extensões",
                subtitle = "Regras de filtragem que vieram dentro dos pacotes convertidos.",
                checked = settings.dnrEnabled,
                onCheckedChange = vm::setDnrEnabled,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { vm.openUrl("https://github.com/HOWCKs/MobiBrowser") },
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text("github.com/HOWCKs/MobiBrowser") }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Artefato instável: abra uma issue no repositório com o texto copiado acima.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Cartão de conteúdo técnico: superfície tonal, forma grande, zero sombra — M3 expressivo. */
@Composable
private fun DevCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** Bloco monoespaçado para texto que é copiado, não lido em voz alta. */
@Composable
private fun MonoBlock(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun yesNo(v: Boolean) = if (v) "sim" else "não"
