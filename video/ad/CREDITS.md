# Credits — 3D scene, environment map, fonts, voice, sound

Everything below is licence-safe for a public contest video without attribution; it is listed here anyway.

## 3D scene (own work, no stock footage)

The car, the cabin, the road, the lamps, the rain: all procedural three.js geometry written in `src/scene3d/`
(`Car.tsx`, `Cockpit.tsx`, `World.tsx`, `Scene.tsx`, `camera.ts`). No third-party models and no stock video. The car
is a nameless study — no badge, no plate, no brand design — and it is driven by the engine's own per-frame signals of
the demo scenario (`public/run/signals_story.json`, exported by `gradle :engine:dumpSignals`), so the steering wheel,
pedals, indicator, wipers, brake lights and the Flow glow are frame-exact with the music. The centre display shows the
real emulator recording as a video texture (`src/components/Scene3D.tsx`). The end card says so: contest entry,
emulator screen recording, 3D simulation, not on the market.

## Environment map (Poly Haven, CC0)

| file | source | licence |
|---|---|---|
| `public/hdri/moonless_golf_1k.hdr` | https://polyhaven.com/a/moonless_golf | CC0 1.0 (https://polyhaven.com/license) — no attribution required |

Used as the image-based light for the night road and the paint reflections (`<Environment>` in `src/scene3d/Scene.tsx`);
the long studio highlights are rect area lights of the scene itself.

## Fonts

- **Pretendard 1.3.9** (Kil Hyung-jin), SIL Open Font License 1.1 — https://github.com/orioncactus/pretendard/releases/tag/v1.3.9
  (`public/fonts/`, git-ignored; `scripts/make-ad.ps1` downloads it). Falls back to Noto Sans KR / Malgun Gothic from `C:/Windows/Fonts`.

## Voice

- Korean narration synthesised with **edge-tts** (Microsoft Edge neural voice `ko-KR-HyunsuMultilingualNeural`; fallback
  InJoon / SunHi). No person is recorded; the script is `tools/lines.py` (blind review: no names, no affiliations).

## Music and sound

- The app's own engine output, recorded inside the app during the capture run (`ds_story.wav`, `ds_console.wav`,
  `ds_remix.wav`). The remix segment plays the bundled public-domain piano sample (Open Goldberg Variations, CC0; see
  `app/src/main/assets/demo_track_LICENSE.txt`) through the app's own variation path. No third-party music.
- The indicator tick is synthesised by `tools/sfx.py` into `public/gen/tick.wav` (two short filtered clicks at the
  blinker's 1.5 Hz phase changes). No sound library.
