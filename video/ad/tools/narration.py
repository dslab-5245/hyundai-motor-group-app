"""Korean voice-over for every narration line (tools/lines.py NARRATION) with edge-tts.

    python -I narration.py <out dir> [--voice ko-KR-HyunsuMultilingualNeural] [--rate +0%]

Writes <out dir>/narration/<id>.wav (48 kHz mono, leading/trailing silence trimmed, peak-normalised to -3 dBFS),
<out dir>/narration.json (id, text, duration) and <out dir>/narration.wav (all lines back to back, for review).
Falls back through the voice list until one synthesises; exits 2 when none does (the videos then render mute-voiced).
"""
import asyncio, json, os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lines import NARRATION

VOICES = ["ko-KR-HyunsuMultilingualNeural", "ko-KR-InJoonNeural", "ko-KR-SunHiNeural"]


def arg(name, default):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


async def synth(text, voice, rate, mp3):
    import edge_tts
    await edge_tts.Communicate(text, voice, rate=rate).save(mp3)


def duration(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True)
    return float(r.stdout.strip() or 0)


def main():
    out = os.path.abspath(sys.argv[1]); nd = os.path.join(out, "narration"); os.makedirs(nd, exist_ok=True)
    voices = [arg("--voice", None)] + VOICES if "--voice" in sys.argv else VOICES
    rate = arg("--rate", "+0%")
    used = None; items = []
    for voice in voices:
        if not voice: continue
        try:
            items = []
            for key, text in NARRATION:
                mp3 = os.path.join(nd, f"{key}.mp3"); wav = os.path.join(nd, f"{key}.wav")
                asyncio.run(synth(text, voice, rate, mp3))
                # trim silence at both ends (edge-tts pads ~0.1-0.5 s), 48 kHz mono, peak -3 dBFS
                subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", mp3, "-af",
                                "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,areverse,"
                                "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.08,areverse,"
                                "apad=pad_dur=0.12,volume=-3dB:precision=double", "-ar", "48000", "-ac", "1", wav], check=True)
                items.append({"id": key, "text": text, "file": f"narration/{key}.wav", "duration": round(duration(wav), 3)})
                os.remove(mp3)
            used = voice; break
        except Exception as e:   # noqa: BLE001 - any failure (403 on the websocket, network) -> next voice
            print(f"{voice}: failed ({e!r:.160})")
    if not used:
        json.dump({"voice": None, "rate": rate, "lines": []}, open(os.path.join(out, "narration.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print("no voice available; rendering without narration"); sys.exit(2)
    json.dump({"voice": used, "rate": rate, "lines": items}, open(os.path.join(out, "narration.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    lst = os.path.join(nd, "all.txt")
    with open(lst, "w", encoding="utf-8") as f:
        for it in items: f.write(f"file '{it['id']}.wav'\n")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", lst, "-ar", "48000", "-ac", "2", os.path.join(out, "narration.wav")], check=True)
    print(f"voice {used} rate {rate}: " + ", ".join(f"{it['id']} {it['duration']:.1f}s" for it in items))


if __name__ == "__main__":
    main()
