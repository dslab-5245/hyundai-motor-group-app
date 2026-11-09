"""Copies what a run's render needs into the Remotion public dir and generates the grain texture.

    python -I stage.py <run folder> <public dir>

public/run/: story_clean.mp4, console_clean.mp4, remix_clean.mp4, ds_story.wav, ds_console.wav, ds_remix.wav (whatever exists)
public/gen/noise.png: 512x512 grey noise for the film-grain overlay
"""
import os, shutil, sys
import numpy as np
from PIL import Image


def main():
    run = os.path.abspath(sys.argv[1]); pub = os.path.abspath(sys.argv[2])
    rd = os.path.join(pub, "run"); os.makedirs(rd, exist_ok=True); os.makedirs(os.path.join(pub, "gen"), exist_ok=True)
    n = 0
    for name in ("story_clean.mp4", "console_clean.mp4", "remix_clean.mp4", "ds_story.wav", "ds_console.wav", "ds_remix.wav"):
        src = os.path.join(run, name); dst = os.path.join(rd, name)
        if os.path.exists(src):
            if not os.path.exists(dst) or os.path.getsize(dst) != os.path.getsize(src) or os.path.getmtime(dst) < os.path.getmtime(src):
                shutil.copy2(src, dst)
            n += 1
    rng = np.random.default_rng(7)
    g = rng.normal(128, 38, (512, 512)).clip(0, 255).astype(np.uint8)
    Image.fromarray(g, "L").save(os.path.join(pub, "gen", "noise.png"))
    print(f"staged {n} media files from {run}")


if __name__ == "__main__":
    main()
