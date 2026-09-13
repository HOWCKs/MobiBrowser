package app.mobibrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobibrowser.core.ext.ExtensionManager
import app.mobibrowser.ui.browser.BrowserScreen
import app.mobibrowser.ui.browser.PageSheet
import app.mobibrowser.ui.extensions.ExtensionsScreen
import app.mobibrowser.ui.extensions.PermissionReviewDialog
import app.mobibrowser.ui.library.LibraryScreen
import app.mobibrowser.ui.onboarding.FirstRunSheet
import app.mobibrowser.ui.settings.SettingsScreen
import app.mobibrowser.ui.tabs.TabsSheet
import app.mobibrowser.ui.userscripts.UserscriptsScreen

/**
 * Roteador da app: a tela de navegação é a base; todo o resto é overlay animado por
 * cima, com `AnimatedVisibility`. Nada de Navigation Library para 6 telas — o custo de
 * dependência não paga o benefício, e o back stack fica explícito num só lugar.
 */
@Composable
fun MobiApp(vm: MobiViewModel) {
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val prompt by vm.installPrompt.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pageSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.snack.collect { message ->
            snackbarHostState.showSnackbar(message.text)
        }
    }

    // Volta: fecha a camada mais alta, uma por vez. O primeiro uso é intencionalmente
    // pegajoso (voltar nele fecharia o app antes de a pessoa ver a explicação do modelo).
    BackHandler(enabled = overlay != Overlay.NONE || pageSheetOpen || prompt != null) {
        when {
            overlay == Overlay.FIRST_RUN -> Unit
            prompt != null -> Unit // decisão é por botão: não dá para "voltar" uma permissão
            pageSheetOpen -> pageSheetOpen = false
            else -> vm.hideOverlay()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            },
            // A tela de navegação pinta as próprias barras; o scaffold só cuida do snackbar.
            containerColor = MaterialTheme.colorScheme.surface,
            content = { insets ->
                BrowserScreen(
                    vm = vm,
                    snackbarHostState = snackbarHostState,
                    onOpenTabs = { vm.show(Overlay.TABS) },
                    onOpenMenu = { pageSheetOpen = true },
                    modifier = Modifier,
                )
            },
        )

        // ------------------------------------------------------- overlays
        AnimatedVisibility(
            visible = overlay == Overlay.TABS,
            enter = slideInVertically(animationSpec = tween(320)) { it },
            exit = fadeOut(tween(160)),
        ) {
            TabsSheet(
                vm = vm,
                onDismiss = { vm.hideOverlay() },
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.EXTENSIONS,
            enter = slideInVertically(animationSpec = tween(320)) { it / 8 } + fadeIn(),
            exit = fadeOut(tween(160)),
        ) {
            ExtensionsScreen(
                vm = vm,
                onDismiss = { vm.hideOverlay() },
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.USERSCRIPTS,
            enter = slideInHorizontally(animationSpec = tween(300)) { it },
            exit = slideInHorizontally { it },
        ) {
            UserscriptsScreen(vm = vm, onDismiss = { vm.hideOverlay() })
        }

        AnimatedVisibility(
            visible = overlay == Overlay.SETTINGS,
            enter = slideInHorizontally(animationSpec = tween(300)) { it },
            exit = slideInHorizontally { it },
        ) {
            SettingsScreen(vm = vm, onDismiss = { vm.hideOverlay() })
        }

        AnimatedVisibility(
            visible = overlay == Overlay.HISTORY || overlay == Overlay.BOOKMARKS,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LibraryScreen(
                vm = vm,
                bookmarksOnly = overlay == Overlay.BOOKMARKS,
                onDismiss = { vm.hideOverlay() },
            )
        }

        // Menu da página (compartilhar, favoritos, modo desktop, extensões deste site)
        AnimatedVisibility(visible = pageSheetOpen, enter = fadeIn(), exit = fadeOut()) {
            PageSheet(
                vm = vm,
                onDismiss = { pageSheetOpen = false },
            )
        }

        // Revisão de permissões pedida pelo motor (PromptDelegate)
        prompt?.let { pending ->
            PermissionReviewDialog(
                prompt = pending,
                onDecision = { grant, privateOk -> vm.respondToPrompt(pending.geckoId, grant, privateOk) },
            )
        }

        if (overlay == Overlay.FIRST_RUN) {
            FirstRunSheet(onContinue = vm::finishFirstRun)
        }
    }
}

