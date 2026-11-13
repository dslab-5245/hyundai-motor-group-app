# Drive Symphony (드라이브 심포니)

**안전하게 달릴수록 풍성해지는, 차가 연주하는 음악.** 차량 신호(조향·브레이크·방향지시등·와이퍼·기어·주행 모드·크루즈·좌석)와 내비 정보(도로명·제한속도·회전·주의 구간)로 로파이·재즈·앰비언트 음악을 실시간 작곡하는 Pleos Connect(Android Automotive OS) 앱. 부드럽게 달리는 동안 레이어가 열리고, 급조작이 반복되면 0.12초 안에 음악이 잠시 가라앉는다. 침묵·경고음·꾸중은 없다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작.

| | |
|---|---|
| 시연 영상 | 미등록(unlisted) 링크는 접수양식 7번에 기재 (4분 54초, 3D 주행 시뮬레이션 인트로 + 에뮬레이터 화면 녹화 + 앱이 렌더한 음원) |
| **APK** | [`apk/DriveSymphony_DriveSymphony_release.apk`](apk/DriveSymphony_DriveSymphony_release.apk) · SHA-256 은 같은 폴더의 `.sha256` |
| 패키지 / 버전 | `io.drivesymphony.app` · 0.9.0 (versionCode 1) · minSdk 28 · targetSdk 34 |
| 개인정보처리방침 | [`docs/privacy-policy.md`](docs/privacy-policy.md) (앱에 네트워크 권한이 없고, 여정 요약만 차량 내 앱 전용 저장소에 남는다) |
| 라이선스 | 소스 MIT (`LICENSE`). 악기 음은 MIT SoundFont FluidR3_GM에서 추출한 샘플 뱅크(4.8 MB, `LICENSE-FluidR3.txt` 동봉) + 합성 서브 레이어이며 뱅크가 없으면 합성 악기로 폴백. 변주 모드 내장 샘플 곡은 CC0(`demo_track_LICENSE.txt`). 그 밖의 저작권 음원은 없다 |

---

## 1. 실행 방법 (심사용 — 차량 없이 10분)

### 1-1. 설치

```bash
# Pleos Connect 에뮬레이터(AVD)를 부팅한 뒤
adb install -r apk/DriveSymphony_DriveSymphony_release.apk
adb shell monkey -p io.drivesymphony.app -c android.intent.category.LAUNCHER 1   # 또는 런처에서 'Drive Symphony' 아이콘
```

Pleos 홈은 부팅 직후 1~2초 뒤 내비 앱을 앱 슬롯에 올리므로, **에뮬레이터가 완전히 켜진 뒤** 앱을 실행한다. 앱이 내비 뒤로 가려져 회색 화면만 보이면 런처에서 한 번 더 실행하면 된다.

### 1-2. 핵심 루프 확인 (Virtual Console 사용, 데모 끔)

1. 앱 실행 → 상단 탭 **작곡실** → **연주 시작**. 2초 안에 음악이 나온다(200 ms 프리롤 뒤 페이드인). 장르 기본값 로파이.
2. Virtual Console → Motion → **Gear D**. 상단 탭이 **연주**(주행 중 글랜스)로 바뀌고 설정이 닫힌다(Distraction Optimized).
3. **Steering** 을 ±30° 로 천천히 돌리면 멜로디가 좌우로 따라온다(패닝).
4. **Turn signal L** → Steering 160° 로 2초 유지 → 글랜스에 "깜빡이 좋아요 · 레이어가 열려요".
5. 30초 가만히 두면 베이스·패드·멜로디가 차례로 열리고 Flow 링이 90% 를 넘는다.
6. Steering 을 한 번 급히 꺾고(±200° 이상, 빠르게) **Brake** 를 짧게 → "긴급 회피로 봤어요, 잠깐 숨만 고를게요" (30초 무사고 뒤 첫 급조작은 유예).
7. 20초 뒤 **Brake** 를 8초 안에 3번 연타 → 0.12초(+오디오 버퍼) 안에 음악이 테이프가 늘어지듯 가라앉고 링이 주황으로("브레이크 반복 · 음악이 잠시 가라앉아요"). 끊기지는 않는다(바닥 0.1).
8. 5초 가만히 → 레이어가 하나씩 돌아온다.
9. **Wiper ON** → 비 텍스처·셰이커. **Cruise ON** → 드론 베드. **Seat → rear_left Occupied** → 작곡실 '뒷좌석' 행이 "탑승 → 강도 약".
10. **Gear P** → 마무리 1마디 뒤 작곡실에 **여정의 곡** 카드(제목·평균 Flow·최장 부드러운 구간·멜로디가 열린 시간) → **여정 요약 공유**(이미지 카드).

### 1-3. Virtual Console 없이 확인 (데모 시나리오)

작곡실 → **데모 시나리오** 토글 ON → "시연 스토리"(106초: 시내 → 깜빡이 보상 → 습관적 급조작 → 회복 → 크루즈 → 86초 긴급 회피 → 비 → 도착 P). 데모가 켜져 있는 동안 글랜스에 **DEMO** 표시가 붙고, 데모는 차량·내비 신호 소스를 멈춘다(끄면 복귀). 기본값은 꺼짐.

### 1-4. 음성(Gleo) 명령 — 인텐트로 대체 확인

```bash
adb shell am startservice -n io.drivesymphony.app/.audio.SymphonyService -a setGenre --es ACTION_PARAM '{"genre":"JAZZ"}'
adb shell am startservice -n io.drivesymphony.app/.audio.SymphonyService -a howIsMyDriving
adb logcat -s SymphonyService   # 응답: functionName/status/message/data, 브로드캐스트 io.drivesymphony.GLEO_CALLBACK
```

스키마는 `app/src/main/assets/schema.json`(함수 4종: `startSymphony`, `setGenre`, `howIsMyDriving`, `pauseSymphony`, 공식 라디오 예제 형식).

### 1-5. 그 밖의 심사 항목

- 오디오 포커스: 다른 미디어 앱을 재생하면 멈추고(LOSS), 내비 안내 중(CAN_DUCK)엔 −12 dB 로 줄었다가 돌아온다. LOSS_TRANSIENT 뒤 GAIN 시 재개.
- 미디어센터(MediaSession)에서 재생/일시정지/장르 전환.
- 최근 앱에서 스와이프 제거 → 재생 정지·알림 제거.
- 부하: `python -I scripts/cpu-share.py --adb adb` 또는 `adb shell top -n 1 | grep drivesymphony`. 작곡실 '오디오 부하' 행과 logcat `audio load`(10초마다). 4% 를 3초 넘기면 라이트 프로파일로 자동 전환.
- 내 음원 변주하기: 작곡실 → **기존 음악 변주하기** → 시스템 문서 선택기에서 로컬 음원 선택 → 같은 flow 규칙으로 로우패스·테이프 새그·음량·폭이 변한다. 음원이 없으면 작곡실의 **샘플 곡으로 체험하기**(내장 CC0 피아노 72초, J.S. 바흐 골드베르크 아리아)를 누르면 된다. 터미널에서는 `adb shell am startservice -n io.drivesymphony.app/.audio.SymphonyService -a io.drivesymphony.action.REMIX_DEMO --ez on true`.

## 2. 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| 검증 환경 | Pleos Connect Emulator **v2.1.0** (Android Automotive, API 34, x86_64, 4 vCPU, RAM 4 GB, Cold boot) + Virtual Console. 2026-10-31 release APK 로 설치·재생·Gleo 인텐트·Virtual Console 실신호(조향·브레이크·방향지시등·와이퍼·크루즈·기어·좌석·벨트)·크래시 0·언더런 0 확인 |
| 측정값(같은 날) | 호스트 GPU: 오디오 스레드 2.6~3.5%, 프로세스 합계 4.6~4.7%(1코어 기준), RSS 115~119 MB. 소프트웨어 GPU: 오디오 3.6~3.8%, 합계 10.0~12.5%(1코어 기준 = 4 vCPU 장치 기준 2.5~3.1%), RSS 161~170 MB |
| 에뮬레이터 설치 | Android Studio SDK Manager → SDK Update Sites 에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` 추가 → API 34 Pleos 시스템 이미지 → Automotive 폼팩터 AVD, Cold boot, RAM 4 GB. (Mac 은 `crp-arm64-sys-img.xml`) |
| 빌드 | JDK 17, Android SDK Platform 35 + Build-Tools, Gradle wrapper 포함. `./gradlew :engine:test` (단위 테스트 64개, Android 불필요) · `./gradlew :app:assembleRelease` (디버그 키 서명 release) · `./gradlew :engine:renderPreviews` (프리뷰 WAV 11개 → `previews/`). Android SDK 가 없으면 `:app` 은 자동 제외되고 엔진만 빌드된다. 샘플 뱅크(`engine/src/main/resources/samples/fluid_lofi.dsb`)는 저장소에 포함되어 있어 별도 다운로드가 없다 |
| SDK 의존성 | `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `navi-data:3.0.4` (Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`) — Pleos Playground 계정으로 접근 |
| 권한 | `pleos.car.permission.CAR_DRIVE / CAR_LIGHTS / CAR_WINDOWS / CAR_SEATS / NAVI_ROUTE`, `android.car.permission.CAR_POWERTRAIN`(기어 속성), `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)`, `POST_NOTIFICATIONS`. **INTERNET·위치·저장소·마이크 권한 없음** |
| 서명 | 공모전 심사용 release 빌드를 **디버그 키**로 서명했다(설치 가능, 마켓 배포용 키 아님). `apksigner verify --print-certs` 로 확인 가능 |
| 필요한 설정 | 없음(계정·서버·API 키 불필요). 내비 정보는 Pleos 내비가 실행 중일 때만 들어오며, 없으면 과속·급회전 예고 규칙만 꺼진다 |

### 에뮬레이터의 알려진 제약(앱이 자동 보정)

- 에뮬레이터 내비는 한 좌표를 반복 보고해 추정 속도가 0 이 된다 → 같은 좌표가 8초 이상 반복되면 속도를 '모름'으로 두고 급조향 기준만 1.5배로 올린 채 그대로 채점한다(Virtual Console 입력에 바로 반응).
- 운전석 안전벨트를 채울 방법이 없다 → 속도를 모를 때는 벨트 알림을 내지 않는다(실차에서는 15 km/h 이상에서만 30초마다 짧은 알림).
- Vehicle SDK 2.3.2 의 `SEAT_OCCUPANCY` Boolean 매핑이 AOSP 값(1=VACANT, 2=OCCUPIED)과 반대라 앱이 뒤집어 읽는다.

## 3. 구성

```
drive-symphony/
├─ engine/   순수 Kotlin/JVM 음악 엔진 (dsp(샘플러 포함) · theory · music · director · remix · scenario · tools) — 단위 테스트 64개
│   └─ src/main/resources/samples/  FluidR3_GM(MIT)에서 추출한 샘플 뱅크 fluid_lofi.dsb 4.8 MB + 매니페스트 + LICENSE-FluidR3.txt
├─ app/      Android Automotive 앱 (audio: SymphonyService/AudioOutput · signals: Vehicle SDK/CarProperty/NaviHelper/Scenario · remix · ui: FlowScreen/ComposerScreen · ux: CarUxRestrictions)
│   └─ src/main/assets/  schema.json(Gleo) · demo_track.ogg(CC0 샘플 곡 72초) · demo_track_LICENSE.txt
├─ apk/      제출용 release APK + SHA-256
├─ previews/ A/B 비교 음원 (lofi_ab_clean.wav · lofi_ab_event.wav: 20.3초까지 동일, 그 뒤 0.12초 안에 갈라짐)
├─ store/    아이콘 512, 스크린샷 2560×1380, 앱마켓 등록 텍스트 listing.md
├─ docs/     privacy-policy.md · pt-cue-sheet.md · README_dev.md(개발 상세)
├─ video/    STORYBOARD.md (시연 영상·숏폼 콘티)
└─ scripts/  package-release.ps1 · emulator-smoke.ps1 · make-demo-video.ps1 · virtual-console.py · cpu-share.py · check_consistency.py
```

동작 원리: Vehicle SDK 리스너 9종 + NaviHelper 콜백을 25 Hz 로 스냅샷 → `DrivingScorer` 가 flow(0~1)를 만든다(하강 0.6 s, 상승 5 s, 30초 무사고 뒤 첫 급조작은 긴급 회피로 유예, 과속은 2초 지속 시만, 바닥 0.1) → `MusicDirector` 가 flow 를 레이어 수·컷오프·테이프 워블·리버브·스테레오 폭·멜로디 개방으로 매핑 → `Arranger`/`SymphonyEngine` 이 8마디 A-A-B-A 형식·모티프 call & response 로 샘플 단위 렌더. 유예되지 않은 감점은 **스텀블**(테이프 새그 0.5초 + 드럼·멜로디 즉시 정지 + 마스터 로우패스 450 Hz 잠수, 복귀는 라이저 뒤 다운비트), 깜빡이 켠 회전은 5음 상행 아르페지오 스파클, 키·멜로디는 핸들을 따라 패닝, 마디 템포는 flow 가 낮을수록 최대 10% 처진다. 같은 도로명은 같은 키·진행·모티프(이 길의 테마). 상세는 `docs/README_dev.md`.

## 4. 제약과 정직한 표시

- Vehicle SDK 2.3.2 에는 차속·가속페달·브레이크 강도가 없다. 속도는 NaviHelper 위치(1 Hz)로 추정(σ=3 m 에서 RMS 1.8 km/h)하며 핵심 채점은 조향·브레이크·방향지시등 3종이다.
- NaviHelper 는 Pleos Connect 내비 전용이다. 다른 내비를 쓰면 도로명이 없어 곡 제목이 시간대만으로 붙는다.
- HUD·클러스터 투사 API 는 서드파티에 열려 있지 않아 "HUD" 는 중앙 화면 글랜스 레이어로 구현했다.
- 리믹스 모드는 앱이 직접 재생하는 로컬 음원에만 적용된다.
- TTS/STT/Vehicle Control SDK 는 '준비 중'이라 쓰지 않는다. Gleo 연동은 스키마 작성·인텐트 처리까지이며 실제 음성 호출은 HMG 측 활성화 절차가 필요하다.
- 악기는 MIT 샘플 뱅크(Rhodes·바이브라폰·베이스·드럼 킷) + 합성 서브 레이어이며, 뱅크가 없으면 코드 합성 악기로 폴백한다(2026-11-03). 상용 로파이 음반 수준의 믹스는 2차 PT 까지의 고도화 항목이다.

## 5. 오픈소스 고지

| 구성요소 | 라이선스 |
|---|---|
| Kotlin stdlib · kotlin-test · kotlinx-coroutines | Apache-2.0 |
| AndroidX core-ktx · lifecycle-runtime-ktx · activity-compose · media · Compose(ui · foundation · material3, BOM) | Apache-2.0 |
| Pleos Vehicle SDK 2.3.2 · NaviHelper 3.0.5 · navi-data 3.0.4 | Pleos Playground 이용약관 |
| 샘플 뱅크 `engine/src/main/resources/samples/fluid_lofi.dsb`: FluidR3_GM SoundFont(Frank Wen, 2000-2002, 2008)에서 추출한 Rhodes·베이스·바이브라폰·스트링·패드·드럼 킷 샘플 4.8 MB | MIT (원문 `LICENSE-FluidR3.txt` 동봉, `scripts/sf2_extract.py` 로 재생성) |
| 영상 제작용 3D 차량 모델 `video/ad/public/models/carconcept/CarConcept.glb`(앱·APK에는 포함되지 않음): "Car Concept", Khronos glTF Sample Assets, © 2024 Darmstadt Graphics Group GmbH. 색·등화 수정, 로고 미사용. 상세는 `video/ad/CREDITS.md` | CC BY 4.0 |
| 내장 샘플 곡 `app/src/main/assets/demo_track.ogg`: J.S. Bach, Goldberg Variations BWV 988 — Aria, Kimiko Ishizaka 연주 'Open Goldberg Variations'(2012), 72초 발췌 | CC0 1.0 퍼블릭 도메인 헌정 (`demo_track_LICENSE.txt`, archive.org/details/OpenGoldbergVariations) |
| 빌드·자료 생성 도구(앱에 포함되지 않음): Gradle, Pillow · numpy · scipy | Apache-2.0 / HPND · BSD |

리버브는 Freeverb 알고리즘(퍼블릭 도메인 튜닝값)을 직접 구현했다. 위 두 항목 외의 외부 코드·샘플·저작권 음원은 없다. 커밋 작성자는 블라인드 심사를 위해 `Drive Symphony <dev@drivesymphony.local>` 로 통일되어 있다.
