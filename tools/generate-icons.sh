#!/bin/sh
set -eu

# Generate Looply launcher and Android 12+ splash assets from one source image.
#
# IMPORTANT:
# - Launcher icons KEEP the supplied image's original background and colors.
# - Only the splash icon gets its outer background removed.
# - The splash artwork is intentionally padded so the entire symbol remains
#   visible and appears smaller on Android 12+ system splash screens.

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/icon_512x512_under_10kb.webp" >&2
    exit 1
fi

SRC="$1"
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
RES="$ROOT/app/src/main/res"

if [ ! -f "$SRC" ]; then
    echo "ERROR: source image not found: $SRC" >&2
    exit 1
fi

mkdir -p \
    "$RES/mipmap-mdpi" \
    "$RES/mipmap-hdpi" \
    "$RES/mipmap-xhdpi" \
    "$RES/mipmap-xxhdpi" \
    "$RES/mipmap-xxxhdpi" \
    "$RES/drawable"

# Launcher icon: preserve the original supplied background exactly.
magick "$SRC" \
    -resize '48x48!' \
    -strip \
    -depth 8 \
    -define png:compression-level=9 \
    "$RES/mipmap-mdpi/ic_launcher.png"

magick "$SRC" \
    -resize '72x72!' \
    -strip \
    -depth 8 \
    -define png:compression-level=9 \
    "$RES/mipmap-hdpi/ic_launcher.png"

magick "$SRC" \
    -resize '96x96!' \
    -strip \
    -depth 8 \
    -define png:compression-level=9 \
    "$RES/mipmap-xhdpi/ic_launcher.png"

magick "$SRC" \
    -resize '144x144!' \
    -strip \
    -depth 8 \
    -define png:compression-level=9 \
    "$RES/mipmap-xxhdpi/ic_launcher.png"

magick "$SRC" \
    -resize '192x192!' \
    -strip \
    -depth 8 \
    -define png:compression-level=9 \
    "$RES/mipmap-xxxhdpi/ic_launcher.png"

TMP_CUTOUT="$(mktemp -t looply-cutout).png"
TMP_SPLASH="$(mktemp -t looply-splash).png"
trap 'rm -f "$TMP_CUTOUT" "$TMP_SPLASH"' EXIT

# Remove only the CONNECTED outer background. Interior dark areas of the symbol
# are deliberately preserved as part of the artwork.
magick "$SRC" \
    -alpha on \
    -fuzz 8% \
    -fill none \
    -draw 'color 0,0 floodfill' \
    -trim +repage \
    -depth 8 \
    -strip \
    "$TMP_CUTOUT"

# 192x192 transparent canvas with the visible artwork limited to about 96px.
# This padding makes Android's splash icon visibly smaller while keeping every
# part of the symbol inside the safe area.
magick -size 192x192 xc:none \
    "$TMP_CUTOUT" \
    -resize '96x96' \
    -gravity center \
    -composite \
    -depth 8 \
    -strip \
    -define png:color-type=6 \
    -define png:compression-level=9 \
    "$TMP_SPLASH"

# The command above may optimize away transparent canvas dimensions on some
# ImageMagick builds, so normalize it to a fixed 192x192 RGBA canvas.
python3 - "$TMP_SPLASH" "$RES/drawable/splash_icon.png" <<'PY'
import sys
from PIL import Image

source = Image.open(sys.argv[1]).convert("RGBA")
canvas = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
source.thumbnail((96, 96), Image.Resampling.LANCZOS)
canvas.alpha_composite(
    source,
    ((192 - source.width) // 2, (192 - source.height) // 2)
)
canvas.save(sys.argv[2], optimize=True)
PY

echo "Generated launcher icons with original background."
echo "Generated transparent, padded 192x192 splash icon."
