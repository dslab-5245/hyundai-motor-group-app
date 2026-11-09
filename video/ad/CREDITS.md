# Credits — footage, fonts, voice

Everything below is licence-safe for a public contest video without attribution; it is listed here anyway.

## Driving POV b-roll (Mixkit)

All seven clips are published under the **Mixkit Stock Video Free License** (https://mixkit.co/license/#videoFree):
free for commercial and personal projects, no attribution required, the clips may be edited and composited; they may
not be redistributed as stock on their own. Each clip's page states the licence; a Restricted-License clip cannot be
downloaded at 1080p by `tools/broll.py`, which rejects the stub Mixkit returns for it (16519, 47615, 47664, 17304,
26253 were rejected that way and are not used). The clips are used dimmed and blurred behind the car display and as
cutaways; they are never presented as footage of the app in a car. None shows a face, a readable plate or a logo.

| id | title | page | use |
|---|---|---|---|
| 42039 | View from inside a car in night traffic | https://mixkit.co/free-stock-video/view-from-inside-a-car-in-night-traffic-42039/ | hook, reveal, stumble, intro |
| 42032 | Driving down an avenue at night | https://mixkit.co/free-stock-video/driving-down-an-avenue-at-night-42032/ | city cutaways |
| 42034 | Driving along a city at night | https://mixkit.co/free-stock-video/driving-along-a-city-at-night-42034/ | city cutaways |
| 42037 | View out of a car window at night | https://mixkit.co/free-stock-video/view-out-of-a-car-window-at-night-42037/ | recovery, grace, bloom |
| 42038 | Driving through a night city | https://mixkit.co/free-stock-video/driving-through-a-night-city-42038/ | highway |
| 2846 | Window on a Rainy Day | https://mixkit.co/free-stock-video/window-on-a-rainy-day-2846/ | rain / remix beat |
| 41575 | Traveling on a nature road at dusk | https://mixkit.co/free-stock-video/traveling-on-a-nature-road-at-dusk-41575/ | arrival |

Source URL pattern: `https://assets.mixkit.co/videos/<id>/<id>-1080.mp4`. Total 324 MB; downloaded into `public/broll/`
(git-ignored).

## Fonts

- **Pretendard 1.3.9** (Kil Hyung-jin), SIL Open Font License 1.1 — https://github.com/orioncactus/pretendard/releases/tag/v1.3.9
  (`public/fonts/`, git-ignored; `scripts/make-ad.ps1` downloads it). Falls back to Noto Sans KR / Malgun Gothic from `C:/Windows/Fonts`.

## Voice

- Korean narration synthesised with **edge-tts** (Microsoft Edge neural voice `ko-KR-HyunsuMultilingualNeural`; fallback
  InJoon / SunHi). No person is recorded; the script is `tools/lines.py` (blind review: no names, no affiliations).

## Music

- The app's own engine output, recorded inside the app during the capture run (`ds_story.wav`, `ds_console.wav`). No
  third-party music.
