# MobiBrowser

Navegador Android (Kotlin + Jetpack Compose, Material Design 3) cujo diferencial é
**instalar e usar extensões do Google Chrome** — com conversão de manifest no próprio
aparelho — junto com os recursos que se esperam de um navegador moderno.

> **Isto é um build instável, de propósito.** Cada push no `main` gera um APK de
> pré-lançamento nos *Actions* deste repositório. Não há teste antes de instalar: você
> instala, usa e abre uma issue dizendo o que quebrou. É assim que este projeto evolui.

---

## Onde baixar o APK

1. Aba **Actions** → workflow **CI — APK instável** → último run concluído.
2. Em *Artifacts*, baixe `mobibrowser-apk-<sha>` (APK universal + por ABI) **ou** use a **GitHub Release `nightly`** marcada como
   pré-lançamento, criada pelo build noturno (com `SHA256SUMS.txt`).
3. No aparelho: instale o APK (`adb install mobibrowser-universal-debug.apk` ou aceite o
   aviso de fonte desconhecida). Assinatura: *debug* — veja [Assinatura](#assinatura).

Para o seu aparelho, o menor APK é o da ABI correta (`arm64-v8a` nos celulares de hoje;
`x86_64` em emuladores). O universal funciona em todos e é maior.

---

## Como extensões funcionam aqui

O motor é **GeckoView** (o mesmo motor do Firefox) — não é WebView nem fork do Chromium.
Isso dá um runtime real de WebExtensions: content scripts, CSS injetado, `storage`,
`declarativeNetRequest`, badges, popups.

Um pacote do Chrome Web Store não roda cru: o app baixa o `.crx`, remove o envelope do
Chrome, **converte o `manifest.json`** (MV2 → MV3, `browser_action` → `action`,
`host_permissions`, `service_worker` → página de eventos, `content_security_policy`,
`web_accessible_resources`), descarta o que é exclusivo do Chrome (`_metadata/`,
`update_url`, `key`, `minimum_chrome_version`, `debugger`…) e reinstala o `.xpi` resultante.

Existem três níveis, e eles são visíveis na tela de extensões:

| Nível | O que roda | Como |
|---|---|---|
| **Nativo** | Extensão completa no runtime do motor | Pacote assinado pela Mozilla (`.xpi` da AMO ou já convertido/assinado) instalado via `WebExtensionController.install()` |
| **Convertida** | Extensão do Chrome Web Store convertida no aparelho | Instalada com `installBuiltIn()` (recursos do app). O GeckoView ainda pode recusar por assinatura — o app detecta `ERROR_SIGNEDSTATE_REQUIRED` e cai para o nível 3 |
| **Modo compatibilidade (MobiBridge)** | Content scripts, CSS injetado, regras de bloqueio, popups simples | A extensão-ponte embutida (`assets/extensions/mobibridge`) lê o pacote convertido e injeta nas páginas; APIs de background do Chrome não existem |

**O que isso significa na prática:** bloqueadores de anúncio/conteúdo, gestores de estilo,
tradutores por content script e extensões "só CSS" costumam funcionar no nível 2 ou 3.
Extensões que dependem de `chrome.debugger`, `chrome.tabCapture`, `chrome.settingsOverride`,
`chrome_url_overrides` (Nova aba própria) ou de service worker com estado **não** funcionam
o suficiente — o app avisa quais chaves foram descartadas em vez de instalar e calar.

### Instalar

- **Pela loja:** toque em **Instalar** no banner que aparece quando você está numa página
  do Chrome Web Store. O ID é lido da URL, então não depende de scraping de API interna.
- **Por ID/URL:** Central de extensões → Instalar → colar
  `cjpalhdlnbpafiamejdnhcphjbkeiagm` ou `https://chromewebstore.google.com/detail/<slug>/<id>`.
- **Arquivo local (sideload):** mesmo diálogo → *Escolher arquivo* → `.crx`, `.zip` ou `.xpi`.
- **Manual (nível 1):** baixe um `.xpi` assinado da AMO e instale pelo mesmo caminho.

---

## Recursos da versão 1

**Navegação**
- Abas com miniaturas ao vivo, reordenar por seleção, fechar todas (normais ou privadas)
- Barra inferior com campo único (URL ou busca), autocomplete por título/histórico
- Progresso de carregamento, recarregar/parar, voltar/avançar, modo desktop
- Localizar na página com contador de ocorrências e navegação anterior/próxima
- Menu da página: compartilhar, favorito, QR code, scripts ativos, permissões por site
- Configurações por site: JavaScript, rastreamento (ETP), usuário (UA móvel/desktop), extensões

**Extensões**
- Lista com estado, modo (nativo/convertida/ponte), avisos e chaves descartadas
- Ligar/desligar, remover, atualizar, abrir página de opções da extensão
- Acesso por site: todos os sites · só em sites permitidos · bloqueados em sites específicos
- Permitir em navegação privada por extensão
- Popups de extensão em folha (com voltar/fechar) e ações da barra com badge e título
- Prompt nativo de revisão de permissões (`onInstallPromptRequest`) antes de instalar

**Privacidade e outros**
- Modo privado por aba (perfil `private` do motor, sem persistir histórico)
- User scripts e estilos próprios (editor embutido + importação de arquivo), no espírito dos
  "estilos do usuário" e dos gestores de script
- Histórico e favoritos com busca; regras `declarativeNetRequest` espelhadas no motor

**Decisões de UI**
- *Scroll-to-hide* das barras **não** existe: o `GeckoView` do release 155 não expõe
  `GeckoViewEventListener`/`setEventListener`, então a alternativa honesta é alternar as
  barras ao toque (um toque na barra de status, botão no canto da barra) — sem "pulo".
- Movimento expressivo (molas, overshoot) é opt-in (`Settings.expressiveMotion`).
- Composição do campo superior é intencionalmente *flat* para a URL caber e o foco ser óbvio.

---

## Estrutura

```
app/src/main/kotlin/app/mobibrowser/
├── MainActivity.kt              única Activity; encaminha VIEW/SEND para a VM
├── MobiApplication.kt           ordem de inicialização (registro → motor → extensões)
├── core/
│   ├── GeckoResultExt.kt        await/awaitResult: GeckoResult ↔ corrotina
│   ├── MobiLog.kt               tag única, ligada por BuildConfig
│   ├── engine/
│   │   ├── GeckoEngine.kt       GeckoRuntime, profile privado, delegados globais
│   │   ├── BrowserTab.kt        1 aba = 1 sessão + delegados + estado Compose
│   │   ├── TabController.kt     lista de abas, seleção, persistência na rotação
│   │   └── TabUiState.kt        estado imutável da aba + estado de action/popup da UI
│   └── ext/
│       ├── CrxPackage.kt        envelope CRX2/CRX3, ZIP seguro, empacotamento .xpi
│       ├── ManifestConverter.kt Chrome → WebExtensions + relatório do que caiu
│       ├── MatchPattern.kt      interpretador de match patterns e do <all_urls>
│       ├── PermissionCatalog.kt permissões → explicação em português + risco
│       ├── ChromeWebStore.kt    ID da URL, download pelo endpoint de update, resumo
│       ├── ExtensionRegistry.kt espelho em JSON do que está instalado e como
│       ├── BridgeScripts.kt     MOBIBRIDGE + content script + user scripts + dnr
│       └── ExtensionManager.kt  instalação, ativação, actions, popups, prompt
├── data/
│   ├── AppPrefs.kt              DataStore: tema, busca, primeira execução, abas
│   └── BrowserDb.kt             SQLite: histórico, favoritos, user scripts
└── ui/
    ├── MobiApp.kt               roteador de telas + sheets globais
    ├── MobiViewModel.kt         fachada única que as telas falam
    ├── browser/  tabs/  extensions/  userscripts/  settings/  library/  onboarding/
    ├── common/                  componentes (folha, linhas, badges, ícone)
    └── theme/                   paleta, tipografia, movimento
app/src/main/assets/extensions/mobibridge/   extensão-ponte (background.js/content.js/shim.js)
app/src/test/kotlin/…                        lógica pura: conversor, CRX, match patterns
```

Regras que valem para a camada `ui/`: ela nunca fala com GeckoView nem com I/O. Toda a
leitura de estado passa por `StateFlow`/`SharedFlow` na `MobiViewModel`, então as telas são
composables puros e o custo de trocar de motor fica confinado em `core/engine/`.

---

## Desenvolvimento

O repositório é compilado **pelo GitHub Actions** (JDK 17 + AGP 8.6.3 + Android SDK 35);
a mesma sequência roda local:

```bash
./gradlew test                                   # lógica pura (conversor, CRX, patterns)
./gradlew assembleUnstable                       # APK universal + por ABI (o build do CI)
./gradlew :app:testUnstableUnitTest              # só os testes, como o CI roda
./gradlew apks                                   # lista os APKs gerados
```

Saída em `app/build/outputs/apk/unstable/`: `app-universal-unstable.apk`,
`app-arm64-v8a-unstable.apk`, `app-armeabi-v7a-unstable.apk`, `app-x86_64-unstable.apk`
(o artefato do Actions e o release `nightly` já carregam o SHA no nome).

O workflow roda os testes antes de `assembleUnstable`: se a conversão de manifest quebra, o
artefato nem é publicado. O `nightly.yml` abre/repõe um *GitHub Release* marcado
`prerelease` + `instável` com os APKs anexados.

Requisitos de versão: `minSdk 26`, `targetSdk 35`, `compileSdk 35`,
GeckoView fixado em `155.0.20260903215306` (canal *release*, assinado pela Mozilla).
Mudar o `GECKOVIEW_VERSION` em `gradle/libs.versions.toml` muda o runtime de extensões
inteiro — por isso está travado e não em `[0, +)`.

### Assinatura

O CI assina com a **chave de debug** do Android SDK (`~/.android/debug.keystore`, criada
automaticamente): serve para instalar em modo desenvolvedor, **não** para publicar na Play Store e **não**
permite two-wire upgrades entre builds de chaves diferentes (desinstale antes de trocar a
chave). Para um build distribuível, crie `keystore.properties` (fora do VCS):

```properties
storeFile=../keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

---

## Limitações conhecidas

- Nível 1 (nativo, completo) só é garantido para pacote assinado pela Mozilla. Sem isso, a
  extensão rejeitada cai para o modo compatibilidade — aceitável ou não é decisão do usuário,
  e o cartão da extensão diz em qual nível ela está.
- `GeckoRuntime.setRuntimeDelayedInitializationEnabled` não existe na API pública do release
  155: o runtime é criado no `Application`, o que é o comportamento padrão do GeckoView.
- Persistir abas na rotação usa o estado do `TabController` (o motor continua vivo no
  `Application`); restaurar sessão com *restoring* nativo do GeckoView (`SSAS`) depende de
  `GeckoViewSupport`, que não entra no build de release comum.
- Download de `.crx` usa o endpoint público de update do Chrome; se o Google mudar isso, o
  sideload por arquivo continua funcionando.

## Feedback

Use o template **Relato de uso do APK** em *Issues*: ele pergunta o SHA do build
(tela *Configurações → Sobre*), a extensão testada e o nível em que ela rodou. Sem o SHA
não dá para correlacionar seu relato com uma compilação.
