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
    ├─ signals/    PleosVehicleSource(Vehicle SDK 2.3.2) · PleosNaviSource(NaviHelper 3.0.5) · ScenarioSource(데모)
    ├─ remix/      LocalTrackPlayer(MediaCodec 디코드 → 48 kHz 스테레오 float)
    ├─ ui/         FlowScreen(주행 중 글랜스 화면) · ComposerScreen(정차 화면) · Theme
    └─ ux/         DrivingStateObserver(CarUxRestrictionsManager)
```

## 동작 원리 (한 장)

1. **신호 수집** — Vehicle SDK 리스너 9종 + NaviHelper 콜백 6종을 `SignalStore`에 모아 25 Hz로 스냅샷.
2. **점수화** — `DrivingScorer`가 조향 변화율, 브레이크 토글 패턴, 깜빡이 규율, 제한속도 대비 속도(내비 위치 미분), 스쿨존, 안전벨트를 보고 **flow(0~1)** 를 만든다. 하강은 0.6 s, 상승은 5 s. 30초 이상 무사고 후 단발 이벤트는 **긴급 회피로 간주해 감점 절반**. 주차·후진 중엔 채점하지 않는다. 바닥은 0.1 (완전 침묵 없음).
3. **음악화** — `MusicDirector`가 flow를 밀도(레이어 수)·컷오프·테이프 워블·바이닐·크러시·리버브·스테레오 폭·게인·화성 확장·멜로디(bloom)로 매핑한다. 급조작 펄스는 150 ms 안에 들리고, 회복은 천천히.
4. **연주** — `Arranger`가 마디마다 장르 규칙(로파이 스윙 58%, 재즈 트리오 워킹베이스·라이드, 앰비언트 패드·아르페지오)으로 이벤트를 만들고 `SymphonyEngine`이 샘플 단위로 합성한다. 와이퍼=비 텍스처·셰이커, 깜빡이=비트 위 림 클릭, 조향=패닝, 주행모드=에너지, 크루즈=드론.
5. **여정의 곡** — 주차(P) 시 평균 flow·최장 부드러운 구간·멜로디가 열린 시간·마디 수를 요약하고, 도로명+시간대로 제목을 붙인다("저녁의 강변북로 (Bb Lo-fi)").

## 빌드

요구: JDK 17, Android Studio(Android SDK 35, Pleos Connect 에뮬레이터 v2.1.0). Android SDK가 없으면 `:app`은 자동으로 제외되고 엔진만 빌드된다.

```bash
# 엔진 단위 테스트 (16개) + 프리뷰 WAV 9개 렌더링 → previews/
./gradlew :engine:test :engine:renderPreviews

# Android 앱 (Android Studio 설치 후)
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # 디버그 키로 서명된 release APK (공모전 제출용)
```

Pleos 에뮬레이터 설정은 `docs`/공모전 문서 참고 (SDK Update Site: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`, Automotive 폼팩터, Cold boot, RAM 4 GB).

## 시연 (에뮬레이터)

1. 앱 실행 → 작곡실 탭 → **연주 시작**.
2. Virtual Console에서 **Steering**을 천천히 돌리면 패닝, **Brake**를 짧게 반복하면 150 ms 안에 음악이 가라앉고 Flow 링이 주황으로 숨쉰다. 5초간 가만히 두면 레이어가 하나씩 돌아온다.
3. **Turn signal** 켜고 조향 → "깜빡이 좋아요" + 레이어 개방. **Wiper ON** → 비 텍스처. **Gear P** → 여정 요약 카드.
4. 내비 없이 시연할 때는 작곡실 → **데모 시나리오** 켜기 → "시연 스토리"(90초: 시내 → 급조작 → 회복 → 고속 크루즈 만개 → 비 → 도착). 실제 차량 신호가 들어오면 데모는 자동 중지된다.
5. 리믹스: **내 음원 변주하기**로 USB/로컬 음원을 고르면 같은 flow 규칙으로 로우패스·워블·폭이 변한다.

## 제약과 정직한 표시

- Vehicle SDK 2.3.2에는 **차속·가속페달·브레이크 강도가 없다.** 속도는 NaviHelper 위치를 미분해 추정하며(내비 실행 중에만), 없으면 속도 기반 규칙은 비활성화된다.
- NaviHelper는 **Pleos Connect 내비 전용**이다(카카오내비 경로는 읽지 못함).
- 실제 **HUD/클러스터 투사 API는 서드파티에 열려 있지 않다.** "HUD 표시"는 중앙 화면의 글랜스 레이어(Flow 링 + 세 줄)로 구현했고, 주행 중에는 그 화면만 보인다.
- 리믹스 모드는 **앱이 직접 재생하는 음원**에만 적용된다(스트리밍 앱의 오디오는 DRM·오디오 캡처 제한으로 처리 불가). 
- TTS/STT/Vehicle Control SDK는 '준비 중'이라 사용하지 않는다. Gleo 연동은 `assets/schema.json` 초안 + 인텐트 필터로 준비돼 있고, 공식 스키마 가이드 검증 후 활성화한다.

## 라이선스

소스: MIT. 모든 악기 음은 코드로 합성되며 외부 샘플·저작권 음원을 포함하지 않는다.
