#!/bin/sh
# ScoreZone self-bootstrapping Gradle launcher.
# It uses the pinned distribution declared in gradle/wrapper/gradle-wrapper.properties.
# This keeps ./gradlew usable even when the official wrapper JAR is not preinstalled.

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
CACHE_DIR="$APP_HOME/.gradle-local"

if [ ! -f "$PROPERTIES" ]; then
    echo "ERROR: Missing $PROPERTIES" >&2
    exit 1
fi

DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES" | head -n 1)
DIST_URL=$(printf '%s' "$DIST_URL" | sed 's/\\:/\:/g')
DIST_FILE=$(basename "$DIST_URL")
DIST_VERSION=$(printf '%s' "$DIST_FILE" | sed -E 's/^gradle-([0-9.]+)-bin\.zip$/\1/')
DIST_DIR="$CACHE_DIR/gradle-$DIST_VERSION"
ZIP_FILE="$CACHE_DIR/$DIST_FILE"

if [ ! -x "$DIST_DIR/bin/gradle" ]; then
    mkdir -p "$CACHE_DIR"
    if [ ! -f "$ZIP_FILE" ]; then
        echo "Downloading pinned Gradle distribution: $DIST_URL"
        if command -v curl >/dev/null 2>&1; then
            curl -L --fail --retry 3 --connect-timeout 15 "$DIST_URL" -o "$ZIP_FILE"
        elif command -v wget >/dev/null 2>&1; then
            wget -O "$ZIP_FILE" "$DIST_URL"
        else
            echo "ERROR: curl or wget is required to bootstrap Gradle." >&2
            exit 1
        fi
    fi

    rm -rf "$DIST_DIR.tmp"
    mkdir -p "$DIST_DIR.tmp"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q "$ZIP_FILE" -d "$DIST_DIR.tmp"
    else
        echo "ERROR: unzip is required to bootstrap Gradle." >&2
        exit 1
    fi
    mv "$DIST_DIR.tmp/gradle-$DIST_VERSION" "$DIST_DIR"
    rm -rf "$DIST_DIR.tmp"
fi

exec "$DIST_DIR/bin/gradle" "$@"
