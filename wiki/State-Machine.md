# 상태 머신

> 실제 `TeslaConnectionStage`·`ProductVoiceStage`와 단발 프렁크 계약을 정의한다. 연결 준비는 [v80 통합 정본](Tesla-Integration.md#v80-인증된-수면-상태의-단발-ble-깨움), 음성은 [v78 모델 연결](Tesla-Integration.md#v78-검증-모델-연결)을 따른다. 버전·설치·저장 ON/OFF·실행검증 수치는 [기기 검증 단일 정본](Device-Validation.md#현재-소스설치검증-상태)에만 둔다. 기존 v76/v78 전이표와 UWB·좌표 전이는 역사로 보존한다. 최신 실행 결과는 기기 검증 정본을 따르며 실차는 미검증이고 v79 sleep 원인·실차 해결은 미확정이다.

[Wiki 홈](Home.md) · [요구사항](Requirements.md) · [네이티브 아키텍처](Native-Architecture.md)

## 상태와 소유권

Kotlin domain 계층이 정책 전이를 정의하고, Android 서비스가 단일 활성 세션·이벤트 처리·오디오 수명을 소유한다. UI 재생성은 세션을 새로 시작하거나 차량 명령을 다시 보내는 사건이 아니다.

| 상태 출처 | 실제 enum | 의미 |
| --- | --- | --- |
| `TeslaConnectionStage` | `IDLE`, `PREPARING`, `CONNECTING`, `AUTHENTICATING` | 감지 대기/연결 준비·실제 GATT 연결·기존 키 인증/새 domain3 인증. 이 상태만으로 마이크를 허용하지 않음 |
| `TeslaConnectionStage` | `CHECKING_VEHICLE` | 인증된 READ_ONLY BODY로 수면 상태 확인. P·음성 준비가 아님 |
| `TeslaConnectionStage` | `WAKING_VEHICLE` | fresh ASLEEP에 한해 같은 GATT 단발 깨움 진행. 물리 기상·P 확인이 아님 |
| `TeslaConnectionStage` | `MONITORING` | 같은 GATT에서 인증된 DRIVE P를 순차 갱신. `parkFresh`와 현재 owner/권한도 별도 검사 |
| `TeslaConnectionStage` | `EXECUTING` | 단발 음성 gate 인계 후 새 DRIVE→ACTION→BODY 진행. 명령 접수/ACK/OPEN은 각각 별도 필드 |
| `TeslaConnectionStage` | `STOPPED`, `BLOCKED`, `FAILED` | 중단·입장 차단·실패. 정리 완료나 물리 미실행을 뜻하지 않음 |
| `ProductVoiceStage` | `IDLE`, `PREPARING`, `LISTENING` | RAM lease와 실제 microphone FGS 준비, fresh P 동안 발화 대기 |
| `ProductVoiceStage` | `RECOGNIZING` | 마이크 실제 해제 후 external PCM 인식. 수집 중으로 표시하지 않음 |
| `ProductVoiceStage` | `STOPPING`, `COMMAND_READY` | 음성 봉인·정리 중, 또는 정리 확인 후 인정된 단발 명령. 차량 완료가 아님 |
| `ProductVoiceStage` | `STOPPED`, `BLOCKED`, `FAILED` | 종료·준비 조건 차단·오디오/인식 실패. 정리 불명은 예약 유지·재시작 필요 |

기어 `UNKNOWN`은 명시 P가 아니며 음성을 봉인한다. 차량 명령 결과의 `UNKNOWN`은 별도로 **결과 불명**을 뜻한다. 전송 후 취소·만료를 미실행 보장으로 표시하지 않는다. 제품은 기본 false 별도 영속 동의와 사용자가 직접 선택한 ready 기본 비서를 요구하며 일반 진단 dry-run·수동 실차 시험과 분리한다.

## 현재 연결 준비와 음성 진입

| 현재 상태 | 이벤트·조건 | 다음 상태·필수 동작 |
| --- | --- | --- |
| 감지 대기 | 저장 ON·새 owner/token·빈 후보·fresh 정확 광고·기존 키·native scan pause 확인 | 단일 GATT 연결·기존 own-key 인증 후 `CHECKING_VEHICLE`, READ_ONLY BODY 읽기. 이전 후보/P/음성/명령 복원 금지 |
| `CHECKING_VEHICLE` | 인증된 BODY·원 ingress receipt 유효·5초 이내·수면 상태 `ASLEEP` | `WAKING_VEHICLE`, 같은 GATT의 새 깨움 전용 conversation으로 RKE 30 단발 |
| `CHECKING_VEHICLE` | 인증된 fresh BODY·수면 상태 `AWAKE` 또는 `UNKNOWN` | 깨움 생략, 새 domain3 인증·READ_ONLY DRIVE 조회 |
| `CHECKING_VEHICLE` | BODY 인증 오류·receipt 없음/음수/미래/stale | 중단·정리. 수면 추정·mic·프렁크 ACTION 없음 |
| `WAKING_VEHICLE` | 검증된 encrypted ACK | 새 domain3 인증·READ_ONLY DRIVE 조회. 물리 기상·P·프렁크 성공으로 승격 금지 |
| `WAKING_VEHICLE` | 현재 요청 상관 일치·signature/payload 없음·명시 status OK/fault 0의 `NOMINAL_ACK` | 인증된 깨움 성공 없이 새 domain3 인증·독립 READ_ONLY DRIVE만 조회. 새 fresh P까지 mic/ACTION 0, 재깨움 없음 |
| `WAKING_VEHICLE` | `REJECTED`·기타 `UNCONFIRMED`(unknown status/payload)·접수 후 timeout/결과 불명 | 중단·정리, 재깨움 없음 |
| 최초 fresh P 전 새 DRIVE | wake ACK/nominal 뒤 인증된 명시 INVALID·gear UNKNOWN, 추가3회 미만·고정 준비창/원기한 이내 | 음성/명령 없이 준비 유지,2초 뒤 새 독립 인증 READ_ONLY DRIVE. ACK부터25초·원 global의 min 고정, wake 재실행 없음 |
| 최초 준비 외 DRIVE 또는 새 DRIVE의 다른 비P/불량 시각 P | 생략/빈/SNA/미지원/D/R/N·인증 오류, 성공 후 monitor/command INVALID, 횟수/기한 초과 | 기존 중단·정리. Invalid/과거 P/source 시각을 제어 허가로 승격하지 않음 |
| 새 DRIVE 확인 | 인증된 fresh 명시 P·동의·선택/ready 비서·권한·owner/lease 유효 | `MONITORING`, 음성 `PREPARING → LISTENING`. 이 전까지 mic와 프렁크 ACTION 0 |
| `MONITORING` / `EXECUTING` | 후속2초 P poll / 오디오 정리 뒤 명령 인계 | 같은 GATT의 새 DRIVE / DRIVE→ACTION→BODY. BODY/wake preflight 재실행 없음 |
| 모든 활성 단계 | OFF·동의 철회·owner/권한 상실·기한 | 새 결과/TX 봉인 후 실제 정리. OFF 항상 가능, 정리 불명은 예약 유지·재시작 필요 |

- 모든 실제 write 직전 owner·OFF·권한·consent·기한을 재검사한다. 깨움 write의 각 조각과 SDK201 미접수 재발급은 원 ASLEEP ingress의 5초 freshness도 재검사한다. 기존 BUSY 제한의 동일 frame 외 재발급·새 깨움 conversation 재시도는 없다.
- 기존 global 기한과 각 실제 protocol 단계 고정10초를 유지한다. BODY→WAKE→DRIVE 및 실제 인증→조회 전이는 별도 단계이며, 같은 단계의 `Waiting`이나 반복 ACK는 기한을 연장하지 않는다.
- 초기 INVALID의 다음 조회는 새 실제 거래지만 ACK 기준 준비창·원 global을 갱신하지 않는다. 회차 전체 monotonic 역행을 감시하며 예약·실제 발급·SDK201·queue drain·late RX에서 검사한다. 새 fresh P를 수용하면 초기 예외를 영구 해제하고 이후 UNKNOWN은 즉시 봉인한다. 재조회 사건은 새 시작 시도이지 SDK 접수·P 증거가 아니다.
- 이후 음성·단발 명령·정리는 기존 v78 모델 및 아래 불변식을 유지한다. DRIVE의 `UNKNOWN`은 P가 아니지만 위 BODY 수면 상태의 `UNKNOWN`은 깨움 생략 후 DRIVE 조회라는 차이를 보존한다.
- 음성은 [v84 prepared owner](Tesla-Integration.md#v84-음성-발화-확인lease별-모델-준비비민감-진단)를 적용한다. `PREPARING`에서 lease 전용 모델 준비 뒤 마이크를 열고 연속120ms 유성을 확인한다. 비명령은 stream 실제 해제 뒤 같은 모델로 `PREPARING → LISTENING`하며 recognizer 보유 중 full cleanup은 false다. `RECOGNIZING`은 실제 마이크 해제 구간이다. 인정/종료에는 JNI 반환·owner full-close 확인 전 명령 인계·lease 반환을 금지한다. schema39 진단은 이 전이를 관측할 뿐 제어 권한이 아니다.


## 과거 UWB·좌표 및 진단 전이

다음 버전별 절들(「v44 화면 표시 실차 음성 전이」 전까지)은 당시 UWB·좌표 서비스와 로그/REA 해석을 보존한다. **아래의 “현재”, 음성 미연결, `RANGING` 권한, 거리 기한·좌표 sidecar는 그 버전 당시 범위이며 v76 운영 전이가 아니다.** 해당 실행 코드·전용 테스트는 제거했고 공유 보안·GATT 경계는 connection으로 이관했다.

### v55 제품 서비스 UWB 전이

`사용자 ON/저장 ON의 새 token 복원 → 빈 후보로 감지 → 신선한 정확 FILTERED_SCAN → 한 후보 한 회차 → native scan pause 확인 → 기존 앱 키 lease·인증·FiRa → UWB → CLEANING_UP → 해제 확인·lease 반환 → 감지 재개·이탈 후 새 접근 대기`다. 현재 제품 모드는 `VEHICLE_SERVICE_UWB`이며 아래 과거/별도 `VEHICLE_NAME_DETECTION`의 GATT 없는 감지 계약을 바꾸지 않는다. 마이크·음성·프렁크로의 전이는 없다.

- 살아 있는 `ObservationService`·token·현재 후보/회차·VIN snapshot·광고 원 `BluetoothDevice`·신선도를 확인한다. CDM/자체 BT·주소 fallback·이전 세대/중복/역순 광고·타이머/재개 callback은 회차를 만들지 않는다. 이전 후보·세션·측정값·명령은 복원하지 않고 새 광고만 소비한다.
- v60의 서비스 입장은 최초 SDK 연결 발급 직전까지 광고10초 신선도를 요구한다. 이번 Operation이 실제 반환 GATT handle을 소유한 뒤에는 별도 admission 상태로 연결 수명을 관리하며 원 광고 age만으로 취소하지 않는다. 초기133은 연결 진전/전송 전의 같은 handle에서2초 뒤1회만 허용하고 기존 단계·전체 기한은 연장하지 않는다. admission 상태는 새 회차·복원에 재사용하지 않고 owner/token/VIN/권한/BT/OFF 검사는 계속 적용한다.
- `RANGING` 미허용이면 감지는 유지하고 UWB 입장만 차단한다. 기존 launcher의 명시적 권한 요청 결과는 준비 상태를 갱신할 뿐이며, 권한 팝업 복귀만으로 종료된 서비스나 이전 UWB를 재시작하지 않는다. OS가 저장 ON 복원을 거절하면 사유를 남기고 직접 시작을 기다린다.
- HOME·잠금·진단 화면 이탈은 정확한 `serviceOwned` 회차를 취소하지 않는다. 수동 UWB는 서비스 OFF·직접 시작·화면 표시/잠금 해제 조건과 화면 이탈·HOME·잠금 취소를 유지한다. 수동 등록/조회·음성/프렁크로 background 예외를 확장하지 않는다.
- 초기 연결·인증·FiRa 협상의 180초 기한과 최초 유효 거리 대기 60초를 유지한다. 첫 유효 callback 뒤 서비스 측정은 `마지막 유효 수신 elapsed + 10초`로 종료 기한을 갱신하며 유효 수신 중 고정 60초·전체 180초 상한은 적용하지 않는다. 무효 callback은 기한을 갱신하지 않는다. 수동 UWB의 측정 60초·전체 180초는 그대로다.
- 한 접근 후보에서 성공·실패 뒤 자동 반복하지 않는다. 스캔 pause·GATT·정리 시간은 이탈로 세지 않으며 정리 후 실제 재관측으로 후보 철회/만료와 새 광고가 확인되어야 재무장한다.
- OFF·권한/BT/owner 상실은 새 소비·TX를 먼저 봉인하고 정리한다. `RANGING` 철회는 UWB를 정리하고, BLE 필수 조건 상실은 감지도 중지한다. native 정리 확인 전 FGS/lease를 먼저 반환하거나 스캔을 재개하지 않는다. 정리 실패는 `CLEANUP_FAILED`·예약/FGS 유지·신규 입장 차단이며 Activity 재생성으로 지우지 않는다.
- wake lock은 대기 중 없고 초기 연결 180초+정리 여유 5초로 제한한다. `serviceOwned` `RANGING`의 새 유효 거리 수신 때만 최소 5초 간격으로 20초 유한 갱신한다. 완료·정리 실패에서도 lock 해제를 시도하되, 해제 자체가 불명이면 lock 참조·실패 latch·`diagnosticCleanupUncertain`·field/scan 예약·FGS를 유지하고 재시작을 요구한다. lock 해제 성공과 native 정리 불명의 FGS/lease 보존은 별개다. 과거 수동/접근 진단의 45초 lock은 이 지속 측정의 상한이 아니다.
- 측정 세션 시작 시 상태를 초기화해 이전 수동 표본을 사용하지 않는다. peer 정지·endpoint close·cleanup은 `livePeerIndices`에서 즉시 제거하며 과거 samples는 진단 근거로만 남긴다. `onOpened`/`onStarted`는 실제 거리 성공이 아니다.
- 알림은 `serviceOwned`·`RANGING`·live peer의 유효 표본 중 callback 수신 elapsed 기준 2초 이내의 최신 값만 표시한다. 동시 수신은 작은 peer index를 선택하고 다른 peer의 각도를 섞지 않는다. 기존 readiness tick에서도 만료를 반영하며 전체 중지/정리 실패·UWB 오류가 거리보다 우선한다. 표시 TTL 2초와 세션 미수신 종료 10초는 별개다.
- AoA 요청은 실제 이번 세션 capability에 따른다. v55에서는 raw 각도·confidence를 각각 검증해 RAM에만 보관했다. v59는 같은 `lastReceivedSample`의 거리·peer·수신 시각에 raw 방위각/고도각·독립 축별 confidence를 묶고 세션의 축별 지원 여부와 함께 FieldLog schema24에 기록한다. 음수를 허용하는 유한 Double/null·confidence 0..2 정수/null·capability Boolean/null 계약을 따른다.
- 다음 표본의 누락·무효 각도나 confidence를 이전 값으로 채우지 않는다. 종료·정리 스냅샷의 지난 표본을 새 수신으로 세거나 현재 알림·미수신 기한·wake lock 갱신에 재사용하지 않는다. v1..v23 로그 해석과 비민감 숫자·Boolean/null만 허용하는 기존 순환 로그 경계는 유지한다.
- schema25 서비스 GATT 스냅샷은 같은 Operation의 단계·SDK 발급/반환·접수·원 callback·CCCD·실제 경과시간을 분리한다. terminal은 보존하되 새 작업은 초기화하고, 이 스냅샷으로 거리 freshness·미수신 기한·wake lock을 갱신하지 않는다. 결과 변환의 false/null/0 기본값을 실제 연결 실패 관측으로 간주하지 않는다.
- schema26 서비스 RX는 `notification → decoder 산출 frame → queue → 실제 conversation 소비`를 분리한다. 미완성 조각은 protocol 처리 성공이 아니며 실제 반환 step·AEAD 결과·FiRa 분류·header 제외 사유를 해당 protocol 시각/누적수와 연결한다. 반복·terminal snapshot은 마지막 결과 보존일 뿐 새 분류가 아니다. 기존 GATT 관측 사건을 재사용해 거리 freshness·측정 기한·wake lock 갱신에서 제외한다.
- schema27의 본문 최상위 구조와 `CURRENT/PREVIOUS/UNMATCHED`는 수신 진단뿐이다. 직전 생성 identity와 일치해도 기존 route/UUID·AEAD·replay 검사를 우회하지 않는다. 미분류/해석 오류의 실제 plaintext에서만 값 없는 bounded 요약을 만들고 매 수신 reset한다. 중복 snapshot·관측 사건은 거리·기한·wake lock을 갱신하지 않으며 직전 identity는 회전·종료에 소거한다.
- UWB AoA의 단위·실차 앞뒤 판별은 미확정이며 정밀360도 검증을 유지한다. v59의 회전 화살표·GPS·나침반 미추가는 당시 범위이고 [정차 회전 시험](Tesla-Integration.md#v59-360도-검증용-raw-계측)도 개발자 UWB 검증용이다. 현재 v66의 별도 좌표/폰 진북 화살표와 UWB를 합성하거나 기기명/해시로 단위를 추정하지 않는다.
- 이 절은 소스 구현 계약이다. 컴파일 클래스의 합성 smoke에서 유효 수신 300초 지속·10초 미수신 종료 등을 관측했어도 RF·잠금·거리·방향·native 정리의 실기기 검증을 대신하지 않는다.

### v66 좌표 안내의 독립 전이

상세는 [v66 정본](Tesla-Integration.md#v66-좌표-기반-나침반uwb-병행)에 둔다. 기존 UWB 전이에 제어·음성 전이를 추가하지 않고 `실제 BeaconService type ACK → armed → 동일 새 접근 request 입장 시 phone source 시작 → domain3 단발 읽기 → 개별 freshness 표시/만료`를 병행한다.

- armed idle에서는 센서 OFF다. 위치 읽기 거절/15초 timeout/null 또는 좌표 없이 operation 종료 시 source를 해제한다. 유효 차량 fix가 있으면 원 나이를 반영한60초 이내 남은 기간만 유지하고 만료/clock invalid에는 UWB 회차 중에도 해제한다. 정상 해제 후 lease만 armed로 유지해 다음 새 접근에 재사용하며 같은 접근 재수집/poll/retry는 없다.
- 위치 실패는 UWB를 실패시키지 않고 실제 공유 SDK/transport 오류는 전체 정리로 보낸다. domain2/3의 crypto·identity·replay를 분리하고 primary 전송/입력을 우선한다.
- heading2초 만료는 화살표만, 폰15초/차량60초 만료는 해당 숫자/방향을 숨긴다.100ms 초과 시계 샘플은 `CLOCK_SAMPLE_DELAYED`로 anchor를 유지한 일시 숨김이며 실제 후퇴는 `CLOCK_INVALID`로 봉인한다. 만료된 값을 새 callback 처리/화면 복귀로 부활시키지 않는다.
- v73은 `시각 불명/미래/모순 → VEHICLE_LOCATION_TIME_INVALID → 수집 중지·raw RAM clear`와 `실제60초 초과 → STALE`을 구분한다. 존재하지만 형식이 잘못된 Timestamp는 parser에서 거부한다. 어느 쪽도 UWB primary 종료·재조회·기한 연장으로 이어지지 않는다. [시각 분류 정본](Tesla-Integration.md#v73-좌표-시각-분류와-v72-uwb-대조)을 따른다.
- OFF·VIN/owner/권한 상실 → 입력/TX·late callback 봉인·raw RAM clear → source/FGS 정리다. 해제 불명은 예약·FGS를 유지하며 새 접근을 차단한다. fieldToken과 leaseId·단조 sequence·terminal ledger·death owner generation·단발 close callback의 최종 IPC 계약은 정본을 따른다.
- background 권한 없는 boot/복원은 BLE/UWB 감지와 좌표 명시 재개를 분리한다. 권한이 있어도 새 owner·빈 좌표로만 복원한다. 권한 결과/팝업 복귀·Activity 재생성은 죽은 서비스·이전 위치 회차를 재시작하는 사건이 아니다.

### v67 관측 전이와 metadata 주소 분리

- 상세는 [v67 정본](Tesla-Integration.md#v67-metadata-주소-처리위치-관측-정본)을 따른다. unsigned field44에 한정해 주소 검사 → body semantic decode → 기존 metadata consumer로 분리한다. to 없음/DOMAIN0·2는 synthetic allowlist이고 명시 route/UUID 불일치는 decode 전에 제외한다. signed 응답·FiRa/stop·AEAD/replay 전이는 유지하며 field3 grant/rejection 전이는 추가하지 않는다.
- schema28의 `navigationObservation` / `VEHICLE_NAVIGATION_OBSERVATION`은 대기·실제 sidecar 시작·HELLO/QUERY 생성과 실제 frame TX·sidecar 수신 판정/authEvidence·최종 reason·terminal을 분리한다. source/FGS는 실제 등록/해제 결과, UI는 presentation status가 바뀔 때만 관측한다. 반복 snapshot이나 센서 매표본을 새 사건·거리 수신으로 세지 않는다.
- raw GPS/heading·절대 GPS/state 시각·키·주소·ID·token은 금지하고 기존1..27 해석·primary RX 분모를 유지한다. owner 소거 전 terminal 관측은 늦은 callback 재입장·위치/세션 재생 허가가 아니다. 관측으로 RF·수집·TTL·기한·retry·권한·마이크/프렁크 전이를 바꾸지 않는다.

### v68 초기 실제 단말정보 전이

- `기존 키 HMAC 검증 성공 → UWB operation의 실제 source.snapshot(false) 한 번 → 암호화 field40 → 기존 field58`이다. field44 수신을 기다리지 않는 초기 전송이며 일반 field1 반복으로 이 전이를 재실행하지 않는다. 초기 배터리/thermal 조회·구독은 없고 명시 GET_MODEL/ONCHANGE 전이는 그대로다.
- `field40 전체 BE16 frame의 모든 조각 성공 callback 완료 → TESLA_UWB_INITIAL_DEVICE_INFO_SENT`만 허용한다. queue/SDK 접수만으로 이벤트를 발행하지 않고 이벤트를 native RF 시작·차량 수락·거리 수신 전이로 해석하지 않는다. v68 당시 schema28 유지·모델/버전 hash·raw값 미기록 계약을 v69의 고정 enum 확장과 구분한다.
- 공식은 onWhitelist 연결 약1초 뒤이고 우리는 HMAC 검증 직후다. 동등한 보안/시점이나 실차 해결로 표시하지 않는다. 기존 single pump·AEAD·route/UUID·counter/replay·owner/OFF/권한·협상30초·sidecar15초·첫 거리60초는 유지하며 field19 재송신·passive-auth DRIVE·field3 grant/rejection·legacy GET_STATUS 전이는 추가하지 않는다.

### v69 선행 RX의 무승격 전이

- `수신별 관측 reset → 기존 활성/크기/시각/기한 거부 → 시작 전 ingress만 parse 전 Ignored → 판정 관측 기록`이다. `receivedAt == startedAt`는 기존 정상 경로를 유지하고 ingress를 처리 시각으로 바꾸지 않는다. 상세 순서와 schema29 계약은 [정본](Tesla-Integration.md#v69-선행-rx-수신-경계-정본)을 따른다.
- primary·sidecar·frunk의 `Ignored`는 phase·기한·request identity·counter/replay·HMAC/AEAD·domain/route/UUID를 바꾸지 않는다. 인증·metadata·FiRa·좌표·성공·새 BODY fallback·actuator로 전이하지 않고 후속 정상 입력은 기존 검증을 모두 통과해야 한다.
- `IGNORED` / `RESPONSE_BEFORE_START`와 실제 protocol 소비 관측은 거리·freshness·기한·wake lock 갱신이 아니다. 반복 snapshot은 새 수신이 아니며 기존1..28 해석과 원문·새 시각·PII 미추가를 유지한다.

### v70 FiRa 재요청의 세대별 전이

상세 순서·공식 근거·REA 원문 누락과 원 DEX 보완의 한계는 [통합 정본](Tesla-Integration.md#v70-fira-재요청-교체유지-정본)을 따른다. 단발 협상 guard 대신 같은 ID의 교체와 다른 ID의 유지/추가를 적용한다. 실제 old close·새 response 전체 TX·new native 시작을 구분하고 이전 endpoint callback이 같은 숫자 ID의 새 세대를 바꾸지 못하게 한다.

재요청은 원 첫 거리 기한을 재발급하지 않으며 거리 freshness·무수신·wake lock 갱신도 아니다. 기존 owner/OFF/권한·암호/route/replay·정리 실패 봉인과 예약 보존을 유지한다. metadata/source·좌표 sidecar 재시작·음성/프렁크·새 제어 허가로 전이하지 않는다. 종료 세대 표본은 현재 알림에서 제외하되 최종 historical 관측과 과거 실패는 보존한다.

### v71 기한 만료의 관측

기존 만료 전이 직전에 실제 최초 만료 기한을 기록하고 `TIMED_OUT / DEADLINE_EXPIRED`로 종료한다. cleanup·재진입이 최초 원인 또는 null을 덮어쓰지 않으며 새 회차는 미관측으로 시작한다. 원인 표시는 새로운 상태 전이·기한 연장·재시도·성공 판정이 아니다. 정확 판정·동률 규칙은 [정본](Tesla-Integration.md#v71-기한-만료-원인-관측)을 따른다.






### v61 FiRa request 세션과 stop 전이

`인증 뒤 request 수신 → uint32 requestSessionId 보존·중복 검사 → 별도 FiRa/backend ID로 plan 준비 → 원 request ID echo response → BLE 완료 시 아직 살아 있는 plan만 RF 시작`이다. request를 endpoint enum1..11로 제한하던 v60 해석은 정정한다. `requestSessionId: Long`은0/누락 기본값0·상위 bit·`0xffff_ffffL`까지 보존하며 response field1의0 생략은 proto 기본값 의미를 유지한다. backend `firaSessionId: Int`·기존 nonzero 정책은 독립이고 peer/bit 추정 매핑은 없다.

|사건|전이·완료 조건|
|---|---|
|알려진 stop enum0..11, 모두 exact 매칭|`Long`으로 확장한 stop값과 원 request ID가 같은 plan만 정리한다.0=NONE은 exact0 key이며 전체 stop 뜻이 아니다. 부분 정리는 남은 세션의 RF·주소·STS를 보존한다.|
|빈/unknown/하나라도 미매칭 stop|회차 봉인 → 전체 소유 RF 로컬 정리 → 새 TX 금지. **우리 fail-closed 정책**이며 공식의 전체 종료와 동등하다는 근거가 아니다.|
|response 전송 대기 중 stop|대상 plan 종료를 먼저 봉인한다. 이후 원 BLE 완료 callback도 닫힌 plan의 RF를 시작하지 못한다.|
|자연 종료·timeout·peer 종료|uint32 전체의 소유 request ID로 정리한다. close 성공 뒤 stopped에는 원 ID 중0..11로 표현 가능한 항목만 전송하고 혼합 목록도 해당 항목만 정산한다.|
|정리 성공·stopped 전부 비표현|새 TX 없이 기존 `TESLA_UWB_SESSION_STOPPED_NOT_SENT` → 통지 처리 완료·로컬 종료 정산. 송신 완료와 처리 완료를 구분해 영구 대기하지 않는다. **공식 `oc0.e.G`는 enum filter 뒤 빈 `z1`도 생성하므로 우리 TX 생략과 다르다.**|
|취소·실패·OFF·권한/owner 상실|새 TX 봉인 → 기존 정리. native close 불명은 lease·예약·재입장 차단 유지. 중복 close/late callback이 다음 세션을 바꾸지 못한다.|

이 변경은 기존 키·인증·권한·owner/token/VIN·요청별 replay·서비스 기한·RAW backend·부분 소거 격리 및 자동 마이크/프렁크 미연결을 유지한다. [v61 REA·원 DEX 대조](Tesla-Integration.md#v61-rea원-dex-대조--fira-식별자-계약-정정)는 정적 근거다. v61 설치·호스트/비차량 회귀는 확인했지만10/26 17:07 실차1회는 인증·CCCD·notification12 뒤 협상30초 기한이 만료됐고 request 처리·RF·거리/각도는 미관측이다. 후속 v62는 분류 관측을 보강하며 제품의 수락·보안·기한 전이는 바꾸지 않는다.

### v65 metadata 수명·거리 receipt 전이

wire 상세는 [단말 정보 응답·거리 receipt 정본](Tesla-Integration.md#v65-단말-정보-응답거리-receipt-시각)에 둔다. metadata는 기존 UWB conversation 안의 별도 typed 입력이며 FiRa request/stop·ACK·인증 승인·거리 수신 전이가 아니다.

- `GET_MODEL → 실제 snapshot → 기존 암호 송신 queue`, `ONCHANGE → owner당 단일 구독·초기 snapshot → 변경 coalescing·최신 미송신1개 → 기존 송신`이다. 반복 요청은 구독을 늘리지 않는다. NONE은 무응답이며 해제로 해석하지 않는다.
- `seal/취소/OFF/owner·권한 상실/종료 → callback·TX 봉인 → task/body 소거·모든 source 구독 해제 → 기존 bounded 정리`다. 부분 등록·해제 실패도 기존 정리 불명/예약 유지에 합산하며 late callback은 다음 owner를 바꾸지 않는다. metadata는 기존 기한·거리 freshness·wake lock을 갱신하지 않는다.
- `native results ingress → receipt 캡처 → owner queue → 현재 권한·상태·기한 검사 → 유효 표본 전달`이다. framework source timestamp는 원값으로 별도 보존한다. 처리 지연 뒤 알림2초나 receipt+10초 기한을 새로 시작하지 않으며 최초60초/기존 협상·수동 기한도 유지한다. close·만료·권한 상실 뒤 queued 표본은 현재 거리/live peer를 부활시키지 못한다.
- field3 UNLOCK/DRIVE 자동 grant·임의 rejection은 이번 전이에 없고 마이크·프렁크·차량 제어로 이어지지 않는다. schema27·RF 설정·retry/timeout은 그대로이며 호스트 합성 관측과 실제 차량/RF 성공을 분리한다.




## 수동·진단 경로와 역사

### v44 화면 표시 실차 음성 전이

`RAM 동의 → DIRECT_MIC COMMAND → ACCEPTED → 입력 정상 해제 → 한 번만 DISPATCHED handoff → 같은 차량 작업의 DRIVE P → fresh VCSEC 프렁크 → 후속 BODY 관측 → bounded 정리`다. 별도 확인 발화는 없다. 이 수동 시작 경로는 현재도 별도이며 일반 dry-run은 차량 미전송이다. v76 제품 음성은 위·아래의 별도 연결 소유권을 사용한다.

- 명령10초·handoff5초, 전체 음성 세션40초를 유지한다. 현재 승인 정책은 최종 n-best 중 정확한 전체 문법 후보를 허용하고 모든 후보의 취소어를 우선한다. 실제 점수가 있으면 채택 후보0.85 이상을 요구하며 승인된 점수 미제공은 점수 gate만 생략한다. 마이크 입력 해제와 인식기/pipe 정리 확인이 모두 끝나야 DISPATCHED로 넘어간다. 어느 쪽이든 불명이면 예약을 유지하고 음성 lease 반환·차량 core 입장을 막는다. UI 단계는 runtime에서 도출하며 재구성·화면 복귀로 새 작업을 만들지 않는다.
- 실차 명령은 명령 인정부터40초 기한 안에서 fresh P와 권한을 검사한다. SDK201 미접수 조각 외 동일 요청의 자동 재발급은 금지한다. 전송 이후 취소·timeout은 UNKNOWN이며 차량 철회를 보장하지 않는다.
- SDK 접수·명령 응답·프렁크 상태를 분리하고 ACK와 후속 명시 OPEN을 모두 확인한 경우만 전체 완료다. OPEN 보고도 후드의 완전 개방 범위를 보증하지 않는다. 프로세스 복원으로 음성 동의·이전 후보·명령을 재생하지 않는다.

### 구현된 로컬 진단 전이

`0.11.0-probe`의 진단은 제품 전체 자동 접근 상태 머신과 구분한다. `SessionPolicy.startDiagnostic()`이 고유 ID·40초 monotonic 만료를 발급하며 기존 캡처·접근 관찰과 상호 배제한다. 개별 캡처·인식 제한은 기존 10초이며 오디오를 40초 상시 수집하는 기능이 아니다.

- `RECOGNIZING → VALIDATING`: 최종 전체 결과만 `DiagnosticCommandPolicy`에 전달한다. 부분·미수신·거절은 gateway를 호출하지 않는다. 취소와 실행 시작은 Main의 같은 직렬 경계에서 처리하며 `now >= deadline`이면 만료다.
- 후보의 로컬 처리 시작 전에 결과를 `UNKNOWN`으로 봉인한다. 완료 결과·예외·재진입·중복 결과는 같은 의도를 다시 처리하지 않는다. `PROCESSED`는 로컬 dry-run이며 차량 전송·승인·물리 개방이 아니다.
- `CLEANING → RESPONSE_INITIALIZING → RESPONDING`: 인식기·writer·FD·RAM 입력의 해제가 확인된 뒤에만 설치된 한국어 오프라인 voice로 안내한다. TTS가 끝날 때까지 같은 current·예약을 유지하며 입력을 다시 시작하지 않는다.
- 입력/출력·포커스 해제 확인 후 소유자가 current·예약을 해제한다. 외부 중지는 소유권을 먼저 풀지 않고 취소를 전달한다. 해제 불명은 재시작 요구와 예약 유지다.
- 처리 전 취소는 `CANCELED`로 처리 차단한다. 로컬 처리 후 취소는 `PROCESSED`/`UNKNOWN`을 보존하고 안내만 중단한다. 처리된 일을 미처리로 가장하지 않는다.
- 이 절의 로컬 dry-run 판정과 차량 제어 게이트를 구분한다. 현재 제품/수동 프렁크는 기존 점수 미제공 정책을 함께 사용하지만 fresh P·기존 키 권한·동의·정확 명령·정리·만료 gate는 생략하지 않는다.

## 과거 감지·접근 읽기 전이

다음 절부터 「수동 차량 키 등록·읽기 전이」 전까지는 당시 버전의 감지·복원·예약 이력이다. `VEHICLE_SERVICE_UWB`·UWB 예약·주기 조회 없음·이전 자동 마이크 등의 서술은 현재 소스 존재나 v76 연결 음성 계약이 아니다. VIN 보존·late callback 봉인·정리 불명 차단의 공용 안전 경계는 유지한다.

### 과거 주말 BLE 반복 시험 전이

이 절은 `0.14.0-probe` 비교 시험과 당시 로그 해석 범위다. 현재 제품의 제목·회차 조작이 아니며 아래 감지 전용/읽기 이력과 함께 보존한다. v55 서비스의 자동 UWB·복원·기한은 위 별도 계약을 따른다.

`0.14.0-probe`는 명시적으로 시작한 서비스가 실행 조건·후보·GATT 회차를 소유한다. 기준 방식은 `WAITING_FOR_APPEARANCE → 실제 CDM BLE 출현 → GATT 회차 → WAITING_FOR_DEPARTURE → 실제 BLE 이탈 → WAITING_FOR_APPEARANCE`다. 개선 방식은 선택된 CDM·외부 BT·필터 스캔을 한 후보로 병합하고 신호 철회/만료 뒤 재무장한다. HOME·잠금·Activity 재생성은 취소하지 않는다. 기존 관찰 전용·오디오 진단은 시작/대기/정리 전체와 배타적이다.

- 매 회차는 `CONNECTING → DISCOVERING → SUBSCRIBING → OBSERVING → CLEANING_UP`의 단계별·전체 제한을 따른다. 서비스 대기 전체에는 회차의 40초 제한을 적용하지 않는다. RX 알림은 횟수만 세고 payload는 읽거나 저장하지 않는다.
- 기준은 완료·실패 뒤 실제 이탈을 요구하며 중복 출현/BT로 재시도하지 않는다. 개선은 자체 GATT·최근 close BT 신호를 억제하고 CDM 이탈 30초·스캔 증거 180초의 유예/만료를 적용한다. BT 해제는 기존 증거를 철회할 뿐 자기 연결 직후 새 후보를 만들지 않는다.
- 선택된 재시도는 실패 경계에서 후보당 최대 2회·정리 포함 40초다. 이전 close 후 2초와 잔여 12초 이상을 요구하고 COMPLETE·BLOCKED·프로필 없음·중지·close 실패는 제외한다. 정리 중 다음 후보는 이전 close·lease 반환·watchdog/association/current 비움 뒤에만 진행한다.
- 감지만 모드는 후보만 집계하고 probe/회차 wake lock을 발급하지 않는다. 기준/개선·개별 옵션은 RAM에서 선택하며 실행 중 고정한다. `autoConnect=true`는 별도 비교 옵션이고 기본 false다. 필터 스캔은 LOW_POWER·40초 창 예약/최소 120초 시작 간격이며 GATT 중 정지·실제 close 후 재개한다. Handler의 절전 지연은 실제 무선 등록 시간을 늘릴 수 있으므로 엄격한 40초 상한으로 해석하지 않는다.
- 기준의 실제 이탈 또는 개선 후보의 최종 철회/만료는 당회차를 정리하고 시험을 유지한다. 앱·알림 중지·Bluetooth OFF·권한/등록/로그 조건 상실은 새 트리거를 봉인한 뒤 정리·서비스 종료로 전환한다. 진행 중 enable 쓰기 뒤 disable을 직렬화한다.
- write callback은 요청 ID가 없어 중복 enable과 disable을 구별할 수 없다. 원격 구독 해제는 별도 CCCD 읽기가 성공하고 값이 정확한 두 바이트 `[0, 0]`일 때만 확인한다.
- 구독 확인·원격 해제·disconnect callback·로컬 close 성공은 별도 증거다. close 성공 때만 예약을 해제하며 close 실패는 `CLEANUP_FAILED`와 예약 유지로 재시작을 요구한다. 늦은 다른 token/GATT callback은 현재 소유자를 변경하지 못한다.
- 최초 수신/처리 지연·거절과 GATT 요청 반환/콜백·첫 RX는 별도 증거다. 원 SDK status 미수신은 null이며 본시험/정리 status를 분리한다. 구독 COMPLETE는 0 RX에서도 가능하며 실차 인증 성공으로 승격하지 않는다.
- 종료 상태는 인증·명령 승인·실차 제어 성공이 아니다. **주말 BLE 반복 시험**에는 키 등록·TX characteristic 쓰기·Fleet API가 없다. 별도 수동 차량 키 경로는 아래에 정의한다.
- 강제 종료 후 자동 복원하지 않는다. 미정상 marker는 최종 회차와 `BLE_FIELD_STOPPED`까지 파일 동기화·pending 0이 확인된 경우에만 정상 종료로 지운다. close/로그 실패는 유지하며 새 실행은 이전 비동기 완료가 새 marker를 지우지 못하게 봉인한다.

### v23 광고명 감지 전이 — 과거/별도 모드

- `VEHICLE_NAME_DETECTION`은 기존 BLE_FIELD 감지 전용 정책을 재사용한다. `명시 시작 → token-bound RAM 이름 한 회 handoff → 정확한 ScanFilter/ScanRecord 이름 일치 → FILTERED_SCAN 후보 병합 → 철회/만료 → 새 후보 대기`이며 GATT 회차·오디오 예약은 만들지 않는다. 현재 홈의 `VEHICLE_SERVICE_UWB`와 별도다.
- 이름 모드의 감지 전용/스캔 옵션을 runtime·service·scanner에서 검사하며 잘못된 조합은 보정 없이 거부한다. 주소 모드는 기존 동작을 유지하고 필터 두 조건을 AND하지 않는다.
- HOME·잠금은 감지 서비스의 중지 사건이 아니다. v24는 공통 저장 VIN을 유지하고 편집 중 화면 입력만 지운다. 사용자/알림 중지·권한/대상/BT 상실·최종 스캐너 실패·서비스 파괴는 새 소비를 봉인하고 pending/타깃 참조를 제거한다. v26의 선별 SDK 오류 대기는 아래 제한 복구 계약을 따른다. 이전 token의 정리가 새 요청을 지우지 않으며 폐기된 callback은 새 후보를 만들 수 없다.
- 후보는 광고 관측 증거이며 현재 근접·인증·P·마이크/제어 허가로 승격하지 않는다. 기존 CDM 일반 접근·자동 음성의 재무장/기한 정책은 유지하고 UWB 세션은 추가하지 않는다.

### v25 OS 전달 기반 필터 스캔

- 창마다 새 무작위 token의 명시적 mutable PendingIntent를 등록하고 비공개 receiver로 5초 배치 결과를 받는다. 현재 RAM session·generation·창 기한을 확인한 뒤 실제 광고명/주소가 일치하는 최신 표본만 기존 정책에 전달한다. 오래된 세대·다른 대상·누락된 이름으로 후보를 만들지 않는다.
- pause/stop은 먼저 소비를 봉인하고 동일 PendingIntent로 stopScan을 요청한다. 정상 반환 후에만 handle 취소·token 비움이 가능하다. 실패는 sticky 정리 불명이며 새 진단을 허용하지 않는다. SDK 등록 반환 코드와 비동기 전달 실패는 성공으로 바꾸지 않는다.
- 프로세스 시작 시 영속 token은 고아 등록 정리에만 쓴다. receiver가 프로세스를 깨워도 시험·FGS·GATT·마이크를 시작하지 않는다. 이전 창 전달이 새 창을 정리하거나 복원하지 못한다.
- 창별 SCAN_STARTED/SCAN_STOPPED를 기록한다. 등록 성공은 실제 광고 수신과 별개이며, UWB·거리·배터리 절감 증거로 사용하지 않는다.

### v26 장시간 감지 예약·복구

- 기존 서비스 readiness 점검에서 `reconcile()`을 호출해 elapsed 기한을 지난 등록을 먼저 정리하고, 다음 시작 기한이 지난 경우에만 재개한다. 별도 wake lock·정확 알람·고빈도 점검은 추가하지 않는다. CPU가 계속 절전 중이면 실행을 보장하지 않으며, readiness 실행 기회에서 오래된 uptime 예약만 기다리는 지연을 줄이는 경로다.
- SDK 스캔 오류 2/3/5만 정상 stop 이후 `SCAN_RECOVERY_SCHEDULED`로 대기한다. 첫 오류 후 최소 120초·둘째 후 최소 240초와 기존 시작 간격 중 늦은 기한을 사용하고, 사용자 시작당 총 2회다. 정상 창·빈 결과·광고 수신으로 예산을 되돌리지 않는다. 초과는 `SCAN_RECOVERY_EXHAUSTED → SCAN_FAILED`로 종료한다.
- 오류 1/4/6·불명 코드·권한/기능 미지원·잘못된 전달·정리 실패는 자동 복구하지 않는다. pause/resume은 기존 기한과 예산을 유지하며 stop·세대 변경·동기 로그 콜백 재진입은 새 등록을 봉인한다. 무광고를 먹통으로 판정하거나 복구 트리거로 사용하지 않는다.
- `SCAN_TIMER_DELAYED`는 1초 이상 예약 지연이며 스캐너 먹통의 증거가 아니다. v8 `SCAN_MATCH`의 interactive/deviceLocked는 앱 처리 시점의 관측값이다. 수신 시각·전달 지연과 별개이고 GATT·UWB·차량 권한을 나타내지 않는다.

### v27 AltBeacon 입력과 감지만 복원

- 위 v25/v26 창 예약·선별 스캔 복구는 과거 버전 계약이다. 현재는 `FGS 요청 → 실제 BeaconService foreground 확인 + native 등록 반환 → 결과 소비`이며 AltBeacon 기본 스캔 기간·foreground/background 대기 0ms를 사용한다. 요청 수락만으로 감지 정상 상태를 만들지 않는다.
- callback은 native 등록 당시 세대에 귀속한다. 실제 `timestampNanos`를 freshness 기준으로 사용하고 오래된·역순·중복·이전 세대 전달은 `lastSeen`을 갱신하지 않는다. 유효 표본은 앱의 과거 40초 창 종료 여부로 버리지 않는다. 반복 ranging cache를 새로운 광고로 소비하지 않는다.
- `pause/stop 요청 → 소비 봉인 → native stop 결과 확인 → GATT 시작/최종 정리`다. 실패하면 소유권을 유지하고 신규 작업을 차단한다. 차량 광고 없음·스캔 중단·권한/BT 실패·FGS fallback을 구분한다. 대기 0ms는 OS/OEM 수신 보장이 아니다.
- 로그 실패는 진단 상태와 미정상 marker에 남기되 감지 정지 전이를 일으키지 않는다. `사용자 ON`과 `실행 중`은 별도 상태이며 OFF는 실행이 이미 종료됐어도 영속화한다.
- v27 당시 복원은 명시 ON인 광고명 감지만 새 token·빈 후보로 시작했다. VIN 복원만으로 키/인증/GATT/마이크/명령을 실행하지 않으며 OS 거절과 사용자 종료를 존중하는 경계는 유지한다. 현재 v55 저장 ON은 위 제품 서비스 계약으로 복원하되 이전 후보·조회·UWB·명령을 재생하지 않는다.

### v28 접근 인증·읽기 전이 — 별도 모드의 이력

- `신선한 정확한 광고 → 새 후보/회차 → PAUSING_SCAN → native stop 확인 → 기존 키만 로드 → CONNECTING/DISCOVERING/MTU/SUBSCRIBING → BODY fresh 인증·읽기 → 새 DRIVE fresh 인증·읽기 → CLEANING_UP → 실제 자원 해제·lease 반환 → 감지 재개`다. 총기한은 40초이며 pause 지연이 기한을 새로 발급하지 않는다.
- CDM/BT·중복/역순/이전 세대 광고·재개 callback·타이머는 추가 읽기의 근거가 아니다. 동일 후보에서는 성공·실패 후 재조회하지 않고 최종 철회/만료 후 새 광고만 다음 회차를 만든다.
- BODY만 검증된 결과는 PARTIAL이다. COMPLETE는 양쪽 검증·정리·예약 반환까지 확인한 경우이며 누락 기어·원 시각·지난 P는 제어 허가가 아니다. 실패한 close는 CLEANUP_FAILED와 소유권 유지·재입장 봉인이다.
- 정확한 서비스 소유 목적만 HOME/잠금에서도 계속된다. 수동 등록/조회 게이트는 유지한다. OFF·owner/권한/BT 상실·기한은 신규 TX를 봉인하고 정리로 이동한다. 프로세스 복원은 이전 조회가 아니라 감지만 수행한다.



### v24 공통 VIN 상태와 작업 대상

- 프로세스 시작은 `LOADING → NOT_REGISTERED / READY / FAILED`, 명시 저장은 `SAVING → READY / FAILED`다. 저장 IO와 복원은 작업을 시작하지 않으며 READY 외에는 키/인증/P/BLE 시작 스냅샷을 발급하지 않는다.
- VIN 변경 입장은 Main에서 직렬화한다. 화면 표시·잠금 해제·진단/관찰/서비스/오디오/UWB/키 예약 없음·chooser 요청 없음·정리 불명 없음이 필요하다. SAVING 중 새 예약과 Activity 지원 조회도 차단한다.
- 작업은 READY VIN을 시작 시 확정하며 실행/정리 동안 변경을 거부한다. 편집 입력 이탈은 저장 VIN을 삭제하지 않으며 실제 작업 취소·GATT 정리·서비스 감지 유지의 기존 수명은 바꾸지 않는다.
- 암호문·키·형식·IO 검증 실패는 정적 코드와 FAILED로 봉인한다. 평문/이전 파일 자동 fallback·자동 키 교체·차량 작업 재개로 성공을 만들지 않는다. 저장 commit 실패에는 암호문 원자복구를 시도하지만 실패 상태를 숨기지 않는다.
- 스캔 stop·지원 조회 destroy·presence 중지·wake lock 해제 실패는 Application runtime에 정리 불명으로 유지한다. VIN 변경뿐 아니라 새 BLE·차량 키·음성·마이크·UWB·접근 진단의 입장을 막으며 Activity 이탈·재생성으로 해제하지 않는다. 스캔 stop 예외 뒤에는 기존 scanner/callback 참조와 실패 상태를 보존하고 새 스캔으로 덮지 않는다. BLE 정상 종료 marker도 정리 불명에서는 남기지 않는다.





## 수동 차량 키 등록·읽기 전이

`0.16.0-probe`에서 도입한 경로를 `0.18.0-probe`에서도 유지한다. Application 소유의 단일 예약·token으로 `PREPARING_KEY → CONNECTING → DISCOVERING → NEGOTIATING_MTU → SUBSCRIBING`을 실행한다. 등록 요청은 `CARD → HANDSHAKING → READING_STATUS`, 이미 등록한 키의 조회는 `HANDSHAKING → READING_STATUS`로 진행한다. 키카드 대기는 120초, 전체는 180초 이내이며 기존 음성 세션의 40초 정책을 늘리지 않는다.

- 모든 기존 관찰·BLE 반복·오디오 진단과 배타적이다. 화면 이탈·배경 전환·잠금·Bluetooth/권한/대상 조건 상실은 새 전송을 봉인하고 `CLEANING`으로 이동한다. Activity 재생성이나 재연결로 재등록·재조회하지 않는다.
- 시작 전 입장 검사와 진행 중 자기 예약을 UI에서 구분한다. 자기 예약/활성 실행은 외부 진단 충돌 문구를 표시하지 않으며 VIN·동의·모든 시작 버튼은 비활성화한다. 활성 실행에는 단계·취소를 제공한다. 정리 실패가 표시 우선순위이며 실제 외부 예약과 runtime 입장·전송 검사는 그대로 유지한다.
- 새 세션의 HMAC·요청 대응·AES-GCM 검증과 인증된 `VehicleStatus` 응답 수신이 성공했을 때만 `VERIFIED_STATUS`다. 개별 잠금·프렁크 필드 누락/미지원은 UNKNOWN이며 응답 인증 성공과 구분한다. 기존 BODY 조회의 등록 보고는 인증 전 관측이다. v22의 별도 DRIVE 조회는 domain3 fresh handshake와 정확한 UUID·route·request hash·AEAD를 검증한 `GetDriveState`만 수용하고 `TESLA_KEY_DRIVE_STATE_VERIFIED`를 기록한다. P·R·N·D/UNKNOWN 및 원본/실제 수신 시각은 지난 관측이며 현재 P·감지·제어 허가로 승격하지 않는다. 등록과 DRIVE 조회를 결합하지 않으며 실패 때 BODY fallback·wake·재전송은 없다.
- 요청 대응·명시적 무오류 `OK` ACK에는 `HANDSHAKING`을 유지한다. ACK 반복은 기존 단계/전체 기한을 늘리거나 상태 요청을 만들지 않는다. `0.21.0-probe`는 서명 누락·태그 길이 오류·HMAC 불일치·해석 불가능한 세션 정보를 가진 응답만 제외하며 같은 요청의 정상 응답을 기존 기한까지 기다린다. 제외 근거를 화면·고정 event에 남기며 키·epoch·counter·기한을 변경하거나 재전송하지 않는다. 검증된 session info만 `READING_STATUS`로 진행한다. 명시적인 fault·지원 불가 status·키 미등록 보고는 기존 종료 판정을 유지하며 인증 전 미등록 보고는 실제 키 부재·등록 철회의 증거가 아니다.
- 등록 전송 뒤 취소·시간 초과는 `UNKNOWN`과 등록 불명을 보존한다. GATT close 성공은 로컬 해제이며 원격 등록 철회·구독 해제 증거가 아니다. close 실패는 `CLEANUP_FAILED`와 예약을 유지하고 다른 진단을 차단한다.
- 늦은 이전 token/GATT 응답·재전송·재사용 counter·기한 경계 `now >= deadline`은 새 상태나 전송을 만들지 않는다. 완료/실패는 `CLEANING` 뒤 봉인하며 키·VIN·응답 버퍼를 로그에 복사하지 않는다.

## v76 연결·v78 로컬 음성 전이표

**역사:** 아래는 당시 전이표다. 현재 최초 연결의 BODY 확인·조건부 단발 깨움은 위 「현재 연결 준비와 음성 진입」이 대체하며 기존 로컬 모델·음성/정리 안전 계약은 유지한다.

| 현재 상태 | 이벤트·조건 | 다음 상태 | 필수 동작 |
| --- | --- | --- | --- |
| 연결 `IDLE` / 음성 `IDLE` | 저장 ON·새 owner/token·빈 후보 | 감지 대기 | 이전 후보·P·음성·명령 복원 금지, 오디오 OFF |
| 감지 대기 | fresh 정확 광고·기존 키·입장 조건·native scan pause 확인 | 연결 `PREPARING → CONNECTING → AUTHENTICATING` | 같은 request/단일 GATT 소유. 광고10초는 최초 SDK 발급까지 검사 |
| 연결 `AUTHENTICATING` | 인증된 fresh 명시 P | 연결 `MONITORING`, 음성 `PREPARING → LISTENING` | 별도 동의·선택/ready 비서·권한·정확 RAM lease 확인 후 microphone FGS 시작 |
| 연결 `MONITORING` | 유효 DRIVE 응답 | `MONITORING` | 응답 후2초에 새 READ_ONLY conversation 조회. 한 outstanding만, RX5초/source10초/future2초 적용 |
| 음성 `LISTENING` | 완전한 bounded 발화 | `RECOGNIZING` | 마이크 실제 해제 후 기존 튜닝 zipformer 인식. 발화 시작부터 모델 로드·최종 판정까지10초, 잘린 발화/부분 결과 불허 |
| 음성 `RECOGNIZING` | 단일 최종 원문의 문법 불일치 | `PREPARING → LISTENING` | native stream/recognizer 정리·PCM 소거·readiness 재확인 후 새 발화. 취소/오류를 이 전이로 재시도하지 않음 |
| 음성 `RECOGNIZING` | 기존 wholephrase·취소 우선 정책 통과 | `COMMAND_READY`, 연결 `EXECUTING` | 실제 mic/native stream/recognizer/FGS 정리 확인 후 handoff5초 내 gate 단발 소진 |
| 연결 `EXECUTING` | monitor 요청 outstanding | `EXECUTING` | 원 응답 drain·검증 후 같은 GATT 새 DRIVE→ACTION→BODY. monitor P 재사용·두 번째 GATT 금지 |
| 연결 `EXECUTING` | 새 명령용 fresh P·SDK 직전 검사 통과 | `EXECUTING` | 명령 인정부터40초 안에 단발 ACTION. SDK 접수 후 결과 불명 재전송 금지 |
| 연결 `EXECUTING` | ACK·후속 BODY 또는 실패/만료 | 종료·정리 | SDK 접수/ACK/OPEN/물리 개방을 분리하고 결과 불명 보존 |
| 모든 활성 단계 | D/R/N/UNKNOWN·stale·disconnect·권한/owner/비서 상실·통화/silenced·음성 취소/실패/기한 | 음성 `STOPPING`, 연결 종료 | 새 음성 결과/TX 즉시 로컬 봉인 후 실제 정리. 기어 감지는 BLE 조회 지연이 있음 |
| 모든 활성 단계 | OFF·동의 철회 | 종료·정리 | false 저장 실패도 RAM 철회, `productVoiceConsentSaveFailed` 유지 |
| 종료·정리 | audio와 BLE 실제 정리 모두 확인 | 음성 `STOPPED`, 다음 새 접근 대기 | 회차 예약 반환·감지 재개. 실제 후보 철회/만료와 새 광고 전 재입장 금지 |
| 종료·정리 | 어느 자원이든 정리 불명 | 실패·재시작 필요 | 예약·참조/필요 FGS 유지, 재입장 차단. 성공/정상 종료로 위장 금지 |

연결 대기에 고정10초/90초 상한은 없다. 유한 초기 연결 이후 fresh P가 계속 필요하며 타이머·일반 트래픽·과거 `connectedObserved`는 P 갱신 근거가 아니다. pause·GATT·정리 시간은 이탈로 세지 않는다.

## 불변식

1. 활성 세션은 최대 하나다. 비동기 이벤트는 세션 ID에 귀속시키고 종료된 세션의 늦은 결과가 새 세션을 전이시키거나 요청을 전송하지 못하게 한다.
2. 발화10초·handoff5초·명령40초는 monotonic으로 판단한다. 연결 대기의 고정 종료와 다르다. 차량 source wall 시각·RX monotonic 신선도는 별도 검증하고 프로세스 복원으로 실행 의도를 재생하지 않는다.
3. 오디오 소유자는 하나다. 제품은 idle capture→완전한 발화→마이크 해제→로컬 PCM 인식 순서이며 native stream/recognizer·버퍼까지 실제 정리한 뒤에만 gate를 넘긴다. 정상 침묵은 입력 차단이나10초 주기 재시작 사유가 아니다. 수동 Android 경로는 writer/FD 정리도 요구한다.
4. 음성 명령을 인정했더라도 **같은 GATT의 새 DRIVE와 SDK 전송 직전**에 fresh 명시 P·기존 앱 키 권한·owner/대상·만료·단발을 검사한다. `null`, 오래된 P, `UNKNOWN`, 속도0·UWB/좌표 거리는 P를 대체하지 못한다.
5. 최종 인식 결과 전체만 실행 후보가 된다. 단어 포함 여부, 부분 결과, 호출어만으로 실행하지 않는다. 음성 확인은 발화자의 신원 인증이 아니다.
6. 취소 이벤트가 전송 시작보다 먼저 처리되면 전송하지 않는다. 검사와 전송 시작의 경쟁은 하나의 직렬 처리 경계로 관리한다. 네트워크 요청 취소가 이미 차량에 전달된 명령을 되돌린다는 보장은 없다.
7. 만료·취소된 명령의 영구 큐는 없다. 결과 불명 요청을 자동 재전송하지 않는다. 로컬 중복 방지는 외부 차량의 exactly-once 실행 보장과 다르다.
8. 인증된 연결·fresh P·동의·비서/권한 조건 밖에서는 제품 마이크/STT를 허용하지 않는다. 봉인과 실제 정리 완료를 구분하고 해제 불명에서는 예약을 유지한다. 감지 서비스 상주 자체는 녹음이 아니다.

## 기존 명령 파서 정책

제품은 `FrunkVoicePolicy`의 **기존** 정책을 재사용한다. v78 제품은 로컬 모델의 단일 최종 원문·confidence `null`을 전달하며 수동 Android 경로의 n-best/점수 계약도 보존한다. 자유 문장 추론·앞 트렁크 동의어·분리 호출어 세션을 추가하지 않는다. 공백·허용 구두점 정규화 뒤 전체가 `헤이테슬라프렁크열어줘`와 일치해야 한다. 어느 후보든 `취소`/`열지마`가 있으면 일치 후보보다 취소를 우선한다.

| 최종 입력 예 | 처리 |
| --- | --- |
| `헤이 테슬라 프렁크 열어줘` | 전체 정확 문법 후보. 다른 안전 게이트까지 통과해야 전송 가능 |
| `헤이 테슬라 앞 트렁크 열어` | 허용 wholephrase 아님, 미전송 |
| `헤이 테슬라` 뒤 별도 결과의 `프렁크 열어줘` | 분리 결과를 이어 승인하지 않음 |
| `취소` / `아니, 열지 마` 또는 n-best 다른 후보에 해당 취소어 | 회차 종료. 전송 후에는 철회 보장 없음 |
| `프렁크 열면 안 돼` / `아까 프렁크 열어달라고 했잖아` / `문 열어줘` | 전체 문법 불일치, 미전송 |
| 정확 후보와 비명령 후보가 함께 있고 취소어 없음 | 첫 정확 후보를 기존 n-best 정책으로 판정. 후보 차이만으로 일괄 거부하지 않음 |
| 실제 양수 유한 점수가 제공됨 | 후보 수와 score 배열 일치 및 채택 후보0.85..1 검사. 미달/부적합은 미전송 |
| 실제 점수 미제공(양수 유한 점수가 없는 null/0.0/−1 등) | 점수 gate만 생략. 정확 문법·취소 우선·동의·P·정리·기한·단발은 유지 |
| 부분 결과·잘린 발화·PCM 미완료·제품 native decode 미완료 / 수동 Android segmented end 미확인 | 명령으로 인정하지 않음 |

제품에서 문법 불일치는 readiness와 실제 정리 확인 후 새 발화 대기다. 취소·모델 누락/인식 실패·기한 만료는 회차를 끝낸다. JNI 실행 중에는 먼저 결과를 봉인하고 실제 반환·두 release 뒤 완료하며 늦은 성공은 명령을 부활시키지 않는다. 별도 확인 발화는 없으며 음성은 화자 인증이 아니다.

## Kotlin/JVM 검증 계획

아래는 현재 제품 회귀가 지켜야 할 계약이며 이 문서 변경에서 실행한 검증 결과가 아니다. 실제 실행 결과는 [기기 검증 정본](Device-Validation.md#현재-소스설치검증-상태)에만 기록한다.

| Given | When | Then |
| --- | --- | --- |
| 새 광고·기존 키 인증·명시 동의 | fresh P 수신 | 정확 owner/lease의 제품 음성만 시작 |
| 연결/P 유지·긴 무음 |10초/90초 경과 | 고정 대기 종료·무음 반복 재시작·파일 저장 없음 |
| 완전한 발화 | 정확 wholephrase/n-best 취소/실제 점수·미제공 | 위 기존 정책과 동일한 판정 |
| 제품 음성 | 비명령/낮은 점수 | 정리·readiness 확인 후 새 발화 대기 |
| 제품 음성 | 취소·실패·절단·기한 만료 | 회차 종료·명령 없음 |
| 인정된 명령 | mic 또는 recognizer/pipe 정리 불명 | dispatch·예약 반환 금지, 재시작 필요 |
| monitor outstanding | 오디오 정리 후 명령 인계 | 원 응답 검증 뒤 같은 GATT의 새 DRIVE P, 단발 ACTION→BODY |
| 활성 회차 | D/R/N/UNKNOWN·stale·disconnect·OFF·동의 철회 | 즉시 로컬 봉인·정리, 이전 명령 재생 없음 |
| false 동의 저장 실패 | 철회 요청 | RAM false·오류 유지·현재 회차 종료 |
| SDK 접수 후 | 취소·timeout·응답 유실 | 결과 불명 보존·재전송 없음 |
| 종료된 lease/프로세스 | late callback·재생 Intent·복원 | 이전 음성/명령 시작 없음 |
| BLE 정리 완료·오디오 미완료 또는 반대 | 종료 callback | 전체 회차 예약 반환·감지 재개 금지 |

단위 테스트는 OS 권한과 실제 마이크 동작을 증명하지 못한다. 오디오 자원 해제·실제 잠금·접근·연속 발화는 [실기기 검증](Device-Validation.md), 게이트웨이 계약은 [Tesla 연동](Tesla-Integration.md)을 함께 따른다.
