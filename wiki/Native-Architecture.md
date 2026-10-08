# Android 네이티브 아키텍처

> 제품 전체 설계와 현재 구현을 구분한다. S23의 `0.4.0-probe`에서 고정 TTS 파일의 외부 PCM 주입으로 한국어 시험 문장 일치 2회를 확인했다. 같은 길이의 무음 대조는 `NO_MATCH(7)`였고 취소·해제·재실행도 확인했다. 반환 신뢰도는 `0.0`이다. 실제 사용자 발화·음향 조건·네트워크 차단·자동 호출어·차량 접근·잠금·주머니 합격과 구분하며 상세 근거는 [검증 기록](Device-Validation.md)에 남긴다.

[Wiki 홈](Home.md) · [요구사항](Requirements.md) · [상태 머신](State-Machine.md)

## 설계 결정

React Native나 JavaScript 브리지는 사용하지 않는다. UI, 권한, Android 서비스 수명, 오디오 캡처, 상태 전이는 Kotlin으로 소유하며 Compose UI, Coroutines와 StateFlow, DataStore, AndroidKeyStore를 사용한다. **현재 유효한 차량 통합 방향은 로컬 BLE 우선**이며 Fleet/OAuth/도메인/서버/결제는 별도 승인하는 선택 인터넷 경로다.

현재 구현은 단일 `app` 모듈이다. 과도한 멀티 모듈화·DI 프레임워크·서버 선행 구현을 피한다. 아래는 제품 전체 책임 구분이며 구현 완료 목록이 아니다. 수동 문장 파서·로컬 dry-run 게이트웨이와 앱 전용 BLE 키 저장·등록·읽기 진단은 구현돼 있다. v22는 별도 Infotainment 기어 읽기까지 추가했지만 실제 차량 수신·제어 직전 신선한 P는 미확인이다. 자동 호출어·실차 제어 게이트웨이·UWB OOB/거리 세션은 아직 구현·검증 전이다.

| 영역 | 책임 |
| --- | --- |
| UI | Compose 화면, 초기 설정, 상태 표시, 명시 승인 입력. 서비스 재생성과 독립적으로 StateFlow를 구독한다. |
| platform | `VoiceInteractionService` 후보, 권한, Bluetooth/접근 신호, foreground service, 오디오 포커스 및 Android 수명 경계. |
| audio | 호출어 탐지, `AudioRecord`, STT 세션, PCM pre-roll, TTS와 자기 재인식 차단. |
| domain | 명령 파서, 안전 정책, 상태 머신, 만료·취소·중복 규칙. |
| gateway | 현재 음성 경로의 mock/dry-run과 별도 수동 BLE 등록·읽기 진단. 향후 공유 프로토콜·키 경계를 재사용하는 네이티브 BLE 차량 게이트웨이가 새 인증·상태·안전 게이트·명령 전송을 소유한다. 실차 프렁크 제어는 미구현 |
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
| `BleFieldConfig.kt` / `BleFieldTrialPolicy.kt` / `BleGattOwnership.kt` | 기준/개선 및 v23 광고명 감지의 불변 실행 조건. 이름 모드는 감지 전용·스캔만으로 runtime에서 강제하고 GATT·BT·연결 재시도를 거부. 기존 병합/만료·자기 GATT 억제·close/예약 반환 경계 유지 |
| `BleSupplementalScanner.kt` / `BleScanReceiver.kt` / `BleScanTarget.kt` / `TeslaBleAdvertisement.kt` | 주소 또는 공식 VIN 기반 광고명 exact 필터. 비공개 receiver·창별 PendingIntent identity·LOW_POWER/offload·5초 배치·40초/120초 예약. 실제 광고명 재검증·최신 일치 batch·세대 봉인. RAM 타깃과 고아 정리 전용 영속 token 분리 |
| `BleEventEvidence.kt` / `BleTrialSummary.kt` / `FieldEventLog.kt` | JSONL v7: 기존 typed 감지/GATT 근거와 비민감 이름 필터 Boolean. VIN·광고명/hash·주소·RX bytes는 기록하지 않으며 미수신 값은 null. 분석기는 v1–v7 하위호환 유지 |
| `UnsupportedRecognitionService.kt` | Android 비서 등록에 필수인 인식 서비스. 인식·지원 검사에는 명시적 비지원 오류를 반환하고 캡처·모델 다운로드·외부 인식을 시작하지 않음 |
| `SessionPolicyTest.kt` | 정책 경계 8개 회귀 테스트. 감지 기준 초기화 시 대기 세션 폐기·실제 이탈 및 cooldown 유지 포함. 실제 OS·차량 접근 시험의 대체물이 아님 |
| `ObservationLifecycleTest.kt` | 관찰 시작 중 OFF, 낡은 START·STOP·서비스 종료, 중복 ON, 종료 후 명시적 재시작, 새 프로세스의 이전 intent 거부 경계 6개 |
| `SpeechSupportProbe.kt` | Activity 소유의 명시적 지원 메타데이터 조회. 온디바이스 API만 사용하고 요청 ID·10초 타임아웃·취소·화면 이탈·destroy를 관리. 녹음·모델 다운로드 없음 |
| `SpeechSupportPolicy.kt` / `SpeechSupportPolicyTest.kt` | 네 지원 분류와 정확한 `ko-KR`을 구분. 대기·다운로드 가능·온라인 보고의 설치 승격과 일반 한국어 태그 오인을 방어하는 테스트 2개 |
| `SpeechRecognitionProbe.kt` | Activity 소유 STT. 직접 마이크·캡처 종료 후 RAM PCM·고정 TTS 파일·무음 대조, 최종/분절 결과의 시험문장 일치·신뢰도 요약, 취소·시간 제한·오디오/FD 해제 및 실패 시 재시작 요구 |
| `SpeechPcmFixture.kt` / `SpeechPcmFixtureTest.kt` | 크기 제한 WAV 디코더와 4개 경계 테스트. PCM16·16 kHz·mono만 허용하며 filler/padding·잘림·초과 데이터·취소 처리 |
| `res/raw/speech_trial_ko.wav` | 설치된 macOS Yuna로 생성한 고정 비개인 시험 문장. 음성 모델·개인 녹음이 아닌 재현용 합성 데이터 |
| `TeslaBleKeyStore.kt` | 우리 앱 UID의 고정 alias에서 P-256 키 생성·재사용. 개인키는 AndroidKeyStore 밖으로 추출하지 않고 제공자 ECDH를 사용한다. TEE/StrongBox의 생성 키·`PURPOSE_AGREE_KEY`만 허용하며 손상·소프트웨어/imported 키를 삭제/교체하거나 fallback하지 않음 |
| `TeslaBleProtocol.kt` | 설정 등록과 읽기 진단이 공유하는 `TeslaBleConversation`. Driver/android_device `PRESENT_KEY`, 새 VCSEC handshake·HMAC·personalized AES-GCM `GET_STATUS`, 응답 무결성·request hash·counter 검증. 제어 명령·자동 재전송·세션 캐시 없음 |
| `TeslaBleKeyProbe.kt` | 수동 BLE 작업·GATT·기한·전역 예약과 화면/잠금/권한/대상 상실 시 전송 봉인·로컬 정리. 등록 보고와 검증된 읽기 상태를 분리하며 close 불명은 예약을 유지 |

`0.5.0-probe`에서 UI 책임을 위와 같이 분리했다. 음성 probe는 화면 재구성마다 만들지 않고 Activity가 소유한다. `onPause`/`onDestroy` 정리를 보존하며 같은 Activity의 진단 이탈은 `leaveDiagnostics()`로 지원 조회·STT·수동 마이크를 정리한다. `0.13.0-probe`의 BLE 시험은 별도로 서비스가 소유하여 화면 이탈·HOME·Activity 재생성에도 유지한다. v24는 VIN을 암호화 설정으로 보존하지만 편집 중 원문은 `remember` RAM에만 두고 제출·이탈·배경 전환 때 비운다. VIN 복원은 진단 자동 재시작이 아니며 원문을 saved UI state·Intent·로그에 옮기지 않는다. 화면 실측 범위는 [실측 기록](Device-Validation.md)을 따른다.

`0.6.0-probe`의 접근 진단은 기본적으로 관찰 전용이다. `automaticMicrophoneEnabled`는 프로세스 기본값 false·비영속이며, 관찰과 세션·음성 예약이 모두 꺼진 상태에서만 별도 변경한다. 관찰 전용 출현은 정책 debounce를 만들기 전에 반환하고 `automaticAllowed()`도 별도 동의를 요구한다. 진단 비활성화 시 동의·지연 작업을 해제한다. 활성화 시 이전 현재 감지를 지우되 실제 이탈·cooldown 조건은 보존한다. 출현·이탈 콜백 횟수와 최근 이벤트는 비민감 RAM 상태로만 누적하며 중복 콜백도 포함한다.

`0.7.0-probe`는 별도 시험 파일을 `noBackupFilesDir/field-diagnostics/events.jsonl`에 보관한다. 프로세스·시험 UUID와 시각·고정 이벤트 코드·허용 상태만 기록하며 기존 RAM 표시와 구분한다. 파일은 자동 삭제·회전·덮어쓰지 않고 프로세스 재시작 후 추가 기록한다. USB 디버깅의 `run-as`로 디버그 APK 파일을 회수한다. 별도 상주 서비스·마이크·깨우기·외부 전송은 추가하지 않는다. 프로세스 생존이나 수신하지 못한 콜백의 복원을 보장하지 않는다.

`0.8.0-probe`는 접근 ON의 CDM 수명을 `ObservationService`로 옮긴다. `observationStartPending`은 요청 대기, `observationServiceRunning`은 FGS 승격 완료, `observing`은 CDM 요청 수락, `present`는 실제 BLE 출현으로 구분한다. 알림·Bluetooth 조건을 시작 전에 검사하고 앱이 표시된 상태의 명시적 ON에서만 요청한다. 앱·알림 종료와 실패는 동일한 정리 경계를 사용한다. 서비스는 `START_NOT_STICKY`이며 프로세스 재생성 시 OFF다. 별도 자동 마이크 동의·기본 비서·기존 오디오 게이트를 유지하며 관찰 ON 자체는 캡처를 시작하지 않는다. 상시 wake lock·추가 BLE 스캔·주기적 원격 조회는 도입하지 않는다. 유형·권한은 [Android 공식 connectedDevice FGS 조건](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)을 따른다.

`0.13.0-probe`는 같은 서비스의 `BLE_FIELD` 모드에 반복 BLE 회차를 이관한다. 실제 BLE 출현당 한 회차이며 종료 후 실제 이탈을 요구한다. 회차 정리 중 재출현은 이전 소유권을 모두 비운 뒤에만 진행한다. `BT_CONNECTED`는 접근 트리거가 아니다. 화면/HOME/잠금과 독립적이나 명시적 중지·Bluetooth/권한/등록/로그 조건 상실은 신규 트리거를 봉인하고 서비스 정리로 이어진다. 대기 중에는 마이크·wake lock·주기적 GATT 연결을 사용하지 않는다. heartbeat는 CPU 절전 중 지연될 수 있으며 파일 공백만으로 서비스 종료나 차량 미접근을 단정하지 않는다.

`0.14.0-probe`는 위 동작을 기준 방식으로 보존하고 같은 정책의 선택 조건으로 개선 방식을 제공한다. BT 보조 미선택은 shadow 계측만 남긴다. 선택된 외부 BT·CDM·필터 스캔은 후보로 병합하며 자체 GATT 중/close 후 15초의 BT 연결은 억제한다. 개선 후보의 CDM 이탈은 30초 유예하고 스캔 증거는 180초 뒤 만료한다. 감지만은 후보를 기록하되 probe·wake lock을 만들지 않는다. 재시도는 선택된 실패에만 2회/총 40초·실제 close 후 2초·잔여 12초 이상을 요구하며 정리 실패·COMPLETE·BLOCKED·프로필 없음·중지는 재시도하지 않는다.

필터 스캔은 주소 또는 광고명 하나를 사용한다. v25는 LOW_POWER·5초 배치의 `startScan(filters, settings, PendingIntent)`로 등록하고, 비공개 명시 receiver에서 현재 RAM 소유자·창별 token·기한을 확인한다. 40초 창 예약·최소 120초 시작 간격을 유지하고 GATT 동안 정지한다. Handler는 절전 중 지연될 수 있어 실제 radio 등록의 40초 상한을 보장하지 않는다. 만료 후 현재 세션 전달은 소비하지 않고 등록만 정리한다. `ScanResult.timestampNanos`의 원 monotonic 시각을 사용해 배치 지연이 증거 수명을 늘리지 못하게 한다. offloaded filtering/batching 미지원·권한·SDK 등록 오류·전달/정리 실패를 구분하고 무필터·고빈도 fallback은 없다. 창별 시작/종료와 후보 첫 match를 JSONL v7로 기록하며 패킷마다 행을 만들지 않는다.

`noBackupFilesDir/ble_pending_scan_token`은 무작위 token만 가진 정리용 메타데이터다. scan 등록 전에 동기 저장하고 동일 PendingIntent의 stopScan 정상 반환 뒤에만 비운다. VIN·광고명·MAC·키는 저장하지 않는다. Application 시작과 소유자 없는 receiver 전달은 고아 등록만 정리하며 실행을 복원하지 않는다. 정리 실패는 process-wide 진단 입장 차단으로 남는다. 이 전환은 스캔 주기 증가나 배터리 절감 입증이 아니며 실제 잠금/주머니 광고 수신과 전력 비용은 별도 측정한다.

실행의 비민감 미정상 marker는 시작 전에 동기 `commit()`으로 기록하고 최종 회차·중지 행의 `fd.sync`와 pending 0 확인 후에만 정상 종료로 지운다. 새 실행은 이전 비동기 완료가 새 marker를 지우지 못하도록 봉인한다. close/로그 실패는 marker를 유지한다. 다음 프로세스는 이전 미정상 실행을 원인 불명으로 남길 뿐 실행을 자동 복원하지 않는다. JSONL v6의 `bleTrial`과 `bleEvidence`는 해당 증거 행에만 있으며 시작/heartbeat는 attempt 0·IDLE·단계시간 null로 이전 회차를 재사용하지 않는다. 기존 v1~v5 바이트는 유지한다.

현재 앱은 프로세스 재생성 시 실행 OFF다. 저장된 선호를 표시하되 자동으로 마이크나 접근 관찰을 복원하지 않는다. 이는 검증 앱의 현재 제한이며 최종 무터치 운영 요구를 충족했다는 뜻이 아니다. `0.1.0-probe`에서 수동 10초 만료·즉시 종료·화면 이탈과 미등록 차량 차단을 확인했고, 후속 지원 조회와 STT·PCM 진단은 아래에 별도로 구분한다.

`0.2.0-probe`의 한국어 지원 진단은 `UnsupportedRecognitionService`의 인식 제공 기능과 별개다. 전자는 OS의 온디바이스 서비스에 지원 정보만 묻고, 후자는 우리 앱에 들어온 인식 요청을 계속 비지원 오류로 거절한다. 지원 조회는 기존 오디오 세션의 소유권이나 종료 사유를 바꾸지 않는다. 완료·오류·시간 초과·취소마다 인식 객체를 해제하고, 완료된 메타데이터는 화면 이탈만으로 지우지 않는다. Activity 재생성 시에는 미조회 상태로 시작한다.

VIN 광고명 계산은 [공식 Tesla BLE 코드의 `VehicleLocalName`](https://github.com/teslamotors/vehicle-command/blob/main/pkg/connector/ble/ble.go)을 따른다. CDM은 이름 필터로 사용자의 최초 선택을 받은 뒤 association 기반 presence를 요청한다. 광고 노출·주소 회전·장기 관찰 가능성은 미검증이며, 이 식별자는 차량 인증이나 발화자 인증이 아니다.

설정의 앱 키 등록과 개발자 진단의 읽기 전용 조회는 같은 프로토콜을 사용하되, 주말 BLE 관찰·음성 dry-run과는 별도 경로다. 사용자 NFC 키카드 접촉·차량 UI 승인은 앱이 대행하지 않는다. 공식 Tesla 앱의 폰키·사설 키·세션을 가져오거나 우리 앱 개인키를 서버로 옮기지 않는다. [공식 등록 계약](https://github.com/teslamotors/vehicle-command/blob/main/pkg/vehicle/security.go)은 P-256 `PRESENT_KEY`를 BLE로 보내며 정상 반환도 전송만 보장한다. 등록/infotainment 동기화 확인용 `DomainInfotainment SessionInfo` 안내와 현재 VCSEC 읽기 진단은 구분한다.

현재 로컬 소스·S23 설치는 `versionCode=25` / `0.25.0-probe`다. v21 초기의 등록 보고 뒤 인증 오류와 실차 검증 0회 기록은 당시 결과이며, 이후 [두 방문의 인증·재연결 조회](Device-Validation.md#실차-앱-키-인증재연결-조회-통과)에서 `STATUS_VERIFIED`·`COMPLETE`·`LOCAL_CLOSED`를 확인했다. v24 후속 실차 로그에는 `DRIVE_STATE_VERIFIED`와 정상 종료가 있고 남아 있는 앱 UI에서 지난 P 관측을 확인했다. 공통 VIN의 재실행 후 마스킹 표시도 유지됐다. 차량 광고명 match·잠금/주머니 재접근·프렁크·UWB 거리·물리 재부팅 뒤 차량 키 사용은 별도 미확인이다. v25는 BLE 전달 경로 전환이며 새 키 등록이나 차량 조회를 자동 실행하지 않는다.


## 서비스와 UI의 수명

음성 서비스가 단일 활성 세션의 수명과 오디오 소유권을 가진다. Compose UI의 재생성·백스택·프로세스 화면 상태가 녹음 세션이나 차량 명령을 소유해서는 안 된다. 서비스 상주 여부와 오디오 캡처 여부, 그리고 배터리 사용은 별개의 상태로 추적한다. 서비스가 살아 있다고 해서 항상 마이크를 쓰거나 wake lock을 보유하는 설계가 아니다.

통화·다른 앱의 마이크 사용·권한 철회로 입력이 제한될 때는 `isClientSilenced()` 등 녹음 구성과 오류를 관찰한다. 정상적인 조용한 환경과 정책에 의한 무음 입력을 구분한다. 오디오 출력 포커스를 얻었다는 사실은 마이크 입력 소유권의 증거가 아니다. TTS 안내와 확인 수집은 분리하고, 안내 중 캡처를 멈추거나 해당 결과를 실행 판단에서 제외하여 자기 재인식을 막는다. 안내 중 확인을 못 들었다면 실행하지 않으며, 동시 발화·취소 지원 수준은 실기기에서 검증한다.

세션은 만료 시각과 식별자를 가진다. 오디오 캡처 경로의 소유자는 하나로 제한하고, 호출어에서 명령으로 넘어갈 때 필요한 동일 세션 버퍼만 인계한다. 세션 종료 시 `AudioRecord`의 stop/release, `SpeechRecognizer`의 cancel/destroy, TTS·포커스·작업·버퍼 해제를 서비스 수명에서 책임진다. 프로세스 복구 때 이전 명령을 복원하거나 자동 재전송하지 않는다. 자세한 규칙은 [상태 머신](State-Machine.md)을 따른다.

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

2026-09-22에 다음 후보의 공식 지원·배포 조건을 비교했다. 현재 앱에는 외부 음성 SDK·가중치·AccessKey를 넣지 않았다. **KWS는 호출어 탐지, STT는 문장 전사이며 서로 같은 기능이 아니다.** 한국어 STT 모델이 있다고 한국어 전용 KWS도 준비된 것은 아니다.

### 후보와 선택 근거

| 후보 | 한국어·Android·PCM | 코드와 모델/서비스 조건 | 결정 |
| --- | --- | --- | --- |
| Android 온디바이스 `SpeechRecognizer` | Android 공식 API. 한국어는 서비스와 설치 모델에 따라 다름. 외부 PCM은 선택 기능 | OS 제공 모델을 재배포하지 않는 경로. 일반 인식기는 원격 처리 가능하므로 사용하지 않음 | 추가 SDK·계정 없는 지원 조회 및 **수동 STT·PCM 진단** 채택. S23 NO_MATCH 시험만으로 실제 엔진 채택 확정 안 함 |
| Porcupine | 공식 한국어 `.pv`, Android custom `.ppn`, mono PCM16 low-level API | 공개 저장소 Apache-2.0과 별개로 모델·SDK 서비스 약관 적용. 계정·비밀 AccessKey 필요. Android 문서는 키 검증용 `INTERNET` 요구 | 한국어 KWS 후보지만 **승인 전 도입 보류**. “헤이 테슬라” 모델 생성·배포권·가격·오프라인 키 검증 조건 미확인 |
| sherpa-onnx + Korean Zipformer | Android·직접 PCM 가능. 한국어 ASR 모델 제공. 조사한 공식 KWS 목록은 중국어/영어이며 한국어 KWS를 확인하지 못함 | 런타임 Apache-2.0. `sherpa-onnx-zipformer-korean-2024-06-24`의 원본 가중치 카드도 별도로 Apache-2.0 표기 | 직접 PCM STT 대안 후보. 가중치 포함 승인·기기 성능·연속 발화·신뢰도 검증 전 보류 |
| sherpa-onnx + SenseVoice | 한국어 포함 다국어 ASR, Android 경로 제공. KWS는 아님 | 런타임 Apache-2.0과 달리 원본 `SenseVoiceSmall`은 **별도 FunASR 모델 라이선스** | 런타임 라이선스만 보고 채택하지 않음. 모델 재배포 조건과 기기 실측 검토 후 결정 |
| whisper.cpp + multilingual Whisper | Android 예제·PCM 처리 가능. `.en`이 아닌 다국어 모델에 한국어 포함. 전용 KWS는 아님 | whisper.cpp MIT, OpenAI Whisper 코드·가중치 MIT. 로컬 추론은 OpenAI API 사용과 다름 | 계정 없는 동일 발화 STT 대안 후보. 모델·APK 크기·메모리·지연·배터리 검증 전 보류 |
| Vosk | Android 오프라인 STT, PCM 입력, `vosk-model-small-ko-0.22` 82 MB 제공. 전용 KWS는 아님 | 런타임과 해당 한국어 모델 Apache-2.0. 다른 Vosk 모델은 라이선스가 다를 수 있음 | 소규모 모델 대안. 차량 소음·연속 문장·명령 오인식 실측 없이 주 경로로 확정하지 않음 |
| openWakeWord | 공식 사전학습 호출어 모델은 영어만 지원. 한국어 모델을 직접 준비해야 함 | 코드 Apache-2.0과 모델별 라이선스는 별도. 한국어 데이터·학습·Android 통합 필요 | 이번 한국어 즉시 도입 후보에서 제외 |

Porcupine의 [현재 요금 페이지](https://picovoice.ai/pricing/)는 Free Trial과 영업 문의를 안내하며, 무료 영구 사용 조건을 확인해 주지 않는다. [이용 약관](https://picovoice.ai/docs/terms-of-use/)에는 제한된 trial·적용 요금·연결 시 usage data 전송 가능성이 명시돼 있다. 오디오가 로컬 처리된다는 설명을 “통신 없음·배포 제한 없음”으로 확대하지 않는다. 계정 생성·키 발급·요금·약관 동의·외부 SDK 도입은 사전 승인 대상이다.

전용 KWS 대신 접근 세션 안에서 STT로 전체 문장과 호출 문구를 함께 판정하는 것은 대안이지 이미 동등하게 검증된 구현이 아니다. [INFERENCE] 연산량·메모리·발화 종료 대기 비용이 늘 수 있으므로 기기 실측이 필요하다. 모델의 공개 벤치마크 수치를 S23·주머니·바람 환경의 성공률로 옮겨 쓰지 않는다. 어떤 대안도 현재 앱에 자동 fallback으로 넣지 않았다.

후보별 공식 근거:

- Porcupine [Android quick start](https://picovoice.ai/docs/quick-start/porcupine-android/), [low-level PCM API](https://github.com/Picovoice/porcupine/tree/master/binding/android), [한국어 모델 목록](https://github.com/Picovoice/porcupine/tree/master/lib/common).
- sherpa-onnx [Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html), [KWS 모델 목록](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html), [런타임 라이선스](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE), [한국어 ONNX 변환 출처](https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24), [원본 한국어 가중치 카드](https://huggingface.co/johnBamma/icefall-asr-ksponspeech-zipformer-2024-06-24).
- SenseVoice [Android·모델 안내](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html), [가중치 카드](https://huggingface.co/FunAudioLLM/SenseVoiceSmall), [FunASR 모델 라이선스](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE).
- Whisper [공식 코드·가중치 라이선스](https://github.com/openai/whisper#license), whisper.cpp [Android 예제](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android), [런타임 라이선스](https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE).
- Vosk [Android](https://alphacephei.com/vosk/android), [모델별 언어·크기·라이선스](https://alphacephei.com/vosk/models), [런타임](https://github.com/alphacep/vosk-api). openWakeWord [지원 언어와 라이선스](https://github.com/dscripka/openWakeWord).

### 지금 구현한 플랫폼 지원 진단

**이번에 채택한 것은 최종 인식 엔진이 아니라 Android 플랫폼 지원 진단 경로**다. `createOnDeviceSpeechRecognizer()`만 사용해 `ko-KR` 요청의 `checkRecognitionSupport()` 결과를 조회한다. 일반 `createSpeechRecognizer()`나 음성 인식 Activity를 열지 않는다. `EXTRA_PREFER_OFFLINE`만으로 오프라인을 보장할 수 없으므로 그것을 대체 경로로 삼지 않는다.

지원 조회의 네 목록은 서로 다른 뜻이다.

| API 결과 | 의미 | 설치 완료로 취급 |
| --- | --- | --- |
| `installedOnDeviceLanguages` | 해당 요청을 로컬에서 사용할 수 있다고 서비스가 보고 | 정확한 `ko-KR` 포함 여부만 설치 보고로 표시 |
| `pendingOnDeviceLanguages` | 다운로드 예정 | 아니오 |
| `supportedOnDeviceLanguages` | 지원하지만 먼저 다운로드해야 함 | 아니오 |
| `onlineLanguages` | 원격 구현으로 지원 | 아니오. 클라우드로 전환하지 않음 |

태그의 대소문자는 구별하지 않지만 `ko`, `ko-KP` 등은 정확한 `ko-KR` 설치 보고로 승격하지 않는다. 목록에 없거나 조회가 실패한 것을 실제 발화 시험의 실패/성공으로 꾸미지 않는다. 설치 보고가 있어도 네트워크 차단 상태의 한국어 인식·정확도·신뢰도·연속 발화는 별도 검증이다. 근거: [RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport).

지원 조회는 오디오를 열지 않는다. `startListening()`·`triggerModelDownload()`를 호출하지 않으며 외부 SDK·모델을 추가하지 않는다. 모델 설치나 설정 변경은 자동으로 수행하지 않는다.

S23 Ultra의 `0.2.0-probe` 실측: 온디바이스 서비스 있음, 정확한 `ko-KR` 설치 보고, 대기·다운로드 가능·온라인 보고 없음. 두 번의 지원 조회와 완료 후 화면 이탈·재진입을 확인했고 이 조회에서는 새 마이크 사용이 관측되지 않았다. 이를 근거로 아래 수동 STT·PCM 적합성 진단을 구현했지만, 외부 엔진으로 자동 전환하거나 지원 메타데이터만으로 최종 채택을 확정하지 않는다.

### STT와 RAM PCM·무음 파일 진단

`0.3.0-probe`에서 시작한 `SpeechRecognitionProbe`는 지원 조회와 별도다. `0.4.0-probe`는 여기에 고정 파일·무음 대조 입력을 추가한다. `createOnDeviceSpeechRecognizer()`와 `ko-KR`만 사용하며, 기본 비서용 `UnsupportedRecognitionService`는 계속 일반 요청을 거절한다. 지원 조회를 성공했다고 자동으로 음성 입력을 시작하지 않는다.

- 직접 경로는 시스템 인식기만 마이크를 소유한다. PCM 경로는 Activity가 16 kHz·mono·PCM16을 최대 10초·320,000바이트 RAM에 모은 뒤 `AudioRecord.stop/release`를 끝내고 인식기를 시작한다. 두 캡처를 병렬 실행하지 않는다.
- 파일 경로는 `AudioRecord`나 스피커를 시작하지 않고 WAV의 PCM 부분만 읽는다. 파일과 무음 대조는 같은 35,549샘플을 사용하며 대조만 0으로 채운다. 기존 PCM 전송·취소·정리 경로를 공유한다. 디코더는 기존 320,000바이트 PCM 버퍼와 최대 8 KiB 헤더 예산 안에서 포맷·경계·EOF를 검증한다.
- `EXTRA_AUDIO_SOURCE`에 pipe의 읽기 FD와 포맷을 전달하고, 모든 외부 PCM 입력에 `EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE`를 명시한다. [공식 분절 세션 계약](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_SEGMENTED_SESSION)에 맞춰 `onSegmentResults`를 받고 `onEndOfSegmentedSession`에서 마무리한다. writer의 EOF로 입력을 닫고 쓰기는 비차단·취소 가능한 worker에서 처리한다. 인식은 10초 제한 정책이며 전송 바이트 자체는 소비 성공의 증거가 아니다.
- `DiagnosticRuntime`의 오디오 예약은 화면 표시·권한·기존 캡처 없음·접근 진단 OFF에서만 획득한다. 예약 중 기존 수동 마이크·자동 접근·기능 활성화를 UI뿐 아니라 실행 진입점에서도 막는다.
- 화면 이탈·취소는 콜백을 먼저 무효화한다. worker 종료, 인식 객체·FD 해제, RAM 덮어쓰기를 확인한다. `0.11.0-probe`부터는 입력 정리 뒤 같은 예약으로 오프라인 음성 안내를 수행하고, 출력·포커스 정리까지 끝난 뒤 예약을 해제한다. 실제 해제 불명은 `RESTART_REQUIRED`와 예약 유지로 새 입력을 막으며 성공으로 숨기지 않는다.
- 일반 최종 결과 또는 최종 분절별 첫 후보를 고정 시험 문장과 비교한다. 공백·구두점 차이만 무시한다. 분절 사이에는 일치 여부·정규화 문자 수와 함께, 상한(128자)을 넘지 않는 동안만 정규화된 누적 문자열을 RAM에 유지한다. 누적 문자열은 회차 정리에서 비우며 state·로그·파일로 나가지 않고, 인식 원문·후보 목록도 어디에도 남기지 않는다. 분절 하나는 유효한 첫 신뢰도를 표시하되 여러 분절의 점수를 문장 신뢰도로 합성하지 않는다. 누락·음수·범위 밖·낮은 점수를 성공 값으로 보충하지 않으며 문장 일치도 명령 실행 허가가 아니다. 호출어·명령 판정은 `SpeechCommandDecision` 이름으로만 남기고 원문은 담지 않는다.

`0.10.0-probe`는 사용자 선택에 따라 진단 버튼 시작 방식을 유지하며, 최종 한 문장에 `SpeechCommandParser`를 적용한다. 문장 시작 정확한 “헤이 테슬라” 뒤 “프렁크 열어줘” 하나만 `FRUNK_OPEN_CANDIDATE`, 호출어 없음·명령 없음·허용 외·취소/부정은 별도 enum이다. 신뢰도는 이번 진단 판정에서 제외하고 원래 값을 기록한다. 이 흐름은 이미 시작한 STT 결과의 문장 판별이며 저전력 KWS가 깨운 별도 STT 세션이나 차량 명령 실행이 아니다. 부분 결과로 후보를 확정하지 않고 취소·실패·정리 실패에는 후보를 남기지 않는다.

`0.11.0-probe`는 `SessionPolicy.startDiagnostic()`의 같은 ID·40초 monotonic 소유권 안에서 최종 판정→`DiagnosticCommandPolicy`→`DryRunVehicleGateway`→입력 해제→`SpeechResponse`→출력 해제를 연결한다. gateway는 로컬 진단 계약만 갖고 계정·키·네트워크·차량 전송 API를 받지 않는다. 후보만 한 번 처리하며 결과 불명은 재시도하지 않는다. 처리 뒤 취소는 이미 관측한 로컬 결과를 보존한다.

`SpeechResponse`는 Android `TextToSpeech`의 한국어 지원·설치된 비네트워크 voice를 확인한다. 초기화 5초·재생 10초 제한, utterance ID별 완료/오류, 취소·포커스 손실, `stop/shutdown`과 포커스 반납을 관리한다. 입력 해제 미확인 시 시작하지 않으며 실패·해제 불명은 성공 안내로 대체하지 않는다. 진단 UI·v4 파일에는 로컬 결과, 안내 상태와 입력·출력·포커스 해제 여부만 추가하고 원문·PCM은 저장하지 않는다. 실측에서는 오프라인 voice 선택·완료 콜백·해제를 확인했으며 물리 스피커 음질·가청성 평가는 하지 않았다.


`0.3.0-probe`의 스피커 재생 시험에서는 두 마이크 경로가 `NO_MATCH(7)`였고 진행 중 이탈 취소·해제를 확인했다. `0.4.0-probe`의 무음 파일 시험에서는 처음 일반 최종 콜백만 사용했을 때 결과가 없었으나, 위 분절 계약 적용 뒤 고정 문장 일치를 두 번 확인했다. 같은 길이의 무음 입력은 `NO_MATCH(7)`, 즉시 HOME은 `USER_CANCELED`였으며 그 뒤 재실행도 성공했다. 새 앱 마이크 사용은 관측되지 않았다. 반환 신뢰도 `0.0`, 실제 발음·마이크·주변 소음·네트워크 차단·연속 문장 등은 최종 채택 전 별도 검증 항목이다.

### 외부 PCM과 한 문장 보존의 별도 게이트

호출어 끝과 STT 시작 사이의 잘림을 줄이려면 동일 접근 세션 안에서만 짧은 RAM 내 PCM 버퍼를 인계하는 방식을 검토한다. [EXTRA_AUDIO_SOURCE 공식 계약](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)에 따르면 이 옵션이 없거나 인식기가 지원하지 않으면 **인식기가 자체 마이크를 연다**. 플래그를 넣었다는 사실이나 언어 지원 조회 성공만으로 기존 `AudioRecord`를 소비한다고 보장할 수 없다.

고정 합성 파일의 외부 PCM 인식은 이제 실측했지만, 호출어와 연결한 캡처 스트림의 첫 음절 보존·단일 오디오 소유권까지 입증한 것은 아니다. 이 조건을 확인하기 전에는 접근 마이크와 시스템 STT를 병렬로 실행하지 않는다. 원거리 버퍼 수집은 금지다. 인계가 불가능하면 사용자가 두 번 말해야 하는 흐름으로 합격 처리하지 않고 동일 스트림을 처리할 온디바이스 대안을 검토한다.

`CONFIDENCE_SCORES`는 선택 사항이며 `-1`은 미제공 값이다. 점수가 없거나 낮은 결과에 임의의 높은 신뢰도를 부여하지 않는다. 호출어 감지는 발화자 인증이 아니고 지원 메타데이터는 명령 실행 권한이 아니다.

## 차량 게이트웨이 경계

음성 명령은 `DryRunVehicleGateway`이며 자동 음성 실차 제어와 전송 API는 연결하지 않았다. 수동 BLE 경로는 공유 `TeslaBleConversation`·`TeslaBleKeyStore`·GATT 예약/취소를 사용한다. 등록 후 VCSEC 잠금/프렁크 읽기와 v22의 별도 Infotainment `GetDriveState` 읽기는 query·도메인·검증 이벤트로 구분한다. 기어의 차량 원본 시각과 최종 fragment 수신 monotonic을 보존하되 기한은 처리 시각 기준으로 유지한다. 지난 P 관측은 현재 상태·근접·제어 허가가 아니다. 주말 BLE 감지 경로는 CCCD 외 TX 없는 RX-only 계약을 유지한다.

v21의 실차 앱 키 인증·암호 조회·같은 키 새 BLE 연결 조회는 통과했다. 다음은 **주차 기어 읽기 → BLE·UWB 차량 감지 개선·검증 → 신선한 명시적 `P`와 안전 게이트 → 별도 승인된 수동 프렁크 → 음성·접근 결합**이다. 프로세스 종료·재부팅 시험을 지금 개발의 필수 조건으로 추가하지 않는다. 실제 차량 게이트웨이는 현재 공유 프로토콜·키·GATT 소유권/정리 경계를 재사용한다. 만료·취소·중복·대상·권한·연결·신선도 검사는 실행 진입점에서도 강제하며 부족하면 제어를 거절한다. 전송 후 `UNKNOWN`은 재전송하지 않고 이전 명령은 복원하지 않는다. 수동 시험을 최종 제품의 매 사용 버튼 조작 경로로 삼지 않으며 초기 설정 후 잠금·주머니 무터치 목표를 유지한다.

[공식 BLE 프로토콜](https://github.com/teslamotors/vehicle-command/blob/main/pkg/protocol/protocol.md)과 [CLI `frunk-open`](https://github.com/teslamotors/vehicle-command/blob/main/cmd/tesla-control/commands.go)은 차량 키 인증이 필요한 BLE 명령 경로를 지원하며 Fleet OAuth를 필수로 두지 않는다. 이것은 공식 SDK 지원이지 우리 앱 구현·실차 성공의 증거가 아니다. 로컬 등록 키는 Driver/android_device이며 Fleet Manager의 BLE 제한과 혼동하지 않는다.

Fleet 서버는 인터넷 기능의 필요성·인증 모델·비용·키/토큰 보관 경계를 확인하고 사용자가 승인한 경우에만 검토한다. 이 선택 분기에 한해 Node.js+TypeScript와 공식 Go `vehicle-command` 서명 프록시 재사용을 검토하며 로컬 AndroidKeyStore 개인키는 추출하지 않는다. [Fleet 자체키·도메인·`_ak` 온보딩](https://developer.tesla.com/docs/fleet-api/virtual-keys/developer-guide)은 BLE 필수 단계나 공식 앱 폰키 개인키 전달 절차가 아니다. Fleet을 선택해도 신선한 `P`·별도 실차 승인·동일한 안전 게이트를 약화하지 않는다.

UWB는 별도 미확인 과제다. [Android UWB 공식 문서](https://developer.android.com/develop/connectivity/uwb)의 peer 주소·채널·세션 키 OOB 교환과 실제 거리·정리 검증이 필요하며 로컬 BLE 장기 키 생성·등록이나 framework capability 조회가 이를 대체하지 않는다.

실제 게이트웨이와 독립된 mock/dry-run 계약은 미전송·거절·요청 승인·관측된 완료·`UNKNOWN`을 구분해야 한다. dry-run 안내는 “실제 차량 명령은 보내지 않았습니다”로 실제 성공과 구분한다. 실제 연동 실패 때 mock 성공으로 조용히 전환하지 않는다. 상세 권한·서명·비용은 [Tesla 연동](Tesla-Integration.md)을 따른다.
