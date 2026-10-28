# 상태 머신

> 이 페이지는 음성 명령·차량 제어를 포함한 제품 전체 상태 머신 계약이다. 현재 v66 소스·생성 APK(`0.66.0`/66)는 BLE 감지·자동 UWB와 별도 좌표 기반 대략적 안내를 병행하며 자동 마이크·프렁크는 미연결이다. 마지막 확인 설치본은`0.65.0`/65·사용자 OFF·실제 서비스 실행0이고 USB 분리 뒤66 설치·기기/실차 검증은 미실행이다. [v66 정본](Tesla-Integration.md#v66-좌표-기반-나침반uwb-병행)의 FGS IPC 최종 보완은 편집 중 계약으로 통과를 주장하지 않는다. 아래 음성 전이표와 버전별 관측은 제품 목표/당시 근거이며 현재 기기·실차 성공과 구분한다. [실측 기록](Device-Validation.md)

[Wiki 홈](Home.md) · [요구사항](Requirements.md) · [네이티브 아키텍처](Native-Architecture.md)

## 상태와 소유권

Kotlin domain 계층이 정책 전이를 정의하고, Android 서비스가 단일 활성 세션·이벤트 처리·오디오 수명을 소유한다. UI 재생성은 세션을 새로 시작하거나 차량 명령을 다시 보내는 사건이 아니다.

| 상태 | 의미 |
| --- | --- |
| `DISABLED` | 사용자가 기능을 끄거나 필수 초기 설정·권한 조건이 충족되지 않음 |
| `WAITING_FOR_VEHICLE` | 저전력 접근 신호만 관찰, 마이크와 STT는 OFF |
| `WAKE_WORD_LISTENING` | 유효 접근 뒤 제한 시간 동안 호출어 감지 |
| `COMMAND_LISTENING` | 같은 세션의 짧은 명령을 수집하고 최종 인식 대기 |
| `CONFIRMING` | 정책상 필요한 음성 확인 수집. 화면·지문 인증으로 대체하지 않음 |
| `VALIDATING` | 전송 직전 권한·상태·근접·만료·중복 조건 검사 |
| `EXECUTING` | 한 번의 요청 전송 시도가 시작되었고 결과를 기다림 |
| `COOLDOWN` | 오디오 종료 후 재활성화 억제, 실제 이탈과 cooldown을 관찰 |
| `ERROR` | 원인과 복구 조건을 기록하고 자원을 정리하는 상태 |

`UNKNOWN`은 세션 상태가 아니라 **명령 결과 불명**이다. 요청 미전송, 명시적 거절, 차량의 요청 승인, 관측된 물리 완료와 구분한다. 취소·만료가 전송 후 발생하면 “실행되지 않았다”는 뜻의 취소 완료로 표시하지 않는다.

플랫폼 검증·mock 모드는 Tesla 계정 없이 시작할 수 있다. 실차 모드에는 별도 초기 인증·가상 키·사용자 승인 조건이 적용된다. 운영 중 음성 확인과 개발자의 실차 시험 승인은 서로 다른 경계다.

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
- OFF·VIN/owner/권한 상실 → 입력/TX·late callback 봉인·raw RAM clear → source/FGS 정리다. 해제 불명은 예약·FGS를 유지하며 새 접근을 차단한다. fieldToken과 leaseId·단조 sequence·terminal ledger·death owner generation·단발 close callback의 최종 IPC 계약은 정본을 따른다.
- background 권한 없는 boot/복원은 BLE/UWB 감지와 좌표 명시 재개를 분리한다. 권한이 있어도 새 owner·빈 좌표로만 복원한다. 권한 결과/팝업 복귀·Activity 재생성은 죽은 서비스·이전 위치 회차를 재시작하는 사건이 아니다.


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




### v44 화면 표시 실차 음성 전이

`RAM 동의 → DIRECT_MIC COMMAND → ACCEPTED → 입력 정상 해제 → 한 번만 DISPATCHED handoff → 같은 차량 작업의 DRIVE P → fresh VCSEC 프렁크 → 후속 BODY 관측 → bounded 정리`다. 별도 확인 발화는 없다. 기존 dry-run 전이는 그대로 차량 미전송이며 자동 접근 음성과도 아직 결합하지 않았다.

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
- 신뢰도 제외는 승인된 로컬 진단에만 해당한다. 아래 실차용 신선한 P·계정/차량 권한·근접·동의·음성 정책·만료 게이트를 생략하지 않는다.

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





### 수동 차량 키 등록·읽기 전이

`0.16.0-probe`에서 도입한 경로를 `0.18.0-probe`에서도 유지한다. Application 소유의 단일 예약·token으로 `PREPARING_KEY → CONNECTING → DISCOVERING → NEGOTIATING_MTU → SUBSCRIBING`을 실행한다. 등록 요청은 `CARD → HANDSHAKING → READING_STATUS`, 이미 등록한 키의 조회는 `HANDSHAKING → READING_STATUS`로 진행한다. 키카드 대기는 120초, 전체는 180초 이내이며 기존 음성 세션의 40초 정책을 늘리지 않는다.

- 모든 기존 관찰·BLE 반복·오디오 진단과 배타적이다. 화면 이탈·배경 전환·잠금·Bluetooth/권한/대상 조건 상실은 새 전송을 봉인하고 `CLEANING`으로 이동한다. Activity 재생성이나 재연결로 재등록·재조회하지 않는다.
- 시작 전 입장 검사와 진행 중 자기 예약을 UI에서 구분한다. 자기 예약/활성 실행은 외부 진단 충돌 문구를 표시하지 않으며 VIN·동의·모든 시작 버튼은 비활성화한다. 활성 실행에는 단계·취소를 제공한다. 정리 실패가 표시 우선순위이며 실제 외부 예약과 runtime 입장·전송 검사는 그대로 유지한다.
- 새 세션의 HMAC·요청 대응·AES-GCM 검증과 인증된 `VehicleStatus` 응답 수신이 성공했을 때만 `VERIFIED_STATUS`다. 개별 잠금·프렁크 필드 누락/미지원은 UNKNOWN이며 응답 인증 성공과 구분한다. 기존 BODY 조회의 등록 보고는 인증 전 관측이다. v22의 별도 DRIVE 조회는 domain3 fresh handshake와 정확한 UUID·route·request hash·AEAD를 검증한 `GetDriveState`만 수용하고 `TESLA_KEY_DRIVE_STATE_VERIFIED`를 기록한다. P·R·N·D/UNKNOWN 및 원본/실제 수신 시각은 지난 관측이며 현재 P·감지·제어 허가로 승격하지 않는다. 등록과 DRIVE 조회를 결합하지 않으며 실패 때 BODY fallback·wake·재전송은 없다.
- 요청 대응·명시적 무오류 `OK` ACK에는 `HANDSHAKING`을 유지한다. ACK 반복은 기존 단계/전체 기한을 늘리거나 상태 요청을 만들지 않는다. `0.21.0-probe`는 서명 누락·태그 길이 오류·HMAC 불일치·해석 불가능한 세션 정보를 가진 응답만 제외하며 같은 요청의 정상 응답을 기존 기한까지 기다린다. 제외 근거를 화면·고정 event에 남기며 키·epoch·counter·기한을 변경하거나 재전송하지 않는다. 검증된 session info만 `READING_STATUS`로 진행한다. 명시적인 fault·지원 불가 status·키 미등록 보고는 기존 종료 판정을 유지하며 인증 전 미등록 보고는 실제 키 부재·등록 철회의 증거가 아니다.
- 등록 전송 뒤 취소·시간 초과는 `UNKNOWN`과 등록 불명을 보존한다. GATT close 성공은 로컬 해제이며 원격 등록 철회·구독 해제 증거가 아니다. close 실패는 `CLEANUP_FAILED`와 예약을 유지하고 다른 진단을 차단한다.
- 늦은 이전 token/GATT 응답·재전송·재사용 counter·기한 경계 `now >= deadline`은 새 상태나 전송을 만들지 않는다. 완료/실패는 `CLEANING` 뒤 봉인하며 키·VIN·응답 버퍼를 로그에 복사하지 않는다.

## 전이표

| 현재 상태 | 이벤트·조건 | 다음 상태 | 필수 동작 |
| --- | --- | --- | --- |
| `DISABLED` | 해당 모드의 초기 설정·필수 권한·사용자 활성화 충족 | `WAITING_FOR_VEHICLE` | 오디오 없이 접근 관찰 준비 |
| `WAITING_FOR_VEHICLE` | 신선한 목표 차량 접근 신호, debounce 통과 | `WAKE_WORD_LISTENING` | 새 세션 ID·monotonic 만료 시각 발급, 단일 오디오 캡처 시작 |
| `WAITING_FOR_VEHICLE` | 불명확·오래된 신호 또는 조건 미충족 | 유지 | 마이크·STT 시작 금지 |
| `WAKE_WORD_LISTENING` | 호출어 감지, 세션 유효 | `COMMAND_LISTENING` | 동일 세션 스트림·버퍼를 지원 경로로 인계 |
| `COMMAND_LISTENING` | 최종 허용 명령, 음성 확인 필요 | `CONFIRMING` | 확인 질문 후 유효한 긍정 응답만 수집 |
| `COMMAND_LISTENING` | 최종 허용 명령, 정책상 추가 확인 불필요 | `VALIDATING` | 음성 확인 생략과 권한·안전 검사 생략을 혼동하지 않음 |
| `COMMAND_LISTENING` | 취소·부정·회상·모호·낮은/미제공 신뢰도 | `COOLDOWN` | 미전송으로 종료 |
| `CONFIRMING` | 허용된 긍정 확인, 세션 유효 | `VALIDATING` | 계정·차량 상태 등을 전송 직전 검사 |
| 전송 전의 활성 상태 | 취소·만료·실제 이탈 | `COOLDOWN` | 전송 차단, 세션 오디오·작업 해제 |
| `VALIDATING` | 필요한 권한·신선한 명시적 P·근접·만료·중복 검사 통과 | `EXECUTING` | 취소 여부와 전송 시작을 직렬화하고 한 번만 전송 |
| `VALIDATING` | 안전 상태·권한 부족 또는 불명 | `COOLDOWN` 또는 `DISABLED` | 이유를 음성 안내하고 미전송 종료 |
| `EXECUTING` | 요청 승인·관측 완료·명시적 실패 | `COOLDOWN` | 실제 확인한 수준의 결과만 안내 |
| `EXECUTING` | 응답 시간 초과·네트워크 단절·취소·이탈로 결과 확인 불가 | `COOLDOWN` | `UNKNOWN`, 철회·미실행을 보장하지 않고 재전송 금지 |
| 활성 상태 | 캡처 오류·정책에 의한 입력 차단·예외 | `ERROR` | 자원 해제. 이미 전송되었다면 미확인 결과는 `UNKNOWN` 보존 |
| 어느 상태 | 사용자 비활성화·필수 권한 철회 | `DISABLED` | 세션 종료, 전송 후 결과가 불명이면 그 사실 유지 |
| `ERROR` | 원인 정리·안내 완료 | `COOLDOWN` 또는 `DISABLED` | 자동 명령 재실행 금지 |
| `COOLDOWN` | 실제 이탈 관측과 cooldown 종료 | `WAITING_FOR_VEHICLE` | 다음 새로운 접근 이벤트를 기다림 |

이탈을 관측했더라도 cooldown이 끝나기 전에는 재활성화하지 않는다. cooldown 중 재연결만으로 음성 창을 연장하거나 새 세션을 만들지 않는다. 오류의 종류에 따라 재설정이 필요할 수 있으며, 알림 터치가 필요한 복구를 정상 무터치 사용으로 보고하지 않는다.

## 불변식

1. 활성 세션은 최대 하나다. 비동기 이벤트는 세션 ID에 귀속시키고 종료된 세션의 늦은 결과가 새 세션을 전이시키거나 요청을 전송하지 못하게 한다.
2. 세션·확인·요청 만료는 monotonic 경과 시간으로 판단한다. 프로세스 재시작 시 기존 실행 의도를 복원하지 않는다. 차량 데이터의 신선도는 이 타이머와 별도로 출처·차량 관측 시각·유효성을 검증한다.
3. 오디오 소유자는 하나다. 호출어→명령처럼 같은 세션 안에서 필요한 스트림은 인계하되, 세션 종료에서는 마이크·STT·버퍼·작업을 반드시 해제한다. TTS가 필요하면 제한된 안내만 끝내고 TTS·포커스도 정리한다. 정상적인 침묵 자체를 권한 차단으로 오인하지 않는다.
4. 음성 확인을 했더라도 **전송 직전** 신선한 명시적 P, 계정·차량 권한, 근접, 만료, 중복을 다시 검사한다. `null`, 오래된 P, `UNKNOWN`, 속도 0은 P를 대체하지 못한다.
5. 최종 인식 결과 전체만 실행 후보가 된다. 단어 포함 여부, 부분 결과, 호출어만으로 실행하지 않는다. 음성 확인은 발화자의 신원 인증이 아니다.
6. 취소 이벤트가 전송 시작보다 먼저 처리되면 전송하지 않는다. 검사와 전송 시작의 경쟁은 하나의 직렬 처리 경계로 관리한다. 네트워크 요청 취소가 이미 차량에 전달된 명령을 되돌린다는 보장은 없다.
7. 만료·취소된 명령의 영구 큐는 없다. 결과 불명 요청을 자동 재전송하지 않는다. 로컬 중복 방지는 외부 차량의 exactly-once 실행 보장과 다르다.
8. `WAITING_FOR_VEHICLE`, `COOLDOWN`, `DISABLED`에서는 마이크·STT가 OFF다. 접근 감시 또는 기본 비서 서비스가 남아 있는 것과 녹음 지속은 구분한다.

## 명령 파서와 확인 정책

파서는 자유 문장 추론 대신 허용 문법을 사용한다. 공백·명시적으로 허용한 문장부호만 정규화하며 부정어·시제·나머지 발화를 지워서 허용 문구를 만들지 않는다.

| 최종 입력 예 | 처리 |
| --- | --- |
| `헤이 테슬라 프렁크 열어줘` | 호출어와 명령을 한 번에 받은 세션에서 프렁크 요청 후보 |
| `헤이 테슬라 앞 트렁크 열어` | 같은 조건의 프렁크 요청 후보 |
| `프렁크 열어줘` / `앞 트렁크 열어` | 현재 세션에서 호출어를 이미 감지한 경우에만 명령 부분으로 허용 |
| `취소` / `아니, 열지 마` | 전송 전 취소, 전송 뒤에는 철회 불보장을 안내 |
| `프렁크 열지 마` / `프렁크 열면 안 돼` | 부정으로 비실행 |
| `아까 프렁크 열어달라고 했잖아` | 회상으로 비실행 |
| `문 열어줘` | MVP 범위 밖·모호한 명령으로 비실행 |
| `프렁크 열어… 아니, 열지 마` | 최종 전체 발화의 취소·부정을 우선, 앞부분으로 실행 금지 |
| 인식 결과가 없거나 후보가 충돌하거나 신뢰도가 낮음·미제공 | 비실행. 고정 임계값을 모든 엔진에 보편적인 값으로 가정하지 않음 |

호출어 대기 밖에서 명령 문구만 들렸다는 이유로 마이크를 켜거나 실행하지 않는다. 실제 허용 표현의 추가는 파서 테스트와 함께 한다.

확인을 요구하는 제품 정책에서는 음성 확인을 현재 명령·세션·만료 시각에 귀속시킨다. 명시적 긍정만 허용하고 침묵·모호한 결과·앱 자신의 TTS를 긍정 응답으로 사용하지 않는다. 명시 승인된 v44 화면 표시 1회 실차 시험은 이 추가 발화를 사용하지 않고 동의·직접 시작 후 인정된 명령을 안전 검사로 전달한다.

## Kotlin/JVM 검증 계획

아래는 제품 전체의 테스트 계약이다. `0.11.0-probe`의 로컬 진단에서는 취소·만료 경계·중복·최종 없음·거절·UNKNOWN 재시도 방지 및 세션 소유권 회귀를 작성·실행했다. 계정·P·실차 전송을 포함한 나머지 계약의 완료는 주장하지 않는다. 실제 차량 호출은 단위 테스트에 포함하지 않는다.

| Given | When | Then |
| --- | --- | --- |
| 유효 호출어 세션과 신선한 P·권한·근접 | 최종 허용 명령, 필요한 긍정 확인 | 전송 직전 검사 후 한 번 전송 |
| 호출어가 없는 세션 | 명령 부분만 입력 | 전송 없음 |
| 명령 수집 중 | 각 부정·회상·범위 밖·혼합 취소 예문 | 전송 없음 |
| 허용 문구의 부분 결과만 있음 | 뒤에 부정이 추가되거나 최종 결과가 오지 않음 | 조기 전송 없음 |
| 최종 인식은 허용 문구 | 신뢰도가 낮거나 미제공, 후보 충돌 | 전송 없음 |
| `CONFIRMING` | 앱의 TTS·모호한 응답·침묵·만료 뒤 긍정 | 전송 없음 |
| 음성 확인을 완료함 | 검사 시 상태가 P에서 D로 변경되거나 P가 오래됨·null | 전송 없음 |
| 검증 결과를 기다림 | 취소가 전송 시작보다 먼저 처리됨 | 요청 전송 없음 |
| 전송 시작 뒤 | 취소·타임아웃·응답 유실 | 결과 불명 시 UNKNOWN, 미실행 보장·재전송 없음 |
| 활성 세션 또는 cooldown | 중복 접근·연결 재시도·반복 명령 | 세션 중복·동일 요청 재전송 없음 |
| 종료된 세션 | 늦은 STT·네트워크 응답 도착 | 새 세션 변경·명령 전송 없음 |
| 세션 종료 조건 각각 | 취소·이탈·권한 철회·예외·만료 | 오디오 종료, 대기 상태에서 STT OFF |
| 프로세스 복구 | 이전에 실행 중이던 의도 기록이 있음 | 차량 명령 자동 복원·전송 없음 |

단위 테스트는 OS 권한과 실제 마이크 동작을 증명하지 못한다. 오디오 자원 해제·실제 잠금·접근·연속 발화는 [실기기 검증](Device-Validation.md), 게이트웨이 계약은 [Tesla 연동](Tesla-Integration.md)을 함께 따른다.
