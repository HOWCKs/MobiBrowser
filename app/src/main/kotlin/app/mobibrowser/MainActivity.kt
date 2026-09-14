package app.mobibrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mobibrowser.core.Diag
import app.mobibrowser.data.ThemeMode
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.MobiApp
import app.mobibrowser.ui.theme.MobiTheme

/**
 * Activity única. Nada de Fragment: o GeckoView é anexado a um `AndroidView` e o
 * `configChanges` no manifest evita recriação em rotação — girar não pode recarregar
 * a página nem perder o estado da extensão.
 */
class MainActivity : ComponentActivity() {

    private val vm: MobiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Edge-to-edge + Compose: o tema do sistema só define o fundo do cold start.
        setContent { MobiRoot(vm) }
        // Um post depois do setContent = a primeira composição pediu frame. É o divisor entre
        // "morreu antes de existir tela" e "morreu com a tela na frente", que é a distinção que
        // interessa quando o fechamento não deixa exceção.
        window.decorView.post { Diag.at("primeira tela visível") }
        intent?.let(vm::onNewIntent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: todo link compartilhado chega aqui, sem recriar a Activity (e a página).
        vm.onNewIntent(intent)
    }

    /** Link pendente capturado antes de onCreate (cold start por VIEW/SEND). */
    override fun onDestroy() {
        // isFinishing importa: rotação/config change recria a Activity sem encerrar o app, e
        // marcar "encerrado limpo" nesse caso mentiria sobre uma morte que não aconteceu.
        if (isFinishing) (application as? MobiApplication)?.noteCleanEnd()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()
    }
}

@Composable
private fun MobiRoot(vm: MobiViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    MobiTheme(
        darkTheme = when (settings.themeMode) {
            ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        },
        dynamicColor = settings.dynamicColor,
        expressive = settings.expressiveMotion,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            MobiApp(vm = vm)
        }
    }
}
