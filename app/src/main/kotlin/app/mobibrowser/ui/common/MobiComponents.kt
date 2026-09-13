package app.mobibrowser.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mobibrowser.core.engine.toHost
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector

/** Ícone do site derivado do host: sem favicons, um monograma estável ainda ajuda scanning. */
@Composable
fun SiteMonogram(
    url: String?,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    val host = url.toHost() ?: "?"
    val seed = host.hashCode()
    val palette = listOf(
        Color(0xFF3B5BDB), Color(0xFF12A594), Color(0xFF8A5BFF), Color(0xFFE8590C),
        Color(0xFF2F9E44), Color(0xFF1971C2), Color(0xFFC2255C), Color(0xFF66A80F),
    )
    val color = palette[Math.floorMod(seed, palette.size)]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = host.removePrefix("www.").take(1).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/** Bitmap vindo do GeckoView (Image.getBitmap) pintado redondo, com fallback de monograma. */
@Composable
fun BitmapIcon(
    bitmap: Bitmap?,
    fallbackUrl: String? = null,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    if (bitmap == null) {
        SiteMonogram(url = fallbackUrl, size = size, modifier = modifier)
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        alpha = alpha,
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.extraSmall),
    )
}

/** Cabeçalho de seção usado nas telas de extensões/configurações/biblioteca. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Linha de configuração com switch, sem reinventar o que o M3 já tem. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
        )
    }
}

/** Cartão de estado vazio: presente nas telas de extensão/biblioteca. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
fun ChipRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Barra de progresso fina usada acima do conteúdo da aba (estilo Material 3 expressivo). */
@Composable
fun ProgressBar(progress: Int, modifier: Modifier = Modifier) {
    if (progress < 0) return
    val fraction = (progress.coerceIn(0, 100)) / 100f
    Canvas(modifier = modifier.fillMaxWidth().height(3.dp)) {
        val h = size.height
        drawRoundRect(
            color = MaterialTheme.colorScheme.surfaceVariant,
            height = h,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h),
        )
        drawRoundRect(
            color = MaterialTheme.colorScheme.primary,
            width = size.width * fraction,
            height = h,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h),
        )
    }
}
