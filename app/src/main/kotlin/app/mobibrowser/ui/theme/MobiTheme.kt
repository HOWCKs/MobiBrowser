package app.mobibrowser.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size

/**
 * Paleta base do MobiBrowser. Com `dynamicColor` ligado (padrão) o Material You do
 * aparelho sobrescreve estas cores — aqui fica o fallback para aparelhos sem Dynamic
 * Color e o tom de marca das telas de extensão.
 */
private val MobiLight = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF00144C),
    secondary = Color(0xFF12A594),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA6F2E6),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF8A5BFF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDDFF),
    onTertiaryContainer = Color(0xFF220058),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF77767F),
    outlineVariant = Color(0xFFC7C5CF),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val MobiDark = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF0A2678),
    primaryContainer = Color(0xFF223DA0),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFF6ED9C9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005049),
    onSecondaryContainer = Color(0xFFA6F2E6),
    tertiary = Color(0xFFCFBBFF),
    onTertiary = Color(0xFF391A7A),
    tertiaryContainer = Color(0xFF51339B),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF12131A),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF12131A),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC7C5CF),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Formas Material 3 *Expressive*: raios grandes e assimétricos nas superfícies de
 * navegação — é o que dá a "personalidade" ao app sem sair do M3.
 */
val MobiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

/**
 * Tipografia: nada de fonte customizada no APK instável (peso + risco de layout break).
 * Títulos de aba com peso 600 e body um pouco maior que o padrão — em tela de celular,
 * URL pequena demais é o erro de UX nº 1 de navegador.
 */
private val MobiTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
}

/** Estilos do campo de endereço e do editor de user script. */
object MobiType {
    val address = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    val url = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)
}

/**
 * Movimento "expressivo": molas com overshoot controlado em abertura de folha e
 * desaceleração na barra. Um único lugar para ajustar a sensibilidade toda.
 */
object MobiMotion {
    val springSoft = androidx.compose.animation.core.spring<Float>(
        dampingRatio = 0.72f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
    )
    val springSnappy = androidx.compose.animation.core.spring<Float>(
        dampingRatio = 0.85f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
    )
    val toolbarHide = androidx.compose.animation.core.spring<Float>(
        dampingRatio = 1f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
    )
    val durationPop = 260

    /**
     * Amplifica o "poppiness" do toque (Feedback.scale-like) usado nos botões de ação.
     * Desligado nas configurações para quem prefere movimento reduzido.
     */
    var expressive: Boolean = true
}

/** Local para a UI saber o tamanho do inset de barra de sistema (evita repetir cálculo). */
data class BarLayout(
    val toolbarHeightPx: Int = 0,
    val bottomBarRect: Rect = Rect.Zero,
    val contentSize: Size = Size.Zero,
)

val LocalBarLayout = staticCompositionLocalOf { BarLayout() }

@Composable
fun MobiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    expressive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            runCatching {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }.getOrElse { if (darkTheme) MobiDark else MobiLight }
        }

        darkTheme -> MobiDark
        else -> MobiLight
    }
    MobiMotion.expressive = expressive

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = MobiTypography,
        shapes = MobiShapes,
        content = content,
    )
}
