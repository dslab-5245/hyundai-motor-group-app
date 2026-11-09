"""Downloads the driving b-roll listed in broll.json into <public>/broll/<id>.mp4 (skips files already there).

    python -I broll.py <public dir> [--check]

Mixkit serves a clip under its Free License at assets.mixkit.co/videos/<id>/<id>-1080.mp4; a Restricted-License clip
answers that URL with a stub of a few bytes, which is rejected here, so nothing restricted can slip into the render.
"""
import json, os, subprocess, sys, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"


def probe_ok(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", path], capture_output=True, text=True)
    return r.returncode == 0 and "video" in r.stdout


def main():
    pub = os.path.abspath(sys.argv[1]); check = "--check" in sys.argv
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    bj = json.load(open(os.path.join(here, "broll.json"), encoding="utf-8"))
    out = os.path.join(pub, "broll"); os.makedirs(out, exist_ok=True)
    ok = 0; total = 0
    for c in bj["clips"]:
        dst = os.path.join(out, f"{c['id']}.mp4")
        if os.path.exists(dst) and os.path.getsize(dst) > 1_000_000 and probe_ok(dst):
            ok += 1; total += os.path.getsize(dst); continue
        if check: print(f"missing: {c['id']} {c['title']}"); continue
        url = c.get("url") or f"https://assets.mixkit.co/videos/{c['id']}/{c['id']}-1080.mp4"
        tmp = dst + ".part"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b: break
                    f.write(b)
            if os.path.getsize(tmp) < 1_000_000 or not probe_ok(tmp):
                os.remove(tmp); print(f"rejected (not a free-licence 1080p file): {c['id']} {c['title']}"); continue
            os.replace(tmp, dst); ok += 1; total += os.path.getsize(dst)
            print(f"downloaded {c['id']} {c['title']} ({os.path.getsize(dst) / 1e6:.1f} MB)")
        except Exception as e:   # noqa: BLE001
            if os.path.exists(tmp): os.remove(tmp)
            print(f"failed {c['id']}: {e}")
    print(f"b-roll: {ok}/{len(bj['clips'])} clips, {total / 1e6:.0f} MB")
    if ok == 0: sys.exit(2)


if __name__ == "__main__":
    main()
