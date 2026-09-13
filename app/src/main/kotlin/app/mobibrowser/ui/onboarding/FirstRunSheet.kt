package app.mobibrowser.ui.onboarding

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mobibrowser.R

/**
 * Primeiro uso: em vez de "termos aceitos", explicar o modelo de extensões — é a
 * decisão que o usuário precisa entender antes de confiar o navegador a um build
 * instável: o que roda no motor, o que roda na ponte e o que ainda não roda.
 */
@Composable
fun FirstRunSheet(onContinue: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { insets ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(insets)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Spacer(Modifier.height(36.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.first_run_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.first_run_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))

                ReasonCard(
                    icon = { Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                    title = "Motor com runtime de extensões",
                    body = "GeckoView (o motor do Firefox) executa WebExtensions de verdade: " +
                        "content scripts, storage, declarativeNetRequest, popups. Estamos no " +
                        "canal nightly porque só ele permite instalar pacote sem assinatura da Mozilla.",
                )
                ReasonCard(
                    icon = { Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                    title = "Chrome Web Store, com conversão",
                    body = "Você abre a loja dentro do navegador e toca em instalar. O MobiBrowser baixa " +
                        "o pacote, converte o manifest (MV2/MV3 → WebExtensions) e instala no motor. " +
                        "Se uma API não existir no Firefox, a extensão é convertida mesmo assim e você vê " +
                        "o que foi descartado; se o motor recusar o pacote, ela roda pela ponte de " +
                        "compatibilidade.",
                )
                ReasonCard(
                    icon = { Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = "Build instável, de propósito",
                    body = stringResource(R.string.first_run_body_2),
                )

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text(stringResource(R.string.first_run_cta), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun ReasonCard(icon: @Composable () -> Unit, title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
