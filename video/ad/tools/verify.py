"""Checks a rendered output folder and writes verify.json + a short table; or checks a plan before rendering.

    python -I verify.py <out dir> [--plan plan.json]
    python -I verify.py --check-plan <plan.json>      every line of type fits its grid zone; no 3D/insert overlaps

Per mp4: streams (codec/profile/size/fps, audio codec/rate), duration, size, integrated loudness and true peak.
Sync: for every warning call-out (harsh steer / brake) in the ad, the music's RMS right after the event versus right
before it; the app's tape sag must make it drop, so a negative dB figure means the call-out lands on an audible dip.
"""
import json, os, re, subprocess, sys
import numpy as np


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,profile,width,height,r_frame_rate,sample_rate,channels,bit_rate:format=duration,size",
                        "-of", "json", path], capture_output=True, text=True)
    return json.loads(r.stdout or "{}")


def loudness(path):
    r = subprocess.run(["ffmpeg", "-nostats", "-i", path, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    m = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", r.stderr, re.S)
    return json.loads(m.group(0)) if m else {}


def pcm(path, t0, t1, sr=48000):
    r = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t0:.3f}", "-t", f"{t1 - t0:.3f}", "-i", path, "-vn", "-ac", "1", "-ar", str(sr), "-f", "f32le", "pipe:1"],
                       capture_output=True)
    return np.frombuffer(r.stdout, dtype=np.float32)


def rms_db(x):
    return 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-9)


# ---- plan check: every line of type fits its zone (the grid in src/layout.ts), so nothing can overlap -----------------
ZONES = {  # (width, height): zone widths in px
    (1920, 1080): {"message": 920, "title": 900, "insert": 564, "center": 1728, "label": 620, "insertLabel": 564, "fullLabel": 900,
                   "callout": 560, "insertCallout": 564, "fullCallout": 560},
    (1080, 1920): {"message": 936, "title": 936, "insert": 936, "center": 936, "label": 936, "insertLabel": 936, "fullLabel": 936,
                   "callout": 936, "insertCallout": 936, "fullCallout": 936},
}
SIZES = {"title": 96, "xl": 64, "l": 44, "m": 30}


def text_width(s, px):
    """Pretendard: Hangul/CJK ~ 0.98 em, Latin/digits ~ 0.56 em, space 0.26 em (letter-spacing ignored)."""
    w = 0.0
    for ch in s:
        o = ord(ch)
        if ch == " ": w += 0.26
        elif 0xAC00 <= o <= 0xD7A3 or 0x3000 <= o <= 0x9FFF or 0xFF00 <= o <= 0xFFEF: w += 0.98
        else: w += 0.56
    return w * px


def mode_at(c, f):
    for s in c["panel"]:
        if s["start"] <= f < s["start"] + s["dur"]: return "full" if s["crop"] == "full" else "insert"
    for s in c["scene3d"]:
        if s["start"] <= f < s["start"] + s["dur"]: return "scene"
    return "none"


def check_plan(plan):
    """Returns a list of problems (empty = ok): type wider than its zone, pills/labels wider than theirs, two 3D scenes
    or two inserts overlapping in time, a 3D scene under an insert, a text block of more than two lines."""
    problems = []
    for name, c in plan["compositions"].items():
        z = ZONES[(c["width"], c["height"])]
        vert = c["height"] > c["width"]
        for t in c["text"]:
            px = SIZES[t["size"]] * (0.9 if vert else 1.0)
            zone = t["pos"] if t["pos"] in z else "message"
            if len(t["lines"]) > 2: problems.append(f"{name}: text at {t['start']/30:.1f}s has {len(t['lines'])} lines (max 2)")
            for ln in t["lines"]:
                w = text_width(ln, px)
                if w > z[zone]: problems.append(f"{name}: '{ln}' {w:.0f}px > zone {zone} {z[zone]}px at {t['start']/30:.1f}s")
        for co in c["callouts"]:
            zone = {"scene": "callout", "insert": "insertCallout", "full": "fullCallout"}.get(mode_at(c, co["start"]))
            if not zone: problems.append(f"{name}: call-out '{co['label']}' at {co['start']/30:.1f}s outside any zone"); continue
            w = text_width(co["label"], 32 if vert else 28) + 110
            if w > z[zone]: problems.append(f"{name}: pill '{co['label']}' {w:.0f}px > zone {zone} {z[zone]}px")
        for lb in c["labels"]:
            zone = {"scene": "label", "insert": "insertLabel", "full": "fullLabel"}.get(mode_at(c, lb["start"]))
            if not zone: problems.append(f"{name}: label '{lb['label']}' at {lb['start']/30:.1f}s outside any zone"); continue
            w = text_width(lb["label"], 26) + 24
            if w > z[zone]: problems.append(f"{name}: label '{lb['label']}' {w:.0f}px > zone {zone} {z[zone]}px")
        for key in ("scene3d", "panel"):
            items = sorted(c[key], key=lambda x: x["start"])
            for a, b in zip(items, items[1:]):
                if b["start"] < a["start"] + a["dur"]: problems.append(f"{name}: two {key} items overlap at {b['start']/30:.1f}s")
        for a in c["scene3d"]:
            for b in c["panel"]:
                if a["start"] < b["start"] + b["dur"] and b["start"] < a["start"] + a["dur"]:
                    problems.append(f"{name}: a 3D scene and an insert overlap at {max(a['start'], b['start'])/30:.1f}s")
    return problems


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if "--check-plan" in sys.argv:
        plan = json.load(open(sys.argv[sys.argv.index("--check-plan") + 1], encoding="utf-8"))
        problems = check_plan(plan)
        for p in problems: print("  ! " + p)
        print(f"plan check: {len(problems)} problem(s)")
        sys.exit(1 if problems else 0)
    out = os.path.abspath(sys.argv[1])
    plan_path = sys.argv[sys.argv.index("--plan") + 1] if "--plan" in sys.argv else os.path.join(out, "plan.json")
    plan = json.load(open(plan_path, encoding="utf-8")) if os.path.exists(plan_path) else None
    names = {"ad_16x9.mp4": "Ad16x9", "short_9x16.mp4": "Short9x16", "demo_full_16x9.mp4": "DemoFull16x9"}
    report = {"files": {}, "sync": []}
    for fn, comp in names.items():
        p = os.path.join(out, fn)
        if not os.path.exists(p): report["files"][fn] = None; continue
        info = probe(p); v = next((s for s in info.get("streams", []) if s["codec_type"] == "video"), {}); a = next((s for s in info.get("streams", []) if s["codec_type"] == "audio"), {})
        l = loudness(p)
        report["files"][fn] = {"video": f"{v.get('codec_name')} {v.get('profile')} {v.get('width')}x{v.get('height')} {v.get('r_frame_rate')}",
                               "audio": f"{a.get('codec_name')} {a.get('sample_rate')} Hz {a.get('channels')} ch {int(a.get('bit_rate', 0)) // 1000} kbps",
                               "duration_s": round(float(info["format"]["duration"]), 2), "size_mb": round(int(info["format"]["size"]) / 1e6, 1),
                               "lufs": round(float(l.get("input_i", 0)), 2), "true_peak_dbtp": round(float(l.get("input_tp", 0)), 2)}
        print(f"{fn}: {report['files'][fn]['video']} | {report['files'][fn]['audio']} | {report['files'][fn]['duration_s']} s | "
              f"{report['files'][fn]['size_mb']} MB | {report['files'][fn]['lufs']} LUFS / {report['files'][fn]['true_peak_dbtp']} dBTP")
        if plan and comp in plan["compositions"]:
            fps = plan["fps"]
            for c in plan["compositions"][comp]["callouts"]:
                if c["tone"] != "warn" or c["graced"]: continue
                t = c["start"] / fps
                before = pcm(p, max(0, t - 0.6), t); after = pcm(p, t + 0.25, t + 1.0)
                if len(before) < 1000 or len(after) < 1000: continue
                d = rms_db(after) - rms_db(before)
                report["sync"].append({"file": fn, "t": round(t, 2), "type": c["type"], "rms_delta_db": round(float(d), 2)})
    if report["sync"]:
        dips = [s for s in report["sync"] if s["rms_delta_db"] < 0]
        print(f"sync: {len(dips)}/{len(report['sync'])} warning call-outs sit on a music dip (RMS after < before); "
              f"mean {np.mean([s['rms_delta_db'] for s in report['sync']]):+.1f} dB")
    json.dump(report, open(os.path.join(out, "verify.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
