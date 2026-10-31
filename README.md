# Drive Symphony — 주행이 연주가 되는 차

> Pleos App Market 공모전 출품작. 차량 신호(조향·브레이크·방향지시등·와이퍼·기어·주행모드·크루즈)와 내비 정보(제한속도·회전·주의구간)로 **로파이 · 재즈 · 앰비언트** 음악을 실시간 생성한다. **부드럽고 안전하게 달릴수록 음악이 풍성해지고, 급조작·과속이 반복되면 음악이 가라앉는다** (절대 거친 왜곡이나 침묵으로 벌하지 않는다). 내 음원을 재생하면서 같은 규칙으로 변주하는 **리믹스 모드**도 있다.

## 구성

```
drive-symphony/
├─ engine/   순수 Kotlin/JVM 음악 엔진 (Android 의존 없음, 어디서나 빌드·테스트)
│   ├─ dsp/        오실레이터·필터·ADSR·딜레이·테이프 워블·비트크러시·리버브·리미터
│   ├─ theory/     코드 퀄리티·보이싱·장르별 진행·키 선택
│   ├─ music/      악기(FM 일렉피아노·베이스·패드·드럼·텍스처), 16스텝 스윙 클락, 어레인저
│   ├─ director/   DrivingSignals → DrivingScorer(flow 점수) → MusicDirector(음악 파라미터)
│   ├─ remix/      외부 PCM 변주 프로세서
│   ├─ scenario/   스크립트 주행(안전/거친/시연 스토리) — 테스트·데모·프리뷰용
│   └─ tools/      PreviewMain: 장르×시나리오 WAV 렌더링
└─ app/      Android Automotive(Pleos Connect) 앱
    ├─ audio/      SymphonyService(포그라운드 미디어 서비스·MediaSession·오디오 포커스), AudioOutput(AudioTrack float)
    ├─ signals/    PleosVehicleSource(Vehicle SDK 2.3.2) · CarPropertyGearSource(android.car GEAR_SELECTION) · PleosNaviSource(NaviHelper 3.0.5) · ScenarioSource(데모)
    ├─ remix/      LocalTrackPlayer(MediaCodec 디코드 → 48 kHz 스테레오 float)
    ├─ ui/         FlowScreen(주행 중 글랜스 화면) · ComposerScreen(정차 화면) · Theme(야간 기본 + 주간 팔레트, 차량 야간 모드 추종)
    └─ ux/         DrivingStateObserver(CarUxRestrictionsManager)
```

## 동작 원리 (한 장)

1. **신호 수집** — Vehicle SDK 리스너 9종 + NaviHelper 콜백 6종을 `SignalStore`에 모아 25 Hz로 스냅샷.
2. **점수화** — `DrivingScorer`가 조향 변화율, 브레이크 토글 패턴, 깜빡이 규율, 제한속도 대비 속도(내비 위치 미분), 스쿨존, 안전벨트를 보고 **flow(0~1)** 를 만든다. 하강은 0.6 s, 상승은 5 s. 30초 이상 무사고 후의 첫 급조작과 그 뒤 2.5초 안의 조작은 **긴급 회피로 간주해 감점·펄스 절반**(끼어들기 회피 = 급조향+급제동 한 묶음; 측정: 최저 flow 0.81 vs 같은 조작이 습관일 때 0.61). 과속은 추정 속도가 잡음이므로 **2초 이상 지속**될 때만 반영(내비의 카메라 과속 알림은 즉시). 주차·후진 중엔 채점하지 않는다. 바닥은 0.1 (완전 침묵 없음). 뒷좌석에 사람이 타면 피드백 강도가 자동으로 '약'이 되고 내리면 돌아온다.
3. **음악화** — `MusicDirector`가 flow를 밀도(레이어 수)·컷오프·테이프 워블·바이닐·크러시·리버브·스테레오 폭·게인·화성 확장·멜로디(bloom)로 매핑한다. 급조작 펄스는 **120 ms** 안에 들리고(`EngineResponseTest`: 같은 시드 A/B 렌더에서 이벤트 후 오디오 -3 dB·컷오프 반감까지 120 ms), 회복은 천천히.
4. **연주** — `Arranger`가 마디마다 장르 규칙(로파이 스윙 58%, 재즈 트리오 워킹베이스·라이드, 앰비언트 패드·아르페지오)으로 이벤트를 만들고 `SymphonyEngine`이 샘플 단위로 합성한다. 와이퍼=비 텍스처·셰이커, 깜빡이=비트 위 림 클릭(켜고 회전하면 다음 2마디 동안 레이어 하나 추가), 조향=패닝, 주행모드=에너지, 크루즈=드론(코드 루트 위의 디튠 톱니 베드, 2초 페이드).
   **형식**: 8마디 섹션 A-A-B-A(같은 키의 두 진행, 재즈 B는 트라이톤 대리), 섹션 끝 필(로파이 스네어 빌드·재즈 브러시 드래그·앰비언트 스웰), 첫 2마디 인트로(드럼 없음), 8마디마다 4음 모티프를 만들어 앞 4마디는 제시·뒤 4마디는 응답(call & response). **스테레오**: EP 피치 패닝, 드러머 시점 햇/라이드 패닝, 멜로디 마이크로 더블러 — 안전 주행 시 좌우 상관 0.71~0.87(이전 0.94~0.98), RMS 약 -14 dBFS.
5. **기존 음악 변주하기** — 앱이 직접 재생하는 로컬 음원에 같은 flow 규칙을 건다(`TrackProcessor`: 로우패스·테이프 워블·크러시·리버브·M/S 폭·게인·리미터). 수치 검증(`TrackProcessorTest`): 안전 주행 시 원곡 대비 +0.15 dB·고역 유지 101%·좌우 상관 동일(사실상 무가공), 거친 주행 시 -5.8 dB·고역 5%·폭 축소(상관 0.72→0.81), 상태 전환 시 클릭 없음, 클리핑 없음.
6. **여정의 곡** — 주차(P) 시 평균 flow·최장 부드러운 구간·멜로디가 열린 시간·마디 수를 요약하고, 도로명+시간대로 제목을 붙인다("저녁의 강변북로 (Bb Lo-fi)"; 주행의 30% 이상 와이퍼가 켜져 있었으면 "비 오는 저녁의 강변북로"). 종료 시 토닉으로 해결하는 **카덴차 1마디** 후 새 곡이 시작된다. **이 길의 테마**: 같은 도로명은 항상 같은 키·진행·모티프 시드를 받아 "강변북로의 테마"가 생기고, 최근 10회의 여정은 작곡실 "여정의 곡 기록"에 남는다.

## 빌드

요구: JDK 17, Android Studio(Android SDK 35, Pleos Connect 에뮬레이터 v2.1.0). Android SDK가 없으면 `:app`은 자동으로 제외되고 엔진만 빌드된다.

```bash
# 엔진 단위 테스트 (40개) + 프리뷰 WAV 11개(장르×주행 9 + A/B 2) 렌더링 → previews/ (+ python -I scripts/analyze_previews.py 로 음량·폭·대역 수치 확인)
./gradlew :engine:test :engine:renderPreviews
# 제출 전 정합성 점검: Gleo 스키마 = 매니페스트 액션 = 서비스 상수, 패키지명, 장르 enum, 권한 집합, README 테스트 수
python -I scripts/check_consistency.py

# Android 앱 (Android Studio 설치 후)
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # 디버그 키로 서명된 release APK (공모전 제출용)
# 제출 패키징: 블라인드 확인(커밋 작성자) → 정합성 점검 → 엔진 테스트 → release APK → apk/<팀명>_DriveSymphony_release.apk + sha256 + apksigner verify
powershell -ExecutionPolicy Bypass -File scripts/package-release.ps1 -Team "<팀명>"
```

Pleos 에뮬레이터 설정은 `docs`/공모전 문서 참고 (SDK Update Site: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`, Automotive 폼팩터, Cold boot, RAM 4 GB).

## 시연 (에뮬레이터)

1. 앱 실행 → 작곡실 탭 → **연주 시작**.
2. Virtual Console에서 **Steering**을 천천히 돌리면 패닝, **Brake**를 짧게 반복하면 150 ms 안에 음악이 가라앉고 Flow 링이 주황으로 숨쉰다. 5초간 가만히 두면 레이어가 하나씩 돌아온다.
3. **Turn signal** 켜고 조향 → "깜빡이 좋아요" + 레이어 개방. **Wiper ON** → 비 텍스처. **Gear P** → 여정 요약 카드.
4. 내비 없이 시연할 때는 작곡실 → **데모 시나리오** 켜기 → "시연 스토리"(106초: 시내 → 습관적 급조작 → 회복 → 고속 크루즈 만개 → 86초 긴급 회피 1회(유예: 숨만 고름) → 비 → 도착). 데모는 작곡실에서 끌 때까지 유지되며(기본 꺼짐) 주행 화면에 DEMO 표시가 붙는다.
5. 리믹스: **내 음원 변주하기**로 USB/로컬 음원을 고르면 같은 flow 규칙으로 로우패스·워블·폭이 변한다.

## 제약과 정직한 표시

- Vehicle SDK 2.3.2에는 **차속·가속페달·브레이크 강도가 없다.** 속도는 NaviHelper 위치(1 Hz)를 `SpeedEstimator`(4초 창 현 거리 + EMA)로 추정한다. 합성 궤적 검증: 위치 잡음 σ=3 m에서 오차 RMS 1.8 km/h, σ=5 m에서 3.1 km/h, 정지 후 3초 안에 0. 내비 미실행 시 속도 기반 규칙은 비활성화된다. 권한은 SDK jar의 권한 검사에 1:1 대응하는 5종(`CAR_DRIVE`·`CAR_LIGHTS`·`CAR_WINDOWS`·`CAR_SEATS`·`NAVI_ROUTE` — `getCurrentLocationInfo()`가 NAVI_ROUTE를 검사함)과, Vehicle 2.3.2가 기어 리스너 대신 권고하는 android.car `GEAR_SELECTION` 속성용 `CAR_POWERTRAIN`만 선언한다(기어는 두 경로를 함께 구독).
- NaviHelper는 **Pleos Connect 내비 전용**이다(카카오내비 경로는 읽지 못함).
- 실제 **HUD/클러스터 투사 API는 서드파티에 열려 있지 않다.** "HUD 표시"는 중앙 화면의 글랜스 레이어(Flow 링 + 세 줄)로 구현했고, 주행 중에는 그 화면만 보인다.
- 리믹스 모드는 **앱이 직접 재생하는 음원**에만 적용된다(스트리밍 앱의 오디오는 DRM·오디오 캡처 제한으로 처리 불가). 
- TTS/STT/Vehicle Control SDK는 '준비 중'이라 사용하지 않는다. Gleo 연동은 공식 라디오 예제의 스키마 형식(`packageName`·`appDescription`·`actionCalls[].function{name,description,parameters,component}`)으로 `assets/schema.json`에 작성했고, 앱은 함수명을 액션으로 하는 인텐트의 `ACTION_PARAM`(JSON) extra를 받아 처리한 뒤 `functionName/status/message/data` 응답을 만든다. 응답 반환 채널은 공개 문서에 없어 브로드캐스트(`io.drivesymphony.GLEO_CALLBACK`)+로그로 두었고, Gleo 심사·활성화는 HMG 측 절차다.
- Pleos Music/Audio 앱 규정 반영: MediaBrowserService + MediaSession(play/pause/stop/skip/playFromMediaId/playFromSearch), `android:appCategory="audio"`, `androidx.car.app.launchable`, 오디오 포커스 정책(LOSS 즉시 정지·포커스 반납, LOSS_TRANSIENT(Gleo·통화) 즉시 정지 후 GAIN 시 재개, CAN_DUCK 앱 자체 -12 dB 램프 감쇠), 작업 스와이프 시 stop→stopSelf→super, 주행 중 화면은 애니메이션 없이 단계값만 갱신(5점 단위 Flow 링·on/off 할로).
- 주행 중 비내비 앱 CPU 예산(5% 미만)은 에뮬레이터/실차에서 측정해야 한다. 데스크톱 JVM 실측은 단일 코어 1.0~1.3%(장르별; 4096점 사인 테이블·필터 계수 캐시·곱셈 포락선·재귀 LFO로 2.3~2.8%에서 절반으로 줄였고 소리는 동일). 차량에서 예산을 넘기면 자동으로 **라이트 프로파일**로 내려간다: 오디오 스레드가 자기 렌더 부하를 재서 4%를 3초 넘기면 리버브를 1채널(미드) 공유로 바꾸고 멜로디 더블러를 끈다(0.3초 크로스페이드, 측정 클릭 0·음량 차 0.02 dB, 비용 −7~8%); 2% 아래로 60초면 복귀. 작곡실 '오디오 부하' 행과 logcat `engine load`로 확인하고, 구성요소별 비용은 `EngineCostBreakdownTest` 출력(COST 행)으로 본다.

## 제출 자료

- `docs/privacy-policy.md` — 개인정보처리방침(한/영). 앱에 INTERNET 권한이 없고 여정 요약만 로컬에 남는다는 사실 기반. 제출 시 공개 URL(예: GitHub Pages)로 올려 링크를 적는다.
- `store/icon-512.png` — 스토어 아이콘 512×512(런처 아이콘과 같은 디자인, `python -I scripts/make_store_assets.py`로 재생성).
- `store/listing.md` — 앱마켓 등록 텍스트: 한 줄 소개·설명(한/영)·주행 중 UX 선언·권한 사용 사유·로컬 저장 데이터·Gleo 명령·심사자 안내(차량 없이 확인하는 순서)·제약·오픈소스 고지. 블라인드 심사 규정에 맞춰 개발자 정보 없음.
- `previews/lofi_ab_clean.wav`·`lofi_ab_event.wav` — 같은 시드·같은 주행의 A/B 음원(30초). 20.0초까지 샘플 단위로 동일하고 20.3초의 브레이크 찍기 뒤 120 ms 안에 갈라진다(`gradlew :engine:renderPreviews`).
- 스크린샷(2560×1380 등)은 에뮬레이터 스모크 스크립트(`scripts/emulator-smoke.ps1`)가 `build/smoke/<ts>/screen_*.png`로 저장한다.

## 라이선스

소스: MIT(`LICENSE`). 모든 악기 음은 코드로 합성되며 외부 샘플·저작권 음원을 포함하지 않는다. 커밋 작성자는 블라인드 심사를 위해 `Drive Symphony <dev@drivesymphony.local>`로 통일되어 있다.
