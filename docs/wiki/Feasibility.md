# 기술 타당성과 공식 근거

[Wiki 홈](Home.md)

> 검토일: 2026-09-22. 문서 조사 결과이며 앱 구현·실기기 시험 결과가 아니다. 네이티브 Kotlin으로 개발해도 Android의 서비스·마이크·Bluetooth 권한 제한은 동일하게 적용된다.

## 판정 용어

- **문서 확인:** 공식 문서 또는 공식 저장소에 명시된 사실. 대상 기기에서의 성공을 뜻하지 않는다.
- **구현 가설:** 문서에 근거해 선택한 시험 후보. 실제 앱 구성과 기기에서 확인해야 한다.
- **실기기 미확인:** Galaxy S23 Ultra와 대상 Model Y의 실제 동작·성능·지원 여부가 확인되지 않았다.
- **구조적 제한:** 일반 앱이 우회 대상으로 삼아서는 안 되는 OS·권한·보안 경계.

## 현재 확인 결과

| 항목 | 판정 | 확인 내용과 남은 조건 |
| --- | --- | --- |
| Android 구현 기술 | 설계 결정 | Kotlin 네이티브, Compose UI. React Native와 JS 수명 관리 경로는 채택하지 않는다. |
| 개발 저장소 | 환경 확인 | 시작 시 소스와 첫 커밋이 없는 저장소. 이번 산출물은 README와 Wiki 원본이다. |
| 연결 기기 | 환경 확인 | 이전 검토의 `adb devices -l` 결과에 연결 기기가 없었다. 이후 기기 상태는 시험 직전에 다시 기록한다. |
| Android·One UI·차량 펌웨어 | 실기기 미확인 | 모델명만으로 실제 버전을 추정하지 않는다. |
| 무터치 마이크 시작 | 구현 가설 | 기본 비서 경로의 문서상 근거는 있으나 잠금·주머니·실제 차량 이벤트 조합은 미시험이다. |
| 한국어 온디바이스 호출어·STT | 실기기 미확인 | 엔진, 모델 설치, 외부 PCM 입력, 연속 문장 처리 여부를 확인해야 한다. |
| 프렁크 제어와 배터리 | 실기기 미확인 | 실제 명령·완료 판정·전력 소모·차량 수면 영향은 측정하지 않았다. |

## A. 접근 신호와 차량 인증은 별개다

| 신호 | 알 수 있는 범위 | 알 수 있다고 가정하면 안 되는 것 |
| --- | --- | --- |
| Tesla 공식 앱 폰키 | 공식 앱과 차량 사이의 키 기능 | 다른 앱에 그 인증 상태나 권한이 자동 공유됨 |
| 음악·통화용 Bluetooth 연결 | 오디오 연결 또는 저수준 링크 이벤트 | 차량 밖 접근 시점부터 감지됨, 폰키 인증 완료 |
| BLE 광고·RSSI | 주변 광고 관측과 신호 세기 | 고정 이름·MAC, 정확한 거리, 발화자의 신원 |
| Companion Device presence | 등록한 기기의 연결·BLE 범위 관련 이벤트 | 차량 키 인증, 다른 앱의 GATT 세션 의미 |

[Companion Device pairing][A3]은 연결 자체를 만들어 주거나 그 자체로 지속 스캔을 활성화하는 기능이 아니다. Presence 관찰은 선택한 API와 기기 식별 방식에 맞게 별도 구성해야 한다. [ACL 이벤트][A4]는 저수준 링크 이벤트이지 Tesla 키 인증 증거가 아니다.

**선행 시험:** 다른 Tesla 차량이 있는 환경, 차량 수면·접근·탑승 전후, Bluetooth 재연결에서 목표 차량만 구분할 수 있는지 관찰한다. 필요한 광고나 식별 방식이 확보되지 않으면 CDM을 사용할 수 있다고 전제하지 않는다. 접근 신호를 얻어도 보안 인증을 대체하지 않는다.

## B. 서비스 시작 허용과 마이크 사용 허용은 두 단계다

[Android 공식 제한][A1]은 다음을 구분한다.

1. Android 12 이상을 대상으로 하는 앱의 백그라운드 foreground service 시작 제한.
2. 마이크 등 while-in-use 권한을 요구하는 서비스의 추가 제한. Android 14 이상을 대상으로 할 때 서비스 생성 시 권한 조건을 검사한다.

Companion Device 관련 시작 예외나 배터리 최적화 예외만으로 두 번째 조건까지 충족되는 것은 아니다. `checkSelfPermission()`이 승인 상태를 반환해도 현재 백그라운드 마이크 접근 자격의 증거가 되지 않는다.

공식 while-in-use 예외 목록에는 `VoiceInteractionService`를 제공하는 앱이 시작한 서비스가 포함된다. [사용자가 선택한 현재 VoiceInteractionService][A2]는 시스템이 유지하는 서비스이며 가볍게 유지하도록 권고된다.

### 우선 검증 후보

```text
초기 설정에서 기본 음성 비서로 지정
  → 실제 차량의 저전력 접근 이벤트
  → VoiceInteractionService를 통한 제한 음성 세션 시작
  → 적절한 microphone FGS와 오디오 캡처
  → 호출어·짧은 명령 처리
  → 이탈·완료·취소·만료 시 오디오/STT 해제
```

이 구성은 **구현 가설**이다. 서비스 선언, 활성 기본 비서 상태, 접근 관찰 API, 해당 Android 버전의 두 종류 시작 제한을 함께 확인해야 한다. 서비스가 생성되었다는 로그만으로 성공이 아니다. 실제 입력이 제공되고, 원거리에서는 오디오가 수집되지 않아야 한다.

### 비교 대안

화면이 보일 때 사용자가 사전에 시작한 microphone FGS가 살아 있는 동안 필요한 순간에만 캡처하는 경로를 비교할 수 있다. 단, 장시간 오디오 OFF 후 재획득, 통화 후 복구, 프로세스 종료·재부팅 이후 상태를 시험해야 한다. 서비스 상주를 곧 상시 녹음 또는 배터리 과소모라고 단정하지 않는다. 반대로 서비스를 한 번 켰다는 이유로 영구 복구가 보장된다고도 말하지 않는다.

기본 비서 선택은 기존 기본 비서 설정에 영향을 준다. 해당 사용자의 동의가 필요하며 DSP 저전력 호출어 하드웨어 접근·모델 등록이 자동으로 허용된다는 뜻은 아니다. 기본 비서를 바꾸지 않는 대안이 실패해도 매사용 알림 터치를 동등한 대안으로 제시하지 않는다.

### 종료와 복구의 경계

- 일반 프로세스 종료, 최근 앱 제거, 사용자 강제 중지, 업데이트, 재부팅은 다른 사건이다.
- [강제 중지][A7]는 사용자 상호작용으로 stopped 상태를 해제하도록 설계되어 있다. 이를 무터치로 우회하는 기능은 지원 목표로 삼지 않는다.
- [microphone FGS][A10]는 부팅 브로드캐스트에서의 시작 제한도 받는다. `BOOT_COMPLETED` 등록만으로 자동 복구가 해결되었다고 보지 않는다. 기본 비서 재바인딩과 최초 잠금 해제 전·후 동작은 별도 시험한다.
- [Samsung 앱 관리][S1]의 절전 예외는 사용자가 초기 설정에서 검토할 항목이다. 마이크 권한 예외를 대신하지 않는다.

## C. 오프라인 한국어와 한 문장 인식

[SpeechRecognizer][A5]의 일반 구현은 오디오를 원격 서버로 보낼 수 있고 연속 인식용으로 설계되지 않았다. `EXTRA_PREFER_OFFLINE`은 [구현에 따라 효력이 없을 수 있는 옵션][A6]이다.

따라서 다음을 별도로 시험한다.

- `isOnDeviceRecognitionAvailable()`와 `checkRecognitionSupport()`로 사용 가능한 엔진·요청 지원 확인.
- `ko-KR` 모델이 실제 설치되어 있는지, 네트워크 없이 인식되는지 확인. 시스템에 온디바이스 엔진이 있다는 사실만으로 한국어 지원을 단정하지 않음.
- 호출어 감지기와 STT 사이에서 명령 첫 음절이 잘리지 않는지 확인. 접근 세션 안에서만 짧은 RAM 버퍼를 검토하며 원거리 오디오를 미리 수집하지 않음.
- [외부 오디오 소스 전달][A6]은 인식기 지원 여부를 확인해야 함. 버퍼를 만들었다는 사실만으로 시스템 STT가 이를 소비한다고 가정하지 않음.
- 호출어 엔진의 한국어 모델·라이선스·비용·네트워크 사용 여부를 선정 전에 확인. 승인 없는 외부 서비스 fallback 금지.
- 신뢰도 점수는 없거나 미제공 값일 수 있음. 이를 높은 신뢰도로 해석하지 않음.

[오디오 입력 공유 정책][A8]에 따라 다른 앱이나 통화가 입력을 가져가면 예외 대신 무음이 제공될 수 있다. 캡처 API 성공과 유효한 입력을 구분하고, 녹음 구성·silenced 상태·라우팅 변경을 진단해야 한다. 호출어를 듣기 위한 캡처도 오디오 수집이므로 해당 세션에만 허용한다.

## D. Tesla 상태와 물리 동작

- [공식 명령 SDK][T2]는 사용자 OAuth 토큰과 차량에 등록한 앱 공개키를 별도로 요구한다. Tesla 공식 앱 폰키는 이 권한을 제공하지 않는다.
- [권한 범위][T1]에서 명령, 차량 데이터, refresh token 권한을 구분한다. `vehicle_cmds`만으로 상태 확인까지 해결된다고 가정하지 않는다.
- [공식 Telemetry 정의][T3]에는 명시적 `ShiftStateP`와 `Unknown`, `Invalid`, `SNA`가 있다. 실제 차에서 적시에 유효한 P를 받는지, 값의 관측 시각·연결 상태를 어떻게 검증하는지는 별도 문제다. 기존 데이터 경로로 충분한지 먼저 확인하고 Telemetry를 무조건 추가하지 않는다.
- 요청 전송, 차량 명령 승인, 래치 해제, 후드의 완전 개방은 구분한다. 물리 완료가 미확인일 때 완료형 TTS를 사용하지 않는다.
- 대상 차량의 [프렁크 매뉴얼][T6] 직접 조회는 영문·한국어 URL 및 브라우저 시도에서 접근 거부(HTTP 403)를 만났다. 이번 검토에서는 대상 트림의 순정 전동 완전 개방 여부를 확정하지 않았다. 차량 내 매뉴얼과 승인된 실차 시험으로 확인한다.

## 중단·재검토 조건

| 조건 | 조치 |
| --- | --- |
| 탑승 전 적합한 접근 신호를 확보하지 못함 | 접근 경로 대안의 정확도·권한·배터리·추가 장비 비용을 비교하고 사용자와 범위 결정 |
| 정식 설정·권한으로 잠금 상태 캡처가 불가능함 | OS·구성·오류 근거를 보고. 특권 ADB·상시녹음·매사용 터치로 우회하지 않음 |
| 한국어·연속 발화가 실용적으로 인식되지 않음 | 온디바이스 엔진 대안을 평가. 비용 또는 오디오 전송 변경은 사전 승인 |
| 신선한 명시적 안전 상태를 확보하지 못함 | 실제 명령 차단. null·오래된 캐시·속도 0으로 P를 대체하지 않음 |
| 전송 후 결과를 알 수 없음 | UNKNOWN으로 보고하고 자동 재전송·나중 실행을 하지 않음 |

구체적 수행 절차와 결과 기록은 [실기기 검증](Device-Validation.md), 명령 계약은 [상태 머신](State-Machine.md)을 따른다.

## 공식 출처

다음은 문서 판단의 근거다. 문서 지원을 실기기 검증 완료로 바꾸어 해석하지 않는다. 가격과 플랫폼 정책은 구현 시 다시 확인한다.

| ID | 공식 자료 | 사용 범위 |
| --- | --- | --- |
| A1 | [FGS 백그라운드 시작 제한][A1] | 시작 예외와 while-in-use 예외 분리 |
| A2 | [VoiceInteractionService][A2] | 사용자 선택 비서의 수명 |
| A3 | [Companion device pairing][A3] | CDM 역할과 한계 |
| A4 | [Bluetooth ACL 이벤트][A4] | 연결 이벤트의 의미 |
| A5 | [SpeechRecognizer][A5] | 온디바이스 인식·지원 검사·신뢰도 |
| A6 | [RecognizerIntent][A6] | 오프라인 옵션·외부 오디오 입력 |
| A7 | [Android stopped state][A7] | 강제 중지 복구 경계 |
| A8 | [오디오 입력 공유][A8] | 입력 경합과 무음·장치 변경 |
| A9 | [CompanionDeviceService][A9] | 기기 presence 콜백 |
| A10 | [FGS 서비스 유형][A10] | microphone 선언·권한·부팅 제한 |
| S1 | [Samsung Application Management][S1] | 절전 및 예외 설정 |
| T1 | [Tesla 인증][T1] | OAuth와 scopes |
| T2 | [Tesla vehicle-command][T2] | 공식 Go SDK·서명 프록시 |
| T3 | [Tesla vehicle_data.proto][T3] | 기어값 구분 |
| T4 | [Tesla 가격][T4] | 사용량 과금과 월 할인 |
| T5 | [Tesla 과금·한도][T5] | 결제·한도·실패 요청 과금 |
| T6 | [Model Y 프렁크 매뉴얼][T6] | 직접 조회 접근 거부, 대상 차량 확인 필요 |

[A1]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
[A2]: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
[A3]: https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing
[A4]: https://developer.android.com/reference/android/bluetooth/BluetoothDevice#ACTION_ACL_CONNECTED
[A5]: https://developer.android.com/reference/android/speech/SpeechRecognizer
[A6]: https://developer.android.com/reference/android/speech/RecognizerIntent
[A7]: https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state
[A8]: https://developer.android.com/media/platform/sharing-audio-input
[A9]: https://developer.android.com/reference/android/companion/CompanionDeviceService
[A10]: https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
[S1]: https://developer.samsung.com/mobile/app-management.html
[T1]: https://developer.tesla.com/docs/fleet-api/authentication/overview
[T2]: https://github.com/teslamotors/vehicle-command
[T3]: https://github.com/teslamotors/fleet-telemetry/blob/main/protos/vehicle_data.proto
[T4]: https://developer.tesla.com/
[T5]: https://developer.tesla.com/docs/fleet-api/billing-and-limits
[T6]: https://www.tesla.com/ownersmanual/modely/ko_kr/GUID-356E0168-47E5-400F-AD83-4F1B86C7D991.html
