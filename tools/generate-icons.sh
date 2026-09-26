#!/bin/sh
set -eu

# Usage:
#   ./tools/generate-icons.sh /path/to/looply-source.webp
#
# Requires ImageMagick (`magick`). The source image can have the original
# dark background. The command removes that background using a controlled
# fuzz range and keeps the Looply mark with transparency.

SRC="${1:-}"
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
RES="$ROOT/app/src/main/res"
TMP="$ROOT/.looply-icon-master.png"

if [ -z "$SRC" ] || [ ! -f "$SRC" ]; then
    echo "Usage: $0 /path/to/source-image.webp" >&2
    exit 1
fi

if ! command -v magick >/dev/null 2>&1; then
    echo "ERROR: ImageMagick (`magick`) is required." >&2
    exit 1
fi

mkdir -p \
  "$RES/drawable" \
  "$RES/mipmap-mdpi" \
  "$RES/mipmap-hdpi" \
  "$RES/mipmap-xhdpi" \
  "$RES/mipmap-xxhdpi" \
  "$RES/mipmap-xxxhdpi"

# Remove the near-black source background. `-fuzz` handles small color
# variations introduced by compressed source images.
magick "$SRC" \
  -alpha on \
  -fuzz 12% \
  -transparent '#0E0F14' \
  -resize 512x512! \
  -strip \
  "$TMP"

magick "$TMP" -resize 48x48! \
  -strip -define png:compression-level=9 \
  "$RES/mipmap-mdpi/ic_launcher.png"

magick "$TMP" -resize 72x72! \
  -strip -define png:compression-level=9 \
  "$RES/mipmap-hdpi/ic_launcher.png"

magick "$TMP" -resize 96x96! \
  -strip -define png:compression-level=9 \
  "$RES/mipmap-xhdpi/ic_launcher.png"

magick "$TMP" -resize 144x144! \
  -strip -define png:compression-level=9 \
  "$RES/mipmap-xxhdpi/ic_launcher.png"

magick "$TMP" -resize 192x192! \
  -strip -define png:compression-level=9 \
  "$RES/mipmap-xxxhdpi/ic_launcher.png"

# Android 12+ system splash icon.
magick "$TMP" -resize 192x192! \
  -strip -define png:compression-level=9 \
  "$RES/drawable/splash_icon.png"

rm -f "$TMP"

echo "Looply icons generated successfully."
