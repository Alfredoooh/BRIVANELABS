#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.9
DIST_DIR="$APP_HOME/.gradle-bootstrap/gradle-$GRADLE_VERSION"
GRADLE_BIN="$DIST_DIR/bin/gradle"
ZIP="$APP_HOME/.gradle-bootstrap/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$APP_HOME/.gradle-bootstrap"
  if [ ! -f "$ZIP" ]; then
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    echo "Gradle $GRADLE_VERSION não encontrado. A descarregar..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 -o "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "$URL"
    else
      echo "ERRO: instale curl ou wget no Termux."
      exit 1
    fi
  fi
  echo "A preparar Gradle $GRADLE_VERSION..."
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP" -d "$APP_HOME/.gradle-bootstrap"
fi
exec "$GRADLE_BIN" "$@"
