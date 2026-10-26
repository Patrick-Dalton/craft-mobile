# Craft Mobile

Os apps do [ArtCraft / Craft App Suite](https://github.com/storytold) (código aberto, Apache-2.0) rodando **nativos** no Android, mais o **Craft Hub**: um app onde você escolhe quais deles baixar, atualizar ou remover.

Página de download: https://patrick-dalton.github.io/craft-mobile/

## Como funciona

- **Apps:** cada app Craft é Rust + egui. O app Android carrega a mesma interface (`*-ui-egui`) numa `GameActivity`, sem WebView. O código original não é alterado: o workflow baixa a versão fixada em `apps/<app>/upstream.env` e acrescenta a camada Android.
- **`shared/java/CraftNativeActivity.java`:** o que o Windows/macOS/Linux dão aos apps e o Android não dá do mesmo jeito: seletores de arquivo (Storage Access Framework), "Abrir com" e "Compartilhar", área de transferência.
- **`shared/shims/rfd` e `shared/shims/arboard`:** substitutos Android das bibliotecas de diálogos de arquivo e de área de transferência, colocados só na compilação Android (`[patch.crates-io]`).
- **Hub (`hub/`):** Kotlin + Jetpack Compose. Lê `catalog.json`, mostra a versão publicada de cada app (`<app>-latest` nos Releases) e instala o APK.

## Estado

| App | Android |
| --- | --- |
| PdfCraft | em andamento |
| Os outros 12 | em breve |

## Assinatura dos APKs

Sem os segredos `CRAFT_KEYSTORE_B64`, `CRAFT_KEYSTORE_PASSWORD`, `CRAFT_KEY_ALIAS` e `CRAFT_KEY_PASSWORD`, cada build é assinado com uma chave descartável: instala, mas para atualizar é preciso desinstalar a versão anterior. Com a chave nos segredos do repositório, as atualizações instalam por cima.

## Licença

Apache-2.0 ou MIT, como os apps originais.
