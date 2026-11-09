"""Post-processing for a rendered composition.

    python -I post.py finalize <rendered.mp4> <final.mp4>        loudness to -16 LUFS / -1.5 dBTP (two-pass loudnorm), video copied
    python -I post.py measure <file.mp4>                         prints integrated LUFS / true peak (JSON)
    python -I post.py sheet <final.mp4> <contact_sheet.png> [n]  n evenly spaced frames (default 12) in a 4-column sheet
    python -I post.py events <final.mp4> <plan.json> <comp> <out.png>   frames at every call-out of that composition, for review
"""
import json, math, os, re, subprocess, sys


def run(args, capture=True):
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(r.stderr[-3000:]); raise SystemExit(f"failed: {' '.join(args[:4])} ...")
    return r


def duration(path):
    return float(run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path]).stdout.strip() or 0)


def measure(path):
    r = subprocess.run(["ffmpeg", "-nostats", "-i", path, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    m = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", r.stderr, re.S)
    return json.loads(m.group(0)) if m else {}


def finalize(src, dst):
    m = measure(src)
    if not m: raise SystemExit("loudnorm measurement failed")
    af = (f"loudnorm=I=-16:TP=-1.5:LRA=11:measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}"
          f":measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true:print_format=summary")
    run(["ffmpeg", "-y", "-v", "error", "-i", src, "-map", "0:v:0", "-map", "0:a:0", "-c:v", "copy", "-af", af,
         "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart", dst])
    out = measure(dst)
    print(f"{os.path.basename(dst)}: {float(out.get('input_i', 0)):.1f} LUFS, true peak {float(out.get('input_tp', 0)):.1f} dBTP "
          f"(was {float(m['input_i']):.1f} LUFS / {float(m['input_tp']):.1f} dBTP), {duration(dst):.1f} s, {os.path.getsize(dst) / 1e6:.1f} MB")


def sheet(src, dst, n=12):
    d = duration(src); cols = 4; rows = math.ceil(n / cols)
    times = [d * (k + 0.5) / n for k in range(n)]
    sel = "+".join(f"between(t\\,{t:.3f}\\,{t + 0.04:.3f})" for t in times)
    run(["ffmpeg", "-y", "-v", "error", "-i", src, "-vf", f"select='{sel}',scale=480:-2,tile={cols}x{rows}:padding=6:margin=6:color=0x0B0B0D", "-frames:v", "1", dst])
    print(f"contact sheet: {dst} ({n} frames)")


def events(src, plan_path, comp, dst):
    plan = json.load(open(plan_path, encoding="utf-8")); fps = plan["fps"]; c = plan["compositions"][comp]
    frames = sorted({x["start"] for x in c["callouts"]})[:24]
    if not frames: print("no call-outs"); return
    sel = "+".join(f"eq(n\\,{f + 3})" for f in frames)   # 3 frames after the event: the pill is fully in
    cols = 4; rows = math.ceil(len(frames) / cols)
    run(["ffmpeg", "-y", "-v", "error", "-i", src, "-vf", f"select='{sel}',scale=480:-2,tile={cols}x{rows}:padding=6:margin=6:color=0x0B0B0D", "-frames:v", "1", "-vsync", "0", dst])
    print(f"event frames: {dst} ({len(frames)} call-outs at " + ", ".join(f"{f / fps:.2f}s" for f in frames) + ")")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "finalize": finalize(sys.argv[2], sys.argv[3])
    elif cmd == "measure": print(json.dumps(measure(sys.argv[2]), indent=1))
    elif cmd == "sheet": sheet(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 12)
    elif cmd == "events": events(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    else: raise SystemExit(__doc__)
