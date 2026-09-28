#!/bin/sh
set -eu

# Generate app ao launcher assets with Android-safe sizing.
# Legacy icons use a 76% artwork scale on a white field.
# Adaptive foreground uses a transparent logo inside Android's 66dp safe zone
# of the 108dp adaptive-icon canvas.

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/source.png" >&2
    exit 1
fi
SRC="$1"
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
RES="$ROOT/app/src/main/res"
[ -f "$SRC" ] || { echo "ERROR: source image not found: $SRC" >&2; exit 1; }

python3 - "$SRC" "$RES" <<'PYTHON'
import sys
from pathlib import Path
from PIL import Image
import numpy as np

src=Path(sys.argv[1]); res=Path(sys.argv[2])
img=Image.open(src).convert('RGB')
side=min(img.size)
img=img.crop(((img.width-side)//2,(img.height-side)//2,(img.width+side)//2,(img.height+side)//2)).resize((512,512),Image.Resampling.LANCZOS)
a=np.array(img).astype(np.int16)
d=np.sqrt(((255-a)**2).sum(axis=2))
alpha=np.clip((d-3)/(24-3)*255,0,255).astype(np.uint8)
logo=Image.fromarray(np.dstack([a.astype(np.uint8),alpha]),'RGBA')
logo=logo.crop(logo.getchannel('A').getbbox())

def save(im,p):
    Path(p).parent.mkdir(parents=True,exist_ok=True)
    im.save(p,'PNG',optimize=True,compress_level=9)

for den,size in [('ldpi',36),('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    c=Image.new('RGB',(size,size),'white')
    art=logo.resize((round(size*.76),round(size*.76)),Image.Resampling.LANCZOS)
    c.paste(art,((size-art.width)//2,(size-art.height)//2),art.getchannel('A'))
    save(c,res/f'mipmap-{den}/ic_launcher.png')
    save(c,res/f'mipmap-{den}/ic_launcher_round.png')

fg=Image.new('RGBA',(108,108),(0,0,0,0))
art=logo.resize((60,60),Image.Resampling.LANCZOS)
fg.alpha_composite(art,(24,24))
save(fg,res/'drawable-nodpi/ic_launcher_foreground.png')
Path(res/'drawable-nodpi/ic_launcher_background.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n    <solid android:color="#FFFFFF" />\n</shape>\n')
adaptive='<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n'
for n in ('ic_launcher.xml','ic_launcher_round.xml'):
    Path(res/f'mipmap-anydpi-v26/{n}').write_text(adaptive)

s=Image.new('RGBA',(192,192),(0,0,0,0))
art=logo.resize((92,92),Image.Resampling.LANCZOS)
s.alpha_composite(art,(50,50))
save(s,res/'drawable/splash_icon.png')
PYTHON
