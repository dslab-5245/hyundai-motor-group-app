# 시연 영상 · 숏폼 스토리보드 — Drive Symphony

공모전 산출물 기준(2026-10-31 사이트 확인): 시연 영상은 **선택·가산점**(목업·프로토타입·화면 녹화 인정, 길이·형식 규정 없음), 숏폼 링크는 선택(가산점 없음, 별도 "숏폼 챌린지 어워드": 세로 30초~1분, 릴스/쇼츠, 해시태그 2~3개에 **`#HMG차량용앱개발공모전` 필수**, 권장 `#PleosAppMarket #HMG앱공모전`, Spot→Solve→Scene 구조). 블라인드 심사: 영상·링크·자막에 성명·소속·연락처·얼굴 없음.

결론: **① 심사용 시연 영상(16:9, 3~4분)은 화면 녹화 + 합성 음원으로 자동 제작**한다(아래 파이프라인). **② 광고형 릴스(9:16, 45초)는 AI 주행 B-roll + UI 합성**으로 만들고, AI 도구가 없으면 파이프라인이 만드는 `short_9x16.mp4`(UI + 자막 + 해시태그)를 그대로 쓴다. 실제 차에서 직접 시연하는 영상은 **필수가 아니다** — 공모전이 화면 녹화·프로토타입을 인정하고, 에뮬레이터 Virtual Console 구간이 "실제 차량 신호 경로"를 보여 준다.

## 1. 파이프라인 (자동)

```powershell
# 에뮬레이터 부팅 → release APK 설치 → 데모 스토리 106초 화면 녹화 + 앱 내부 WAV 탭 → Virtual Console 큐시트 85초 → 동기화·자막·컷
powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\make-demo-video.ps1
# 결과: build\video\<timestamp>\demo_video_16x9.mp4, short_9x16.mp4, story_clean.mp4, console_clean.mp4, *.srt, README.txt
```

- 소리: 에뮬레이터 스피커를 녹음하지 않는다(끊김·지연). 앱의 `RECORD` 액션이 렌더 블록을 그대로 WAV로 쓰고(`AudioTap`), 글랜스 화면의 **● REC 배지가 클래퍼보드**다 — `video_sync.py`가 배지가 처음 보이는 프레임을 찾아 WAV 샘플 0에 맞춘다(오차 ≤ 1프레임).
- 자막: `STORY_CAPTIONS`(시나리오 타임라인)·`CUE_CAPTIONS`(큐시트 단계, `cue.log`의 호스트 시각)에서 생성. `*.srt`를 고치고 `python -I scripts\video_sync.py <폴더>`만 다시 돌리면 재렌더.
- 라우드니스: EBU R128 측정 후 정적 게인으로 −16 LUFS / −1.5 dBTP(다이내믹스 보존, 가라앉는 구간이 그대로 들린다).
- 타이틀 카드: Pillow + Noto Sans KR. 사람 이름·소속 없음.

## 2. 심사용 시연 영상 구성 (16:9, 약 3분 40초)

| 구간 | 길이 | 내용 | 자막·내레이션 포인트 |
|---|---|---|---|
| 인트로 카드 | 5 s | 앱 이름, 한 줄 소개 | "주행이 연주가 되는 차 · 부드럽게 달릴수록 음악이 풍성해집니다" |
| 시연 1 카드 | 4 s | 데모 시나리오 설명 | 시내 → 습관적 급조작 → 회복 → 고속도로 → 회피 → 비 → 도착 |
| 시연 1 | 113 s | 글랜스 화면 녹화 + 합성 음원 | 6 s 깜빡이 보상 / 20–28 s **브레이크 반복 → 테이프 새그·필터 닫힘** / 44 s 크루즈 드론 / 78 s 블룸 / 86 s 회피는 숨만 고름 / 90 s 비 / 103 s 여정 카드 |
| 시연 2 카드 | 4 s | Virtual Console → SDK | "데모 꺼짐 · 실제 차량 신호 경로" |
| 시연 2 | ~90 s | 콘솔 큐시트 11단계 | 패닝 → 깜빡이 보상 → 20 s 안정 → 회피 1회 → 브레이크 3연타 → 회복 → 와이퍼·크루즈 → 뒷좌석 → P |
| 아웃트로 카드 | 7 s | 기능 요약 3줄 | 신호 7종 실시간 작곡 / 가라앉을 뿐 침묵·꾸중 없음 / Gleo 4종 · 글랜스 · CPU 5% 미만 · 네트워크 없음 |

내레이션(선택): 자막만으로 충분하도록 썼다. 목소리를 넣는다면 TTS보다 자막 유지가 블라인드 규정에 안전하다.

## 3. 광고형 릴스 (9:16, 45초) — AI B-roll + UI 합성

구조(공모전 권장 Spot → Solve → Scene):

| 시간 | 샷 | 화면 | 자막 |
|---|---|---|---|
| 0–6 s Spot | AI: 저녁 강변도로, 운전자 손과 핸들(얼굴 없음), 실내 조명 | 풀샷, 대시보드 중앙 디스플레이는 어둡게 | "운전 중 음악은 늘 같은 곡을 틀어 줄 뿐이었다" |
| 6–12 s | AI: 급제동 순간, 브레이크등 반사 | 디스플레이에 UI 합성: Flow 링이 주황으로, 캡션 "브레이크 반복" | "급제동을 하면 — 음악이 잠시 가라앉는다" (소리: 테이프 새그) |
| 12–24 s Solve | AI: 부드러운 주행, 가로등 흐름 | UI 합성: 링이 녹색으로 차오르고 레이어 점이 하나씩 켜짐 | "부드럽게 달릴수록 베이스·키·드럼이 차례로 쌓인다" |
| 24–32 s | AI: 깜빡이 점멸, 완만한 우회전 | UI: "깜빡이 켜고 회전" 캡션 | "깜빡이를 켜고 돌면 즉시 보상" |
| 32–40 s | AI: 고속도로, 와이퍼 | UI: 비 레이어 점 + 드론 | "와이퍼는 빗소리 텍스처, 크루즈는 드론 베드" |
| 40–45 s Scene | AI: 주차 후 실내, 디스플레이에 '여정의 곡' 카드 | 카드 풀샷 → 로고 | "오늘 당신이 연주한 곡 · Drive Symphony" + 해시태그 |

소리: `story_clean.mp4`의 합성 음원(20–28 s 새그 구간을 6–12 s에, 44–60 s 블룸 구간을 12–32 s에 배치). 자막은 `short.srt` 문구를 재사용.

### AI 영상 프롬프트 (Veo 3.1 / Kling 3.0, 영어로 입력, 각 6~8초, 9:16)

공통 접두: `cinematic, vertical 9:16, evening, soft tungsten interior light, shallow depth of field, no faces, no text, no logos, realistic Korean riverside expressway, right-hand traffic, left-hand drive sedan`

1. Spot: `Over-the-shoulder shot from the rear seat: a driver's hands on the steering wheel of a modern sedan at dusk on a riverside expressway, the center infotainment display is dark, city lights streak past, calm, 24fps, slow dolly in`
2. 급제동: `Interior shot, the driver's foot presses the brake pedal hard, dashboard lights reflect on the windshield, brake lights of the car ahead flare red, slight camera shake, 2 seconds of tension then release`
3. 부드러운 주행: `Smooth steady driving at night, street lamps passing rhythmically, the center display glows softly (blank screen, to be replaced), hands relaxed on the wheel, dreamy, gentle motion`
4. 깜빡이 회전: `Close-up of the turn-signal stalk being flicked, the dashboard indicator blinking green, then a gentle right turn onto a quiet street, warm interior`
5. 비·와이퍼: `Rain on the windshield at night, wipers sweeping, bokeh of tail lights, the center display visible between the wipers (blank, to be replaced)`
6. 도착: `Car parked in an underground garage, engine off, the driver's hand resting on the gear selector, the center display is the only light source (blank, to be replaced), peaceful`

디스플레이는 **빈 화면(blank screen)으로 생성**하고 UI를 합성한다. 합성은 ffmpeg `perspective`(UI 4점 매핑) 또는 CapCut/Premiere의 코너핀. 예:

```powershell
# UI 클립(1920x1080)을 B-roll의 디스플레이 영역(네 모서리 좌표 x0:y0 … x3:y3, 좌상·우상·좌하·우하)에 코너핀 합성
ffmpeg -i broll_02.mp4 -i story_clean.mp4 -filter_complex "[1:v]scale=1920:1080,format=rgba,perspective=x0=420:y0=880:x1=660:y1=860:x2=430:y2=1040:x3=665:y3=1025:sense=destination[ui];[0:v][ui]overlay=0:0:shortest=1" -c:a copy out_02.mp4
```

(실제 좌표는 B-roll 프레임을 한 장 뽑아 측정. 디스플레이 밝기 차이는 `eq=brightness=-0.05` 정도로 맞춘다.)

### 게시 문구 (숏폼 챌린지)

> 급제동하면 음악이 가라앉고, 부드럽게 달리면 다시 피어납니다. 차량 신호가 실시간으로 작곡하는 드라이브 심포니.
> #HMG차량용앱개발공모전 #PleosAppMarket #HMG앱공모전

## 4. 사람이 할 일

1. `make-demo-video.ps1` 실행 → `build/video/<ts>/demo_video_16x9.mp4`를 보고 자막 타이밍·문구 확인(`*.srt` 수정 → `video_sync.py` 재실행).
2. 유튜브 **미등록(unlisted)** 업로드 → 접수양식 ⟦시연 영상 링크⟧. 제목·설명에 이름·소속 금지.
3. (선택) Veo/Kling로 B-roll 6컷 생성 → 합성 → 릴스/쇼츠 업로드(세로, 45초, 해시태그 3개) → ⟦숏폼 링크⟧. 없으면 `short_9x16.mp4` 업로드.

## 5. 광고급 영상 파이프라인 (video/ad · scripts/make-ad.ps1)

위 1~4절의 화면 녹화 + 자막 카드 방식 대신, 같은 런 폴더에서 **Remotion**으로 렌더하는 광고급 버전이 `video/ad/`에 있다
(자세한 사용법은 `video/ad/README.md`, 푸티지·폰트·음성 출처는 `video/ad/CREDITS.md`).

```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\make-ad.ps1 -Run C:\Users\user\dev\drive-symphony\build\video\<timestamp>
# 결과: build\ad\<timestamp>\ad_16x9.mp4 (약 78초), short_9x16.mp4 (약 49초, 해시태그 포함), demo_full_16x9.mp4 (약 3.9분, 내레이션)
#       + narration.wav, events.json, plan.json, contact_sheet.png, event_frames_*.png, verify.json
```

- 이벤트 콜아웃은 `logcat.txt`의 `drive event` 줄을 `sync.json`의 REC 배지 시각으로 클린 영상 시간에 맞춘다(±1프레임).
- 음악은 앱 자체 WAV, 내레이션(edge-tts 한국어)은 음악을 −6 dB 덕킹. 최종 −16 LUFS / −1.5 dBTP.
- 주행 B-roll은 Mixkit 무료 라이선스 7컷(얼굴·번호판·로고 없음), 디스플레이 패널 뒤에 어둡게 깔리며 앱 영상으로 제시하지 않는다.
