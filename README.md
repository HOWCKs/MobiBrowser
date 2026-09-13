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

### Canal do motor (importante)

O app roda sobre o **GeckoView do canal nightly** (`geckoviewChannel = "nightly"` em
`gradle/libs.versions.toml`, artifact `org.mozilla.geckoview:geckoview-nightly-omni`). Motivo: no canal de release o motor **recusa add-on sem
assinatura da Mozilla**, e um pacote convertido da Chrome Web Store nunca tem essa
assinatura — sem isso, o diferencial do app não existiria. No nightly o MobiBrowser escreve
um YAML de configuração do GeckoView (`GeckoEngine.debugConfigPath`) com
`xpinstall.signatures.required=false`, e o pacote convertido instala no runtime de verdade.

O preço é o esperado: o motor muda a cada dia (a versão é dinâmica, `158.+`), pode ter
regressão de estabilidade e **não** serve para navegação sensível. Para voltar ao motor
estável, troque `geckoviewChannel` para `release` — o app continua funcionando, só que as
extensões não assinadas passam a rodar pelo modo de compatibilidade (nível 3).

Um pacote do Chrome Web Store não roda cru: o app baixa o `.crx`, remove o envelope do
Chrome, **converte o `manifest.json`** (MV2 → MV3, `browser_action` → `action`,
`host_permissions`, `service_worker` → página de eventos, `content_security_policy`,
`web_accessible_resources`), descarta o que é exclusivo do Chrome (`_metadata/`,
`update_url`, `key`, `minimum_chrome_version`, `debugger`…) e reinstala o `.xpi` resultante.

Existem três níveis, e eles são visíveis na tela de extensões:

| Nível | O que roda | Como |
|---|---|---|
| **Nativo** | Extensão completa no runtime do motor | Pacote assinado pela Mozilla (`.xpi` da AMO ou já convertido/assinado) instalado via `WebExtensionController.install()` |
| **Convertida** | Extensão do Chrome Web Store, convertida e instalada no runtime | `WebExtensionController.install("file://…xpi")` com a verificação de assinatura desligada pelo canal nightly; se o motor recusar, o app detecta `ERROR_SIGNEDSTATE_REQUIRED` e cai para o nível 3 |
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

O repositório é compilado **pelo GitHub Actions** (JDK 17 + AGP 9.3.2 + Gradle 9.7.1 +
Kotlin 2.4.20 + Compose BOM 2026.09.00); a mesma sequência roda local. `compileSdk` é 37 e
`targetSdk` continua 35: o Material 3 atual exige compilar contra 37, e manter o alvo em 35
deixa o comportamento de barras/gestos exatamente como o app foi desenhado.

```bash
./gradlew test                                   # lógica pura (conversor, CRX, patterns)
./gradlew assembleUnstable                       # APK universal + por ABI (o build do CI)
./gradlew :app:testDebugUnitTest               # só os testes, como o CI roda
# (AGP 9 só cria tarefas de teste unitário para o tested build type — por isso Debug)
./gradlew apks                                   # lista os APKs gerados
```

Saída em `app/build/outputs/apk/unstable/`: `app-universal-unstable.apk`,
`app-arm64-v8a-unstable.apk`, `app-armeabi-v7a-unstable.apk`, `app-x86_64-unstable.apk`
(o artefato do Actions e o release `nightly` já carregam o SHA no nome).

O workflow roda os testes antes de `assembleUnstable`: se a conversão de manifest quebra, o
artefato nem é publicado. O `nightly.yml` abre/repõe um *GitHub Release* marcado
`prerelease` + `instável` com os APKs anexados.

Requisitos de versão: `minSdk 26` (se o `checkAarMetadata` reclamar do nightly, o número
sobe para o mínimo do motor — é decisão de produto, não de gosto), `targetSdk 35`,
`compileSdk 37`, `geckoviewChannel = "nightly"` com versão `158.+`.
Mudar o canal/versão em `gradle/libs.versions.toml` muda o runtime de extensões inteiro:
por isso o release fica pinado (`155.0.20260903215306`) e só o nightly é dinâmico.

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

- O suporte a extensão não assinada depende do canal nightly do motor. Num build com
  `geckoviewChannel = "release"`, a extensão rejeitada cai para o modo compatibilidade — e o
  cartão dela diz em qual nível está.
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
