# 드라이브 심포니 개인정보처리방침 / Drive Symphony Privacy Policy

최종 수정일: 2026-10-31 · 적용 앱: 드라이브 심포니 (패키지 `io.drivesymphony.app`) · 문의: hjkim00113@gmail.com

## 1. 요약

- 드라이브 심포니는 **개인정보를 수집·전송하지 않습니다.** 앱에는 네트워크(INTERNET) 권한 자체가 없습니다.
- 차량 신호(조향각·브레이크·방향지시등·와이퍼·기어·주행 모드·크루즈·좌석 착좌/벨트)와 내비게이션 정보(현재 도로명·제한속도·다음 회전까지 거리·주의 구간)는 **차량 안에서 실시간으로 음악 파라미터로 바꾸는 데만** 쓰이고, 원본 신호는 저장되지 않습니다.
- 저장되는 것은 "여정의 곡" 요약뿐입니다: 주행 시간, 평균 흐름 점수, 가장 길게 부드러웠던 구간, 마디 수, 곡 제목(도로명 + 시간대 + 키·장르), 선택한 장르. 최근 10회까지 **차량 내 앱 전용 저장소**에만 남고, 앱 데이터를 삭제하거나 앱을 삭제하면 함께 지워집니다.
- 이미지 카드 공유는 사용자가 "공유" 버튼을 눌렀을 때만, 사용자가 고른 앱으로, Android 공유 시트를 통해 이루어집니다. 앱이 스스로 외부에 보내는 데이터는 없습니다.

## 2. 처리하는 정보와 목적

| 정보 | 출처 | 목적 | 보관 |
|---|---|---|---|
| 조향각, 브레이크 페달, 방향지시등, 와이퍼, 기어, 주행 모드, 크루즈, 운전석·뒷좌석 착좌, 안전벨트 | Pleos Vehicle SDK (읽기 전용) | 주행 흐름 점수 계산, 음악 밀도·톤·음량 조절, 분위기 레이어(비·깜빡이) | 메모리에서 즉시 처리, 저장 안 함 |
| 현재 도로명, 제한속도, 회전까지 거리, 주의 구간, 위치 기반 속도(1 Hz 폴링한 내비 위치의 변화로 계산) | Pleos NaviHelper SDK | 과속 여부·급회전 예고 반영, 곡 제목의 도로명, "이 길의 테마" 시드 | 도로명만 여정 요약에 포함 |
| 여정 요약(시간·점수·제목·장르) | 앱이 계산 | "여정의 곡 기록" 화면, 공유 카드 | 차량 내 앱 전용 저장소, 최근 10회 |
| 사용자 설정(장르, 모드, 피드백 강도, 자동 연주 여부, 변주 모드에서 마지막으로 고른 음원 파일의 위치) | 사용자 입력 | 앱 동작 | 차량 내 앱 전용 저장소 |
| 로컬 음원 파일(변주 모드) | 사용자가 문서 선택기에서 고른 파일(저장소 전체 권한 없이 그 파일에만 접근) | 선택한 곡을 재생하면서 실시간 변주 | 파일을 복사·저장하지 않음, 제목만 화면 표시 |
| 음성 명령(Gleo) | 차량 음성 비서가 전달하는 함수명·파라미터(장르 등) | 재생 시작·장르 변경·점수 안내·일시정지 | 저장 안 함 |

위치 좌표 자체는 저장하지 않습니다. 앱은 마이크·카메라·연락처·계정 정보에 접근하지 않습니다.

## 3. 제3자 제공·국외 이전

없습니다. 앱은 서버가 없고 네트워크 권한이 없습니다. 분석(analytics)·광고 SDK를 포함하지 않습니다.

## 4. 이용자의 권리

- 여정 기록 삭제: 차량 설정 → 앱 → 드라이브 심포니 → 저장공간 → 데이터 삭제(또는 앱 삭제).
- 공유한 이미지 카드는 사용자가 선택한 앱(메신저 등)의 정책을 따릅니다.
- 문의: hjkim00113@gmail.com

## 5. 아동

이 앱은 운전자를 위한 앱이며 아동을 대상으로 하지 않습니다.

## 6. 변경

방침이 바뀌면 이 페이지의 최종 수정일을 갱신합니다.

---

## English summary

Drive Symphony (package `io.drivesymphony.app`) **does not collect or transmit personal data**. The app has no INTERNET permission. Vehicle signals (steering, brake, turn signal, wiper, gear, drive mode, cruise, seat occupancy / belt) and navigation data (road name, speed limit, distance to next turn, caution ahead, position-derived speed) are used only to shape the music in real time and are not stored. The only persisted data is a "journey song" summary (duration, average flow score, longest smooth stretch, bar count, generated title with road name, chosen genre), kept in the app's private storage for the last 10 trips and removed when you clear app data or uninstall. Sharing an image card happens only when you tap Share, through the Android share sheet, to the app you choose. Local audio files chosen for "variation" mode are played from your device and never copied. Gleo voice commands reach the app only as a function name and parameters and are not stored. No third-party, analytics or advertising SDKs. Contact: hjkim00113@gmail.com
