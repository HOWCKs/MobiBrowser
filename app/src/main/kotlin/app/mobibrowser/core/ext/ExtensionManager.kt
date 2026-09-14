package app.mobibrowser.core.ext

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.mobibrowser.core.MobiLog
import app.mobibrowser.core.awaitResult
import app.mobibrowser.core.engine.ExtensionActionUi
import app.mobibrowser.core.engine.ExtensionPopupUi
import app.mobibrowser.core.engine.GeckoEngine
import app.mobibrowser.data.AppPrefs
import app.mobibrowser.data.BrowserDb
import app.mobibrowser.data.ScriptKind
import app.mobibrowser.data.UserScript
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Orquestra instalação/ativação de extensões e é a fonte de estado da tela de Extensões
 * e dos botões de ação na barra de ferramentas.
 *
 * Dois modos de execução, e isso é a alma do produto:
 *
 * 1. [ExtensionRegistry.Mode.NATIVE] — o pacote vira uma WebExtension instalada no
 *    GeckoView (`WebExtensionController.install`). Tem runtime completo: service
 *    worker/event page, storage, declarativeNetRequest, popups, badges.
 *    Assinatura: o motor, no canal de release, só instala pacote assinado pela Mozilla —
 *    o install nativo de um item da loja falharia com `ERROR_SIGNEDSTATE_REQUIRED`. Por
 *    isso o app roda sobre o **GeckoView nightly**, em que `xpinstall.signatures.required`
 *    pode ser desligado (GeckoEngine escreve o YAML de config do motor). Aí o pacote
 *    convertido instala nativamente. Se alguém voltar o canal para `release`
 *    (gradle/libs.versions.toml) ou o motor recusar por outro motivo, caímos no modo 2.
 *
 * 2. [ExtensionRegistry.Mode.BRIDGE] — caminho de compatibilidade: extraímos os
 *    content scripts/estilos e as regras dNR do pacote convertido e os servimos pela
 *    MobiBridge (extensão embutida no APK, instalada com `installBuiltIn`, que é o
 *    único mecanismo que dispensa assinatura). Roda de verdade o código que pinta a
 *    página; não roda o que exige privilégio do navegador.
 *
 * A escolha do modo é sempre comunicada na UI (nada de fingir que é idêntico).
 */
class ExtensionManager(
    private val context: Context,
    private val engine: GeckoEngine,
    private val prefs: AppPrefs,
    private val db: BrowserDb,
    private val registry: ExtensionRegistry,
    private val bridge: BridgeScripts,
    private val scope: CoroutineScope,
) {
    private val store = ChromeWebStore()
    private val controller: WebExtensionController get() = engine.webExtensions

    private val extensionsRoot = File(context.filesDir, "extensions").apply { mkdirs() }
    private val stagingRoot = File(context.cacheDir, "ext-staging").apply { mkdirs() }

    /** Extensões vivas no motor, por geckoId. */
    private val engineExtensions = mutableMapOf<String, WebExtension>()
    private val engineMeta = mutableMapOf<String, MetaSnapshot>()
    private val latestAction = mutableMapOf<String, WebExtension.Action>()

    private data class MetaSnapshot(
        val name: String?,
        val description: String?,
        val icon: Bitmap?,
        val permissions: List<String>,
        val origins: List<String>,
        val dataCollection: List<String>,
        val rating: Double,
        val location: String,
    )

    data class ExtensionUiState(
        val geckoId: String,
        val name: String,
        val version: String,
        val mode: ExtensionRegistry.Mode,
        val enabled: Boolean,
        val knownByEngine: Boolean,
        val storeId: String?,
        val storeUrl: String?,
        val description: String?,
        val permissions: List<String>,
        val origins: List<String>,
        val warnings: List<String>,
        val droppedKeys: List<String>,
        val siteAccess: ExtensionRegistry.SiteAccess,
        val allowList: List<String>,
        val denyList: List<String>,
        val privateAllowed: Boolean,
        val icon: Bitmap?,
        val updateAvailable: String?,
        val hasOptionsPage: Boolean,
        val scriptCount: Int,
    ) {
        /** Espelho da regra do registro, para a UI mostrar o efeito prático por site. */
        fun allowsSite(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            if (siteAccess == ExtensionRegistry.SiteAccess.ALL_SITES) {
                return denyList.none { pattern -> MatchPattern.matches(pattern, url) }
            }
            return MatchPattern.matchesAny(allowList, url)
        }
    }

    sealed interface InstallProgress {
        data class Working(val step: String) : InstallProgress
        data class Success(val name: String, val mode: ExtensionRegistry.Mode) : InstallProgress
        data class Failure(val message: String, val detail: String?, val storeId: String?) : InstallProgress
    }

    private val _extensions = MutableStateFlow<List<ExtensionUiState>>(emptyList())
    val extensions: StateFlow<List<ExtensionUiState>> = _extensions.asStateFlow()

    private val _actions = MutableStateFlow<Map<String, ExtensionActionUi>>(emptyMap())
    val actions: StateFlow<Map<String, ExtensionActionUi>> = _actions.asStateFlow()

    private val _popup = MutableStateFlow<ExtensionPopupUi?>(null)
    val popup: StateFlow<ExtensionPopupUi?> = _popup.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private val _prompt = MutableStateFlow<InstallPrompt?>(null)
    val prompt: StateFlow<InstallPrompt?> = _prompt.asStateFlow()

    /** Chamado quando a UI deve abrir uma URL (ex.: página de opções da extensão). */
    var onOpenUrl: ((String) -> Unit)? = null

    data class InstallPrompt(
        val geckoId: String,
        val name: String,
        val permissions: List<String>,
        val origins: List<String>,
        val dataCollection: List<String>,
        val storeId: String?,
    )

    /** Página da loja para o banner "instalar esta extensão". */
    fun storeIdFor(url: String?): String? = ChromeWebStore.idFrom(url)

    suspend fun storeSummary(storeId: String) = store.fetchSummary(storeId)

    /* ------------------------------------------------------------------ *
     * Bootstrap                                                          *
     * ------------------------------------------------------------------ */

    fun start() {
        controller.setPromptDelegate(
            object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension,
                    // Sem projeção `out`: sobrepor método Java exige o tipo do parâmetro
                    // exatamente como ele é (`String[]` -> Array<String>); com `out` o compilador
                    // diz "overrides nothing" (visto no CI).
                    permissions: Array<String>?,
                    origins: Array<String>?,
                    dataCollectionPermissions: Array<String>?,
                ): GeckoResult<WebExtension.PermissionPromptResponse> = requestUserConsent(
                    extension,
                    permissions,
                    origins,
                    dataCollectionPermissions,
                )
            },
        )

        scope.launch(Dispatchers.Main.immediate) {
            // Espelho das preferências para o delegate (que roda fora de coroutine).
            prefs.settings.collect { settings ->
                bridge.userscriptsEnabled = settings.userscriptsEnabled
                bridge.dnrEnabled = settings.dnrEnabled
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            ensureBridgeInstalled()
            syncWithEngine()
            publish()
        }
        // Política por site mudou no registro → recalcular o que a UI mostra.
        scope.launch { registry.records.collect { publish() } }
    }

    /**
     * Instala a extensão-ponte embutida. `installBuiltIn` aceita pasta dentro de
     * `assets/` e é o único caminho do GeckoView que não exige assinatura Mozilla —
     * por isso ela vem no APK e não é "baixada".
     */
    private suspend fun ensureBridgeInstalled() {
        if (!prefs.current().bridgeEnabled) {
            MobiLog.i(SCOPE, "ponte desativada nas configurações")
            return
        }
        val installed = runCatching {
            withTimeoutOrNull(30_000) {
                controller.installBuiltIn("resource://android/assets/$BRIDGE_DIR/")
                    .awaitResult()
            }
        }.getOrNull()

        if (installed == null) {
            MobiLog.w(SCOPE, "MobiBridge não instalada: modo compatibilidade fica só com user scripts locais")
            return
        }
        // Mensagens da ponte chegam ao BridgeScripts identificadas pelo app id do manifest.
        runCatching { installed.setMessageDelegate(bridge, BRIDGE_NATIVE_APP) }
            .onFailure { MobiLog.w(SCOPE, "não consegui registrar o delegate da ponte", it) }
        MobiLog.i(SCOPE, "MobiBridge pronta (${installed.id})")
    }

    /** Lista do motor + nosso registro → estado de UI. */
    suspend fun syncWithEngine() {
        val live = runCatching {
            withTimeoutOrNull(20_000) { controller.list().awaitResult() }
        }.getOrNull().orEmpty()

        engineExtensions.clear()
        engineMeta.clear()
        live.forEach { ext ->
            engineExtensions[ext.id] = ext
            ext.setActionDelegate(actionDelegate)
            engineMeta[ext.id] = snapshotOf(ext)
        }
        publish()
    }

    private fun snapshotOf(ext: WebExtension): MetaSnapshot {
        val meta = runCatching { ext.metaData }.getOrNull()
        return MetaSnapshot(
            name = meta?.name,
            description = meta?.description,
            icon = runCatching {
                runBlocking { withTimeoutOrNull(5_000) { meta?.icon?.getBitmap(64)?.awaitResult() } }
            }.getOrNull(),
            permissions = meta?.requiredPermissions?.toList().orEmpty(),
            origins = meta?.requiredOrigins?.toList().orEmpty(),
            dataCollection = meta?.requiredDataCollectionPermissions?.toList().orEmpty(),
            rating = meta?.averageRating?.toDouble() ?: 0.0,
            location = ext.location,
        )
    }

    private fun publish() {
        val ids = (registry.records.value.keys + engineExtensions.keys).toSet()
        _extensions.value = ids.mapNotNull { geckoId ->
            val record = registry.get(geckoId)
            val live = engineExtensions[geckoId]
            val meta = engineMeta[geckoId]
            val name = record?.name ?: meta?.name ?: geckoId
            ExtensionUiState(
                geckoId = geckoId,
                name = name,
                version = record?.version ?: "?",
                mode = record?.mode ?: ExtensionRegistry.Mode.NATIVE,
                enabled = record?.enabled ?: (live != null),
                knownByEngine = live != null,
                storeId = record?.storeId,
                storeUrl = record?.storeId?.let { ChromeWebStore.detailUrl(it) },
                description = meta?.description ?: if (record?.isBridge == true) {
                    "Convertida da Chrome Web Store; executada pela ponte de compatibilidade."
                } else {
                    null
                },
                permissions = (record?.permissions ?: meta?.permissions ?: emptyList()).distinct(),
                origins = (record?.origins ?: meta?.origins ?: emptyList()).distinct(),
                warnings = record?.warnings.orEmpty(),
                droppedKeys = record?.droppedKeys.orEmpty(),
                siteAccess = record?.siteAccess ?: ExtensionRegistry.SiteAccess.ALL_SITES,
                allowList = record?.allowList.orEmpty(),
                denyList = record?.denyList.orEmpty(),
                privateAllowed = record?.privateAllowed ?: false,
                icon = meta?.icon,
                updateAvailable = record?.updateAvailable,
                hasOptionsPage = record?.optionsPage != null,
                scriptCount = if (record?.isBridge == true) db.listScripts(record.geckoId).size else 0,
            )
        }.sortedWith(compareBy<ExtensionUiState> { !it.enabled }.thenBy { it.name.lowercase() })
    }

    /* ------------------------------------------------------------------ *
     * Instalação                                                         *
     * ------------------------------------------------------------------ */

    suspend fun installFromStore(input: String): Boolean {
        val storeId = ChromeWebStore.idFrom(input)
        if (storeId == null) {
            _progress.value = InstallProgress.Failure(
                "Não achei um id de extensão nisso.",
                "Use a URL da página da Chrome Web Store (…/detail/<nome>/<id>) ou cole só o id de 32 letras.",
                null,
            )
            return false
        }
        return runCatching { downloadAndInstall(storeId) }
            .fold(
                onSuccess = { it },
                onFailure = { t ->
                    MobiLog.e(SCOPE, "instalação de $storeId falhou", t)
                    _progress.value = InstallProgress.Failure(
                        "Não foi possível instalar a extensão.",
                        t.message,
                        storeId,
                    )
                    false
                },
            )
    }

    private suspend fun downloadAndInstall(storeId: String): Boolean {
        step("Baixando pacote da Chrome Web Store…")
        val staging = File(stagingRoot, "dl-$storeId").apply { deleteRecursively(); mkdirs() }
        val crx = File(staging, "package.crx")
        store.downloadCrx(storeId, crx)
        val summary = store.fetchSummary(storeId)
        return installPackage(
            bytes = crx.readBytes(),
            preferredStoreId = storeId,
            displayName = summary.name,
            description = summary.description,
        )
    }

    /** Sideload: aceita .crx, .xpi, .zip e pasta compactada de extensão. */
    suspend fun installFromFile(uri: Uri): Boolean = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Não consegui ler o arquivo escolhido.")
        step("Lendo pacote…")
        installPackage(bytes, preferredStoreId = null, displayName = null, description = null)
    }.getOrElse {
        MobiLog.e(SCOPE, "sideload falhou", it)
        _progress.value = InstallProgress.Failure("Arquivo inválido.", it.message, null)
        false
    }

    private suspend fun installPackage(
        bytes: ByteArray,
        preferredStoreId: String?,
        displayName: String?,
        description: String?,
    ): Boolean {
        val workDir = File(stagingRoot, "w-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            step("Desempacotando…")
            val payload = CrxPackage.extract(bytes).zipPayload
            val files = CrxPackage.unzip(payload, workDir)
            val rawManifest = CrxPackage.readManifest(workDir)
            if (rawManifest == null) {
                fail("Sem manifest.json no pacote.", "Não parece uma extensão (arquivos no ZIP: ${files.size}).", preferredStoreId)
                return false
            }

            step("Convertendo manifest para WebExtensions…")
            val converted = try {
                ManifestConverter.convert(rawManifest, forcedGeckoId = preferredStoreId?.let { "$it@mobi-store" })
            } catch (e: ManifestConverter.ConversionException) {
                fail("Conversão recusada.", e.message, preferredStoreId)
                return false
            }
            File(workDir, "manifest.json").writeText(converted.manifest)
            val geckoId = converted.report.geckoId

            step("Empacotando .xpi…")
            val xpi = File(extensionsRoot, "$geckoId.xpi")
            CrxPackage.zip(workDir, xpi)

            // 1ª tentativa: motor (WebExtension real).
            step("Instalando no motor…")
            val installed = runCatching {
                withTimeoutOrNull(60_000) {
                    controller.install(
                        "file://${xpi.absolutePath}",
                        WebExtensionController.INSTALLATION_METHOD_FROM_FILE,
                    ).awaitResult()
                }
            }
            val installError = installed.exceptionOrNull()
            if (installed.isSuccess && installed.getOrNull() != null) {
                registry.upsert(
                    baseRecord(geckoId, converted, xpi.absolutePath, null, preferredStoreId, displayName)
                        .copy(mode = ExtensionRegistry.Mode.NATIVE, xpiPath = xpi.absolutePath),
                )
                syncWithEngine()
                _progress.value = InstallProgress.Success(converted.report.name, ExtensionRegistry.Mode.NATIVE)
                return true
            }

            // 2ª tentativa: ponte de compatibilidade (só content scripts/estilos/regras).
            val code = (installError as? WebExtension.InstallException)?.code
            MobiLog.w(SCOPE, "install nativo recusado (code=$code): ${installError?.message}")
            step("Motor recusou o pacote — instalando em modo compatibilidade…")
            val keptDir = File(extensionsRoot, geckoId).apply { deleteRecursively(); mkdirs() }
            workDir.copyRecursively(keptDir, overwrite = true)
            val scripts = harvestBridgeScripts(geckoId, converted.manifest, keptDir, converted.report)
            val dnrRules = harvestDnrRules(keptDir, converted.report)

            registry.upsert(
                baseRecord(geckoId, converted, null, keptDir.absolutePath, preferredStoreId, displayName)
                    .copy(
                        mode = ExtensionRegistry.Mode.BRIDGE,
                        dnrRules = dnrRules,
                        warnings = converted.report.warnings +
                            if (scripts == 0) {
                                listOf(
                                    "Este pacote não expõe content scripts/estilos — em modo compatibilidade " +
                                        "não há o que executar.",
                                )
                            } else {
                                listOf(MODE_BRIDGE_NOTE)
                            },
                    ),
            )
            publish()
            _progress.value = InstallProgress.Success(converted.report.name, ExtensionRegistry.Mode.BRIDGE)
            return true
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private fun baseRecord(
        geckoId: String,
        converted: ManifestConverter.Converted,
        xpiPath: String?,
        dirPath: String?,
        storeId: String?,
        displayName: String?,
    ) = ExtensionRegistry.Record(
        geckoId = geckoId,
        storeId = storeId,
        name = displayName?.takeIf { it.isNotBlank() } ?: converted.report.name,
        version = converted.report.version,
        xpiPath = xpiPath,
        dirPath = dirPath,
        permissions = permissionsOf(converted.manifest),
        origins = originsOf(converted.manifest),
        warnings = converted.report.warnings,
        droppedKeys = converted.report.droppedKeys,
        optionsPage = optionsPageOf(converted.manifest),
        popupPage = converted.report.takeIf { it.hasPopup }?.let { "popup" },
    )

    private fun permissionsOf(manifest: String): List<String> = buildList {
        val json = JSONObject(manifest)
        json.optJSONArray("permissions")?.let { arr -> for (i in 0 until arr.length()) add(arr.optString(i)) }
    }

    private fun originsOf(manifest: String): List<String> = buildList {
        val json = JSONObject(manifest)
        listOf("permissions", "host_permissions").forEach { key ->
            json.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i)
                    if (value.contains("://") || value == "<all_urls>") add(value)
                }
            }
        }
    }

    private fun optionsPageOf(manifest: String): String? {
        val json = JSONObject(manifest)
        json.optJSONObject("options_ui")?.optString("page")?.takeIf { it.isNotBlank() }?.let { return it }
        return json.optString("options_page").takeIf { it.isNotBlank() }
    }

    /**
     * Content scripts/estilos declarados no manifest viram linhas no banco, lidas pela
     * ponte. É a parte que dá para transportar com fidelidade sem runtime de extensão.
     */
    private fun harvestBridgeScripts(
        geckoId: String,
        manifest: String,
        root: File,
        report: ManifestConverter.ConversionReport,
    ): Int {
        val scripts = mutableListOf<UserScript>()
        val json = JSONObject(manifest)
        val entries = json.optJSONArray("content_scripts") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val matches = entry.optJSONArray("matches")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: listOf("<all_urls>")
            val pattern = matches.joinToString("\n")
            val runAtIdle = entry.optString("run_at", "document_end") != "document_start"

            entry.optJSONArray("js")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val path = arr.optString(k)
                    val file = File(root, path)
                    if (file.isFile) {
                        scripts += UserScript(
                            id = "$geckoId#$path",
                            name = "${report.name} · $path",
                            code = file.readText(),
                            pattern = pattern,
                            kind = ScriptKind.JS,
                            enabled = true,
                            sourceExtension = geckoId,
                            runAtIdle = runAtIdle,
                        )
                    }
                }
            }
            entry.optJSONArray("css")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val path = arr.optString(k)
                    val file = File(root, path)
                    if (file.isFile) {
                        scripts += UserScript(
                            id = "$geckoId#$path",
                            name = "${report.name} · $path",
                            code = file.readText(),
                            pattern = pattern,
                            kind = ScriptKind.CSS,
                            enabled = true,
                            sourceExtension = geckoId,
                            runAtIdle = runAtIdle,
                        )
                    }
                }
            }
        }
        db.replaceExtensionScripts(geckoId, scripts)
        return scripts.size
    }

    private fun harvestDnrRules(root: File, report: ManifestConverter.ConversionReport): String? {
        val rules = JSONArray()
        report.ruleResourcePaths.forEach { path ->
            val file = File(root, path)
            if (!file.isFile) return@forEach
            runCatching {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    if (i > MAX_HARVESTED_RULES) return@runCatching
                    rules.put(arr.getJSONObject(i))
                }
            }.onFailure { MobiLog.w(SCOPE, "regras de $path ilegíveis", it) }
        }
        return if (rules.length() == 0) null else rules.toString()
    }

    /* ------------------------------------------------------------------ *
     * Ações da UI                                                        *
     * ------------------------------------------------------------------ */

    fun dismissProgress() {
        _progress.value = null
    }

    suspend fun setEnabled(geckoId: String, enabled: Boolean) {
        val record = registry.get(geckoId) ?: return
        registry.update(geckoId) { it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) }
        if (record.mode == ExtensionRegistry.Mode.NATIVE) {
            val ext = engineExtensions[geckoId]
            if (ext != null) {
                runCatching {
                    if (enabled) {
                        controller.enable(ext, WebExtensionController.EnableSource.USER).awaitResult()
                    } else {
                        controller.disable(ext, WebExtensionController.EnableSource.USER).awaitResult()
                    }
                }.onFailure { MobiLog.w(SCOPE, "enable/disable nativo falhou", it) }
            }
        } else {
            db.listScripts(geckoId).forEach { db.setScriptEnabled(it.id, enabled) }
        }
        publish()
    }

    suspend fun uninstall(geckoId: String) {
        val record = registry.get(geckoId)
        val ext = engineExtensions[geckoId]
        if (ext != null) {
            runCatching { controller.uninstall(ext).awaitResult() }
                .onFailure { MobiLog.w(SCOPE, "uninstall nativo falhou", it) }
        }
        engineExtensions.remove(geckoId)
        engineMeta.remove(geckoId)
        _actions.update { it - geckoId }
        db.listScripts(geckoId).forEach { db.deleteScript(it.id) }
        record?.dirPath?.let { File(it).deleteRecursively() }
        record?.xpiPath?.let { File(it).delete() }
        registry.remove(geckoId)
        publish()
    }

    /**
     * Acesso por site. No modo ponte isso é aplicado de verdade (o BridgeScripts consulta
     * o registro a cada pedido de script). No modo nativo o motor tem o próprio modelo de
     * permission prompts por origem — na v1 guardamos a intenção e mostramos na UI, sem
     * fingir que já cortamos o runtime da extensão assinada.
     */
    suspend fun setSiteAccess(
        geckoId: String,
        access: ExtensionRegistry.SiteAccess,
        allowList: List<String>,
        denyList: List<String> = emptyList(),
    ) {
        registry.update(geckoId) { it.copy(siteAccess = access, allowList = allowList, denyList = denyList) }
        publish()
    }

    suspend fun setPrivateAllowed(geckoId: String, allowed: Boolean) {
        registry.update(geckoId) { it.copy(privateAllowed = allowed) }
        val ext = engineExtensions[geckoId]
        if (ext != null && ext.isBuiltIn) {
            // Built-in ignora a flag; só a ponte entra aqui e ela não roda em aba anônima.
        }
        if (ext != null && !ext.isBuiltIn) {
            runCatching { controller.setAllowedInPrivateBrowsing(ext, allowed).awaitResult() }
                .onFailure { MobiLog.w(SCOPE, "setAllowedInPrivateBrowsing falhou", it) }
        }
        publish()
    }

    /** Reinstala a versão mais nova do pacote da loja por cima da existente. */
    suspend fun updateFromStore(geckoId: String): Boolean {
        val record = registry.get(geckoId) ?: return false
        val storeId = record.storeId ?: return false
        return downloadAndInstall(storeId)
    }

    fun openOptionsPage(geckoId: String) {
        val record = registry.get(geckoId) ?: return
        val path = record.optionsPage ?: return
        val location = engineExtensions[geckoId]?.location ?: run {
            onOpenUrl?.invoke(ChromeWebStore.detailUrl(record.storeId ?: return))
            return
        }
        onOpenUrl?.invoke(location.trimEnd('/') + "/" + path.trimStart('/'))
    }

    /* ------------------------------------------------------------------ *
     * Browser actions / popups                                          *
     * ------------------------------------------------------------------ */

    private val actionDelegate by lazy {
        object : WebExtension.ActionDelegate {
            override fun onBrowserAction(
                extension: WebExtension,
                session: org.mozilla.geckoview.GeckoSession?,
                action: WebExtension.Action,
            ) {
                latestAction[extension.id] = action
                scope.launch(Dispatchers.Main.immediate) {
                    val bitmap = runCatching {
                        withTimeoutOrNull(4_000) { action.icon?.getBitmap(64)?.awaitResult() }
                    }.getOrNull()
                    _actions.update {
                        it + (
                            extension.id to ExtensionActionUi(
                                extensionId = extension.id,
                                name = engineMeta[extension.id]?.name ?: registry.get(extension.id)?.name
                                    ?: extension.id,
                                title = action.title,
                                enabled = action.enabled ?: true,
                                badgeText = action.badgeText,
                                badgeBackground = action.badgeBackgroundColor,
                                badgeTextColor = action.badgeTextColor,
                                icon = bitmap,
                            )
                            )
                    }
                }
            }

            override fun onTogglePopup(
                extension: WebExtension,
                action: WebExtension.Action,
            ): GeckoResult<org.mozilla.geckoview.GeckoSession>? {
                if (_popup.value?.extensionId == extension.id) {
                    dismissPopup()
                    return null
                }
                return openPopupSession(extension, action)
            }

            override fun onOpenPopup(
                extension: WebExtension,
                action: WebExtension.Action,
            ): GeckoResult<org.mozilla.geckoview.GeckoSession>? = openPopupSession(extension, action)
        }
    }

    private fun openPopupSession(
        extension: WebExtension,
        action: WebExtension.Action,
    ): GeckoResult<org.mozilla.geckoview.GeckoSession> {
        val session = engine.newExtensionPopupSession(extension.id)
        _popup.value = ExtensionPopupUi(
            extensionId = extension.id,
            name = engineMeta[extension.id]?.name ?: registry.get(extension.id)?.name ?: extension.id,
            session = session,
        )
        return GeckoResult.fromValue(session)
    }

    /** Toque do usuário no ícone da extensão na barra de ferramentas. */
    fun tapAction(geckoId: String) {
        val action = latestAction[geckoId]
        if (action != null) {
            runCatching { action.click() }
                .onFailure { MobiLog.w(SCOPE, "action.click() falhou", it) }
            return
        }
        // Sem browser action (extensão de página/ou só content script): abrir as opções.
        openOptionsPage(geckoId)
    }

    fun dismissPopup() {
        val current = _popup.value ?: return
        _popup.value = null
        runCatching { current.session.close() }
    }

    /* ------------------------------------------------------------------ *
     * Prompt de permissões (PromptDelegate)                              *
     * ------------------------------------------------------------------ */

    private fun requestUserConsent(
        extension: WebExtension,
        permissions: Array<out String>?,
        origins: Array<out String>?,
        dataCollectionPermissions: Array<out String>?,
    ): GeckoResult<WebExtension.PermissionPromptResponse> {
        val result = GeckoResult<WebExtension.PermissionPromptResponse>()
        _prompt.value = InstallPrompt(
            geckoId = extension.id,
            name = engineMeta[extension.id]?.name ?: registry.get(extension.id)?.name ?: extension.id,
            permissions = permissions?.toList().orEmpty(),
            origins = origins?.toList().orEmpty(),
            dataCollection = dataCollectionPermissions?.toList().orEmpty(),
            storeId = registry.get(extension.id)?.storeId,
        )
        // Guardamos o result para a UI resolver quando o usuário decidir.
        pendingResults[extension.id] = result
        return result
    }

    private val pendingResults = mutableMapOf<String, GeckoResult<WebExtension.PermissionPromptResponse>>()

    fun respondToPrompt(geckoId: String, grantPermissions: Boolean, allowPrivate: Boolean) {
        val result = pendingResults.remove(geckoId) ?: return
        result.complete(
            WebExtension.PermissionPromptResponse(grantPermissions, allowPrivate, false),
        )
        _prompt.value = null
    }

    private fun step(text: String) {
        _progress.value = InstallProgress.Working(text)
    }

    private fun fail(message: String, detail: String?, storeId: String?) {
        _progress.value = InstallProgress.Failure(message, detail, storeId)
    }

    companion object {
        private const val SCOPE = "extensions"
        private const val BRIDGE_DIR = "extensions/mobibridge"
        private const val BRIDGE_NATIVE_APP = "mobibridge-native"
        private const val MAX_HARVESTED_RULES = 25_000
        const val MODE_BRIDGE_NOTE =
            "Modo compatibilidade: content scripts, estilos e regras de bloqueio rodam pela " +
                "MobiBridge. APIs privilegiadas (webRequest bloqueante, debugger, tabCapture, " +
                "nativas de UI) não estão disponíveis porque não há runtime de extensão."
    }
}
