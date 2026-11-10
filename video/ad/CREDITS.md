# Credits — 3D scene, environment map, fonts, voice, sound

Everything below is licence-safe for a public contest video. One asset (the car model) requires attribution; the
end card of every film and the upload description carry it.

## Car model (CC BY 4.0, attribution required)

| file | source | licence |
|---|---|---|
| `public/models/carconcept/CarConcept.glb` | "Car Concept", Khronos glTF Sample Assets — https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/CarConcept | © 2024, Darmstadt Graphics Group GmbH, CC BY 4.0 (model and textures: Eric Chadwick). Started from the public-domain (CC0) "Free Concept Car 004" by Unity Fan. |

The licence excludes the Khronos and 3D Commerce logos, so they are not shown: `src/scene3d/CarModel.tsx` hides the
logo plate (`License_Plate`) and the steering-wheel emblem (`InteriorSteeringEmblem`) and drops the lettered tyre
sidewall texture. Changes made to the model (CC BY "indicate if changes were made"): repainted deep navy with dark
upholstery, glass replaced by a plain tinted material, brake/indicator/head lights and the cluster glow driven by the
drive signals, wheels, steering wheel and pedals animated, a floating centre display added. The car carries no badge,
no plate and no brand design.

## 3D scene (own work, no stock footage)

The road, the lamps, the skyline, the rain, the lighting and the camera are procedural three.js written in
`src/scene3d/` (`World.tsx`, `Scene.tsx`, `camera.ts`, `CarModel.tsx`). No stock video. The car is driven by the
engine's own per-frame signals of the demo scenario (`public/run/signals_story.json`, exported by
`gradle :engine:dumpSignals`), so the steering wheel, pedals, indicators, brake lights and the Flow glow are
frame-exact with the music. The centre display shows the real emulator recording as a video texture
(`src/components/Scene3D.tsx`). The end card says so: contest entry, emulator screen recording, 3D simulation, not on
the market.

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
