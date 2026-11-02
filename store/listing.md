# Pleos App Market 등록 자료 — Drive Symphony

접수양식·앱마켓 등록 화면에 그대로 옮겨 적을 수 있도록 정리한 텍스트. 블라인드 심사 규정에 따라 개발자 성명·소속·연락처를 쓰지 않는다. `⟦ ⟧`는 등록 직전에 채운다.

## 1. 기본 정보

| 항목 | 값 |
|---|---|
| 앱 이름 | Drive Symphony (드라이브 심포니) |
| 패키지 | `io.drivesymphony.app` |
| 버전 | 0.9.0 (versionCode 1) |
| 카테고리 | 음악·오디오 (주행 중 사용 가능 카테고리, `pleos.app.category = MUSIC_AUDIO`) |
| 최소 / 타깃 SDK | 28 / 34 (Pleos Connect 에뮬레이터 v2.1.0, API 34) |
| 네트워크 | 사용하지 않음 (`INTERNET` 권한 없음) |
| 개인정보처리방침 | `docs/privacy-policy.md` → ⟦공개 URL⟧ |
| 아이콘 | `store/icon-512.png` (512×512, 둥근 변형 `icon-512-rounded.png`) |
| 스크린샷 | 2560×1380 5장 — 글랜스(주행) / 작곡실(정차) / 여정의 곡 카드 / 음원 선택 / 장르·강도 설정 (`python -I scripts/make_store_assets.py --screens <폴더>`로 규격 변환) |
| APK | `apk/⟦팀명⟧_DriveSymphony_release.apk` (디버그 키 서명 release) |

## 2. 한 줄 소개

주행이 연주가 되는 차. 부드럽게 달릴수록 음악이 풍성해집니다.

## 3. 짧은 설명 (앱마켓 목록용, 80자 안)

조향·브레이크·깜빡이·와이퍼가 연주하는 로파이·재즈·앰비언트. 안전한 운전이 곧 더 좋은 음악.

## 4. 상세 설명

**주행이 연주가 되는 차.** 드라이브 심포니는 차량 신호(조향각·브레이크·기어·방향지시등·와이퍼·크루즈)와 내비 정보(도로명·제한속도·다음 회전)를 읽어 로파이·재즈·앰비언트 음악을 실시간으로 작곡합니다. 미리 녹음된 곡을 트는 것이 아니라 매 여정이 새 곡이고, 같은 길을 달리면 같은 테마가 돌아옵니다("이 길의 테마").

**부드럽게 달릴수록 음악이 풍성해집니다.** 급조향·브레이크 연타·깜빡이 없는 차선 변경이 있으면 음악이 0.12초 안에 잠깐 가라앉고(음량·고역·폭이 줄어듦), 5초쯤 차분히 달리면 레이어가 하나씩 돌아옵니다. 가라앉는 방식은 음악적입니다: 테이프가 잠깐 늘어지듯 음정과 템포가 함께 내려앉았다가(0.5초) 제자리로 돌아오고, 드럼과 멜로디가 그 자리에서 멈추며, 필터가 물속으로 잠겼다가 1~2초에 걸쳐 떠오릅니다. 다시 차분해지면 레이어는 라이저 뒤 다운비트에 맞춰 돌아오고, 깜빡이를 켜고 돌면 짧은 아르페지오가 바로 답합니다. 핸들을 돌리면 키와 멜로디가 그쪽으로 따라 움직이고, 거칠게 달릴수록 템포가 조금 처집니다. 로파이에서는 킥마다 패드와 키가 숨 쉬는 펌핑이 그루브를 살아 있게 하고, 8마디마다 라이저가, 멜로디가 처음 열릴 때는 스웰이 붙습니다. 벌을 주는 소리는 없습니다. 침묵도, 경고음도, 거친 효과음도 없이 음악이 "숨을 고르는" 방식으로만 말합니다. 긴급 회피처럼 보이는 한 번의 급조작은 습관과 다르게 읽어 반만 반응합니다.

**두 가지 모드.** ① 음악 만들기: 엔진이 작곡합니다. ② 내 음원 변주하기: USB·로컬 음원을 고르면 같은 규칙으로 그 곡의 저역·워블·스테레오 폭이 주행에 따라 변합니다. 음원은 복사되지 않고 기기에서 바로 재생됩니다. 음원이 없어도 내장 샘플 곡(바흐 골드베르크 아리아, 퍼블릭 도메인 CC0, 72초)으로 바로 들어볼 수 있습니다.

**차에서만 가능한 것들.** 깜빡이를 켜고 돌면 림 클릭과 함께 레이어가 하나 더 열리고, 와이퍼가 오래 켜져 있던 여정은 제목에 "비 오는"이 붙으며, 크루즈 컨트롤을 켜면 드론 베드가 깔립니다. 주행 중에는 Flow 링과 세 줄만 보이는 정적 글랜스 화면을 유지하고, 설정·기록·공유·음원 선택은 정차했을 때만 열립니다. 도착해 P로 바꾸면 "여정의 곡" 카드(제목·평균 Flow·가장 길게 부드러웠던 구간)가 나오고 이미지로 공유할 수 있습니다. Gleo 음성으로 "드라이브 심포니 틀어줘", "재즈로 바꿔", "내 운전 어때?", "음악 멈춰"를 말할 수 있습니다.

**개인정보.** 네트워크 권한이 없어 아무것도 전송하지 않습니다. 저장하는 것은 최근 10개 여정의 요약(제목·길이·평균 Flow·마디 수·장르)과 설정뿐이며, 앱 데이터 삭제·앱 삭제로 함께 지워집니다.

## 5. English (short)

Drive Symphony turns driving into a live score. Steering, braking, turn signals, wipers and cruise control shape lo-fi, jazz or ambient music composed in real time; the smoother you drive, the richer the music gets. Harsh moves make the music breathe for a moment, never punish with silence or alerts. Variation mode applies the same rules to your own local tracks. No network, nothing leaves the car.

## 6. 주행 중 UX 선언 (Distraction Optimized)

- 활동(`MainActivity`)에 `distractionOptimized = true`. 주행 제한 상태(`CarUxRestrictionsManager`) 또는 재생 중 기어 D에서는 글랜스 화면(Flow 링 + 곡 제목·코드·이벤트 한 줄)만 보이고 애니메이션·스크롤·영상이 없다.
- 설정·여정 기록·공유·음원 선택·데모 토글은 정차 상태에서만 열린다.
- 오디오 포커스: LOSS → 일시정지, LOSS_TRANSIENT → 일시정지 후 복귀, CAN_DUCK → −12 dB 램프. 재생 시작 시 200 ms 프리롤 무음으로 클릭을 막는다.
- 작업 제거(`onTaskRemoved`) 시 재생을 정리하고 알림을 내린다. 미디어 센터(MediaSession)에서 재생/일시정지/장르 전환이 가능하다.
- CPU 예산: 데스크톱 JVM에서 합성 1.0~1.3%(1코어). 차량에서 4%를 3초 넘기면 라이트 프로파일(리버브 1채널 공유·더블러 생략)로 자동 전환, 작곡실 '오디오 부하' 행과 logcat `engine load`에 표시. 에뮬레이터 실측(2026-10-31, release): 소프트웨어 GPU에서 오디오 스레드 3.6~3.8%, 프로세스 합계 10.0~12.5%(1코어 기준, UI 3~6% 포함), RSS 161~170 MB; 호스트 GPU에서 오디오 2.6~3.5%, 합계 4.6~4.7%, RSS 115~119 MB, 언더런 0.

## 7. 권한과 사용 사유

| 권한 | 왜 필요한가 | 어디서 쓰는가 |
|---|---|---|
| `pleos.car.permission.CAR_DRIVE` | 조향각·브레이크 페달·기어·주행 모드·크루즈 상태 → 채점·패닝·에너지·드론 | `signals/PleosVehicleSource.kt` |
| `pleos.car.permission.CAR_LIGHTS` | 방향지시등 → 깜빡이 규율 채점, 림 클릭·보상 레이어 | `signals/PleosVehicleSource.kt` |
| `pleos.car.permission.CAR_WINDOWS` | 앞유리 와이퍼 → 비 텍스처, "비 오는" 제목 | `signals/PleosVehicleSource.kt` |
| `pleos.car.permission.CAR_SEATS` | 좌석 점유·안전벨트 → 뒷좌석 탑승 시 피드백 강도 자동 '약', 주행 중 벨트 해제 시 흐름 상한 0.4 | `signals/PleosVehicleSource.kt` |
| `pleos.car.permission.NAVI_ROUTE` | 현재 위치(속도 추정)·도로명·제한속도·다음 회전·주의 구간 → 과속·회전 예고 규칙, 곡 제목 | `signals/PleosNaviSource.kt` (`getCurrentLocationInfo()`가 이 권한을 검사) |
| `android.car.permission.CAR_POWERTRAIN` | android.car `GEAR_SELECTION` 속성 (Vehicle SDK 2.3.2가 기어 리스너 대신 권고) → 트립 시작/종료, 주행 화면 전환 | `signals/CarPropertyGearSource.kt` |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` | 미디어 재생 포그라운드 서비스와 재생 알림 | `audio/SymphonyService.kt` |

위치·저장소·네트워크·마이크 권한은 없다. 리믹스 음원은 시스템 문서 선택기(`OpenDocument`)의 URI를 쓰므로 저장소 권한이 필요 없다.

## 8. 로컬에 저장하는 데이터

| 항목 | 내용 | 보관 |
|---|---|---|
| 여정 요약 | 제목(도로명 포함)·길이·평균 Flow·최장 부드러운 구간·마디 수·장르·감점 이벤트 개수 | 최근 10개, 앱 전용 저장소 |
| 설정 | 장르·피드백 강도·자동 시작·데모 모드·리믹스 음원 URI | 앱 전용 저장소 |
| 공유 카드 PNG | 사용자가 공유를 누른 순간만 생성 | `cache/share/`, FileProvider로 선택한 앱에만 전달 |

차량 신호·내비 데이터는 음악을 만드는 데만 쓰고 저장하지 않는다.

## 9. Gleo 음성 명령 (`assets/schema.json`)

| 함수 | 예시 발화 | 응답 |
|---|---|---|
| `startSymphony` (genre 선택) | "드라이브 심포니 틀어줘", "재즈로 연주 시작해" | "재즈 연주를 시작했어요" |
| `setGenre` (genre 필수) | "앰비언트로 바꿔줘" | "앰비언트로 바꿨어요" (다음 마디에서 전환) |
| `howIsMyDriving` | "내 운전 어때?", "음악 왜 가라앉았어?" | "지금 흐름 88점, 4분째 부드럽게 달리고 있어요" |
| `pauseSymphony` | "음악 멈춰" | "연주를 멈췄어요" |

인텐트 액션 = 함수명, 인자는 `ACTION_PARAM` 엑스트라의 JSON. 활동과 서비스 양쪽 인텐트 필터에 선언되어 있고, `python -I scripts/check_consistency.py`가 스키마·매니페스트·서비스 상수의 일치를 검사한다.

## 10. 심사자 안내 (차량 없이 확인하는 방법)

1. 설치 후 실행 → 작곡실 → **연주 시작**. 2초 안에 음악이 나와야 한다(200 ms 프리롤 뒤 페이드인).
2. 에뮬레이터 Virtual Console: **Steering**을 돌리면 패닝, **Brake**를 짧게 3번 연타하면 약 0.17초 안에(엔진 반응 0.12초 + 오디오 버퍼 48 ms) 음악이 가라앉고 글랜스의 Flow 링이 주황으로 숨쉰다. 5초간 가만히 두면 레이어가 돌아온다.
3. **Turn signal** 켜고 조향 → 글랜스에 "깜빡이 좋아요" + 레이어 하나 추가. **Wiper ON** → 비 텍스처. **Gear P** → 여정의 곡 카드.
4. 차량 신호 입력이 어려우면 작곡실 → **데모 시나리오** → "시연 스토리"(106초). 주행 화면에 DEMO 표시가 붙으며, 작곡실에서 끌 때까지 유지된다.
5. 주행 제한: 재생 중 기어를 D로 바꾸면(또는 차량의 UX 제한이 켜지면) 설정이 닫히고 글랜스만 남는다.
6. 오디오 포커스: 다른 미디어 앱을 재생하면 멈추고, 내비 안내 중에는 −12 dB로 줄었다가 돌아온다.
7. 음성: Gleo에서 위 4개 발화. 응답은 `io.drivesymphony.GLEO_CALLBACK` 브로드캐스트로도 확인 가능.
8. 부하: `python -I scripts/cpu-share.py --adb adb`(스레드별 30초 창, /proc 기반) 또는 `adb shell top -n 1 | grep drivesymphony`. 작곡실 '오디오 부하' 행에 합성 부하와 라이트 프로파일 여부가 표시되고, logcat에 10초마다 `audio load` 줄이 남는다.
10. 안전벨트·속도: 에뮬레이터는 벨트를 채울 수 없고 내비 위치가 고정이라 속도를 알 수 없다. 앱은 속도를 모르면 벨트 알림을 내지 않고(실차에서 15 km/h 이상일 때만 30초마다 짧은 알림) 급조향 기준만 1.5배로 올린 채 그대로 채점하므로, 콘솔 입력에 음악이 바로 반응한다. 내비 경로 시뮬레이션으로 속도가 생기면 실차와 같은 규칙이 적용된다.
11. 뒷좌석: Virtual Console → Seat → rear_left **Occupied** → 작곡실 '뒷좌석' 행이 '탑승 → 강도 약'으로 바뀌고 벌칙 반응이 부드러워진다. 해제하면 설정한 강도로 복귀(logcat `rear seat`).
9. 최근 앱에서 스와이프로 제거 → 재생 정지·알림 제거.

## 11. 제약과 지원 범위

- Vehicle SDK 2.3.2에는 차속·가속 페달·브레이크 강도 신호가 없다. 속도는 NaviHelper 위치(1 Hz)로 추정하며(검증: 위치 잡음 σ=3 m에서 오차 RMS 1.8 km/h), Pleos 내비가 꺼져 있으면 과속·회전 예고 규칙만 꺼지고 나머지는 동작한다.
- NaviHelper는 Pleos Connect 내비 전용이다. 다른 내비를 쓰면 도로명이 없어 곡 제목이 시간대만으로 붙는다.
- HUD·클러스터 투사 API는 서드파티에 열려 있지 않아 "HUD"는 중앙 화면의 글랜스 레이어로 구현했다.
- 리믹스 모드는 로컬 음원(USB·기기 파일)만 지원한다(스트리밍 앱의 오디오는 읽을 수 없다).

## 12. 오픈소스 고지

앱·엔진 코드는 이 저장소에서 직접 작성했다. 사용한 라이브러리와 라이선스:

| 구성요소 | 라이선스 |
|---|---|
| Kotlin stdlib·kotlin-test, kotlinx-coroutines | Apache-2.0 |
| AndroidX core-ktx, lifecycle-runtime-ktx, activity-compose, media, Compose(ui·foundation·material3, BOM) | Apache-2.0 |
| Pleos Vehicle SDK 2.3.2, NaviHelper 3.0.5, navi-data 3.0.4 (`nexus-playground.pleos.ai`) | Pleos Playground 이용약관 |
| 빌드·자료 생성 도구: Gradle, Pillow·numpy·scipy(스크립트만, 앱에 포함 안 됨) | Apache-2.0 / HPND·BSD |
| 샘플 뱅크 `engine/src/main/resources/samples/fluid_lofi.dsb`: FluidR3_GM SoundFont(Frank Wen 외)에서 추출한 Rhodes·베이스·바이브라폰·스트링·패드·드럼 킷 샘플 4.8 MB | MIT (원문 `LICENSE-FluidR3.txt` 동봉) |
| 내장 샘플 곡 `demo_track.ogg`: J.S. Bach, Goldberg Variations BWV 988 — Aria, Kimiko Ishizaka 연주 'Open Goldberg Variations'(2012), 72초 발췌 | CC0 1.0 퍼블릭 도메인 헌정 (archive.org/details/OpenGoldbergVariations) |

리버브는 Freeverb 알고리즘(퍼블릭 도메인 튜닝값)을 직접 구현했다. 악기·시퀀서·채점 로직에 외부 코드나 샘플은 없다.

## 13. 영상·숏폼 메모

- 시연 영상(가산점, 길이·형식 규정 없음, 화면 녹화 인정): `scripts/make-demo-video.ps1`가 데모 스토리 106초 + Virtual Console 큐시트 85초를 녹화하고 `scripts/video_sync.py`가 자막·카드와 함께 16:9 약 3분 40초로 조립한다(`build/video/<ts>/demo_video_16x9.mp4`). 유튜브 미등록 업로드 → 접수양식 링크.
- 숏폼(선택, 가산점 없음, 별도 '숏폼 챌린지 어워드'): 세로 30초~1분, 해시태그 `#HMG차량용앱개발공모전`(필수) `#PleosAppMarket #HMG앱공모전`, Spot→Solve→Scene. 같은 스크립트가 45초 `short_9x16.mp4`를 만든다; AI B-roll 합성판 콘티는 `video/STORYBOARD.md`.
- 영상·저장소·첨부 어디에도 개발자 성명·소속이 보이지 않게 한다(커밋 작성자는 `Drive Symphony <dev@drivesymphony.local>`).
