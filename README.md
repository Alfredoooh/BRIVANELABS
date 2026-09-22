# Brivane Notes Android

Projeto Android Kotlin + WebView para Brivane Notes.

## Build local

```bash
chmod +x gradlew build_brivane_notes.sh
./gradlew assembleDebug --no-daemon
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`

## Release assinado

O ZIP privado inclui `keystore/brivane-release.jks` e `keystore/key.properties`.

```bash
./gradlew assembleRelease --no-daemon
```

O APK assinado fica em `app/build/outputs/apk/release/app-release.apk`.

**Importante:** mantenha o `.jks`, `key.properties` e `CREDENTIALS.txt` privados. Para Codemagic, carregue o `.jks` em Code signing identities em vez de colocá-lo no Git.

## Codemagic

O `codemagic.yaml` usa `android_signing` e as variáveis `CM_KEYSTORE_PATH`, `CM_KEYSTORE_PASSWORD`, `CM_KEY_ALIAS` e `CM_KEY_PASSWORD`, que são injetadas pelo Codemagic quando a identidade de assinatura é configurada.
