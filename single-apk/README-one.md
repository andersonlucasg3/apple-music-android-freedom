# APK único — Apple Music One

Um APK por ABI com o **engine Apple Music 7.0.0 patcheado** e a nossa UI **embutida**:
sem companion, sem `sharedUserId` de terceiros, sem recursos próprios. A activity do
engine (`com.apple.android.music.common.MainActivity`, login) continua no APK; só deixa
de ser a `LAUNCHER`.

- UI: [`single-apk/single-ui`](single-ui) — package `com.anderson.applemusic.one`.
- Build: [`single-apk/build-one-apk.ps1`](build-one-apk.ps1).
- Empacotamento (dex extra + manifest binário): [`tools/axml_inject.py`](tools/axml_inject.py)
  e [`tools/pack_single_apk.py`](tools/pack_single_apk.py), herdados do spike da fase A.

## Por que funciona

O engine foi minerado pelo R8: qualquer classe/extensão de terceiro (AndroidX, Kotlin,
Compose, Coil) colide com a cópia que já existe no dex do engine. A UI aqui é **só Java
+ APIs do framework** (`android.app.Activity`, `MediaBrowser`/`MediaController`,
`HttpURLConnection`, `org.json`, `LruCache`, `GradientDrawable`, `SeekBar`), **sem `res/`**
e **sem `R.*`** — as únicas classes no dex gerado são `com.anderson.applemusic.one.*`
(normais + anônimas), então ele convive com o dex do engine na posição `classesN.dex`.

## Módulo da UI

Não há módulo Gradle: o build é `javac --release 8` + `d8 --min-api 29`, compilando contra
`android-36` (compileSdk 36), sem dependências e sem buildConfig. Isso é o mesmo caminho
reprodutível que a fase A usou.

| Arquivo | Papel |
|---|---|
| `OneActivity.java` | telas (abas, prateleiras, mini player, player cheio, busca), foco/toque, diálogo de atualização |
| `MediaClient.java` | `MediaBrowser` no `MediaPlaybackService`, `__AUTO_ROOT__`, transporte, custom actions |
| `ArtworkLoader.java` | capas por `HttpURLConnection`/`ContentResolver` + `LruCache` + `Executor` |
| `CatalogSearch.java` | busca na `amp-api` com o developer token do engine (AES/GCM do AndroidKeyStore) |
| `UpdateManager.java` | `updates.json` → comparação por ABI, download com progresso, SHA-256, `PackageInstaller` |
| `Version.java` | **gerado no build** (`-PoneVersion`/`-PoneVersionCode`): `NAME`/`CODE` da UI, sem `R.*`/BuildConfig |

O serviço é `com.apple.android.music/.player.MediaPlaybackService` (o shorthand
`/.MediaPlaybackService` não existe; foi verificado no probe da fase A e no `core/`).

Tema: a activity é injetada com `android:theme="@android:style/Theme.Material.NoActionBar"`
(`0x0103022e`, mesmo id de API 29 a 36) e o `onCreate` reforça com
`setTheme(android.R.style.Theme_Material_NoActionBar)`. Ícone: herdado do engine
(a activity não declara `android:icon`). A UI trata os insets do sistema (edge-to-edge do
targetSdk 36).

## Build

```powershell
./single-apk/build-one-apk.ps1 -PoneVersion 1.8.0           # versionCode 10800 derivado
./single-apk/build-one-apk.ps1 -Abi arm64,arm32 -PoneVersion 1.8.0
```

O build compila a UI uma vez e gera, para cada ABI:

| ABI | Engine de entrada | Saída |
|---|---|---|
| arm64 | `.pipeline/ci162/AppleMusic-engine-arm64.apk` | `single-apk/out-one/AppleMusic-one-arm64.apk` |
| arm32 | `.pipeline/rel160/AppleMusic-engine-arm32.apk` | `single-apk/out-one/AppleMusic-one-arm32.apk` |
| x86 | `.pipeline/emulator/AppleMusic-engine-x86.apk` | `single-apk/out-one/AppleMusic-one-x86.apk` |

Antes do `javac` o build gera `out-one/generated/.../Version.java` com `NAME = -PoneVersion` e
`CODE = -PoneVersionCode` (derivado como `MAJOR*10000+MINOR*100+PATCH` quando omitido); é essa
versão da UI — não o `7.0.0` do engine — que aparece no diálogo e que o updater compara.

Passos por ABI: extrai o `AndroidManifest.xml` binário → injeta a activity (MAIN/LAUNCHER + tema)
e `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>` → empacota o dex
novo como `classesN.dex` → `zipalign -p 4` → `apksigner` com `tools/tv-app-signing.p12`
(alias `morphe`, senha `Morphe`) → verifica assinatura, `launchable-activity` e audita o dex com
`dexdump`.

O CI (`.github/workflows/build.yml`) faz o mesmo em bash depois de montar/assinar os engines:
compila a UI com `javac`+`d8` dos build-tools, injeta nos `AppleMusic-engine-*.apk`, reassina com
o p12 dos secrets e publica `AppleMusic-one-arm32.apk`/`AppleMusic-one-arm64.apk` com
`one-arm32`/`one-arm64` no `updates.json` (`versionCode` = versão da UI).

Auditoria do dex (falha o build se sobrar classe de fora):

```
dexdump -l plain single-apk/out-one/dex/classes.dex | findstr "Class descriptor"
# 60 classes, todas Lcom/anderson/applemusic/one/...
```

## Instalar / abrir

Sempre por cima do engine já instalado (`install -r`), nunca desinstalando — o UID e a
conta são preservados:

```powershell
adb -s RXCX100BZ5J  install -r single-apk/out-one/AppleMusic-one-arm64.apk   # celular
adb -s emulator-5554 install -r single-apk/out-one/AppleMusic-one-x86.apk    # TV x86

adb -s <serial> shell am start -n com.apple.android.music/com.anderson.applemusic.one.OneActivity
```

O engine continua logado; o botão **Conta** abre o login do Apple ID (ver **Login**).

## Login (conta Apple)

O engine **não tem activity de autenticação**. O login é o diálogo Compose
`AuthenticationDialogAms` (`com.apple.mediaservices.amsfacade`), criado pelo
`DialogConnectorViewModel` da activity. O botão **Conta** faz:

1. inicia `com.apple.android.music.common.MainActivity` (a activity do engine que hospeda
   o `StoreApiDialogs` — ela herda de `StoreBaseActivity`);
2. por reflexão chama `S()` na activity: instala a `ComposeView` de `StoreApiDialogs` em
   tela cheia, cobrindo a home do engine;
3. chama `U().showAMSAuthWithType(AuthenticateRequest.Type.Interactive)`: o diálogo AMS
   aparece e é ele que dispara o login (`AuthenticationDialogAms` →
   `AmsAuthViewModel.startSignIn`).

Resultado: **Conta** abre direto a tela *"Continue com o e-mail ou número de telefone"* do
Apple ID. Se a reflexão falhar (classe ou método ausentes em outro build), o botão cai no
comportamento antigo: abre a `MainActivity` do engine.

### Deep link / extra: não existe para o login de conta

Testado no emulador (`am start -a VIEW -d ... -n com.apple.android.music/.common.MainActivity`):

- `apple-music://accountSummary` → home do engine (nenhum auth);
- `https://itunes.apple.com/accountSummary` → tela **Account** do engine (nenhum auth);
- `music://itunes.apple.com/sonosAuth` e os demais `apple-music://...` são roteados pelo
  `C3871e`/`deeplink` para destinos de conteúdo/commerce; não chamam
  `showAMSAuthWithType`;
- `musicsdk://applemusic/authenticate-v3?appPackage=<pkg>&devToken=<token>&appName=<nome>`
  (`SdkAuthActivity`) **abre a mesma tela do Apple ID** via
  `SdkLandingViewModel.signIn` → `X6.e.c(..., SDK)`, mas é o fluxo de autorização do
  **MusicKit SDK**: exige `devToken`/`appPackage`, mostra o consentimento do SDK, devolve
  um `music_user_token` para o app chamador, chama `moveTaskToBack` ao terminar e tem um
  guard de uso único (`C.f45619d`). Com parâmetros de teste ele fica preso no loader.
  Por isso **não** é usado como caminho principal; a reflexão é o caminho suportado.

O manifest do engine não declara nenhuma activity de auth própria
(`SdkAuthActivity`/`SonosAuthActivity` só repassam para a `MainActivity`;
`SocialWebActivity` é WebView de social), e o diálogo é Compose — não dá para instanciá-lo
numa `Activity` pura sem AndroidX/Compose no nosso dex.

### Fechar o sheet (X) ou concluir o login

O sheet AMS é um overlay Compose dentro da `MainActivity` do engine. Fechar pelo **X**
(`userClosedPageView` → `onDismiss` → `DialogConnectorViewModel.hideAMSAuthWithType`) ou
concluir o login (o `AmsAuthViewModel` emite o resultado e o mesmo `onDismiss` roda) só
esconde o sheet; a home do engine continuaria na frente. A UI agora observa
`getShouldShowAMSAuthWithType()` do `DialogConnectorViewModel` (por reflexão, a cada 250 ms)
e, quando ele volta a `null`, chama `finish()` na `MainActivity`. Assim **X**, **BACK** e
login concluído caem de volta nesta UI; o engine (processo, serviço e conta) continua vivo
como backend.

### Volta do engine: catálogo recarregado

Ao abrir a `MainActivity` a UI marca `refreshOnResume`; no `onResume` ela limpa abas,
prateleiras, drill-down e capas, reconecta o `MediaBrowser` (o serviço pode ter reiniciado
após o login) e reassina `__AUTO_ROOT__`. O `MediaClient.disconnect()` descarta cache e
callbacks, então nada velho é reentregue; se o catálogo vier vazio/erro (serviço ainda
subindo), a UI reconecta até 4× antes de desistir.

## O que está funcional (P0)

- **Browse**: `__AUTO_ROOT__` → abas Início/Rádio/Biblioteca; prateleiras com título e
  linha horizontal de cards com capa; Biblioteca lista as categorias; drill-down de um
  nível com "‹ Voltar".
- **Play**: toque/ENTER no card → `playFromMediaId`; play/pause, prev/next na barra e no
  player cheio; `SeekBar` com seek.
- **Mini player** sempre visível com sessão (capa/título/artista/play-pause) → player
  cheio com capa grande, tempos, controles, **Ações** (`getCustomActions()` →
  `sendCustomAction`) e **Criar Estação** quando a ação é anunciada. No D-pad, o botão
  **Tocando** (aparece na barra de topo quando há sessão) abre o player cheio.
- **Ações por item** (long-press no card): toca o item, espera `PLAYING` + a ação aparecer
  e só então envia (`CREATE_STATION` verificado).
- **Busca**: `EditText` → `amp-api` com o developer token decifrado do engine (AES/GCM,
  alias `music_token_store_key`), resultados com capa; tocar toca pelo **id cru**.
  Fallback: botão "Abrir a busca do Apple Music" (deep link para `common.MainActivity`).
- **Conta** abre o login do Apple ID direto (ver **Login**), não a home do engine; fechar
  pelo **X**, dar **BACK** ou concluir o login volta para esta UI com o catálogo recarregado.
- **Atualizações**: mostra `Version.NAME (CODE)` e o engine instalado; checa o `updates.json`
  do último release (automático ao abrir, no máximo 1x/24 h; o botão força), compara
  `one-<abi>.versionCode`, baixa o APK da ABI com progresso, confere o SHA-256 e instala pela
  `PackageInstaller` (diálogo do sistema). Estados: sem atualização, disponível, baixando (%),
  verificando, instalando, erro — com **Cancelar** no download.
- **Foco D-pad**: tudo `focusable`, anel de foco, DOWN da aba entra no conteúdo, ENTER
  aciona; toque funciona igual (celular/carro).

## Validado

| Device | Resultado |
|---|---|
| `RXCX100BZ5J` (arm64, API 36, toque) | instala por cima do engine logado; browse, play, mini player, player cheio, seek, Ações (Favorito enviado), Criar Estação por item, busca (HTTP 200, 18 resultados, "Get Lucky" tocando), Conta (abre `common.MainActivity` e volta), Atualizações. Evidências `evidence/one-phone-0*.png` e `one-phone-1*.png`. |
| `emulator-5554` (TV x86, API 34, D-pad) | browse (Home/Radio/Library e prateleiras), D-pad (direita/baixo/ENTER) tocando, mini player, player cheio (botão Tocando), busca (HTTP 200, 18 resultados, id cru `617154366` tocando). Playback tocou de verdade no x86 (`state=PLAYING`), não ficou em BUFFERING. Evidências `evidence/one-tv-0*.png`. |
| Carro `100.74.124.38:5555` | não estava online; build arm32 gerado e não instalado. |
| `RXCX100BZ5J` + `emulator-5554` (1.8.0/10800) | instalado por cima (versionCode 1614 igual, cert igual): segue logado e tocando (`state=PLAYING`). Botão **Atualizações** mostra `Instalada: 1.8.0 (10800)` e "Sem atualização" lendo o `updates.json` real da `apps-1.7.0`, que ainda não tem `one-*` (ausência tratada sem crashar). Evidências `.pipeline/one-update-phone-0*.png` e `.pipeline/one-update-tv-0*.png`. Download/instalação de um release com `one-*` ainda não exercitados (1.8.0 não publicado). |
| `RXCX100BZ5J` + `emulator-5554` (1.8.2/10802) | **Conta** abre o login do Apple ID direto — "Continue com o e-mail ou número de telefone", WebView AMS carregada, sem a home do engine; X + BACK volta para esta UI com o catálogo carregado; busca HTTP 200 (`daft`, 18 resultados) no emulador depois de voltar; `logcat -b crash` vazio nos dois. Evidências `evidence/one-login-tv-0*.png` e `evidence/one-login-phone-0*.png`. Login completo (senha/2FA) não exercitado: a senha não está no repo e as duas contas já estavam logadas. |
| `RXCX100BZ5J` + `emulator-5554` (1.8.3/10803) | **Conta** → login direto; **X** sozinho e **BACK** fecham a `MainActivity` e caem na UI com o catálogo recarregado (log `Apple ID sheet dismissed, finishing the engine host` + `back from the engine, rebuilding the browse session`); busca HTTP 200 nos dois (`daft`, 18 resultados); `logcat -b crash` vazio. Evidências `evidence/one-login-close-{tv,phone}-{before,sheet,after-x,after-back,search}.png`. Deep links testados: `apple-music://accountSummary` (home), `https://itunes.apple.com/accountSummary` (Account) e `musicsdk://applemusic/authenticate-v3` (abre o Apple ID, mas é o fluxo SDK; ver **Login**) — evidência `one-login-close-deeplink-sdk.png`. Login completo (senha/2FA) não exercitado; o sucesso usa o mesmo `onDismiss` do X (verificado no decompilado). |

## Limitações / P1

- **Biblioteca/Rádio** aparecem pelas abas do engine, mas sem telas próprias (paridade
  com `car-app`: Recentes, Playlists, Artistas, Álbuns; estações).
- **Mini player** é por activity (não é overlay persistente entre telas).
- **Updater**: o caminho de download + SHA-256 + `PackageInstaller` não foi exercitado ponta a
  ponta porque ainda não existe release com `one-*`; o caminho "sem atualização" (parsing do
  `updates.json` real) foi validado nos dois devices.
- **`one-x86`** não é publicado pelo CI (só arm32/arm64); o build local gera para o emulador.
- **Polimento visual** Apple (capas arredondadas, gradiente no player, tipografia) e
  estados de erro (engine ausente/não logado, busca offline) ficam para o P1.
- **Performance**: a Home monta todas as prateleiras de uma vez (~22 × 12 cards ≈ 260
  capas) e baixa todas as capas; falta carregar sob demanda (o emulador x86 aguentou, o
  box arm32 de 2 GB não foi medido).
- No emulador TV, a primeira assinatura de `__AUTO_ROOT__/home` veio vazia (0 filhos);
  ao trocar de aba e voltar (ou na segunda abertura) veio completa (~22 prateleiras) —
  possível corrida do engine, não da UI.
- Foco inicial no D-pad pode cair na barra de topo antes de o conteúdo chegar; as abas
  têm `OnKeyListener` para DOWN entrar no conteúdo.
- **Login**: o diálogo AMS é hospedado pela `MainActivity` do engine (única com o
  `StoreApiDialogs`); a UI o fecha (X/BACK/sucesso) ao ver
  `shouldShowAMSAuthWithType` voltar a `null`. O fluxo de senha/2FA do Apple ID não é
  exercitado automaticamente (a senha não está no repo); o caminho de sucesso foi
  verificado no decompilado (`AmsAuthViewModel` → `onDismiss` → mesmo
  `hideAMSAuthWithType` do X).
- **Assinatura**: os APKs saem com o cert Morphe; sem ele o `install -r` falha e a conta
  seria perdida ao desinstalar.
