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
| `OneActivity.java` | telas (abas, prateleiras, mini player, player cheio, busca), foco/toque |
| `MediaClient.java` | `MediaBrowser` no `MediaPlaybackService`, `__AUTO_ROOT__`, transporte, custom actions |
| `ArtworkLoader.java` | capas por `HttpURLConnection`/`ContentResolver` + `LruCache` + `Executor` |
| `CatalogSearch.java` | busca na `amp-api` com o developer token do engine (AES/GCM do AndroidKeyStore) |

O serviço é `com.apple.android.music/.player.MediaPlaybackService` (o shorthand
`/.MediaPlaybackService` não existe; foi verificado no probe da fase A e no `core/`).

Tema: a activity é injetada com `android:theme="@android:style/Theme.Material.NoActionBar"`
(`0x0103022e`, mesmo id de API 29 a 36) e o `onCreate` reforça com
`setTheme(android.R.style.Theme_Material_NoActionBar)`. Ícone: herdado do engine
(a activity não declara `android:icon`). A UI trata os insets do sistema (edge-to-edge do
targetSdk 36).

## Build

```powershell
./single-apk/build-one-apk.ps1
```

Compila a UI uma vez e gera, para cada ABI:

| ABI | Engine de entrada | Saída |
|---|---|---|
| arm64 | `.pipeline/ci162/AppleMusic-engine-arm64.apk` | `single-apk/out-one/AppleMusic-one-arm64.apk` |
| arm32 | `.pipeline/rel160/AppleMusic-engine-arm32.apk` | `single-apk/out-one/AppleMusic-one-arm32.apk` |
| x86 | `.pipeline/emulator/AppleMusic-engine-x86.apk` | `single-apk/out-one/AppleMusic-one-x86.apk` |

Passos por ABI: extrai o `AndroidManifest.xml` binário → injeta a activity (MAIN/LAUNCHER
+ tema) → empacota o dex novo como `classesN.dex` → `zipalign -p 4` → `apksigner` com
`tools/tv-app-signing.p12` (alias `morphe`, senha `Morphe`) → verifica assinatura,
`launchable-activity` e audita o dex com `dexdump`.

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

O engine continua logado; a tela de login dele abre pelo botão **Conta**
(`com.apple.android.music/.common.MainActivity`).

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
- **Conta** e **Atualizações** (mostra a versão atual; updater real no P1).
- **Foco D-pad**: tudo `focusable`, anel de foco, DOWN da aba entra no conteúdo, ENTER
  aciona; toque funciona igual (celular/carro).

## Validado

| Device | Resultado |
|---|---|
| `RXCX100BZ5J` (arm64, API 36, toque) | instala por cima do engine logado; browse, play, mini player, player cheio, seek, Ações (Favorito enviado), Criar Estação por item, busca (HTTP 200, 18 resultados, "Get Lucky" tocando), Conta (abre `common.MainActivity` e volta), Atualizações. Evidências `evidence/one-phone-0*.png` e `one-phone-1*.png`. |
| `emulator-5554` (TV x86, API 34, D-pad) | browse (Home/Radio/Library e prateleiras), D-pad (direita/baixo/ENTER) tocando, mini player, player cheio (botão Tocando), busca (HTTP 200, 18 resultados, id cru `617154366` tocando). Playback tocou de verdade no x86 (`state=PLAYING`), não ficou em BUFFERING. Evidências `evidence/one-tv-0*.png`. |
| Carro `100.74.124.38:5555` | não estava online; build arm32 gerado e não instalado. |

## Limitações / P1

- **Biblioteca/Rádio** aparecem pelas abas do engine, mas sem telas próprias (paridade
  com `car-app`: Recentes, Playlists, Artistas, Álbuns; estações).
- **Mini player** é por activity (não é overlay persistente entre telas).
- **Updater real** (`updates.json`, download do `AppleMusic-one-<abi>.apk`,
  `PackageInstaller`) não entrou; o botão só mostra a versão.
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
- **Assinatura**: os APKs saem com o cert Morphe; sem ele o `install -r` falha e a conta
  seria perdida ao desinstalar.
