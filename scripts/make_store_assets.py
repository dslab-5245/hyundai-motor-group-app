"""Store-listing assets for Pleos App Market.
    python scripts/make_store_assets.py                 -> store/icon-512.png (+ rounded preview)
    python scripts/make_store_assets.py --screens DIR   -> also store/screenshots/*.png at 2560x1380 from the
                                                           emulator captures in DIR (2560x1440 -> centre crop)
"""
from PIL import Image, ImageDraw
import glob
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "store")
os.makedirs(OUT, exist_ok=True)
S = 512
SS = 4  # supersampling for clean edges
W = S * SS
BG = (0x0B, 0x0F, 0x14, 255)
RING = (0xE8, 0xA8, 0x60, 255)
BAR = (0xF2, 0xF4, 0xF7, 255)

img = Image.new("RGBA", (W, W), BG)
d = ImageDraw.Draw(img)
# adaptive icon: 108dp viewport, the visible safe zone is the central 72dp -> scale the vector's 108 grid onto 512 px
# so that the icon reads the same as the launcher (ring radius 32 of 108 == 0.296 of the width).
k = W / 108.0
cx = cy = 54 * k
r_out, r_in = 32 * k, 25 * k
d.ellipse([cx - r_out, cy - r_out, cx + r_out, cy + r_out], fill=RING)
d.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=BG)
for x, y, w, h in [(40, 58, 5, 12), (51, 42, 6, 28), (63, 50, 5, 20)]:
    d.rectangle([x * k, y * k, (x + w) * k, (y + h) * k], fill=BAR)
icon = img.resize((S, S), Image.LANCZOS)
icon.save(os.path.join(OUT, "icon-512.png"), optimize=True)

mask = Image.new("L", (W, W), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.18), fill=255)
rounded = Image.new("RGBA", (W, W), (0, 0, 0, 0))
rounded.paste(img, (0, 0), mask)
rounded.resize((S, S), Image.LANCZOS).save(os.path.join(OUT, "icon-512-rounded.png"), optimize=True)
print("wrote", OUT)


def convert_screens(src_dir: str) -> None:
    """Emulator captures (any size) -> 2560x1380 store screenshots: scale to width 2560, centre-crop the height."""
    out_dir = os.path.join(OUT, "screenshots")
    os.makedirs(out_dir, exist_ok=True)
    files = sorted(glob.glob(os.path.join(src_dir, "*.png")))
    for f in files:
        im = Image.open(f).convert("RGB")
        w, h = im.size
        scale = 2560 / w
        im = im.resize((2560, max(1, round(h * scale))), Image.LANCZOS)
        h2 = im.size[1]
        if h2 >= 1380:
            top = (h2 - 1380) // 2
            im = im.crop((0, top, 2560, top + 1380))
        else:  # letterbox is forbidden on the car screen but acceptable in a store listing; pad with the night bg
            canvas = Image.new("RGB", (2560, 1380), BG[:3])
            canvas.paste(im, (0, (1380 - h2) // 2))
            im = canvas
        name = os.path.splitext(os.path.basename(f))[0] + "_2560x1380.png"
        im.save(os.path.join(out_dir, name), optimize=True)
        print("wrote", name)
    if not files:
        print("no PNG files in", src_dir)


if "--screens" in sys.argv:
    convert_screens(sys.argv[sys.argv.index("--screens") + 1])
