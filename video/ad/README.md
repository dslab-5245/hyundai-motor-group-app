# video/ad — ad-grade videos from a capture run

Turns one `scripts/make-demo-video.ps1` run folder (`build/video/<timestamp>/`) into three films, rendered
deterministically with [Remotion](https://www.remotion.dev) (React → headless Chrome → H.264):

| output | what | length |
|---|---|---|
| `ad_16x9.mp4` | cinematic product film: hook → reveal → layers → stumble → recovery → bloom → reward → grace → remix → arrival → end card | ~78 s |
| `short_9x16.mp4` | vertical short (Spot → Solve → Scene) with the contest hashtags on the end card | ~49 s |
| `demo_full_16x9.mp4` | narrated full demo: intro, chapter cards, the whole story and Virtual Console recordings with phase labels and call-outs, outro | ~3.9 min |

plus `narration.wav`, `events.json`, `plan.json`, `contact_sheet.png` (12 frames of the ad), `event_frames_<comp>.png`
(a frame 0.1 s after every call-out, for checking sync), `render.log`. All 1080p H.264 High, AAC 256 kbps,
−16 LUFS integrated / −1.5 dBTP.

## Render a run (≤ 5 commands)

```powershell
# 1. (once) tools: Node 24+, Python 3.12 with numpy/Pillow, ffmpeg 8 on PATH, `pip install -U edge-tts`
# 2. everything, unattended (first run also does npm install, downloads Pretendard and the 7 Mixkit clips, ~330 MB):
powershell -ExecutionPolicy Bypass -File scripts\make-ad.ps1 -Run build\video\20261031-165522
# 3. only one composition / without voice / with another Edge voice:
powershell -ExecutionPolicy Bypass -File scripts\make-ad.ps1 -Run build\video\<ts> -Only Ad16x9 -Voice ko-KR-InJoonNeural
# 4. look at it while editing (Remotion Studio, after a run has been staged):
cd video\ad; npx remotion studio
```

Output goes to `build/ad/<run folder name>/` (override with `-Out`).

## How it works

```
run folder ──stage.py──► public/run/{story_clean.mp4, console_clean.mp4, ds_*.wav}
logcat/timing/cue/sync ──events.py──► public/run/events.json   (event times in clean-video seconds)
tools/lines.py ──narration.py (edge-tts)──► public/run/narration/*.wav + narration.json
broll.json ──broll.py──► public/broll/*.mp4   (Mixkit free licence, rejected if restricted)
events + narration + b-roll ──plan.py──► public/run/plan.json  (layered timelines per composition)
plan.json ──Remotion (src/)──► raw mp4 ──post.py finalize (loudnorm)──► final mp4 ──post.py sheet/events──► PNGs
```

* **Sync.** Event time in a clean video = (logcat time − logcat time of `recording started: …/ds_<seg>.wav`) + `badge_s`,
  where `badge_s` is the first clean-video frame showing the red ● REC badge (WAV sample 0). `events.py` reads it
  from the run's `sync.json` (`badge_s − cut_start_s`), otherwise detects the badge (pure-red pixels in the app pane),
  otherwise derives it from `timing.json`. Call-outs are placed on the exact event frame (±1 frame = 0.033 s).
* **Director** (`tools/plan.py`). Beats are anchored on events: the first signalled turn, the first harsh event, the
  smooth-streak, the first graced event and the arrival phase, with fallbacks. Beat lengths follow the measured
  narration durations. Music is the app's own WAV (gain from `sync.json`), cut only where the picture cuts and merged
  into one segment wherever consecutive shots are contiguous; it ducks −6 dB under speech.
* **Design** (`src/`). `theme.ts` (dark #0B0B0D→#15120E, amber #C8862B/#E0A458, text #F3EEE6, muted #8A8178,
  Pretendard), `layout.ts` (panel / text / call-out / label positions per orientation and crop),
  `components/Background` (graded, vignetted POV footage), `components/Panel` (floating car display with bezel,
  perspective, reflection, bass-reactive amber glow, flow-driven dip and cool cast), `components/Kinetic` (word-by-word
  springs), `components/Callouts` (event pills with repeat counter and leader line), `components/Labels` (phase / cue
  lower-thirds in the demo), `components/Cards` (chapter, end, outro with the Flow-ring motif), `components/Spectrum`
  (bottom bar spectrum from `useAudioData`/`visualizeAudio`), `components/Sound` (music segments + ducking, narration).
* **Copy.** Every Korean line lives in `tools/lines.py` (narration, call-out labels, story phases, console cue steps,
  hashtags). Keep claims truthful: contest entry, not on the market; b-roll is never presented as app footage (the end
  card says so).

## Changing things

* Different wording → `tools/lines.py`, re-run `make-ad.ps1` (narration is regenerated).
* Different cut → `tools/plan.py` (`ad()`, `short()`, `demo()`); `tools/stills.mjs Ad16x9 out/stills 120 600` renders
  single frames quickly; `npx remotion studio` scrubs the timeline.
* New b-roll → add to `broll.json` (id, page, roles); only Mixkit Free-License clips download at 1080p.
* A run with a different scenario still renders: beats that cannot find their anchor fall back to defaults in
  `plan.py:anchors()`; a run without `console_clean.mp4` skips the console chapter; without a TTS voice the films
  render without narration (`narration.py` exits 2 and `make-ad.ps1` continues).

## Known limits

* The story recording ends before the app's journey-song card appears, so the arrival beat is typographic over the
  parked UI; a run that captures the card could show it by extending `shot("story", …)` at the end of `ad()`.
* The Virtual Console's signalled turn opens the emulator's blind-spot camera inset over the app for ~6 s; the demo
  shows it as recorded, the ad uses the story's signalled turn instead.
* `-webkit-box-reflect`, `backdrop-filter` and `mix-blend-mode` are Chromium features; rendering uses Remotion's
  bundled Chrome Headless Shell with the ANGLE GL backend (`remotion.config.ts`).
