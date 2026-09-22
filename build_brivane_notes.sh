#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

if [ ! -f app/src/main/java/com/brivanelabs/notes/MainActivity.kt ]; then
  echo "ERRO: MainActivity.kt não encontrado."
  exit 1
fi
if [ ! -f app/src/main/assets/index.html ]; then
  echo "ERRO: app/src/main/assets/index.html não encontrado."
  exit 1
fi

termux-setup-storage >/dev/null 2>&1 || true

echo "== A compilar DEBUG =="
./gradlew assembleDebug --no-daemon

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="$HOME/storage/downloads/BrivaneNotes.apk"
mkdir -p "$(dirname "$APK_DEST")"
cp "$APK_SRC" "$APK_DEST"
echo "✔ DEBUG: $APK_DEST"

echo "== A compilar RELEASE assinado =="
./gradlew assembleRelease --no-daemon
RELEASE_SRC="app/build/outputs/apk/release/app-release.apk"
RELEASE_DEST="$HOME/storage/downloads/BrivaneNotes-release.apk"
cp "$RELEASE_SRC" "$RELEASE_DEST"
echo "✔ RELEASE: $RELEASE_DEST"
