"""Reads one capture run (scripts/make-demo-video.ps1 output) and writes events.json: every drive event, tape sag,
cue step and story phase, in seconds of the synced clean videos (story_clean.mp4 / console_clean.mp4 / remix_clean.mp4).

    python -I events.py <run folder> <out events.json>

Time mapping per segment: t_clean = (t_logcat - t_logcat of "recording started: .../ds_<seg>.wav") + badge_s, where
badge_s is the first frame of the clean video that shows the red REC badge (= WAV sample 0). badge_s comes from the
run's sync.json when present, otherwise it is detected here (pure-red pixels inside the app pane), otherwise derived
from timing.json. Nothing run-specific is hard-coded.
"""
import json, os, re, subprocess, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lines import CALLOUTS, GRACED, STORY_PHASES, CUE_STEPS, REMIX_PHASES, REMIX_LINES

FPS = 30
PANE = (640, 101, 1892, 922)   # app pane inside the 1920x1080 Pleos display (x0, y0, x1, y1)


def ffprobe_duration(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                       capture_output=True, text=True)
    return float(r.stdout.strip() or 0)


def detect_badge(mp4, seconds=12):
    """First time (s) the REC badge (pure red glyphs in the app pane) stays on for 3 consecutive frames."""
    w, h = 960, 540
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", mp4, "-t", str(seconds), "-vf", f"fps={FPS},scale={w}:{h}",
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"], stdout=subprocess.PIPE)
    n = w * h * 3; i = 0; streak = 0
    x0, y0, x1, y1 = [v // 2 for v in PANE]
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n: break
        f = np.frombuffer(buf, dtype=np.uint8).reshape(h, w, 3).astype(np.int16)[y0:y1, x0:x1]
        red = (f[:, :, 0] > 170) & (f[:, :, 1] < 110) & (f[:, :, 2] < 120) & (f[:, :, 0] - f[:, :, 1] > 80)
        c = int(red.sum())
        streak = streak + 1 if 10 <= c <= 4000 else 0
        if streak >= 3:
            p.kill(); return round((i - 2) / FPS, 3)
        i += 1
    p.wait()
    return None


LOG_RE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)\s+\d+\s+\d+\s+\w\s+(\w+):\s*(.*)$")


def parse_logcat(path):
    """[(seconds since the first line, tag, message)] with midnight / month rollover handled."""
    out = []; base = None; prev = None; wrap = 0.0
    for line in open(path, encoding="utf-8-sig", errors="replace"):
        m = LOG_RE.match(line.rstrip("\n"))
        if not m: continue
        mo, d, hh, mm, ss, ms, tag, msg = m.groups()
        t = int(mo) * 31 * 86400 + int(d) * 86400 + int(hh) * 3600 + int(mm) * 60 + int(ss) + int(ms) / 1000.0
        if prev is not None and t < prev - 43200: wrap += 366 * 86400.0   # year rollover
        prev = t; t += wrap
        if base is None: base = t
        out.append((t - base, tag, msg))
    return out


EVENT_RE = re.compile(r"drive event (\w+)( \(graced\))? -> flow ([\d.]+), gear (\w+)")
SAG_RE = re.compile(r"tape sag #(\d+) after (\w+)")


def segment(name, run, log, timing, sync):
    clean = os.path.join(run, f"{name}_clean.mp4")
    if not os.path.exists(clean): return None
    t0 = next((t for t, tag, msg in log if "recording started" in msg and msg.strip().endswith(f"ds_{name}.wav")), None)
    t_end = next((t for t, tag, msg in log if "recording closed" in msg and f"ds_{name}.wav" in msg), None)
    badge = None; source = None
    if sync and name in sync and "badge_s" in sync[name]:
        badge = float(sync[name]["badge_s"]) - float(sync[name].get("cut_start_s", 0.0)); source = "sync.json"
    if badge is None:
        badge = detect_badge(clean); source = "detected"
    if badge is None and timing:
        key = "story_demo" if name == "story" else "console_cue"
        if key in timing and f"{name}_record" in timing:
            badge = 1.0 - (timing[key] - timing[f"{name}_record"]) / 1000.0; source = "timing.json"   # video_sync.py cuts 1.0 s before the action
    if badge is None: badge = 0.9; source = "default"
    dur = ffprobe_duration(clean)
    if t0 is None:
        print(f"{name}: no 'recording started' line in logcat; events unavailable"); t0 = 0.0
    events = []; sags = []
    for t, tag, msg in log:
        if tag != "SymphonyService": continue
        tc = round(t - t0 + badge, 3)
        if tc < 0 or tc > dur: continue
        m = EVENT_RE.search(msg)
        if m:
            kind, graced, flow, gear = m.groups()
            label, line, tone = CALLOUTS.get(kind, (kind, "", "info"))
            if graced:
                glabel, gline, tone = GRACED; line = f"{label} · {gline}"; label = glabel
            events.append({"t": tc, "type": kind, "graced": bool(graced), "flow": float(flow), "gear": gear,
                           "label": label, "line": line, "tone": tone}); continue
        m = SAG_RE.search(msg)
        if m:
            sags.append({"t": tc, "n": int(m.group(1)), "after": m.group(2)}); continue
        if "rear seat occupied" in msg:
            label, line, tone = CALLOUTS["REAR_SEAT"]
            events.append({"t": tc, "type": "REAR_SEAT", "graced": False, "flow": None, "gear": "", "label": label, "line": line, "tone": tone})
        elif "rear seat empty" in msg:
            label, line, tone = CALLOUTS["REAR_SEAT_EMPTY"]
            events.append({"t": tc, "type": "REAR_SEAT_EMPTY", "graced": False, "flow": None, "gear": "", "label": label, "line": line, "tone": tone})
    wav = f"ds_{name}.wav" if os.path.exists(os.path.join(run, f"ds_{name}.wav")) else None
    return {"file": f"{name}_clean.mp4", "wav": wav, "duration": dur, "badge_s": badge, "badge_source": source,
            "log_t0": t0, "music_end": round(t_end - t0 + badge, 3) if t_end is not None else dur,
            "events": events, "sags": sags}


def main():
    run = os.path.abspath(sys.argv[1]); out = os.path.abspath(sys.argv[2])
    timing = json.load(open(os.path.join(run, "timing.json"))) if os.path.exists(os.path.join(run, "timing.json")) else {}
    sync = json.load(open(os.path.join(run, "sync.json"))) if os.path.exists(os.path.join(run, "sync.json")) else None
    log = parse_logcat(os.path.join(run, "logcat.txt")) if os.path.exists(os.path.join(run, "logcat.txt")) else []
    result = {"run": run, "segments": {}}
    story = segment("story", run, log, timing, sync)
    if story:
        off = (timing.get("story_demo", 0) - timing.get("story_record", 0)) / 1000.0 if timing else 0.1
        base = story["badge_s"] + off   # demo scenario t=0 in the clean video
        story["scenario_t0"] = round(base, 3)
        story["phases"] = [{"t0": round(base + a, 3), "t1": round(base + b, 3), "label": l, "line": s} for a, b, l, s in STORY_PHASES]
        result["segments"]["story"] = story
    console = segment("console", run, log, timing, sync)
    if console:
        cues = []
        cue_log = os.path.join(run, "cue.log")
        if os.path.exists(cue_log) and "console_cue" in timing:
            base = console["badge_s"] + (timing["console_cue"] - timing["console_record"]) / 1000.0
            steps = []
            for line in open(cue_log, encoding="utf-8-sig"):
                m = re.match(r"(\d+)\s+(\d+\.)\s(.*)$", line)
                if m: steps.append((base + (int(m.group(1)) - timing["console_cue"]) / 1000.0, m.group(2), m.group(3).strip()))
            for k, (t, key, raw) in enumerate(steps):
                nxt = steps[k + 1][0] if k + 1 < len(steps) else t + 8.0
                label, line = CUE_STEPS.get(key, (raw, ""))
                cues.append({"t0": round(t, 3), "t1": round(max(t + 2.5, nxt - 0.3), 3), "step": key, "label": label, "line": line})
        console["cues"] = cues
        result["segments"]["console"] = console
    remix = segment("remix", run, log, timing, sync)   # the bundled sample track under the same demo story
    if remix:
        off = (timing.get("remix_demo", 0) - timing.get("remix_record", 0)) / 1000.0 if timing else 0.1
        base = remix["badge_s"] + off
        remix["scenario_t0"] = round(base, 3)
        remix["phases"] = [{"t0": round(base + a, 3), "t1": round(base + b, 3), "label": l, "line": s}
                           for a, b, l, s in REMIX_PHASES if base + a < remix["duration"] - 1.0]
        for e in remix["events"]:
            if not e["graced"] and e["type"] in REMIX_LINES: e["line"] = REMIX_LINES[e["type"]]
        result["segments"]["remix"] = remix
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump(result, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for name, seg in result["segments"].items():
        print(f"{name}: {seg['duration']:.1f} s, badge {seg['badge_s']:.3f} s ({seg['badge_source']}), "
              f"{len(seg['events'])} events, {len(seg['sags'])} sags, music ends {seg['music_end']:.1f} s")
        for e in seg["events"]:
            print(f"   {e['t']:7.2f}  {e['type']:16s} {'graced ' if e['graced'] else ''}flow={e['flow']}  {e['label']}")


if __name__ == "__main__":
    main()
