"""Checks a rendered output folder and writes verify.json + a short table.

    python -I verify.py <out dir> [--plan plan.json]

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


def main():
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
