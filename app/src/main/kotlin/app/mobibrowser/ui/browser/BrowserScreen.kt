package app.mobibrowser.ui.browser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobibrowser.R
import app.mobibrowser.core.engine.BrowserTab
import app.mobibrowser.core.engine.ExtensionActionUi
import app.mobibrowser.core.engine.TabUiState
import app.mobibrowser.core.ext.ExtensionManager
import app.mobibrowser.ui.MobiViewModel
import app.mobibrowser.ui.common.BitmapIcon
import app.mobibrowser.ui.common.ProgressBar
import app.mobibrowser.ui.theme.MobiType
import app.mobibrowser.ui.theme.MobiMotion
import org.mozilla.geckoview.GeckoView

/**
 * Tela de navegação: um único [GeckoView] reusado entre abas (trocar = `setSession`),
 * barra de endereço que vira campo de busca com sugestões do histórico, fileira de ações
 * de extensões com badge, folha de popup da extensão e banners de contexto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    vm: MobiViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenTabs: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab by vm.selectedTab.collectAsStateWithLifecycle()
    val state by vm.tabState.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val popup by vm.popup.collectAsStateWithLifecycle()
    val progress by vm.installProgress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var toolbarHeight by remember { mutableStateOf(0) }
    // Barras alternáveis por toque: o GeckoView do canal de release não expõe listener de
    // rolagem (sem setEventListener/GeckoViewEventListener), então scroll-to-hide não existe
    // — em vez de fingir que existe, a troca é explícita e previsível.
    var barsVisible by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(state?.url) { editing = false }
    LaunchedEffect(tab) { query = state?.url.orEmpty() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::installFromUri)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ----------------------------------------------------------- topo
        AnimatedVisibility(
            visible = barsVisible || editing,
            enter = slideInVertically { shift -> -shift },
            exit = slideOutVertically { shift -> -shift },
        ) {
        Surface(
            tonalElevation = if (editing) 3.dp else 0.dp,
            color = if (editing) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
            shape = if (editing) MaterialTheme.shapes.large else MaterialTheme.shapes.extraSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (editing) 8.dp else 0.dp,
                    vertical = if (editing) 6.dp else 0.dp,
                )
                .onSizeChanged { toolbarHeight = it.height },
        ) {
            Column {
                AddressRow(
                    state = state,
                    editing = editing,
                    query = query,
                    onQueryChange = { query = it },
                    onStartEditing = {
                        editing = true
                        query = state?.url.orEmpty()
                    },
                    onSubmit = {
                        vm.submit(it)
                        editing = false
                    },
                    onCancel = {
                        editing = false
                        query = state?.url.orEmpty()
                    },
                    suggestions = if (editing) vm.addressSuggestions(query) else emptyList(),
                    onPickSuggestion = {
                        vm.openUrl(it)
                        editing = false
                    },
                    onReload = { if (state?.loading == true) vm.stop() else vm.reload() },
                    onMenu = onOpenMenu,
                )
                ProgressBar(progress = state?.progress ?: -1)
            }
        }

        }

        // -------------------------------------------------------- conteúdo
        Box(Modifier.weight(1f)) {
            if (!barsVisible) {
                // A única forma de trazer as barras de volta tem que estar no caminho do
                // polegar e não pode cobrir conteúdo: pílula colada no topo, discreta.
                BarsHandle(
                    onClick = { barsVisible = true },
                    show = true,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 2.dp),
                )
            }
            val current = tab
            if (current == null) {
                NoTabState(
                    onNewTab = { vm.newTab() },
                    onInstallFile = { filePicker.launch(arrayOf("*/*")) },
                )
            } else {
                EngineSurface(tab = current)
            }

            // Column de embrulho: AnimatedVisibility é extensão de ColumnScope e o Kotlin não
            // deixa usar um receptor implícito de fora da fronteira de um layout (o Box aqui).
            Column(Modifier.align(Alignment.TopCenter)) {
                AnimatedVisibility(
                    visible = state?.crashed == true,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = fadeOut(),
                ) {
                    CrashCard(onReload = { vm.reload() })
                }
            }

            // Banner contextual: página de item da Chrome Web Store → instalar.
            val storeId = state?.url?.let { app.mobibrowser.core.ext.ChromeWebStore.idFrom(it) }
            Column(Modifier.align(Alignment.BottomCenter)) {
                AnimatedVisibility(
                    visible = storeId != null && state?.crashed != true,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    StoreInstallBanner(
                        storeId = storeId.orEmpty(),
                        installing = progress is ExtensionManager.InstallProgress.Working,
                        onInstall = {
                            // ouEmpty(): dentro do lambda o compilador não herda o "storeId !=
                            // null" do AnimatedVisibility, então o nullability se perde.
                            vm.setStoreInstallInput(storeId.orEmpty())
                            vm.installFromStore(storeId.orEmpty())
                        },
                        onOpenExtensions = { vm.openExtensions() },
                    )
                }
            }

            if (progress != null) {
                InstallStatusCard(progress = progress!!, onDismiss = vm::dismissInstallProgress)
            }

            if (vm.engineOff) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                    ) {
                        Text(
                            text = "Motor desligado para diagnóstico. Nada abre página até você " +
                                "reativar — as telas locais continuam funcionando.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.setEngineOff(false) }) { Text("Reativar") }
                    }
                }
            }

            // Popup da extensão: folha modal com a sessão que o motor nos deu.
            popup?.let { ui ->
                Dialog(
                    onDismissRequest = vm::dismissPopup,
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    ui.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = vm::dismissPopup) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                                }
                            }
                            AndroidView(
                                factory = { ctx -> GeckoView(ctx) },
                                update = { view ->
                                    // GeckoView exige releaseSession() antes de trocar: setSession
                                    // com uma sessão já aberta nesta view lança IllegalStateException.
                                    if (view.session !== ui.session) {
                                        view.releaseSession()
                                        view.setSession(ui.session)
                                    }
                                },
                                // O Compose destrói o AndroidView ao sair da composição; a sessão
                                // é recurso do motor, não da view — devolvê-la aqui.
                                onRelease = { view -> view.releaseSession() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 220.dp, max = 480.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------- barra inferior
        AnimatedVisibility(
            visible = !editing,
            enter = slideInVertically(animationSpec = tween(MobiMotion.durationPop)) { it },
            exit = slideOutVertically { it },
        ) {
            BottomBar(
                state = state,
                actions = actions.values.toList(),
                tabsCount = vm.tabList.value.count { it.isPrivate == (state?.isPrivate ?: false) },
                onTabs = onOpenTabs,
                onBack = vm::goBack,
                onForward = vm::goForward,
                onFind = { vm.showFind() },
                onExtensionTap = vm::tapExtensionAction,
                onNewTab = { vm.newTab() },
                onHideBars = { barsVisible = false },
            )
        }

        AnimatedVisibility(
            visible = state?.findVisible == true,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            FindRow(
                query = state?.findQuery.orEmpty(),
                matches = state?.findMatches ?: 0,
                onChange = vm::find,
                onNext = vm::findNext,
                onClose = vm::closeFind,
            )
        }
    }
}

@Composable
private fun EngineSurface(tab: BrowserTab, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            GeckoView(context).apply {
                setBackgroundColor(Color.Transparent.toArgb())
            }
        },
        update = { view ->
            // Idem popup — e é o caminho crítico do início: com a restauração deixando uma aba
            // ativa, o update roda mais de uma vez com sessões diferentes, e sem releaseSession()
            // o segundo setSession é um IllegalStateException na main thread (o doc do 155 é
            // explícito: "you must use releaseSession() first, otherwise IllegalStateException").
            if (view.session !== tab.session) {
                view.releaseSession()
                view.setSession(tab.session)
            }
        },
        onRelease = { view -> view.releaseSession() },
        modifier = modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressRow(
    state: TabUiState?,
    editing: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onStartEditing: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    suggestions: List<String>,
    onPickSuggestion: (String) -> Unit,
    onReload: () -> Unit,
    onMenu: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            if (editing) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_stop))
                }
            } else {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.action_menu))
                }
            }

            Surface(
                onClick = { if (!editing) onStartEditing() },
                shape = MaterialTheme.shapes.large,
                color = if (editing) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) {
                if (editing) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MobiType.address,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { onSubmit(query); keyboard?.hide() },
                        ),
                        placeholder = { Text(stringResource(R.string.address_hint), style = MobiType.address) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            TextButton(onClick = { onSubmit(query); keyboard?.hide() }) {
                                Text(stringResource(R.string.action_go))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = if (state?.secure == true) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = stringResource(
                                if (state?.secure == true) R.string.secure_connection else R.string.insecure_connection,
                            ),
                            tint = if (state?.secure == true) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = state?.displayUrl?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.address_hint),
                            style = MobiType.address,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (state?.javascript == false) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = "JS off",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onReload) {
                if (state?.loading == true) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.action_stop))
                } else {
                    AnimatedVisibility(visible = !editing, enter = fadeIn(), exit = fadeOut()) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reload))
                    }
                }
            }
        }

        AnimatedVisibility(visible = editing && suggestions.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    suggestions.forEach { suggestion ->
                        ListItem(
                            headlineContent = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                            modifier = Modifier
                                .clickable { onPickSuggestion(suggestion) }
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Alça das barras: um toque esconde, um toque traz de volta. `show = true` é a versão
 * flutuante (barras escondidas), `false` é o grabber no topo da barra inferior.
 */
@Composable
private fun BarsHandle(
    onClick: () -> Unit,
    show: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = if (show) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        border = if (show) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = modifier.height(20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.width(if (show) 76.dp else 44.dp),
        ) {
            Icon(
                imageVector = if (show) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (show) stringResource(R.string.action_show_bars) else stringResource(R.string.action_hide_bars),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (show) {
                Text(
                    stringResource(R.string.action_show_bars),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: TabUiState?,
    actions: List<ExtensionActionUi>,
    tabsCount: Int,
    onTabs: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onFind: () -> Unit,
    onExtensionTap: (String) -> Unit,
    onNewTab: () -> Unit,
    onHideBars: () -> Unit,
) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        BarsHandle(
            onClick = onHideBars,
            show = false,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (actions.isNotEmpty()) {
            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(actions, key = { it.extensionId }) { action ->
                    ExtensionActionChip(action = action, onTap = { onExtensionTap(action.extensionId) })
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 2.dp),
        ) {
            IconButton(onClick = onBack, enabled = state?.canGoBack == true) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            IconButton(onClick = onForward, enabled = state?.canGoForward == true) {
                Icon(
                    Icons.AutoMirrored.Filled.Forward,
                    contentDescription = stringResource(R.string.action_forward),
                    tint = if (state?.canGoForward == true) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onFind) {
                Icon(Icons.Default.FindInPage, contentDescription = stringResource(R.string.action_find_in_page))
            }
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                    .clickable(onClick = onTabs),
                contentAlignment = Alignment.Center,
            ) {
                BadgedBox(badge = { if (tabsCount > 1) Badge { Text(tabsCount.toString()) } }) {
                    Text(
                        tabsCount.coerceAtMost(99).toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            IconButton(onClick = onNewTab) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_new_tab))
            }
        }
    }
}

@Composable
private fun ExtensionActionChip(action: ExtensionActionUi, onTap: () -> Unit) {
    Surface(
        onClick = onTap,
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        modifier = Modifier.size(width = 46.dp, height = 42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            BadgedBox(
                badge = {
                    if (!action.badgeText.isNullOrBlank()) {
                        Badge(
                            containerColor = action.badgeBackground?.let { Color(it) }
                                ?: MaterialTheme.colorScheme.error,
                            contentColor = action.badgeTextColor?.let { Color(it) }
                                ?: MaterialTheme.colorScheme.onError,
                        ) { Text(action.badgeText!!) }
                    }
                },
            ) {
                BitmapIcon(
                    bitmap = if (action.enabled) action.icon else null,
                    fallbackUrl = action.name,
                    size = 24.dp,
                    alpha = if (action.enabled) 1f else 0.4f,
                )
            }
        }
    }
}

@Composable
private fun FindRow(
    query: String,
    matches: Int,
    onChange: (String) -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.action_find_in_page)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
            )
            Text(
                if (matches == 0) stringResource(R.string.find_no_results) else "$matches",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.find_next))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.find_close))
            }
        }
    }
}

@Composable
private fun CrashCard(onReload: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
            Text(
                stringResource(R.string.crashed_tab),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReload) { Text(stringResource(R.string.crash_restore)) }
        }
    }
}

@Composable
private fun StoreInstallBanner(
    storeId: String,
    installing: Boolean,
    onInstall: () -> Unit,
    onOpenExtensions: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 6.dp),
        ) {
            Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.extensions_store_banner),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (installing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onInstall) { Text(stringResource(R.string.extensions_install_action)) }
            }
            TextButton(onClick = onOpenExtensions) { Text(stringResource(R.string.extensions_details)) }
        }
    }
}

@Composable
private fun InstallStatusCard(progress: ExtensionManager.InstallProgress, onDismiss: () -> Unit) {
    val (text, failed) = when (progress) {
        is ExtensionManager.InstallProgress.Working -> progress.step to false
        is ExtensionManager.InstallProgress.Success ->
            "${progress.name} — ${if (progress.mode == app.mobibrowser.core.ext.ExtensionRegistry.Mode.NATIVE) "no motor" else "em modo compatibilidade"}" to false

        is ExtensionManager.InstallProgress.Failure ->
            (progress.message + (progress.detail?.let { "\n$it" } ?: "")) to true
    }
    // Sucesso: some sozinho. Falha: fica até o usuário fechar (a mensagem tem o motivo).
    LaunchedEffect(progress) {
        if (!failed) {
            kotlinx.coroutines.delay(2_800)
            onDismiss()
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp)) {
            if (!failed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(if (failed) 0.dp else 12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = if (failed) 6 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun NoTabState(onNewTab: () -> Unit, onInstallFile: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Navegador com extensões",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.extensions_none_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onNewTab) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_new_tab))
            }
            TextButton(onClick = onInstallFile) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.extensions_install_file))
            }
        }
    }
}
