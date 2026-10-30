"""Store-listing assets for Pleos App Market: 512x512 icon (same design as the adaptive launcher icon).
    python scripts/make_store_assets.py
Writes store/icon-512.png and store/icon-512-rounded.png (preview only; the store wants the square one).
"""
from PIL import Image, ImageDraw
import os

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
