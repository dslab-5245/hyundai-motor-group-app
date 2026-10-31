"""Assembles the demo videos from what scripts/make-demo-video.ps1 recorded (python -I scripts/video_sync.py <dir>).

Per segment (ds_story, ds_console): finds the first frame that shows the red REC badge (the in-app WAV tap starts at
the same instant, so that frame is sample 0 of the WAV), measures the WAV loudness, writes Korean captions timed to
the demo story / console cue log, and encodes a synced, captioned 1080p cut plus a clean one. Then it builds
  demo_video_16x9.mp4   intro card + story + console (+ cards) + outro, 1920x1080 30 fps, AAC
  short_9x16.mp4        a 45 s vertical cut of the story for Reels / Shorts (1080x1920)
Requires ffmpeg on PATH, numpy and Pillow (cards). Fonts: Noto Sans KR / Malgun Gothic from C:/Windows/Fonts.
"""
import json, os, re, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
FONT_DIR = "C:/Windows/Fonts"
FONT_BOLD = next((p for p in (f"{FONT_DIR}/NotoSansKR-Bold.ttf", f"{FONT_DIR}/malgunbd.ttf") if os.path.exists(p)), None)
FONT_REG = next((p for p in (f"{FONT_DIR}/NotoSansKR-Regular.ttf", f"{FONT_DIR}/malgun.ttf") if os.path.exists(p)), None)
SUB_FONT = "Noto Sans KR" if "Noto" in (FONT_REG or "") else "Malgun Gothic"
BG = (11, 15, 20); TEXT = (242, 244, 247); MUTED = (167, 177, 190); ACCENT = (232, 168, 96); GOOD = (138, 227, 179)
W, H, FPS = 1920, 1080, 30
TARGET_LUFS, TARGET_TP = -16.0, -1.5
ENC = ["-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-r", str(FPS),
       "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart"]


def run(args, capture=False):
    r = subprocess.run(args, cwd=OUT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0 and not capture:
        print(r.stderr[-2000:]); raise SystemExit(f"command failed: {' '.join(args[:3])} ...")
    return r


def duration(path):
    r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture=True)
    return float(r.stdout.strip() or 0)


def find_badge(mp4, seconds=25):
    """First time (s) at which the REC badge (pure red text) is on screen for 3 consecutive 30 fps frames."""
    w, h = 960, 540
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", mp4, "-t", str(seconds), "-vf", f"fps={FPS},scale={w}:{h}",
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"], cwd=OUT, stdout=subprocess.PIPE)
    n = w * h * 3; i = 0; streak = 0
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n: break
        f = np.frombuffer(buf, dtype=np.uint8).reshape(h, w, 3).astype(np.int16)
        f = f[55:455, 330:940]   # the app pane only: the Pleos cluster / climate bar have pure-red glyphs of their own
        red = (f[:, :, 0] > 170) & (f[:, :, 1] < 110) & (f[:, :, 2] < 120) & (f[:, :, 0] - f[:, :, 1] > 80)
        c = int(red.sum())
        streak = streak + 1 if 10 <= c <= 4000 else 0
        if streak >= 3:
            p.kill(); return (i - 2) / FPS
        i += 1
    p.wait()
    return None


def loudness(wav):
    r = run(["ffmpeg", "-v", "info", "-nostats", "-i", wav, "-af", "ebur128=peak=true", "-f", "null", "-"], capture=True)
    i = re.findall(r"I:\s+(-?[\d.]+) LUFS", r.stderr); tp = re.findall(r"Peak:\s+(-?[\d.]+) dBFS", r.stderr)
    integrated = float(i[-1]) if i else -16.0
    peak = float(tp[-1]) if tp else -1.5
    gain = min(TARGET_LUFS - integrated, TARGET_TP - peak)
    print(f"{os.path.basename(wav)}: {integrated:.1f} LUFS, true peak {peak:.1f} dBTP -> gain {gain:+.1f} dB")
    return gain


def fonts_dir():
    """Copies the two fonts next to the outputs so the subtitles filter gets a path without a drive colon."""
    import shutil
    d = os.path.join(OUT, "fonts"); os.makedirs(d, exist_ok=True)
    for f in (FONT_BOLD, FONT_REG):
        if f and not os.path.exists(os.path.join(d, os.path.basename(f))): shutil.copy(f, d)
    return "fonts"


def srt(path, cues):
    """cues: list of (start, end, text) in seconds."""
    def ts(t):
        t = max(0.0, t); h = int(t // 3600); m = int(t % 3600 // 60); s = t % 60
        return f"{h:02d}:{m:02d}:{s:06.3f}".replace(".", ",")
    with open(path, "w", encoding="utf-8") as f:
        for k, (a, b, text) in enumerate(cues, 1):
            f.write(f"{k}\n{ts(a)} --> {ts(b)}\n{text}\n\n")


STORY_CAPTIONS = [   # (story time start, end, text): the demo scenario timeline (engine/.../Scenarios.kt story)
    (0.5, 6.0, "강변북로 · 로파이\n출발과 함께 '이 길의 테마'가 시작됩니다 — 같은 길은 같은 테마"),
    (6.0, 12.5, "깜빡이를 켜고 도는 회전 → 즉시 보상: 레이어 하나가 더 열립니다"),
    (13.0, 19.5, "부드럽게 달릴수록 베이스·키·드럼이 차례로 쌓입니다"),
    (20.0, 28.0, "브레이크 반복(습관) → 음악이 잠시 가라앉습니다\n테이프가 늘어지듯 가라앉고, 필터가 닫힙니다 · 침묵은 없습니다"),
    (28.5, 43.5, "다시 부드럽게 → 5초마다 레이어가 하나씩 돌아옵니다"),
    (44.0, 56.0, "고속도로 · 크루즈 컨트롤 → 드론 베드가 깔립니다"),
    (56.5, 76.0, "안정 주행이 이어지면 멜로디가 피어납니다(블룸)"),
    (78.0, 85.0, "부드러운 주행 25초 유지 → 가장 풍성한 편곡"),
    (86.0, 89.5, "급조향 1회 → 긴급 회피로 봅니다: 짧게 숨만 고르고 돌아옵니다"),
    (90.0, 98.0, "와이퍼 → 비 텍스처와 셰이커가 더해집니다"),
    (99.0, 103.0, "제동 · 정차"),
    (103.5, 112.0, "주차(P) → 오늘 당신이 연주한 곡: 여정의 곡 카드"),
]
CUE_CAPTIONS = {   # Virtual Console cue steps (scripts/virtual-console.py cue) -> caption
    "1.": "에뮬레이터 Virtual Console · 실제 차량 신호 경로(SDK)\n기어 D, 핸들 가만히",
    "2.": "조향 ±30° → 음악이 좌우로 따라옵니다(패닝)",
    "3.": "깜빡이 켜고 160° 회전 → 즉시 보상 레이어",
    "4.": "20초 안정 주행 → 레이어가 차례로 열립니다",
    "5.": "급조향 + 브레이크 1회 → 긴급 회피로 인식: 숨만 고릅니다",
    "6.": "12초 안정 주행",
    "7.": "브레이크 3연타 → 음악이 가라앉습니다(테이프 새그 · 필터 닫힘)",
    "8.": "8초 안정 → 회복",
    "9.": "와이퍼 · 크루즈 → 비 텍스처 · 드론 베드",
    "10.": "뒷좌석 탑승 감지 → 피드백 강도 '약'으로",
    "11.": "제동 · 주차(P) → 여정의 곡 카드",
}


def card(name, lines, seconds, sub=None, accent=ACCENT):
    """A title card: big line(s) + optional small lines, rendered with Pillow, encoded as a short clip with silence."""
    img = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(img)
    big = ImageFont.truetype(FONT_BOLD, 88); mid = ImageFont.truetype(FONT_REG, 44); small = ImageFont.truetype(FONT_REG, 34)
    y = H // 2 - (len(lines) * 110 + (len(sub or []) * 56 + 30 if sub else 0)) // 2
    for k, line in enumerate(lines):
        f = big if k == 0 else mid
        tw = d.textlength(line, font=f); d.text(((W - tw) / 2, y), line, font=f, fill=TEXT if k == 0 else MUTED); y += 110 if k == 0 else 70
    if sub:
        y += 30
        for line in sub:
            tw = d.textlength(line, font=small); d.text(((W - tw) / 2, y), line, font=small, fill=accent if line.startswith("#") else TEXT); y += 56
    d.rectangle([W // 2 - 60, H - 120, W // 2 + 60, H - 116], fill=accent)
    png = os.path.join(OUT, f"card_{name}.png"); img.save(png)
    mp4 = os.path.join(OUT, f"card_{name}.mp4")
    run(["ffmpeg", "-y", "-v", "error", "-loop", "1", "-framerate", str(FPS), "-t", str(seconds), "-i", png,
         "-f", "lavfi", "-t", str(seconds), "-i", "anullsrc=r=48000:cl=stereo",
         "-vf", f"fade=in:0:12,fade=out:{int(seconds * FPS) - 15}:15", "-shortest"] + ENC + [mp4])
    return mp4


def segment(name, t0_offset_ms, captions, start_before=1.0, end_after=None):
    """Sync + cut one recorded segment. Returns (clean_mp4, captioned_mp4) or None when the material is missing."""
    mp4 = os.path.join(OUT, f"ds_{name}.mp4"); wav = os.path.join(OUT, f"ds_{name}.wav")
    if not (os.path.exists(mp4) and os.path.exists(wav)): print(f"{name}: no material"); return None
    t_badge = find_badge(mp4)
    if t_badge is None: print(f"{name}: REC badge not found, assuming 3.0 s"); t_badge = 3.0
    t_event = t_badge + t0_offset_ms / 1000.0        # when the demo / cue started, in video time
    start = max(0.0, t_event - start_before)
    vid_len = duration(mp4); wav_len = duration(wav)
    end = min(vid_len, t_badge + wav_len, t_event + end_after) if end_after else min(vid_len, t_badge + wav_len)
    gain = loudness(wav)
    rel = t_event - start   # caption time base: event time in the cut
    srt_path = os.path.join(OUT, f"{name}.srt")
    srt(srt_path, [(a + rel, b + rel, t) for a, b, t in captions])
    # libass scales SRT styles from a 384x288 canvas: FontSize 13 = 49 px and margins 40 = 150 px on 1080p
    style = (f"FontName={SUB_FONT},FontSize=13,Bold=0,PrimaryColour=&H00F2F4F7,BackColour=&HB00B0F14,"
             "BorderStyle=4,Outline=1,Shadow=0,MarginV=24,MarginL=40,MarginR=40,Alignment=2,WrapStyle=0")
    fontsdir = fonts_dir()
    print(f"{name}: badge at {t_badge:.2f} s, event at {t_event:.2f} s, cut {start:.2f}..{end:.2f} s ({end - start:.1f} s)")
    common = ["ffmpeg", "-y", "-v", "error", "-ss", f"{start:.3f}", "-i", mp4, "-itsoffset", f"{t_badge - start:.3f}", "-i", wav,
              "-map", "0:v:0", "-map", "1:a:0", "-t", f"{end - start:.3f}", "-af", f"volume={gain:.2f}dB"]
    clean = os.path.join(OUT, f"{name}_clean.mp4")
    run(common + ["-vf", f"scale={W}:{H}"] + ENC + [clean])
    captioned = os.path.join(OUT, f"{name}_captioned.mp4")
    run(common + ["-vf", f"scale={W}:{H},subtitles={name}.srt:fontsdir={fontsdir}:force_style='{style}'"] + ENC + [captioned])
    return clean, captioned, (t_badge, t_event, start, end, gain)


def concat(parts, out):
    lst = os.path.join(OUT, "concat.txt")
    with open(lst, "w", encoding="utf-8") as f:
        for p in parts: f.write(f"file '{os.path.basename(p)}'\n")
    run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", "concat.txt", "-c", "copy", "-movflags", "+faststart", out])


def vertical_short(story_clean, t_event, start, seg_start=15.0, length=45.0):
    """9:16 cut of the story: title block, the UI in the middle, captions below; from story time seg_start."""
    s0 = (t_event - start) + seg_start     # offset inside story_clean
    img = Image.new("RGBA", (1080, 1920), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    big = ImageFont.truetype(FONT_BOLD, 72); mid = ImageFont.truetype(FONT_REG, 40); small = ImageFont.truetype(FONT_REG, 32)
    for k, (line, f, col) in enumerate([("주행이 연주가 되는 차", big, TEXT), ("Drive Symphony · Pleos App Market", mid, MUTED),
                                       ("부드럽게 달릴수록 음악이 풍성해집니다", mid, ACCENT)]):
        tw = d.textlength(line, font=f); d.text(((1080 - tw) / 2, 300 + k * 90), line, font=f, fill=col + (255,))
    tag = "#HMG차량용앱개발공모전  #PleosAppMarket  #HMG앱공모전"
    tw = d.textlength(tag, font=small); d.text(((1080 - tw) / 2, 1760), tag, font=small, fill=MUTED + (255,))
    ov = os.path.join(OUT, "short_overlay.png"); img.save(ov)
    cues = [(0.0, 4.5, "급제동·급조향을 하면\n음악이 잠시 가라앉습니다"),
            (5.0, 12.5, "브레이크 반복 → 테이프가 늘어지듯\n필터가 닫히고 레이어가 줄어듭니다"),
            (13.5, 22.0, "다시 부드럽게 달리면\n5초마다 레이어가 하나씩 돌아옵니다"),
            (23.0, 31.0, "조향·브레이크·깜빡이·와이퍼가 연주하는\n로파이 · 재즈 · 앰비언트"),
            (32.0, 39.0, "녹음된 곡이 아닙니다\n매 여정이 새 곡, 같은 길은 같은 테마"),
            (40.0, 45.0, "안전한 운전이 곧 더 좋은 음악\nDrive Symphony")]
    srt_path = os.path.join(OUT, "short.srt"); srt(srt_path, cues)
    # 1080x1920 frame: PlayResY 288 -> FontSize 9 = 60 px, MarginV 48 = 320 px above the bottom (below the UI block)
    style = (f"FontName={SUB_FONT},FontSize=9,PrimaryColour=&H00F2F4F7,BackColour=&H00000000,BorderStyle=1,Outline=0,"
             "Shadow=0,MarginV=48,MarginL=12,MarginR=12,Alignment=2,WrapStyle=0")
    fontsdir = fonts_dir()
    out = os.path.join(OUT, "short_9x16.mp4")
    fc = (f"color=c=0x0B0F14:s=1080x1920:r={FPS}[bg];[0:v]scale=1080:608[ui];[bg][ui]overlay=0:700:shortest=1[v1];"
          f"[v1][1:v]overlay=0:0[v2];[v2]subtitles=short.srt:fontsdir={fontsdir}:force_style='{style}'[v]")
    run(["ffmpeg", "-y", "-v", "error", "-ss", f"{s0:.3f}", "-t", f"{length:.3f}", "-i", story_clean, "-i", ov,
         "-filter_complex", fc, "-map", "[v]", "-map", "0:a:0", "-af", "afade=t=out:st=42:d=3"] + ENC + [out])
    return out


def main():
    timing = json.load(open(os.path.join(OUT, "timing.json"))) if os.path.exists(os.path.join(OUT, "timing.json")) else {}
    results = {}
    # --- story
    off = timing.get("story_demo", 0) - timing.get("story_record", 0)
    story = segment("story", off, STORY_CAPTIONS, start_before=1.0, end_after=113.0)
    if story: results["story"] = story
    # --- console (captions from the cue log: host-ms + printed step line)
    cue_log = os.path.join(OUT, "cue.log")
    if os.path.exists(cue_log) and "console_cue" in timing:
        steps = []
        for line in open(cue_log, encoding="utf-8-sig"):
            m = re.match(r"(\d+)\s+(\d+\.)\s", line)
            if m: steps.append(((int(m.group(1)) - timing["console_cue"]) / 1000.0, m.group(2)))
        caps = []
        for k, (t, key) in enumerate(steps):
            nxt = steps[k + 1][0] if k + 1 < len(steps) else t + 8.0
            caps.append((t + 0.2, max(t + 2.5, nxt - 0.3), CUE_CAPTIONS.get(key, key)))
        off_c = timing["console_cue"] - timing.get("console_record", 0)
        console = segment("console", off_c, caps, start_before=1.0, end_after=(steps[-1][0] + 9.0) if steps else None)
        if console: results["console"] = console
    if not results: raise SystemExit("nothing to assemble")
    # --- cards + full 16:9 video
    parts = [card("intro", ["Drive Symphony", "주행이 연주가 되는 차"],
                  5.0, sub=["차량 신호가 실시간으로 작곡하는 로파이 · 재즈 · 앰비언트", "부드럽게 달릴수록 음악이 풍성해집니다"])]
    if "story" in results:
        parts.append(card("story", ["시연 1 · 데모 시나리오", "106초 · 저녁의 강변북로 · 시내 → 습관적 급조작 → 회복 → 고속도로 → 회피 → 비 → 도착"], 4.0))
        parts.append(results["story"][1])
    if "console" in results:
        parts.append(card("console", ["시연 2 · 실제 차량 신호", "Pleos Connect 에뮬레이터 Virtual Console → Vehicle SDK → 음악 (데모 꺼짐)"], 4.0))
        parts.append(results["console"][1])
    parts.append(card("outro", ["주행이 연주가 되는 차", "Drive Symphony"], 7.0,
                      sub=["조향 · 브레이크 · 기어 · 깜빡이 · 와이퍼 · 크루즈 · 내비 → 실시간 작곡",
                           "안전하지 않은 조작은 음악이 잠시 가라앉을 뿐, 침묵도 꾸중도 없습니다",
                           "Gleo 음성 명령 4종 · 주행 중 글랜스 화면 · 오디오 CPU 5% 미만 · 네트워크 없음"]))
    final = os.path.join(OUT, "demo_video_16x9.mp4"); concat(parts, final)
    print(f"demo video: {final} ({duration(final):.0f} s)")
    if "story" in results:
        clean, _, (t_badge, t_event, start, end, gain) = results["story"]
        short = vertical_short(clean, t_event, start)
        print(f"short: {short} ({duration(short):.0f} s)")
    with open(os.path.join(OUT, "README.txt"), "w", encoding="utf-8") as f:
        f.write("Drive Symphony demo video material (scripts/make-demo-video.ps1 + scripts/video_sync.py)\n\n"
                "demo_video_16x9.mp4   full demo video: intro card, story (captioned), console (captioned), outro\n"
                "short_9x16.mp4        45 s vertical cut for Reels/Shorts (hashtags in the overlay; post text: see video/STORYBOARD.md)\n"
                "story_clean.mp4       story segment, synced audio, no captions (for editing / AI b-roll compositing)\n"
                "story_captioned.mp4   story segment with captions\n"
                "console_clean.mp4 / console_captioned.mp4   Virtual Console segment\n"
                "ds_*.mp4 / ds_*.wav   raw screen recording and raw engine audio (WAV sample 0 = first REC-badge frame)\n"
                "*.srt                 captions (edit and re-run video_sync.py to re-render)\n"
                "card_*.png/.mp4       title cards\n")


if __name__ == "__main__":
    main()
