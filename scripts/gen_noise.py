#!/usr/bin/env python3
"""Generate a tileable frosted-glass noise tile for NekoChatMini glass drawables.

96x96 transparent PNG with very-low-alpha white salt speckle. Tiled (tileMode=repeat)
under the translucent glass layers to give a subtle frosted grain instead of flat
plastic transparency. Re-run to regenerate:  python3 scripts/gen_noise.py
"""
import random
from PIL import Image

SIZE = 96
MAX_ALPHA = 8  # keep grain barely-there; bump if too subtle on device

random.seed(42)  # deterministic tile
img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
px = img.load()
for y in range(SIZE):
    for x in range(SIZE):
        a = random.randint(0, MAX_ALPHA)
        px[x, y] = (255, 255, 255, a)

out = "app/src/main/res/drawable-nodpi/noise_tile.png"
img.save(out, optimize=True)
print("wrote", out)
