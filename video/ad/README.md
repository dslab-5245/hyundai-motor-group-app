# video/ad — ad-grade films from a capture run (v3: 3D driving simulation)

Turns one `scripts/make-demo-video.ps1` run folder (`build/video/<timestamp>/`) into three films, rendered
deterministically with [Remotion](https://www.remotion.dev) (React → headless Chrome → H.264) and
[three.js](https://threejs.org) (`@remotion/three`, react-three-fiber, ANGLE GL):

| output | what | length |
|---|---|---|
| `ad_16x9.mp4` | cinematic product film: a 3D concept coupe on a wet night road past a lit skyline, the camera gliding from outside the car into the cabin and back, driven frame-exact by the demo scenario's own signals; hook → reveal → reward → layers → stumble → recovery → bloom → grace → rain → remix → arrival → journey → end card | 86 s |
| `short_9x16.mp4` | vertical short (Spot → Solve → Scene): hook → stumble → recovery → reward → layers → arrival → end card with the contest hashtags | 45 s |
| `demo_full_16x9.mp4` | narrated full demo: 3D intro, chapter cards, the whole story / Virtual Console / remix recordings flat with phase labels and call-outs, 3D outro, outro card | ~4 min 55 s |

plus `narration.wav`, `events.json`, `signals_story.json`, `plan.json`, `contact_sheet.png` (12 frames of the ad),
`event_frames_<comp>.png` (a frame 0.1 s after every call-out, for checking sync), `verify.json` (streams, loudness,
true peak, metadata), `render.log`. All 1080p H.264 High, AAC 256 kbps, −16 LUFS integrated / −1.5 dBTP.

## Render a run (≤ 5 commands)

```powershell
# 1. (once) tools: Node 24+, Python 3.12 with numpy/Pillow, ffmpeg 8 on PATH, `pip install -U edge-tts`, JDK 17 + Gradle (signals export)
# 2. everything, unattended (first run also does npm install and downloads Pretendard; -Run/-Out take ABSOLUTE paths):
powershell -ExecutionPolicy Bypass -File scripts\make-ad.ps1 -Run C:\...\drive-symphony\build\video\20261109-114519
# 3. only one composition / without voice / with another Edge voice:
powershell -ExecutionPolicy Bypass -File scripts\make-ad.ps1 -Run <abs run> -Only Ad16x9 -Voice ko-KR-InJoonNeural
# 4. one frame while editing (fast) / scrub the timeline (Remotion Studio, after a run has been staged):
cd video\ad; npx remotion still src/index.ts Ad16x9 out.png --frame 1230 --gl angle
cd video\ad; npx remotion studio
```

Output goes to `build/ad/<run folder name>/` (override with `-Out`). A full render of the three films takes roughly
25 minutes on an RTX 4060 Ti (the 3D scenes render at 1080p with 4× MSAA and post-processing).

## How it works

```
run folder ──stage.py──► public/run/{story_clean.mp4, console_clean.mp4, remix_clean.mp4, ds_*.wav, panel stills}
logcat/timing/cue/sync ──events.py──► public/run/events.json          (event times in clean-video seconds)
tools/lines.py ──narration.py (edge-tts)──► public/run/narration/*.wav + narration.json
engine ──gradle :engine:dumpSignals──► public/run/signals_story.json   (steer, brake, blinker, wiper, speed, flow, pulse per frame)
tools/sfx.py ──► public/gen/tick.wav                                   (indicator tick, synthesised)
events + narration + signals ──plan.py──► public/run/plan.json         (layered timelines per composition)
plan.json ──verify.py --check-plan──► every text item measured against its grid zone; the run stops on overflow
plan.json ──Remotion (src/)──► raw mp4 ──post.py finalize (loudnorm)──► final mp4 ──post.py sheet/events──► PNGs
```

* **Sync.** Event time in a clean video = (logcat time − logcat time of `recording started: …/ds_<seg>.wav`) + `badge_s`,
  where `badge_s` is the first clean-video frame showing the red ● REC badge (WAV sample 0). `events.py` reads it
  from the run's `sync.json` (`badge_s − cut_start_s`), otherwise detects the badge (pure-red pixels in the app pane),
  otherwise derives it from `timing.json`. Call-outs are placed on the exact event frame (±1 frame = 0.033 s).
* **Signals.** The 3D car is not animated by hand: `SignalsMain.kt` replays the demo scenario through the real engine
  and writes one row per frame (steer, brake, blinker, wiper, cruise, gear, speed, flow, pulse, event). `plan.py`
  aligns the exported scenario clock with the recording by the median offset between the exported event times and
  the logged event times (`scenario_offset`, ≈1.05 s), so a brake stab in the music is the brake pedal on screen.
* **Director** (`tools/plan.py`). Beats are anchored on events (the first signalled turn, the first harsh event, the
  first graced event, the arrival), beat lengths follow the measured narration durations. A 3D scene is
  `scene(src, t_in, dur, keys)`: a list of (time, camera preset, {cut|hold|orbit}) keys over the named presets in
  `src/scene3d/camera.ts` (side, frontQ, doorGlass, wide, pedal, rearLow, top, display, follow…), smoothed between keys,
  cut where marked. The stumble choreography: side tracking → low front on the harsh steer → cut: pedal → cut: low
  rear with the brake-light flare and the nose dip → pull back up to a top shot while the music sinks → back down
  through the driver's window as it recovers. Music is the app's own WAV (gain from `sync.json`), cut only where the
  picture cuts; it ducks −6 dB under speech.
* **3D** (`src/scene3d/`). `CarModel.tsx` (the car: the Khronos glTF sample "Car Concept", CC BY 4.0, see
  `CREDITS.md`; logo plate and emblem hidden, repainted navy; wheels, calipers, steering wheel, pedals, brake /
  indicator / head lights and the cluster glow are driven by the drive signals; glass fades as the camera passes
  through it; central driving position), `Cockpit.tsx` (the drive-state type and the floating display with the
  recording as a video texture; the older procedural cabin is only used by the `Cockpit3D` spike), `World.tsx` (damp
  three-lane road with a fine grain and soft wetter patches, dashed lines, lamp posts, guard rail, a skyline of lit
  windows, rain), `Scene.tsx` (HDRI + key/fill + studio area panels that dim when the camera is inside; bloom, chromatic
  aberration that grows with the penalty pulse, vignette, grain, ACES). The world moves, the car stays at the origin.
* **Design v3** (`src/`). `theme.ts` (deep navy #060A12 → #0B1220, ice #8FB3FF, text #C9D6F2, Pretendard 400/500, no
  heavy weights), `layout.ts` (fixed, disjoint zones per orientation: title top-left, phase label top-right, message
  bottom-left of at most two lines, call-out pills bottom-right, the flat app insert on the left with a text column
  on the right, the full recording with labels under it), `components/Scene3D` (one 3D scene per item),
  `components/Panel` (the flat app pane insert), `components/Kinetic` (word-by-word springs with a soft scrim on the
  3D picture), `components/Callouts` (event pills with repeat counter), `components/Labels` (phase / cue labels),
  `components/Cards` (chapter, end, outro with the Flow-ring motif), `components/Ticks` + `tools/sfx.py` (indicator
  tick), `components/Sound` (music segments + ducking, narration), `components/Backdrop`, `components/Grain`.
  `tools/verify.py --check-plan` measures every line with the real font against its zone width, so nothing can sit on
  top of anything else.
* **Copy.** Every Korean line lives in `tools/lines.py` (narration, call-out labels, story phases, console cue steps,
  hashtags). Keep claims truthful: contest entry, emulator recording, 3D simulation, not on the market (the end card
  footer says so).

## Changing things

* Different wording → `tools/lines.py`, re-run `make-ad.ps1` (narration is regenerated).
* Different cut → `tools/plan.py` (`ad()`, `short()`, `demo()` and the `*_keys()` camera lists); `npx remotion still`
  renders single frames in ~30 s; `npx remotion studio` scrubs the timeline.
* New camera → `CAM` in `src/scene3d/camera.ts` (position, look-at, fov); `insideness()` decides when the glass and
  the studio rig fade.
* Different look → lights and effects in `src/scene3d/Scene.tsx`, paint and lamps in `CarModel.tsx`. Every camera
  preset can be judged from one still each: `npx remotion render src/index.ts Preset3D out/pre --sequence --scale 0.5`.
* Different grid → `src/layout.ts` and the matching `ZONES` table in `tools/verify.py`.
* A run with a different scenario still renders: beats that cannot find their anchor fall back to defaults in
  `plan.py:anchors()`; a run without `console_clean.mp4` skips the console chapter; without `remix_clean.mp4` the
  remix beat is skipped; without a TTS voice the films render without narration (`narration.py` exits 2 and
  `make-ad.ps1` continues). The signals export needs the JDK/Gradle toolchain once; the JSON is then cached in
  `public/run/`.

## Known limits

* The car is a nameless concept coupe (no badge, no plate, no brand design; attribution in `CREDITS.md` and on the
  end card); its wipers do not move; the road is a straight night expressway. The Virtual
  Console chapter of the full demo shows the emulator's blind-spot camera inset as recorded.
* `useOffthreadVideoTexture` (the recording on the car display) only works while rendering; the Remotion Studio
  preview shows a still of the app pane instead.
* Rendering uses Remotion's bundled Chrome Headless Shell with the ANGLE GL backend (`remotion.config.ts`);
  `backdrop-filter` and `mix-blend-mode` are Chromium features.
