# Android 네이티브 아키텍처

> **v0.52 추가:** 현재 소스는 **`0.52.0-probe`**(`versionCode=52`)다. 아래 v0.50 경로를 명령 판정에 그대로 쓰고, 음성 인식 진단 화면에만 sherpa-onnx zipformer 한국어 그림자 인식을 더했다(Moonshine 재도입 아님, 명령 판정·실차 음성 시험 미사용). 구조는 [아래 그림자 경로](#android-온디바이스-speechrecognizer-pcm-파이프v050)를 본다.
>
> 이 문서는 현재 구현과 과거 이력을 구분한다. v0.50 전환 당시 소스는 **`0.50.0-probe`**(`versionCode=50`)로, v47~v49의 로컬 Moonshine STT(sherpa-onnx)를 제거하고 앱이 캡처한 16 kHz mono PCM을 Android 내장 온디바이스 `SpeechRecognizer`(ko-KR)에 `EXTRA_AUDIO_SOURCE` 파이프·segmented session으로 전달한다. 후보 중 하나라도 정확히 일치하면 수락하고, 점수 미제공(0.0 등)은 신뢰도 게이트를 생략한다. P·HANDOFF·명령 시간·안전 동의 게이트는 그대로이며 STT 일치는 차량 제어 허가가 아니다. v0.50 기기 smoke·사용자 발화 정확도·실차 성공은 미검증이다. 과거 `0.1.0`~`0.4.0-probe`의 한국어 시험 문장 일치 2회·무음 대조 `NO_MATCH(7)`·취소·해제 기록은 당시 **Android 시스템 음성 인식기** 직접 경로의 결과이며 v0.50 PCM 파이프 경로의 인식 성능 근거가 아니다. 실제 사용자 발화·음향 조건·네트워크 차단·자동 호출어·차량 접근·잠금·주머니 합격과 구분하며 상세 근거는 [검증 기록](Device-Validation.md)에 남긴다.

[Wiki 홈](Home.md) · [요구사항](Requirements.md) · [상태 머신](State-Machine.md)

## 설계 결정

React Native나 JavaScript 브리지는 사용하지 않는다. UI, 권한, Android 서비스 수명, 오디오 캡처, 상태 전이는 Kotlin으로 소유하며 Compose UI, Coroutines와 StateFlow, DataStore, AndroidKeyStore를 사용한다. **현재 유효한 차량 통합 방향은 로컬 BLE 우선**이며 Fleet/OAuth/도메인/서버/결제는 별도 승인하는 선택 인터넷 경로다.

현재 구현은 단일 `app` 모듈이다. 과도한 멀티 모듈화·DI 프레임워크·서버 선행 구현을 피한다. 아래는 제품 전체 책임 구분이며 구현 완료 목록이 아니다. 수동 문장 파서·로컬 dry-run 게이트웨이와 앱 전용 BLE 키 저장·등록·읽기 진단은 구현돼 있다. v22는 별도 Infotainment 기어 읽기까지 추가했지만 실제 차량 수신·제어 직전 신선한 P는 미확인이다. 자동 호출어와 UWB OOB/거리 세션은 아직 구현·검증 전이다. 실차 제어 게이트웨이는 구현됐지만 실제 차량 개방은 미검증이다.

| 영역 | 책임 |
| --- | --- |
| UI | Compose 화면, 초기 설정, 상태 표시, 명시 승인 입력. 서비스 재생성과 독립적으로 StateFlow를 구독한다. |
| platform | `VoiceInteractionService` 후보, 권한, Bluetooth/접근 신호, foreground service, 오디오 포커스 및 Android 수명 경계. |
| audio | 호출어 탐지, `AudioRecord`, STT 세션, PCM pre-roll, TTS와 자기 재인식 차단. |
| domain | 명령 파서, 안전 정책, 상태 머신, 만료·취소·중복 규칙. |
| gateway | 현재 음성 경로의 mock/dry-run과 별도 수동 BLE 등록·읽기 진단. 공유 프로토콜·키 경계를 재사용하는 네이티브 BLE 경로가 인증·상태·안전 게이트·명령 전송을 소유하며 프렁크 1회 제어까지 구현됐다. 실제 차량 개방은 미검증이고 v47 음성 경로는 신뢰도 `null`로 전송 전에 차단된다 |
| settings | DataStore의 비민감 설정과 현재 AndroidKeyStore의 앱 전용 비추출 하드웨어 P-256/ECDH 키. 선택 Fleet 토큰·서버 비밀 보관은 별도 승인·설계 전 |

### 현재 검증 앱 구현

| 파일 | 실제 책임 |
| --- | --- |
| `MainActivity.kt` | Activity 소유 음성 probe와 런타임 상태 수집, 사용자 권한·설정 진입, 저장 VIN의 시작 스냅샷을 사용하는 정확 광고명 CDM chooser 및 pending 변경 게이트. 화면 이탈 시 수동 음성 진단 정리; 주말 BLE 시험은 취소하지 않음 |
| `ui/AppActions.kt` / `ui/HeyTeslaApp.kt` | UI 효과의 기존 Activity/런타임 콜백 연결, 홈·설정·진단·차량 앱 키 등록과 뒤로 이동. 진단/키 등록 이탈은 수동 probe를 취소하며 서비스 BLE 시험은 유지 |
| `ui/HomeScreen.kt` / `ui/SettingsScreen.kt` | 실제 런타임 상태의 짧은 요약·다음 행동, 시스템 차량 등록·앱 키 등록·권한·기본 비서 설정. 가짜 차량 상태·명령 버튼 없음 |
| `ui/DiagnosticsScreen.kt` / `ui/VehicleVinSection.kt` | 진단 상단 공통 VIN 등록·마스킹·저장/복원 상태, 음성·주말 BLE·차량 키·UWB 지원·마이크·접근·이벤트 및 설정 앱 키 등록 화면. 중복 VIN 입력 없이 명시 작업·동의·취소를 유지하고 등록 보고와 인증 성공을 구분 |
| `ui/AppTheme.kt` / `ui/AppComponents.kt` | 차콜 색상·타이포의 단일 원본과 설정 행·제목·상세 행의 공통 표현 및 접근성 의미 |
| `DiagnosticApp.kt` | 프로세스 내 상태·이벤트, 관찰/주말 시험 요청·서비스 소유권 게이트, 기본 비서·권한 조건, DataStore의 비민감 선호. 시작/대기/정리 전체의 음성·기존 관찰 상호 배제. association ID는 내부 RAM에 두고 공개 상태는 count만 제공 |
| `VehicleVin.kt` / `VehicleVinController.kt` / `VehicleVinStore.kt` | VIN 형식·redacted 객체, Main 상태/입장 직렬화와 IO 암호화 저장·복원, 독립 AndroidKeyStore AES-GCM alias 및 noBackup 암호문. 정적 실패 코드·작업 시작 스냅샷·실행 중 변경 차단, 자동 실행/평문 fallback 없음 |
| `FieldEventLog.kt` | 허용된 비민감 이벤트의 JSONL 인코딩·단일 IO writer·제한된 큐·2 MiB append 전용 파일. 저장 대기·유실·한도·실패 상태 제공 |
| `AccessServices.kt` | 시스템 BLE presence 콜백과 기본 비서 경로, 음성 비서 진단 안내 |
| `ObservationService.kt` | 관찰 전용과 `BLE_FIELD` 모드의 `connectedDevice` FGS·지속 알림·CDM 소유권. 실행 조건 고정·후보/회차·보조 스캔·제한 재시도·bounded 정리. GATT 회차만 최대 45초 partial wake lock·30분 heartbeat |
| `MicrophoneService.kt` | microphone FGS, 16 kHz PCM 입력의 개수·RMS 요약, silenced·만료·종료 처리. 오디오 저장·STT 없음 |
| `SessionPolicy.kt` | 캡처 단일 세션·만료·실제 이탈·cooldown과 관찰 요청 토큰 정책. 이전 START·STOP이 새 관찰 소유권을 변경하지 못하게 함 |
| `BleConnectionProbe.kt` / `BleGattSession.kt` / `BleCccdSubscription.kt` | 서비스 소유 등록 차량 GATT 연결·Tesla 서비스/TX/RX 확인·RX 구독·짧은 관찰과 bounded 정리. 주소는 RAM에서 바이트 배열 API로 해석하며 TX 쓰기·인증·명령은 없음. CCCD 해제는 성공한 정확한 `[0, 0]` readback만 증거로 인정 |
| `BleFieldConfig.kt` / `BleFieldTrialPolicy.kt` / `BleGattOwnership.kt` | 기존 감지만 및 등록 주소 비교와 별도 인증 읽기 preset. 인증 모드는 신선한 광고만 접근당 1회·CDM/BT shadow·재시도 없음. native pause/close/lease·후보 만료·구 세대 봉인 유지 |
| `BleSupplementalScanner.kt` / `BleScanReceiver.kt` / `BleScanTarget.kt` / `TeslaBleAdvertisement.kt` | AltBeacon 2.21.2 location FGS·기본 기간과 정확한 OS 이름/주소 필터. 원 ScanResult timestamp·최신 batch·세대 봉인. receiver는 이전 PendingIntent 고아 정리만 담당 |
| `BleEventEvidence.kt` / `BleTrialSummary.kt` / `TeslaApproachReadSummary.kt` / `FieldEventLog.kt` | JSONL v9: 기존 BLE 근거와 접근별 BODY/DRIVE 검증·부분 결과·원본/수신 시각·정리 분리. 식별자/키/RX 원문 없음·미관측 null. 분석기 v1–v9 호환 |
| `UnsupportedRecognitionService.kt` | Android 비서 등록에 필수인 인식 서비스. 인식·지원 검사에는 명시적 비지원 오류를 반환하고 캡처·모델 다운로드·외부 인식을 시작하지 않음 |
| `SessionPolicyTest.kt` | 정책 경계 8개 회귀 테스트. 감지 기준 초기화 시 대기 세션 폐기·실제 이탈 및 cooldown 유지 포함. 실제 OS·차량 접근 시험의 대체물이 아님 |
| `ObservationLifecycleTest.kt` | 관찰 시작 중 OFF, 낡은 START·STOP·서비스 종료, 중복 ON, 종료 후 명시적 재시작, 새 프로세스의 이전 intent 거부 경계 6개 |
| `SpeechSupportProbe.kt` | Activity 소유의 온디바이스 지원 조회. `createOnDeviceSpeechRecognizer()`의 `checkRecognitionSupport()`로 `ko-KR` 지원 메타데이터를 조회하며 마이크·인식은 시작하지 않음. 조회 결과는 준비 상태이며 인식 성공이 아님 |
| `SpeechSupportPolicy.kt` / `SpeechSupportPolicyTest.kt` | `KoreanLanguageSupport`·`SpeechSupportMetadata`로 지원 메타데이터를 분류 |
| `AndroidPcmRecognizer.kt` | 앱 PCM을 `EXTRA_AUDIO_SOURCE` 파이프에 non-blocking으로 쓰고 `EXTRA_SEGMENTED_SESSION`의 `onSegmentResults`를 결합해 n-best 후보·점수를 반환. 타임아웃 운영 15초·재생 테스트 30초. 실패는 부분 세그먼트가 있어도 명령 없음 |
| `SpeechMicCapture.kt` / `SpeechRecordingStore.kt` | 16 kHz mono PCM 최대 10초(320,000바이트) 캡처와 개발 시험 녹음의 `no_backup/speech-recordings/<id>/` 보관 |
| `SpeechRecognitionProbe.kt` / `SpeechPcmFixture.kt` / `SpeechPcmFixtureTest.kt` | Activity 소유 시험. 직접 마이크 최대 10초 캡처·고정 파일·무음 대조를 같은 `AndroidPcmRecognizer` 경로로 인식하고, 캡처 종료·취소·자원 정리·재시작 요구를 관리. WAV는 PCM16·16 kHz·mono만 허용 |
| `res/raw/speech_trial_ko.wav` | 설치된 macOS Yuna로 생성한 고정 비개인 시험 문장. 음성 모델·개인 녹음이 아닌 재현용 합성 데이터 |
| `TeslaBleKeyStore.kt` | 우리 앱 UID의 고정 alias에서 P-256 키 생성·재사용. 개인키는 AndroidKeyStore 밖으로 추출하지 않고 제공자 ECDH를 사용한다. TEE/StrongBox의 생성 키·`PURPOSE_AGREE_KEY`만 허용하며 손상·소프트웨어/imported 키를 삭제/교체하거나 fallback하지 않음 |
| `TeslaBleProtocol.kt` | 설정 등록과 읽기 진단이 공유하는 `TeslaBleConversation`. Driver/android_device `PRESENT_KEY`, 새 VCSEC handshake·HMAC·personalized AES-GCM `GET_STATUS`, 응답 무결성·request hash·counter 검증. 제어 명령·자동 재전송·세션 캐시 없음 |
| `TeslaBleKeyProbe.kt` | 수동 등록/읽기·접근 읽기의 직접 Android GATT, 단계/쓰기 기한·기존 프로토콜·전역 예약. 화면/잠금/권한/대상 상실 시 전송 봉인·로컬 정리. 등록 보고와 검증된 상태를 분리하며 close 불명은 예약 유지 |
| `TeslaNativeGattOwner.kt` / `TeslaNativeGattConnection.kt` | 시도별 native handle 소유권·할당 불명·취소·단계별 단일 pending 요청·이전 handle/요청 거절·Service Changed 봉인. 실제 SDK 생성·disconnect/close 호출 경계 |
| `TeslaGattWritePump.kt` / `TeslaGattWriteObservation.kt` | callback 밖 단일 쓰기 예약·접수/완료 분리·분할 진행과 마지막 실제 쓰기 관측 보존. 로컬 순번은 native 요청 ID가 아님 |

`0.5.0-probe`에서 UI 책임을 위와 같이 분리했다. 음성 probe는 화면 재구성마다 만들지 않고 Activity가 소유한다. `onPause`/`onDestroy` 정리를 보존하며 같은 Activity의 진단 이탈은 `leaveDiagnostics()`로 지원 조회·STT·수동 마이크를 정리한다. `0.13.0-probe`의 BLE 시험은 별도로 서비스가 소유하여 화면 이탈·HOME·Activity 재생성에도 유지한다. v24는 VIN을 암호화 설정으로 보존하지만 편집 중 원문은 `remember` RAM에만 두고 제출·이탈·배경 전환 때 비운다. VIN 복원은 진단 자동 재시작이 아니며 원문을 saved UI state·Intent·로그에 옮기지 않는다. 화면 실측 범위는 [실측 기록](Device-Validation.md)을 따른다.

`0.6.0-probe`의 접근 진단은 기본적으로 관찰 전용이다. `automaticMicrophoneEnabled`는 프로세스 기본값 false·비영속이며, 관찰과 세션·음성 예약이 모두 꺼진 상태에서만 별도 변경한다. 관찰 전용 출현은 정책 debounce를 만들기 전에 반환하고 `automaticAllowed()`도 별도 동의를 요구한다. 진단 비활성화 시 동의·지연 작업을 해제한다. 활성화 시 이전 현재 감지를 지우되 실제 이탈·cooldown 조건은 보존한다. 출현·이탈 콜백 횟수와 최근 이벤트는 비민감 RAM 상태로만 누적하며 중복 콜백도 포함한다.

`0.7.0-probe`는 별도 시험 파일을 `noBackupFilesDir/field-diagnostics/events.jsonl`에 보관한다. 프로세스·시험 UUID와 시각·고정 이벤트 코드·허용 상태만 기록하며 기존 RAM 표시와 구분한다. 파일은 자동 삭제·회전·덮어쓰지 않고 프로세스 재시작 후 추가 기록한다. USB 디버깅의 `run-as`로 디버그 APK 파일을 회수한다. 별도 상주 서비스·마이크·깨우기·외부 전송은 추가하지 않는다. 프로세스 생존이나 수신하지 못한 콜백의 복원을 보장하지 않는다.

`0.8.0-probe`는 접근 ON의 CDM 수명을 `ObservationService`로 옮긴다. `observationStartPending`은 요청 대기, `observationServiceRunning`은 FGS 승격 완료, `observing`은 CDM 요청 수락, `present`는 실제 BLE 출현으로 구분한다. 알림·Bluetooth 조건을 시작 전에 검사하고 앱이 표시된 상태의 명시적 ON에서만 요청한다. 앱·알림 종료와 실패는 동일한 정리 경계를 사용한다. 서비스는 `START_NOT_STICKY`이며 프로세스 재생성 시 OFF다. 별도 자동 마이크 동의·기본 비서·기존 오디오 게이트를 유지하며 관찰 ON 자체는 캡처를 시작하지 않는다. 상시 wake lock·추가 BLE 스캔·주기적 원격 조회는 도입하지 않는다. 유형·권한은 [Android 공식 connectedDevice FGS 조건](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)을 따른다.

`0.13.0-probe`는 같은 서비스의 `BLE_FIELD` 모드에 반복 BLE 회차를 이관한다. 실제 BLE 출현당 한 회차이며 종료 후 실제 이탈을 요구한다. 회차 정리 중 재출현은 이전 소유권을 모두 비운 뒤에만 진행한다. `BT_CONNECTED`는 접근 트리거가 아니다. 화면/HOME/잠금과 독립적이나 명시적 중지·Bluetooth/권한/등록/로그 조건 상실은 신규 트리거를 봉인하고 서비스 정리로 이어진다. 대기 중에는 마이크·wake lock·주기적 GATT 연결을 사용하지 않는다. heartbeat는 CPU 절전 중 지연될 수 있으며 파일 공백만으로 서비스 종료나 차량 미접근을 단정하지 않는다.

`0.14.0-probe`는 위 동작을 기준 방식으로 보존하고 같은 정책의 선택 조건으로 개선 방식을 제공한다. BT 보조 미선택은 shadow 계측만 남긴다. 선택된 외부 BT·CDM·필터 스캔은 후보로 병합하며 자체 GATT 중/close 후 15초의 BT 연결은 억제한다. 개선 후보의 CDM 이탈은 30초 유예하고 스캔 증거는 180초 뒤 만료한다. 감지만은 후보를 기록하되 probe·wake lock을 만들지 않는다. 재시도는 선택된 실패에만 2회/총 40초·실제 close 후 2초·잔여 12초 이상을 요구하며 정리 실패·COMPLETE·BLOCKED·프로필 없음·중지는 재시도하지 않는다.

필터 스캔은 주소 또는 광고명 하나를 사용한다. v25는 LOW_POWER·5초 배치의 `startScan(filters, settings, PendingIntent)`로 등록하고, 비공개 명시 receiver에서 현재 RAM 소유자·창별 token·기한을 확인한다. 40초 창 예약·최소 120초 시작 간격을 유지하고 GATT 동안 정지한다. Handler는 절전 중 지연될 수 있어 실제 radio 등록의 40초 상한을 보장하지 않는다. 만료 후 현재 세션 전달은 소비하지 않고 등록만 정리한다. `ScanResult.timestampNanos`의 원 monotonic 시각을 사용해 배치 지연이 증거 수명을 늘리지 못하게 한다. offloaded filtering/batching 미지원·권한·SDK 등록 오류·전달/정리 실패를 구분하고 무필터·고빈도 fallback은 없다. 창별 시작/종료와 후보 첫 match를 JSONL v7로 기록하며 패킷마다 행을 만들지 않는다.

`noBackupFilesDir/ble_pending_scan_token`은 무작위 token만 가진 정리용 메타데이터다. scan 등록 전에 동기 저장하고 동일 PendingIntent의 stopScan 정상 반환 뒤에만 비운다. VIN·광고명·MAC·키는 저장하지 않는다. Application 시작과 소유자 없는 receiver 전달은 고아 등록만 정리하며 실행을 복원하지 않는다. 정리 실패는 process-wide 진단 입장 차단으로 남는다. 이 전환은 스캔 주기 증가나 배터리 절감 입증이 아니며 실제 잠금/주머니 광고 수신과 전력 비용은 별도 측정한다.

v26은 같은 readiness 점검에서 scanner의 elapsed 기한을 조정한다. CPU를 새로 깨우지 않으며, 이미 실행 기회가 생겼을 때 밀린 uptime 예약을 기다리지 않고 만료 등록을 정리한다. 스캔 SDK 오류 2/3/5만 정상 stop 이후 최대 2회·120초/240초 cooldown으로 복구하며 기존 최소 시작 간격을 지킨다. 정상 창/수신으로 예산을 되돌리지 않고 권한·정리·비지원·불명 오류는 중단한다. 무광고에 대한 watchdog 복구는 없고 GATT 연결 재시도와도 별개다. JSONL v8은 예약 지연·복구·광고 전달 처리 시점 잠금을 분리하며 패킷별 로그·식별자 필드는 추가하지 않는다.

실행의 비민감 미정상 marker는 시작 전에 동기 `commit()`으로 기록하고 최종 회차·중지 행의 `fd.sync`와 pending 0 확인 후에만 정상 종료로 지운다. 새 실행은 이전 비동기 완료가 새 marker를 지우지 못하도록 봉인한다. close/로그 실패는 marker를 유지한다. 다음 프로세스는 이전 미정상 실행을 원인 불명으로 남길 뿐 실행을 자동 복원하지 않는다. JSONL v6의 `bleTrial`과 `bleEvidence`는 해당 증거 행에만 있으며 시작/heartbeat는 attempt 0·IDLE·단계시간 null로 이전 회차를 재사용하지 않는다. 기존 v1~v5 바이트는 유지한다.

v27은 명시적 광고명 감지 ON 설정만 복원한다. 이전 후보·GATT·명령·마이크는 재생하지 않으며 다른 진단 모드는 자동 재시작하지 않는다. 백그라운드 location FGS 시작 제한과 사용자 종료를 존중한다. 위 v25/v26 스케줄·로그 실패 종료·복원 금지 설명은 해당 버전의 이력이며 현재 스캔 경로는 아래 v27 계약으로 대체한다.

v46까지 `0.2.0-probe`의 한국어 지원 진단은 `UnsupportedRecognitionService`의 인식 제공 기능과 별개였다. 전자는 OS의 온디바이스 서비스에 지원 정보만 물었고, 후자는 우리 앱에 들어온 인식 요청을 계속 비지원 오류로 거절한다. 지원 조회는 기존 오디오 세션의 소유권이나 종료 사유를 바꾸지 않았다. 완료·오류·시간 초과·취소마다 인식 객체를 해제하고, 완료된 메타데이터는 화면 이탈만으로 지우지 않았다. Activity 재생성 시에는 미조회 상태로 시작했다. v47은 OS 지원 조회 자체를 제거했으며 `UnsupportedRecognitionService`의 비지원 거절만 남는다.

VIN 광고명 계산은 [공식 Tesla BLE 코드의 `VehicleLocalName`](https://github.com/teslamotors/vehicle-command/blob/main/pkg/connector/ble/ble.go)을 따른다. CDM은 이름 필터로 사용자의 최초 선택을 받은 뒤 association 기반 presence를 요청한다. 광고 노출·주소 회전·장기 관찰 가능성은 미검증이며, 이 식별자는 차량 인증이나 발화자 인증이 아니다.

설정의 앱 키 등록과 개발자 진단의 읽기 전용 조회는 같은 프로토콜을 사용하되, 주말 BLE 관찰·음성 dry-run과는 별도 경로다. 사용자 NFC 키카드 접촉·차량 UI 승인은 앱이 대행하지 않는다. 공식 Tesla 앱의 폰키·사설 키·세션을 가져오거나 우리 앱 개인키를 서버로 옮기지 않는다. [공식 등록 계약](https://github.com/teslamotors/vehicle-command/blob/main/pkg/vehicle/security.go)은 P-256 `PRESENT_KEY`를 BLE로 보내며 정상 반환도 전송만 보장한다. 등록/infotainment 동기화 확인용 `DomainInfotainment SessionInfo` 안내와 현재 VCSEC 읽기 진단은 구분한다.

v42 당시 소스·기기 설치는 `versionCode=42` / `0.42.0-probe`였다. 화면 표시 음성 프렁크 경로·S23 기기31개 검증·실제 화면 조작을 확인했으며, 아래 내용은 그 시점의 시스템 음성 인식기 기록이다. 현재 v0.50은 앱 PCM을 Android 온디바이스 `SpeechRecognizer`에 파이프로 전달하므로 [최신 검증 절차](Device-Validation.md#0500-probe--android-온디바이스-speechrecognizer-전환)를 따른다.

### v42 화면 표시 음성 프렁크

**이 절은 v42~v46의 Android 시스템 음성 인식기 실차 음성 기록이다.** v47~v49는 Moonshine을 썼고 v0.50은 n-best 정확 일치·점수 미제공 시 신뢰도 게이트 생략으로 바꿨으므로, 아래 “최종 단일 후보·신뢰도 0.85 이상” 조건은 현재 경로에 그대로 적용되지 않는다.

- 기존 `SpeechRecognitionProbe`·`VehicleGateway`는 차량 미전송 dry-run으로 유지한다. 별도 `FrunkVoiceProbe`는 온디바이스 DIRECT_MIC만 사용한다. RAM 동의·설치된 ko-KR·foreground/잠금 해제·감지 정상 중지·단일 예약을 확인한다. 명령10초/확인8초·최종 단일 후보·신뢰도0.85 이상·정확한 “헤이 테슬라 프렁크 열어줘”와 “확인”을 요구한다. 확인 차례는 화면 안내이며 TTS/상시 호출어 탐지가 아니다.
- `FrunkVoicePolicy`의 승인 ticket은 한 번만 소비한다. recognizer cancel/destroy 정상 반환→speech 예약 반환→차량 작업 입장을 같은 Main 직렬 경계에서 수행한다. VIN/association snapshot·화면·권한·동의/작업 기한을 재검사한다. HOME·잠금·진단 이탈·회전은 신규 전송을 취소하며 화면 복귀/프로세스 복원은 승인 재생 사건이 아니다.
- `TeslaFrunkSequence`는 단일 GATT에서 fresh DRIVE P→새 VCSEC actuator 대화→새 BODY 상태 조회를 수행한다. `TeslaBlePurpose.FRUNK_OPEN`만 공개 `ClosureMoveRequest.frontTrunk=MOVE(1)`를 생성한다. 기존 등록키만 사용하고 domain/route/UUID/HMAC/AEAD/response counter 검증을 유지한다.
- 확인부터40초, P 수신 monotonic5초·원 wall10초를 제한으로 사용한다. 주말 실측의 차량/폰 wall 시계차 약1.1초를 고려해 원 wall 미래차만2초 허용한다. 원 시각은 보정하지 않으며 monotonic 미래·역행에는 이 허용치를 적용하지 않는다. 승인 검사와 실제 actuator SDK 발급 직전에 P·기한·취소를 다시 검사한다.
- `TeslaFrunkPolicy`는 offset별 미접수201만 기존 펌프로 복구한다. SDK0 이후 동일 offset·전체 명령 재전송은 금지한다. SDK 예외/반환 불명은 전송 가능성을 남긴다. 명령 frame 완료·AEAD 검증 ACK·명시 거절·후속 BODY의 명시 OPEN을 구분하며 ACK와 OPEN 모두 있어야 전체 완료다. 응답 불명 뒤 허용하는 것은 남은 기한 안의 BODY 조회1회뿐이며 명령은 재발급하지 않는다.
- 로그v21은 요청·SDK 접수·차량 응답·열림 관측·정리 Boolean과 고정 이벤트만 보존한다. `COMMAND_SENT`는 첫 actuator 조각 SDK0이며 전체 frame·차량 승인·물리 개방 완료가 아니다. 분석기의 `frunkEvents`는 BLE 시험 ID 없는 수동 회차도 비민감 값으로 제공한다. 원문 발화·음성·키·VIN·주소·payload는 기록하지 않는다.

### v41 쓰기 BUSY 제한 복구·BODY 절전 관측

- `TeslaGattWritePump`는 SDK 반환201만 BUSY_WAIT로 분리해100ms 후 최대2회 추가 발급한다. 다른 SDK 오류·예외·SDK0 뒤 callback 오류/timeout은 동일 쓰기를 재전송하지 않는다. 예약 세대·중복 큐·권한/OFF/owner·취소·원 쓰기5초/단계/회차 기한을 검사하며 기존 조각 buffer를 재사용한다.100ms는 우리 제한값이지 Tesla APK에서 확인한 수치가 아니다.
- `TeslaNativeGattOwner`는 미접수 WRITE201의 pending 토큰만 해제하고 새 발급은 새 Request를 사용한다. 이전 토큰을 캡처해 게시된 callback은 거절한다. Android에는 native 쓰기 요청ID가 없으므로 새 발급 이후 진입한 callback의 실제 RF 요청까지 식별한다고 주장하지 않는다.
- `writeBusyRetryCount`는 실제 SDK 추가 발급 직전에만0→1→2로 증가하고 다음 조각/쓰기는0부터 시작한다. 예약 취소는 마지막 SDK 결과를 지우지 않는다.201만으로 차량 전송·키 등록·차량 인증이 성립하지 않는다.
- `TeslaBleConversation.receiveBodyStatus`는 매칭·AEAD 검증 뒤 원 `vehicleSleepStatusValue`를 `TeslaReadOnlyStatus.bodyVehicleSleepStatus`에 전달한다. sequence는 BODY 수신 elapsed와 함께 보존하고 DRIVE timeout에도 지우지 않되 새 회차에 재사용하지 않는다.0=UNKNOWN,1=AWAKE,2=ASLEEP, 미래 nonnegative 값은 미지원 코드다. 당시 BODY 상태이며 이후 Infotainment 준비나 현재 상태를 보증하지 않는다.
- 로그v20·분석기·진단 상세는 이 두 관측을 전달한다. BODY 검증/수신 시각 없는 절전 값과 범위 밖 재발급 횟수는 null이다. 구버전은 새 필드를 관측한 것으로 해석하지 않는다. BODY/DRIVE 세션 분리·domain/route/UUID/HMAC·응답 암호 검증·제어 금지를 유지한다. 자동 wake/bond·HELLO 반복·timeout 연장은 추가하지 않는다.

### v40 Tesla 연결 모드·기존 GATT 재접속

- `TeslaNativeGattOwner.connectNative`는 지원 OS가 모두 SDK36 이상이므로 Tesla APK의 SDK34+ 조건에 맞춰 `autoConnect=true`를 실제 SDK에 전달한다. 불필요한 버전 분기·별도 라이브러리·무기한 복원은 추가하지 않는다.
- 접근 인증의 전송 전 초기133만 원래 NativeTransport·owner·handle을 유지한 채2초 뒤 `BluetoothGatt.connect()`를1회 호출한다. SDK false/예외·handle 없음/불명·연결 진전·기한/권한/BT/OFF 상실에는 새 handle fallback 없이 기존 종료 경로로 정리한다. 상위 v39의 새 광고/3.5초/최대2회 읽기 복구와는 별도 한도다.
- 연결 콜백 진입에서 generation을 캡처하고 MainHandler 처리에서 검증한다. 대기 중 중복133은 기존 예약을 유지하고 CONNECTED0은 예약 취소·초기화1회로 처리한다. 연결 재발급 이후 native callback에 새로 진입한 이벤트의 원래 RF 시도는 SDK 요청ID가 없어 구별할 수 없다.
- 최종 종료는 disconnect+close·예약 반환/보존을 유지한다. 최초500ms 준비·원 광고10초·단계/전체40초 기한·키/암호·쓰기/부분결과 경계를 늘리거나 완화하지 않는다.
- 로그v19의 `gattConnectAction=CREATE/RECONNECT`와 `gattConnectAccepted`는 실제 SDK 호출/Boolean 관측이다. `gattAutoConnect`는 해당 handle 생성 인자이며 인자가 없는 `connect()`의 별도 설정값이 아니다. SDK 접수·콜백 연결·차량 인증을 분리하고 구버전 미관측은 null로 유지한다.

### v39 60초 이탈 대기 제거·제한 재조회

- 인증 읽기는 `READ_START_FRESH_MS=10초`를 광고 최신성·부재 기준으로 사용하며60초 이탈을 재조회 전제로 삼지 않는다. GATT/정리 중에는 후보 만료 대신 원래40초 조회 기한만 적용하고, 실제 정리 후10초 재관측 유예로 스캔 중단을 이탈로 오인하지 않는다. 비인증 모드의60초 후보 TTL은 변경하지 않는다.
- 일시 GATT 실패·단계/쓰기 타임아웃만 localClosed·예약 반환 확인 후3.5초 대기한다. 완료 이후 실제 수신한 신선한 새 ScanResult에서만 같은 후보의 다음 회차를 만든다. timer·이전 세대·미래/역순/중복·완료 이전 수신 결과는 조회를 시작하지 않는다.
- 후보당 최대2회·첫 시작의40초 기한·최소 연결/정리 잔여 예산을 유지한다. COMPLETE·BLOCKED·CANCELED·보안/프로토콜 오류·OFF·close 불명은 자동 반복하지 않는다. 초기133 내부1회 재접속과 전체 조회 재시도는 별도 한도이며, 차량 제어 명령의 재전송 정책이 아니다.
- 로그v18은 기존 후보·회차·`RETRY_SCHEDULED/COOLDOWN`으로 복구를 구분한다. 분석기는 v18의 두 회차·clean 실패·대기/예산/예약 근거를 확인할 때만 복구로 분류하며 v1~v17의 반복 조회 위반 판정은 유지한다. 복구 관측·SDK0·구독은 인증 성공이 아니다.

### v38 직접 GATT 전환

- `connectGatt(autoConnect=false, TRANSPORT_LE, PHY_LE_1M, MainHandler)`의 반환 handle을 시도별로 보유한다. 연결 status0/state2→서비스/TX/RX 속성·CCCD 확인→MTU callback의 실제값→로컬 notification 설정·CCCD SDK 접수/성공 callback→기존 프로토콜 순서다. 같은 단계의 중복 callback이 초기화를 재실행하지 않는다.
- SDK callback 진입에서 시각·pending identity를 캡처하고 항상 MainHandler에 post한다. 이것은 앱이 실제 SDK callback을 실행한 시각이지 RF/controller 발생 시각이 아니다. 현재 op/handle/요청·권한·기한·단계를 다시 확인하며 Service Changed는 `GATT_SERVICES_INVALIDATED`로 봉인·정리한다.
- API33+ `writeCharacteristic(characteristic, value, WRITE_TYPE_DEFAULT)`를 사용한다. 요청 관측 후 SDK 직전 취소를 재검사하고 이때까지는 queued로 남긴다. 실제 SDK 호출 직전만 마지막 제출로 바꾸고 SDK 원 Int/고정 예외 분류/반환 시각을 RESULT에 기록한다. 접수0만으로 offset을 진행하지 않으며 올바른 TX callback 성공 뒤에만 다음 MTU-3 조각/RX 처리를 진행한다.
- 종료는 먼저 op·큐·settle·retry·callback을 봉인한다. 할당 중 취소는 실제 반환 handle 부착 후 정리하며, 할당 불명/close 예외는 예약을 유지한다. 준비/할당 대기가 끝난 뒤 cleanup을 한 번만 시작해 null 반환/예외의 stop+finally가 종료 통지를 중복하지 않는다. close 정상 반환은 로컬 호출 완료 관측이며 원격 disconnect·플랫폼 내부 자원 회수 ACK가 아니다.
- 기존500ms 준비·초기 native133에만 close 확인→1초→새 handle1회·총40초·원 광고10초·후보60초·인증/암호·키/VIN·부분 결과 경계는 유지한다. Nordic 기본 discovery 대기/내부 retry와 package-private bridge는 남기지 않는다. Tesla의 legacy write API·1초 device-info 지연을 HELLO 정책으로 복사하지 않았다.
- 로그v17은 `gattTransport=NATIVE`와 SDK 발급/반환·callback·RX 시각을 기록하고 library 전용 필드는 생산 writer/UI에서 제거한다. 분석기는 보존된 v14~v16 행의 원 library 관측만 계속 읽으며 native 관측으로 변환하지 않는다. 개인키·VIN·주소·원시 payload·예외 원문은 기록하지 않는다.


### v27 AltBeacon 배포 소스 전환

- [AltBeacon 2.21.2 Maven 배포본](https://repo.maven.apache.org/maven2/org/altbeacon/android-beacon-library/2.21.2/)을 `:altbeacon`으로 고정했다. sources SHA-256 `5973dadd84879d6eb6e8b2e8c72e8b3ba0e49e8ab291ac75712a4fadcb8f79c3`, AAR SHA-256 `da1ff7986f672b56c902775a7c2659e7ea78bb1ac3f11c8515d2e2860e8804b5`. Apache LICENSE/NOTICE와 AAR의 거리 모델 리소스를 보존한다. Tesla RSSI를 정확한 거리로 변환하는 기능을 추가한 것은 아니다.
- `BeaconManager.java`의 exclusive `setRawScanFilters`/`setRawScanCallback`, `CycledLeScannerForLollipop.java`의 native ScanResult/세대/시작·정지·오류 전달, 신규 `RawScanCallback.java`만 Tesla 입력 어댑터다. `PermissionsInspector.kt`는 compileSdk 36 nullable 필드를 처리하는 호환 수정이다. 이후 버전 갱신 때 이 네 파일과 OS 필터·timestamp 경로를 대조한다. stock 공개 API라고 설명하지 않는다.
- `NonBeaconLeScanCallback`의 콜백 도착 시각을 원 수신 시각으로 위장하지 않는다. native OS 필터를 비콘 manufacturer/service-data 필터 대신 정확한 Tesla 이름/선택 주소로 대체한다. 가짜 parser·무필터 OR fallback·MAC 고정·GATT UUID 광고 포함 가정은 없다.
- `ForegroundServiceScanStrategy`가 `BeaconService`의 location FGS·알림 102·스캐너를 소유한다. 보조 스캔 중 `ObservationService`는 bound-only CDM/정책/GATT 소유자다. `MainActivity`가 foreground/background를 알리고 기본 스캔 기간 1,100ms·30,000ms 및 무선 모드·`longScanForcingEnabled=false`를 유지한다. `Settings.ScanPeriods`의 두 betweenScanPeriod는 명시적으로 0ms다. `IntentScanStrategy`와 병행하지 않는다.
- binding 수락과 native 스캔 등록은 구분한다. FGS 미실행/fallback을 실패로 드러내고 소비자를 해제한다. GATT pause·최종 stop은 SDK stop 무예외 반환 이후에만 진행하며 이는 RF ACK가 아니다. native callback은 등록 시점 세대로 캡처해 이전 결과로 새 후보를 되살리지 않는다.
- 신규 로그만 512KiB×4·큐 512로 순환하며 로그 실패는 감지 중지에서 분리한다. 복원은 ON 설정·VIN·권한을 다시 확인하고 새 후보로 시작한다. ApplicationExitInfo의 USER_REQUESTED는 kill-background/force-stop을 공개 사유만으로 세분할 수 없어 자동 복원을 보수적으로 억제한다.


### v28 접근 기반 기존 키 읽기

- `TeslaApproachReadRequest`는 현재 광고의 기기·원 시각·owner/token/후보/회차/association·기한을 RAM에서만 묶는다. `TeslaApproachReadSequence`는 기존 프로토콜을 바꾸지 않고 같은 연결에서 BODY→별도 fresh DRIVE conversation을 순차 실행한다. 무결성 검증은 차량 인증서 기반 신원 인증과 다르다.
- `TeslaBleKeyProbe`가 기존 native 연결/발견/MTU/구독/fragment/정리를 재사용한다. `getExistingReadOnly()`는 기존 하드웨어 P256 alias만 읽는다. 수동 읽기도 새 키를 생성하지 않으며 키 생성은 명시적 등록 경로에만 남는다.
- runtime은 현재 FGS owner·request 인스턴스·회차·native pause·VIN/association/권한/기한으로 background 예외를 제한한다. Activity의 일반 취소는 수동 목적만 대상으로 한다. 40초에는 pause 대기도 포함되며 Keystore IO는 전송을 봉인한 후 반환까지 소유권을 유지한다.
- 결과 snapshot은 RAM 및 허용된 v9 scalar 로그로 전달한다. 양쪽 응답 검증과 실제 자원 해제·예약 반환이 모두 있어야 전체완료다. 복원은 감지만이며 조회 목적·후보·결과를 재생하지 않는다.

### v29 쓰기 요청 진단

- `TeslaBleKeyProbe`는 SDK 호출 전 `TESLA_APPROACH_WRITE_REQUESTED`, 반환/고정 예외 뒤 `TESLA_APPROACH_WRITE_RESULT`, 유효 native 완료에서 `TESLA_APPROACH_WRITE_CALLBACK`을 기록한다. SDK nonzero는 `GATT_WRITE_REJECTED`, 예외는 `GATT_WRITE_EXCEPTION`, callback 실패는 `GATT_WRITE_FAILED`다. SDK 반환 0은 차량 인증 성공이 아니다.
- v10 로그의 nullable 쓰기 관측은 로컬 순번·쓰기 방식·TX 속성·MTU·프레임/조각 길이·offset·pending·요청/반환/콜백 monotonic 시각을 포함한다. 순번은 native request ID가 아니며 예외 원문·주소·키·VIN·패킷은 기록하지 않는다. 마지막 실제 쓰기 관측은 정리/최종 결과까지 유지한다.
- 기존 TX 이벤트 및 새 REQUESTED 이벤트 이후 owner/권한/기한을 다시 검사해 재진입 취소 뒤 native 호출을 막는다. offset은 SDK 접수가 아닌 유효 성공 callback 이후에만 진행한다. 근거 없는 delay/retry/NO_RESPONSE 전환은 없다.

### v30 DRIVE 응답 진단

- `TESLA_APPROACH_RX_NOTIFICATION`은 현재 op/GATT/RX 소유 경계 뒤의 실제 native callback 관측, `RX_FRAME`은 decoder가 완성한 프레임, `RX_RESPONSE`는 conversation.receive 판정 후의 고정 분류다. 이전 세대·다른 characteristic·취소 입력을 새 회차 통계에 붙이지 않는다. 새 로그 이벤트 뒤에도 allowed를 다시 검사해 취소 후 송신을 막는다.
- v11의 RX/프레임/protocol count는 query별이 아닌 회차 누적이다. 마지막 RX/프레임 시각은 완성 fragment의 native monotonic 시각을 사용한다. `responseQuery`는 마지막 실제 처리 대상이므로 현재 `query=DRIVE_STATE`라도 마지막 관측이 BODY일 수 있다. count0은 관측 경로에서 미수신이지 차량이 송신하지 않았다는 증명이 아니다.
- optional protocol observer는 도메인·route·요청 UUID 불일치, ACK 대기, SessionInfo 검증/제외, 상태 검증/미완성을 고정 사유로만 기록한다. 매칭·암호·ACK 기한 판단은 그대로다. raw RX·키·VIN·주소·UUID·route·hash·예외 원문은 저장하지 않는다.

### v31 GATT 단일 쓰기 큐

- `TeslaGattWritePump`는 op당 재사용 runnable 하나를 가진다. descriptor pending·queued·SDK 제출·callback 대기를 구분하고 첫 frame/후속 chunk/새 protocol SEND를 모두 같은 MainHandler 큐에 등록한다. callback 내부에서 native 쓰기를 즉시 제출하지 않는다. 추가 시간 지연은 없다.
- 등록 전·예약 이벤트 뒤·runnable 실행·SDK 직전에 owner/GATT/권한/기한/봉인과 현재 frame을 검사한다. stop은 runnable 제거와 봉인을 수행해 살아남은 이전 runnable도 제출하지 못하게 한다. SDK nonzero는 재시도 없이 실패·봉인, SDK0은 callback 대기, 성공 callback만 offset을 진행한다.
- v12는 native 연결 status/state/entry 시각, CCCD SDK 반환/native 완료와 queue/실제 제출 시각을 별도 nullable로 기록한다. 봉인 뒤 disconnect는 최종 결과를 덮지 않는다. 마지막 실제 쓰기와 새 예약을 분리해 예약 취소가 이전 SDK/callback 관측을 지우거나 새 예약에 붙이지 않는다.
- queue false는 취소·해제를 포함하므로 SDK 접수나 성공 증거가 아니다. 마지막 실제 writeSequence가 마지막 QUEUED보다 작을 수도 있다. 실제 호출/접수/완료는 dispatch 시각·SDK 반환·native callback을 각각 확인한다. 임의 지연·앱 retry·autoConnect 변경·wake lock은 없다.

### v36 초기 GATT 133 제한 재접속

- 접근 읽기의 초기 `ConnectRequest.fail(133)`에 한해 기존 GATT의 `closeConfirmed()` 성공 후 같은 조회 회차에서 한 번 새 manager를 만든다. 대기는 단조 시계 기준 1초이며 원 광고 최신성·OFF·권한·association·CONNECTING/전체 기한을 다시 검사한다. 초기 scan pause 뒤 500ms 대기는 유지한다.
- 연결 성공·서비스 탐색·프로토콜 전송 이후 오류, 수동 조회·키 등록, 다른 오류에는 적용하지 않는다. 조회/인증 요청 재송신·기한 연장·autoConnect·차량 깨움은 없다. Nordic 내부 retry는 0으로 유지해 첫 native handle만 확보하는 소유권 계약을 우회하지 않는다.
- 정리 실패는 owner와 lease를 보존하며 재접속을 금지한다. retiring manager의 observer·서비스 무효화·initialize·늦은 callback은 새 manager 상태를 변경하지 못한다. `TeslaGattConnectRetry`의 예약은 취소/봉인 시 제거한다.
- schema16의 기존 summary로 `TESLA_APPROACH_GATT_RETRY_SCHEDULED/STARTED/CANCELED`를 남긴다. 최초 133을 보존하고 STARTED에서는 이전 연결 시각/status를 비운다. STARTED는 대기 종료이며 실제 새 enqueue·native 연결 성공과 다르다. 분석기는 이를 같은 조회 회차의 단계로 보존한다.
- 133은 원인명이 아니다. [Nordic 공식 논의](https://github.com/nordicsemi/Android-BLE-Library/issues/170#issuecomment-693442033)와 [2.11.0 재접속 API](https://github.com/nordicsemi/Android-BLE-Library/blob/2.11.0/ble/src/main/java/no/nordicsemi/android/ble/ConnectRequest.java)를 참고했으며 OS·차량 측 문제 해결을 보장하지 않는다. DRIVE 인증·암호 검증은 변경하지 않았다.

### v35 BODY ACK·차량 오류·Nordic 쓰기 실패 관측

- BODY STATUS는 기존 domain→route→UUID·기한 검사를 통과한 명시 status 존재/fault0/operationOK/payload부재/서명부재 ACK만 `ACK_WAITING`으로 처리한다. ACK는 성공·BODY 완료·DRIVE 전환·재송신·counter 소비·기한 연장이 아니다. 기존 request hash·nonce/tag·counter·AEAD를 통과한 실제 암호 BODY가 있어야 진행한다. DRIVE STATUS에는 이번 ACK 정책을 확장하지 않는다.
- BODY의 fault 또는 WAIT/ERROR/unknown operation 보고는 `RESPONSE_REJECTED`로 관측하고 기존 실패/정리 경로를 유지한다. 오류 header는 비인증 보고이며 인증된 차량 상태·키 미등록 증거로 승격하지 않는다. 공식 VCSEC의 nominal envelope 수용과 앱의 암호 BODY 후속 대기는 구분한다.
- 로그v16은 `responseSignedStatusPresent`·`responseSignedMessageFault`·`responseOperationStatus`를 기록한다. status 부재는 false/null/null이고 unknown enum은 원 Int를 유지한다. malformed/expired 수신은 이전 값을 재사용하지 않는다. 원문·UUID/route·새 hash·예외는 추가 저장하지 않는다.
- Nordic write fail의 원 Int는 `libraryWriteFailureCode`, 전달 시각은 `libraryWriteCompletedElapsedMs`다. 음수 library reason을 native GATT status로 바꾸지 않는다. 제출 객체 동일성·op/GATT/봉인 검사를 통해 이전 callback을 차단하고 새 쓰기에는 이전 실패를 붙이지 않는다. 최종 봉인은 queued보다 마지막 실제 쓰기 근거를 우선 보존한다.
- 공식 근거: 고정 [VCSEC response parser](https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/vehicle/vcsec.go), [GetError](https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/error.go), Nordic2.11.0 [FailCallback](https://github.com/NordicSemiconductor/Android-BLE-Library/blob/2.11.0/ble/src/main/java/no/nordicsemi/android/ble/callback/FailCallback.java). AltBeacon·500ms·MTU·기한·같은 후보1회·180초 TTL·retry0/autoConnect=false·차량 제어 경계는 유지한다.

### v34 반복 광고·후보 만료·응답 대응 관측

- `BleFieldTrialPolicy`는 실제 filtered 광고의 원 단조 수신 시각과 앱 적용 시각을 별도로 관측한다. 유효 광고 세대 누계·후보별 광고 수·같은 후보 조회 억제 수는 전체 무선 패킷·실제 방문 수가 아니다. 중복/역순/미래/오래된 입력은 관측값을 갱신하지 않는다. 180초 TTL·실제 expire·같은 후보1회·source 게이트는 유지한다.
- `BleCandidateDiagnosticsPublisher`는 세대별 재사용 Runnable 하나로 dirty 집계를 최대10초 빈도로 기록한다. 후보/조회/스캔/정지 전이는 강제 flush하며 clean 상태를 계속 polling하지 않는다. 진단 getter/타이머는 후보 만료·조회·스캔 재시작을 실행하지 않고, OFF/이전 세대의 periodic 전달을 봉인한다. 진단 기록 예외도 감지를 정지시키지 않는다.
- 로그v15의 `candidateDiagnostics`는 `BLE_CANDIDATE_DIAGNOSTICS` 전용 행에만 붙인다. 원 수신/앱 처리·만료 예정·실제 만료/지연을 구분하고 unknown은 null이다. 진단 화면의 다음 접근 상태와 접힌 상세는 같은 RAM snapshot을 사용하며 복원 후보로 재생하지 않는다.
- 수신 헤더의 `requestUuidPresent`/`requestUuidMatches`/`toRouteMatches`는 RAM의 현재 요청과 비인증 Boolean 비교다. DOMAIN_MISMATCH 조기 반환 전 관측하지만 domain→route→UUID·ACK/HMAC/AEAD 판정은 유지한다. 원 UUID/route·새 hash·payload는 보존하지 않고 미발급/부재는 unknown이다. malformed/expired는 이전 대응 값을 지우며 새 RX 없는 BODY→DRIVE 전환은 마지막 BODY 근거를 재라벨하지 않는다.

### v33 Nordic GATT 이관

- `TeslaBleKeyProbe.NordicTransport`가 회차별 Nordic `BleManager` 하나로 수동 등록·수동 읽기·FGS 접근 읽기의 연결/탐색/MTU/CCCD/with-response 쓰기/notify를 맡는다. 앱의 직접 connectGatt·descriptor/characteristic write 경로는 제거했다. 기존 프레임·쓰기 pump·BODY→DRIVE·ACK/HMAC/AEAD/domain/route/UUID 검증은 유지한다. Nordic ready는 Tesla 인증·전체 조회 완료가 아니다.
- `TeslaGattConnectSettle`은 elapsed 기준 최소500ms, 조기 wake의 잔여 대기·깊은 절전 후 기한 검사·취소/관측 재진입·이전 회차 봉인을 처리한다. 상시 wake lock·Thread.sleep은 추가하지 않았다. Nordic retry0·autoConnect=false·PHY1M, 기존 MTU247 요청/5초 기한·40초 접근 총기한·같은 후보1회는 유지한다. 서비스 탐색 전 Nordic 기본 대기는 non-bonded300ms/bonded1600ms이며 스캔 정지 후 대기와 다른 단계다.
- `TeslaNordicConnection`은 첫 enqueue 직후 실제 handle을 보유하고 cleanup에서 명시 close 반환을 확인한 다음 manager 자원을 정리한다. Nordic 내부 close가 예외를 삼키므로 manager.close 반환만으로 lease를 풀지 않는다. 생성된 handle의 close가 불명이면 기존 예약 유지·재입장 차단을 적용한다.
- 작은 `NordicGattAccess.java`는 같은 upstream package에서 2.11.0의 package-private `requestHandler.bluetoothGatt`만 읽는다. 공개 API가 아니며 reflection·대규모 fork도 아니다. 초기 서비스 검색 전 실패에서 실제 정리 증거를 확보하기 위한 최소 내부 접근이다. 버전 업그레이드에는 필드 접근·빈 큐 첫 enqueue 동기 실행·같은 Handler callback·초기 fail 게시 순서를 다시 검증해야 한다.
- 초기 실패 observer의 mapped reason은 native status가 아니다. 뒤따르는 ConnectRequest.fail의 양수 원 status만 연결 실패 코드로 관측한다. 라이브러리 전달/완료 시각은 v14 `library*ElapsedMs`, 실제 대기는 `connectSettle*ElapsedMs`로 구분한다. 미제공 SDK 반환·native 발급/진입 시각은 null이며 enqueue를 대입하지 않는다. 원 광고 timestamp는 AltBeacon raw ScanResult 경로를 유지한다.
- raw logger를 no-op으로 두고 Log.ASSERT 최소 우선순위로 payload 문자열 생성을 억제한다. 앱 소유 세션 재료·프레임·수신 큐는 지운다. Nordic 성공 쓰기의 공개 packet/Data는 지우지만 실패·취소의 private WriteRequest 복사본까지 즉시 zeroize한다는 보장은 없다. 비추출 개인키·차량 키/VIN 저장·허용 로그 정책은 유지한다.

### v32 수신 header·연결 시작 관측

- optional observer를 작은 immutable `TeslaBleResponseObservation`으로 전환했다. 기존 String callback shim은 없으며 Sequence·fixture를 이동했다. parsing 뒤 받은 header의 from/to 존재·형식, 실제 DOMAIN의 raw value, payload case와 기대 domain을 관측한다. `DOMAIN_MISMATCH` 조건은 그대로이며 `FROM_MISSING`/`FROM_NOT_DOMAIN`/`DOMAIN_DIFFERENT`로만 세분한다.
- receive마다 비민감 scalar 관측을 초기화하고 callback 직전에 immutable 값 하나를 만든다. observer가 없으면 관측 객체를 만들지 않는다. malformed·만료 입력에 이전 header를 재사용하지 않으며 새 RX가 없으면 이전 BODY 관측을 DRIVE로 재라벨하지 않는다. 0은 실제 DOMAIN 값일 때만 관측하며 필드 부재·비DOMAIN은 null이다.
- 실제 `ScanResult.isConnectable`을 RAM 요청에 전달한다. `TeslaBleKeyProbe.connect`는 Main의 `connectGatt` 호출 직전/반환 또는 예외 시각과 원 광고 elapsed 기준 age를 기록한다. 이는 앱 API 경계이며 RF/controller 완료 시각이 아니다. `TESLA_APPROACH_GATT_CONNECT_RESULT`는 정상 API 반환의 관측이고, 예외 경로도 최종 summary에 시각을 보존한다.
- connectable은 관측만 하며 광고 필터·후보 소비·연결 허가·133 원인을 대신하지 않는다. 같은 후보1회, 원기기·freshness·scan stop·실제 FGS owner·기존 키·단일 lease와 ACK/HMAC/AEAD/domain/route/UUID 조건은 유지한다. raw header는 인증 전 관측이며 주소·route·UUID·packet·키·VIN·hash를 보존하지 않는다.





## 서비스와 UI의 수명

음성 서비스가 단일 활성 세션의 수명과 오디오 소유권을 가진다. Compose UI의 재생성·백스택·프로세스 화면 상태가 녹음 세션이나 차량 명령을 소유해서는 안 된다. 서비스 상주 여부와 오디오 캡처 여부, 그리고 배터리 사용은 별개의 상태로 추적한다. 서비스가 살아 있다고 해서 항상 마이크를 쓰거나 wake lock을 보유하는 설계가 아니다.

통화·다른 앱의 마이크 사용·권한 철회로 입력이 제한될 때는 `isClientSilenced()` 등 녹음 구성과 오류를 관찰한다. 정상적인 조용한 환경과 정책에 의한 무음 입력을 구분한다. 오디오 출력 포커스를 얻었다는 사실은 마이크 입력 소유권의 증거가 아니다. TTS 안내와 확인 수집은 분리하고, 안내 중 캡처를 멈추거나 해당 결과를 실행 판단에서 제외하여 자기 재인식을 막는다. 안내 중 확인을 못 들었다면 실행하지 않으며, 동시 발화·취소 지원 수준은 실기기에서 검증한다.

세션은 만료 시각과 식별자를 가진다. 오디오 캡처 경로의 소유자는 하나로 제한하고, 호출어에서 명령으로 넘어갈 때 필요한 동일 세션 버퍼만 인계한다. 세션 종료 시 `AudioRecord`의 stop/release, `AndroidPcmRecognizer`의 파이프 닫기와 `SpeechRecognizer` cancel/destroy·RAM PCM 버퍼 해제, TTS·포커스·작업·버퍼 해제를 세션 소유자가 책임진다. 화면 음성 진단과 실차 동의 probe는 Activity와 `DiagnosticRuntime` 예약이 같은 직렬 경계에서 소유하며, 취소·화면 이탈·오류·시간 초과에서 해제하고 해제 불명은 `RESTART_REQUIRED`와 예약 유지로 새 시험을 막는다. 프로세스 복구 때 이전 명령을 복원하거나 자동 재전송하지 않는다. 자세한 규칙은 [상태 머신](State-Machine.md)을 따른다.

## 접근·마이크 후보

우선 검증 후보는 다음 조합이다.

1. 사용자가 기본 음성 도우미로 선택한 `VoiceInteractionService`.
2. 실제 저전력 접근 신호(CDM/CDS 등 후보)를 통한 차량 근접 판단.
3. 접근 창 안에서만 제한 시간으로 실행하는 microphone foreground service와 `AudioRecord`.

이는 후보일 뿐 성공이나 권한 예외를 보장하지 않는다. CDM의 FGS 시작 예외와 microphone의 while-in-use 권한 예외는 별개다. CDM으로 FGS를 시작할 수 있다고 해서 DSP 또는 마이크 사용 권한이 자동으로 획득되는 것은 아니다. 사전 시작된 FGS는 비교 대안이며, 매번 사용자가 수동으로 시작하는 흐름을 제품 성공 경로로 삼지 않는다.

관련 Android 제약은 다음 공식 문서가 기준이다.

- [Foreground service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [VoiceInteractionService API](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
- [SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer)

현재 검증 앱은 Android 16/API 36 기기에 맞춰 compile/min/target SDK 36과 도구 버전을 고정했다. 검증 조합은 [README](../README.md#빌드와-실행)에 기록한다. 더 최신 SDK 경고는 숨기지 않았으며, 제한 우회를 위한 target SDK 하향이나 더 최신 OS에서의 동작 보장은 하지 않는다.

## 호출어와 STT

2026-09-22에 다음 후보의 공식 지원·배포 조건을 비교했다. 아래 결정 열은 그 비교 시점의 판단이며, v47~v49는 마지막 Moonshine Tiny Korean 행을 패키지 경로로 썼고 v0.50에서 제거했다. v0.52는 Korean Zipformer 행을 진단 그림자 인식으로 채택했다(명령 판정 미사용). **KWS는 호출어 탐지, STT는 문장 전사이며 서로 같은 기능이 아니다.** 한국어 STT 모델이 있다고 한국어 전용 KWS도 준비된 것은 아니다.

### 후보와 선택 근거

| 후보 | 한국어·Android·PCM | 코드와 모델/서비스 조건 | 결정 |
| --- | --- | --- | --- |
| Android 온디바이스 `SpeechRecognizer` | Android 공식 API. 한국어는 서비스와 설치 모델에 따라 다름. 외부 PCM은 선택 기능 | OS 제공 모델을 재배포하지 않는 경로. 일반 인식기는 원격 처리 가능하므로 사용하지 않음 | 추가 SDK·계정 없는 지원 조회 및 **수동 STT·PCM 진단** 채택. S23 NO_MATCH 시험만으로 실제 엔진 채택 확정 안 함 |
| Porcupine | 공식 한국어 `.pv`, Android custom `.ppn`, mono PCM16 low-level API | 공개 저장소 Apache-2.0과 별개로 모델·SDK 서비스 약관 적용. 계정·비밀 AccessKey 필요. Android 문서는 키 검증용 `INTERNET` 요구 | 한국어 KWS 후보지만 **승인 전 도입 보류**. “헤이 테슬라” 모델 생성·배포권·가격·오프라인 키 검증 조건 미확인 |
| sherpa-onnx + Korean Zipformer | Android·직접 PCM 가능. 한국어 ASR 모델 제공. 조사한 공식 KWS 목록은 중국어/영어이며 한국어 KWS를 확인하지 못함 | 런타임 Apache-2.0(AAR 내 ONNX Runtime MIT). `sherpa-onnx-zipformer-korean-2024-06-24`의 원본 가중치 카드도 별도로 Apache-2.0 표기. 학습 데이터 KsponSpeech 조건은 별도일 수 있음 | 당시 보류. **v0.52 진단 그림자로 채택(명령 판정 미사용)**. 사용자 승인 후 1.13.8·int8·핫워드로 패키지. 기기 성능·연속 발화·신뢰도는 미검증 |
| sherpa-onnx + SenseVoice | 한국어 포함 다국어 ASR, Android 경로 제공. KWS는 아님 | 런타임 Apache-2.0과 달리 원본 `SenseVoiceSmall`은 **별도 FunASR 모델 라이선스** | 런타임 라이선스만 보고 채택하지 않음. 모델 재배포 조건과 기기 실측 검토 후 결정 |
| whisper.cpp + multilingual Whisper | Android 예제·PCM 처리 가능. `.en`이 아닌 다국어 모델에 한국어 포함. 전용 KWS는 아님 | whisper.cpp MIT, OpenAI Whisper 코드·가중치 MIT. 로컬 추론은 OpenAI API 사용과 다름 | 계정 없는 동일 발화 STT 대안 후보. 모델·APK 크기·메모리·지연·배터리 검증 전 보류 |
| Vosk | Android 오프라인 STT, PCM 입력, `vosk-model-small-ko-0.22` 82 MB 제공. 전용 KWS는 아님 | 런타임과 해당 한국어 모델 Apache-2.0. 다른 Vosk 모델은 라이선스가 다를 수 있음 | 소규모 모델 대안. 차량 소음·연속 문장·명령 오인식 실측 없이 주 경로로 확정하지 않음 |
| openWakeWord | 공식 사전학습 호출어 모델은 영어만 지원. 한국어 모델을 직접 준비해야 함 | 코드 Apache-2.0과 모델별 라이선스는 별도. 한국어 데이터·학습·Android 통합 필요 | 이번 한국어 즉시 도입 후보에서 제외 |
| sherpa-onnx + Moonshine Tiny Korean | Android·직접 PCM. 비스트리밍 단일 발화 추론 | 런타임 Apache-2.0, AAR 내 ONNX Runtime MIT, 한국어 모델은 **Moonshine AI Community License**(MIT 아님) | **v47~v49 채택, v0.50 제거**. 빈 결과·“브렁크” 오인식과 신뢰도 미제공으로 Android 온디바이스 `SpeechRecognizer`로 전환 |

Porcupine의 [현재 요금 페이지](https://picovoice.ai/pricing/)는 Free Trial과 영업 문의를 안내하며, 무료 영구 사용 조건을 확인해 주지 않는다. [이용 약관](https://picovoice.ai/docs/terms-of-use/)에는 제한된 trial·적용 요금·연결 시 usage data 전송 가능성이 명시돼 있다. 오디오가 로컬 처리된다는 설명을 “통신 없음·배포 제한 없음”으로 확대하지 않는다. 계정 생성·키 발급·요금·약관 동의·외부 SDK 도입은 사전 승인 대상이다.

전용 KWS 대신 접근 세션 안에서 STT로 전체 문장과 호출 문구를 함께 판정하는 것은 대안이지 이미 동등하게 검증된 구현이 아니다. [INFERENCE] 연산량·메모리·발화 종료 대기 비용이 늘 수 있으므로 기기 실측이 필요하다. 모델의 공개 벤치마크 수치를 S23·주머니·바람 환경의 성공률로 옮겨 쓰지 않는다. 어떤 대안도 현재 앱에 자동 fallback으로 넣지 않았다.

후보별 공식 근거:

- Porcupine [Android quick start](https://picovoice.ai/docs/quick-start/porcupine-android/), [low-level PCM API](https://github.com/Picovoice/porcupine/tree/master/binding/android), [한국어 모델 목록](https://github.com/Picovoice/porcupine/tree/master/lib/common).
- sherpa-onnx [Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html), [KWS 모델 목록](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html), [런타임 라이선스](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE), [한국어 ONNX 변환 출처](https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24), [원본 한국어 가중치 카드](https://huggingface.co/johnBamma/icefall-asr-ksponspeech-zipformer-2024-06-24).
- SenseVoice [Android·모델 안내](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html), [가중치 카드](https://huggingface.co/FunAudioLLM/SenseVoiceSmall), [FunASR 모델 라이선스](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE).
- Whisper [공식 코드·가중치 라이선스](https://github.com/openai/whisper#license), whisper.cpp [Android 예제](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android), [런타임 라이선스](https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE).
- Vosk [Android](https://alphacephei.com/vosk/android), [모델별 언어·크기·라이선스](https://alphacephei.com/vosk/models), [런타임](https://github.com/alphacep/vosk-api). openWakeWord [지원 언어와 라이선스](https://github.com/dscripka/openWakeWord).

### 과거 플랫폼 지원 진단(v0.2~v46)

**이 절은 v46까지의 Android 시스템 음성 인식기 진단 기록이며 v47에서 제거됐다.** 당시 채택한 것은 최종 인식 엔진이 아니라 Android 플랫폼 지원 진단 경로였다. `createOnDeviceSpeechRecognizer()`만 사용해 `ko-KR` 요청의 `checkRecognitionSupport()` 결과를 조회했다. 일반 `createSpeechRecognizer()`나 음성 인식 Activity를 열지 않았다. `EXTRA_PREFER_OFFLINE`만으로 오프라인을 보장할 수 없으므로 그것을 대체 경로로 삼지 않았다. v0.50은 같은 지원 조회를 `SpeechSupportProbe`로 다시 쓰며 현재 인식 경로는 아래 PCM 파이프다.

지원 조회의 네 목록은 서로 다른 뜻이다.

| API 결과 | 의미 | 설치 완료로 취급 |
| --- | --- | --- |
| `installedOnDeviceLanguages` | 해당 요청을 로컬에서 사용할 수 있다고 서비스가 보고 | 정확한 `ko-KR` 포함 여부만 설치 보고로 표시 |
| `pendingOnDeviceLanguages` | 다운로드 예정 | 아니오 |
| `supportedOnDeviceLanguages` | 지원하지만 먼저 다운로드해야 함 | 아니오 |
| `onlineLanguages` | 원격 구현으로 지원 | 아니오. 클라우드로 전환하지 않음 |

태그의 대소문자는 구별하지 않지만 `ko`, `ko-KP` 등은 정확한 `ko-KR` 설치 보고로 승격하지 않는다. 목록에 없거나 조회가 실패한 것을 실제 발화 시험의 실패/성공으로 꾸미지 않는다. 설치 보고가 있어도 네트워크 차단 상태의 한국어 인식·정확도·신뢰도·연속 발화는 별도 검증이다. 근거: [RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport).

지원 조회는 오디오를 열지 않는다. `startListening()`·`triggerModelDownload()`를 호출하지 않으며 외부 SDK·모델을 추가하지 않는다. 모델 설치나 설정 변경은 자동으로 수행하지 않는다.

S23 Ultra의 `0.2.0-probe` 당시 실측: 온디바이스 서비스 있음, 정확한 `ko-KR` 설치 보고, 대기·다운로드 가능·온라인 보고 없음. 두 번의 지원 조회와 완료 후 화면 이탈·재진입을 확인했고 이 조회에서는 새 마이크 사용이 관측되지 않았다. 그 결과로 수동 STT·PCM 적합성 진단을 구현했지만, 당시에도 외부 엔진 자동 전환은 하지 않았고 지원 메타데이터만으로 최종 채택을 확정하지 않았다. v47~v49는 이 시스템 지원 경로를 쓰지 않았고 v0.50에서 다시 사용한다.

### Android 온디바이스 SpeechRecognizer PCM 파이프(v0.50)

`0.50.0-probe`는 v47~v49의 Moonshine 경로(`MoonshineRecognizer.kt`, sherpa AAR, 모델 자산, `assets/licenses`, `tools/prepare_moonshine.py`)를 제거했다. `.local-models/`는 보존하되 쓰지 않는다. API 키·계정을 쓰지 않는다.

- 데이터 흐름: `SpeechMicCapture`가 16 kHz·mono·PCM16을 최대 10초(320,000바이트) 캡처 → `AndroidPcmRecognizer`가 같은 PCM을 `EXTRA_AUDIO_SOURCE` 파이프에 non-blocking으로 씀 → `EXTRA_SEGMENTED_SESSION`의 `onSegmentResults`를 결합해 최종 후보 목록 생성. 비segmented 모드는 S23에서 결과가 null이었고 `EXTRA_BIASING_STRINGS`는 효과가 없었다. 타임아웃은 운영 15초, 재생 테스트 30초다.
- 입력 모드: 진단 UI는 직접 마이크·녹음 후 PCM·고정 합성 파일·무음 대조 네 모드를 제공하며 모두 같은 인식 경로를 쓴다. 파일·무음은 스피커나 앱 마이크를 열지 않고 무음만 0으로 채운다. 병렬 캡처를 금지한다.
- 결과 계약(`FrunkVoicePolicy`): S23 Google 인식 서비스는 `MAX_RESULTS`와 무관하게 2~3개 후보를 주고 confidence는 항상 0.0이었다. 후보 중 하나라도 정규화 후 `헤이테슬라프렁크열어줘`와 정확히 일치하면 수락하고, 하나라도 `취소`/`열지마`를 포함하면 차단한다. 점수 0.0·−1·null·비유한값은 “점수 미제공”으로 기록하고 신뢰도 게이트를 생략하며, 실제 점수는 0.85 이상이어야 한다. 발음 변형(페이/해이/해의 테슬라, 브렁크/프랑크)은 수락하지 않는다. 인식기 실패는 부분 세그먼트가 있어도 명령 없음이다.
- 실차 동의 회차: `FrunkVoiceProbe`도 같은 경로를 쓴다. P·HANDOFF 5초·명령 10초·안전 동의 게이트는 그대로이며 STT 일치는 차량 제어 허가가 아니다. 실차 성공은 미검증이다.
- 보존·정리: 일반 로그에는 원문을 남기지 않으며 마스킹 어휘에 “열어”, “줘”, “해이”, “페이”, “브렁크”, “프랑크”를 추가했다. v49부터 사용자가 직접 시작한 개발용 마이크 시험은 `no_backup/speech-recordings/<UUID>/`에 16 kHz mono PCM16 WAV·캡처 메타데이터·최종 원문 결과를 기본 보관한다. 별도 저장 동의 체크 없이 시작 버튼 근처에 안내하며 화면 진입·복원·복귀만으로 녹음하지 않는다. 실차 프렁크 1회 안전 동의는 유지하고 합성·무음 파일 시험은 자동 저장하지 않는다. 자동 업로드·백업·삭제는 하지 않는다. `tools/speech_recordings.py`로 ADB 회수·휴대폰 온디바이스 재인식이 가능하며 재인식은 마이크·차량 경로를 호출하지 않는다. 취소·화면 이탈·오류·시간 초과에서 캡처·파이프·recognizer·RAM·예약을 정리하고, 해제 불명은 `RESTART_REQUIRED`와 예약 유지로 새 시험을 막으며 성공으로 숨기지 않는다.
- 화면 후보(v0.51): `SttTranscript`(앞 3개 후보·전체 개수)를 `FrunkVoiceState.transcript`(판정·차단 사유 포함)와 `SpeechTrialState.transcript`에만 둔다. 사용자 취소 회차에는 붙이지 않고, 다음 같은 시험 시작은 새 상태로 교체하며 프로세스 종료 시 사라진다. 일반 로그·RAM 이벤트·`SpeechTrialSummary`·필드 스냅샷·새 파일로 내보내지 않는다. 기존 개발용 마이크 시험의 녹음 결과 파일 보관은 v49 정책 그대로다.
- 그림자 인식(v0.52): `SpeechRecognitionProbe`에서만 같은 PCM을 sherpa-onnx 오프라인 zipformer(`modified_beam_search`, 핫워드 "헤이 테슬라"·"프렁크 열어줘")로 한 번 더 인식한다. 실행 조건은 Android 결과를 받은 뒤, 시험이 취소되지 않았고 화면이 보이며 세션 마감 전일 때다. 인식기는 시험마다 로드하고 `finally`에서 해제한다. asset이 없으면 native 쪽 `exit`을 피하려고 로드 전에 asset 존재를 먼저 확인한다. 실패는 `SHADOW_MODEL_MISSING`/`SHADOW_LOAD_FAILED`/`SHADOW_DECODE_FAILED`로 표시한다. 결과는 `FrunkVoicePolicy`·`recognitionOutcome`·`FrunkVoiceProbe`에 넣지 않으며, 원문은 v0.51 후보와 같이 RAM 화면 상태에만 두고 로그·이벤트·요약·녹음 저장소에 쓰지 않는다.
- 재생: `SpeechRecordingReplayDeviceTest`의 `replaySavedRecording`(인자 `recordingId`)은 저장 녹음을 재인식해 `runs/`에 추가하고, `replayAll`은 fixture와 모든 저장 녹음을 `HTSTT` 로그로만 남긴다.

v47~v49 Moonshine 경로의 구현·라이선스·빌드 기록은 [기기 검증 0.47.0-probe](Device-Validation.md#0470-probe--moonshine-tiny-korean-stt-전환)에 과거 기록으로 남는다.

### 과거 시스템 STT·PCM 진단(v0.3~v46)

`0.3.0-probe`에서 시작한 `SpeechRecognitionProbe`는 당시 지원 조회와 별도였고, `0.4.0-probe`는 여기에 고정 파일·무음 대조 입력을 추가했다. `createOnDeviceSpeechRecognizer()`와 `ko-KR`만 사용하며, 기본 비서용 `UnsupportedRecognitionService`는 계속 일반 요청을 거절한다. 아래는 v46까지의 기록이며 v47~v49에는 Moonshine 경로로 대체됐다가 v0.50에서 Android 온디바이스 인식기(PCM 파이프)로 돌아왔다.

- 직접 경로는 시스템 인식기만 마이크를 소유한다. PCM 경로는 Activity가 16 kHz·mono·PCM16을 최대 10초·320,000바이트 RAM에 모은 뒤 `AudioRecord.stop/release`를 끝내고 인식기를 시작한다. 두 캡처를 병렬 실행하지 않는다.
- 파일 경로는 `AudioRecord`나 스피커를 시작하지 않고 WAV의 PCM 부분만 읽는다. 파일과 무음 대조는 같은 35,549샘플을 사용하며 대조만 0으로 채운다. 기존 PCM 전송·취소·정리 경로를 공유한다. 디코더는 기존 320,000바이트 PCM 버퍼와 최대 8 KiB 헤더 예산 안에서 포맷·경계·EOF를 검증한다.
- `EXTRA_AUDIO_SOURCE`에 pipe의 읽기 FD와 포맷을 전달하고, 모든 외부 PCM 입력에 `EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE`를 명시한다. [공식 분절 세션 계약](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_SEGMENTED_SESSION)에 맞춰 `onSegmentResults`를 받고 `onEndOfSegmentedSession`에서 마무리한다. writer의 EOF로 입력을 닫고 쓰기는 비차단·취소 가능한 worker에서 처리한다. 인식은 10초 제한 정책이며 전송 바이트 자체는 소비 성공의 증거가 아니다.
- `DiagnosticRuntime`의 오디오 예약은 화면 표시·권한·기존 캡처 없음·접근 진단 OFF에서만 획득한다. 예약 중 기존 수동 마이크·자동 접근·기능 활성화를 UI뿐 아니라 실행 진입점에서도 막는다.
- 화면 이탈·취소는 콜백을 먼저 무효화한다. worker 종료, 인식 객체·FD 해제, RAM 덮어쓰기를 확인한다. `0.11.0-probe`부터는 입력 정리 뒤 같은 예약으로 오프라인 음성 안내를 수행하고, 출력·포커스 정리까지 끝난 뒤 예약을 해제한다. 실제 해제 불명은 `RESTART_REQUIRED`와 예약 유지로 새 입력을 막으며 성공으로 숨기지 않는다.
- 일반 최종 결과 또는 최종 분절별 첫 후보를 고정 시험 문장과 비교한다. 공백·구두점 차이만 무시한다. 분절 사이에는 일치 여부·정규화 문자 수와 함께, 상한(128자)을 넘지 않는 동안만 정규화된 누적 문자열을 RAM에 유지한다. 누적 문자열은 회차 정리에서 비우며 state·로그·파일로 나가지 않고, 인식 원문·후보 목록도 어디에도 남기지 않는다. 분절 하나는 유효한 첫 신뢰도를 표시하되 여러 분절의 점수를 문장 신뢰도로 합성하지 않는다. 누락·음수·범위 밖·낮은 점수를 성공 값으로 보충하지 않으며 문장 일치도 명령 실행 허가가 아니다. 호출어·명령 판정은 `SpeechCommandDecision` 이름으로만 남기고 원문은 담지 않는다.

### 명령 판정·로컬 응답(v47에서도 유지)

`SpeechCommandParser`, `DiagnosticCommandPolicy`, `DryRunVehicleGateway`, `SpeechResponse` 경계는 v47도 그대로 쓴다. 바뀐 것은 입력 엔진과 신뢰도 `null`뿐이며, 아래 v0.10~v0.11 기록의 판정·응답 계약은 현재도 유효하다.

`0.10.0-probe`는 사용자 선택에 따라 진단 버튼 시작 방식을 유지하며, 최종 한 문장에 `SpeechCommandParser`를 적용한다. 문장 시작 정확한 “헤이 테슬라” 뒤 “프렁크 열어줘” 하나만 `FRUNK_OPEN_CANDIDATE`, 호출어 없음·명령 없음·허용 외·취소/부정은 별도 enum이다. 신뢰도는 이번 진단 판정에서 제외하고 원래 값을 기록한다. 이 흐름은 이미 시작한 STT 결과의 문장 판별이며 저전력 KWS가 깨운 별도 STT 세션이나 차량 명령 실행이 아니다. 부분 결과로 후보를 확정하지 않고 취소·실패·정리 실패에는 후보를 남기지 않는다.

`0.11.0-probe`는 `SessionPolicy.startDiagnostic()`의 같은 ID·40초 monotonic 소유권 안에서 최종 판정→`DiagnosticCommandPolicy`→`DryRunVehicleGateway`→입력 해제→`SpeechResponse`→출력 해제를 연결한다. gateway는 로컬 진단 계약만 갖고 계정·키·네트워크·차량 전송 API를 받지 않는다. 후보만 한 번 처리하며 결과 불명은 재시도하지 않는다. 처리 뒤 취소는 이미 관측한 로컬 결과를 보존한다.

`SpeechResponse`는 Android `TextToSpeech`의 한국어 지원·설치된 비네트워크 voice를 확인한다. 초기화 5초·재생 10초 제한, utterance ID별 완료/오류, 취소·포커스 손실, `stop/shutdown`과 포커스 반납을 관리한다. 입력 해제 미확인 시 시작하지 않으며 실패·해제 불명은 성공 안내로 대체하지 않는다. 진단 UI·v4 파일에는 로컬 결과, 안내 상태와 입력·출력·포커스 해제 여부만 추가하고 원문·PCM은 저장하지 않는다. 실측에서는 오프라인 voice 선택·완료 콜백·해제를 확인했으며 물리 스피커 음질·가청성 평가는 하지 않았다.


`0.3.0-probe`의 스피커 재생 시험에서는 두 마이크 경로가 `NO_MATCH(7)`였고 진행 중 이탈 취소·해제를 확인했다. `0.4.0-probe`의 무음 파일 시험에서는 처음 일반 최종 콜백만 사용했을 때 결과가 없었으나, 위 분절 계약 적용 뒤 고정 문장 일치를 두 번 확인했다. 같은 길이의 무음 입력은 `NO_MATCH(7)`, 즉시 HOME은 `USER_CANCELED`였으며 그 뒤 재실행도 성공했다. 새 앱 마이크 사용은 관측되지 않았다. 당시 반환 신뢰도는 `0.0`이었고 이 결과는 Android 시스템 인식기 기준이다. v47 Moonshine은 신뢰도 `null`이며, 실제 발음·마이크·주변 소음·네트워크 차단·연속 문장 등은 여전히 별도 검증 항목이다.

### 과거 외부 PCM 인계 검토와 신뢰도 원칙(v0.3~v46, 은퇴)

v47~v49는 시스템 STT와 `EXTRA_AUDIO_SOURCE`를 쓰지 않고 앱이 소유한 Moonshine에 PCM을 넘겼다. v0.50은 다시 앱이 캡처한 PCM을 `EXTRA_AUDIO_SOURCE` 파이프(segmented session)로 Android 온디바이스 인식기에 넘기므로, 아래 인계 위험은 현재 경로에도 적용된다. 아래 본문은 v46까지의 검토 기록이다.

호출어 끝과 STT 시작 사이의 잘림을 줄이려면 동일 접근 세션 안에서만 짧은 RAM 내 PCM 버퍼를 인계하는 방식을 검토했다. [EXTRA_AUDIO_SOURCE 공식 계약](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)에 따르면 이 옵션이 없거나 인식기가 지원하지 않으면 **인식기가 자체 마이크를 연다**. 플래그를 넣었다는 사실이나 언어 지원 조회 성공만으로 기존 `AudioRecord`를 소비한다고 보장할 수 없다.

고정 합성 파일의 외부 PCM 인식은 당시 실측했지만, 호출어와 연결한 캡처 스트림의 첫 음절 보존·단일 오디오 소유권까지 입증한 것은 아니다. 이 조건을 확인하기 전에는 접근 마이크와 시스템 STT를 병렬로 실행하지 않는다. 원거리 버퍼 수집은 금지다. 인계가 불가능하면 사용자가 두 번 말해야 하는 흐름으로 합격 처리하지 않고 동일 스트림을 처리할 온디바이스 대안을 검토한다. v47~v49의 패키지 Moonshine 경로가 그 대안이었으나 v0.50에서 제거했으며, 어느 경로도 동일 PCM을 외부 서비스로 보내지 않는다.

당시 시스템 인식기의 `CONFIDENCE_SCORES`는 선택 사항이며 `-1`은 미제공 값이었다. 점수가 없거나 낮은 결과에 임의의 높은 신뢰도를 부여하지 않는다. v0.50은 사용자 결정에 따라 0.0·−1·null·비유한 점수를 "점수 미제공"으로 기록하고 신뢰도 게이트를 생략하되, n-best 후보의 정확 문장 일치와 나머지 안전 게이트는 그대로 요구한다. 실제 점수가 오면 0.85 이상이어야 한다. 호출어 감지는 발화자 인증이 아니고 지원 메타데이터는 명령 실행 권한이 아니다.

## 차량 게이트웨이 경계

음성 명령은 `DryRunVehicleGateway`이며 자동 음성 실차 제어와 전송 API는 연결하지 않았다. 수동 BLE 경로는 공유 `TeslaBleConversation`·`TeslaBleKeyStore`·GATT 예약/취소를 사용한다. 등록 후 VCSEC 잠금/프렁크 읽기와 v22의 별도 Infotainment `GetDriveState` 읽기는 query·도메인·검증 이벤트로 구분한다. 기어의 차량 원본 시각과 최종 fragment 수신 monotonic을 보존하되 기한은 처리 시각 기준으로 유지한다. 지난 P 관측은 현재 상태·근접·제어 허가가 아니다. 주말 BLE 감지 경로는 CCCD 외 TX 없는 RX-only 계약을 유지한다.

v21의 실차 앱 키 인증·암호 조회·같은 키 새 BLE 연결 조회는 통과했다. 다음은 **주차 기어 읽기 → BLE·UWB 차량 감지 개선·검증 → 신선한 명시적 `P`와 안전 게이트 → 별도 승인된 수동 프렁크 → 음성·접근 결합**이다. 프로세스 종료·재부팅 시험을 지금 개발의 필수 조건으로 추가하지 않는다. 실제 차량 게이트웨이는 현재 공유 프로토콜·키·GATT 소유권/정리 경계를 재사용한다. 만료·취소·중복·대상·권한·연결·신선도 검사는 실행 진입점에서도 강제하며 부족하면 제어를 거절한다. 전송 후 `UNKNOWN`은 재전송하지 않고 이전 명령은 복원하지 않는다. 수동 시험을 최종 제품의 매 사용 버튼 조작 경로로 삼지 않으며 초기 설정 후 잠금·주머니 무터치 목표를 유지한다.

[공식 BLE 프로토콜](https://github.com/teslamotors/vehicle-command/blob/main/pkg/protocol/protocol.md)과 [CLI `frunk-open`](https://github.com/teslamotors/vehicle-command/blob/main/cmd/tesla-control/commands.go)은 차량 키 인증이 필요한 BLE 명령 경로를 지원하며 Fleet OAuth를 필수로 두지 않는다. 이것은 공식 SDK 지원이지 우리 앱 구현·실차 성공의 증거가 아니다. 로컬 등록 키는 Driver/android_device이며 Fleet Manager의 BLE 제한과 혼동하지 않는다.

Fleet 서버는 인터넷 기능의 필요성·인증 모델·비용·키/토큰 보관 경계를 확인하고 사용자가 승인한 경우에만 검토한다. 이 선택 분기에 한해 Node.js+TypeScript와 공식 Go `vehicle-command` 서명 프록시 재사용을 검토하며 로컬 AndroidKeyStore 개인키는 추출하지 않는다. [Fleet 자체키·도메인·`_ak` 온보딩](https://developer.tesla.com/docs/fleet-api/virtual-keys/developer-guide)은 BLE 필수 단계나 공식 앱 폰키 개인키 전달 절차가 아니다. Fleet을 선택해도 신선한 `P`·별도 실차 승인·동일한 안전 게이트를 약화하지 않는다.

UWB는 별도 미확인 과제다. [Android UWB 공식 문서](https://developer.android.com/develop/connectivity/uwb)의 peer 주소·채널·세션 키 OOB 교환과 실제 거리·정리 검증이 필요하며 로컬 BLE 장기 키 생성·등록이나 framework capability 조회가 이를 대체하지 않는다.

실제 게이트웨이와 독립된 mock/dry-run 계약은 미전송·거절·요청 승인·관측된 완료·`UNKNOWN`을 구분해야 한다. dry-run 안내는 “실제 차량 명령은 보내지 않았습니다”로 실제 성공과 구분한다. 실제 연동 실패 때 mock 성공으로 조용히 전환하지 않는다. 상세 권한·서명·비용은 [Tesla 연동](Tesla-Integration.md)을 따른다.
