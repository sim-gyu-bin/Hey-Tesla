# Tesla 통합 전제

[Wiki 홈](Home.md)

## 문서 상태와 범위

이 문서는 Fleet API 설계 전제와 로컬 BLE 수동 진단 구현을 구분한다. Fleet API/OAuth/서버·실제 프렁크 명령은 아직 구현·실행하지 않았다. `0.18.0-probe`는 BLE 인증의 중간 OK 응답 처리와 고정 실패 근거를 수정한 버전이다. 실제 방문은 전송 시도 뒤 결과 불명이며 등록 성공으로 표현하지 않는다. 아래 `VehicleGateway`는 여전히 로컬 dry-run이며 실차 요청 실패를 dry-run으로 숨기는 fallback은 없다.

`0.14.0-probe`의 BLE 감지 대조 시험은 별도 경로다. CDM 기준과 선택된 BT·주소 필터 스캔·제한 재시도로 근접 후보와 GATT 회차를 분리하며, 서비스는 등록 차량 GATT·Tesla 서비스/TX/RX·RX 구독과 정리만 검사한다. 감지만 모드는 GATT도 하지 않는다. `VehicleGateway`는 여전히 로컬 dry-run이며 연결/구독·첫 RX는 가상 키·권한·차량 인증이 아니다. CCCD 제어 외 TX characteristic 쓰기·차량 명령·Fleet API 호출은 하지 않는다. 실제 검증과 합성 입력은 [기기 검증](Device-Validation.md#0140-probe--ble-감지-대조-시험)에 구분하고 UWB 세션 start·유효 거리·폰키 인증을 아래에서 따로 다룬다.

앱 내부 UI·도메인·파서·상태 머신·게이트웨이·테스트는 Kotlin, Compose, Coroutines/StateFlow, DataStore로 구현한다. 서버 필요성이 확인된 경우에만 Node.js+TypeScript를 사용할 수 있으나, Tesla 명령 서명은 공식 Go `vehicle-command` 서명 프록시를 재사용한다. Node 서버를 먼저 개발하는 방식은 채택하지 않는다. SDK·라이브러리 버전은 구현 시 실제 호환성을 검증한 뒤 고정한다. 외부 음성 SDK는 미선정·미승인 상태다.

## 0.16 도입·0.17 표시·0.18 인증 응답 수정 — 로컬 BLE 키 등록·읽기 진단

- **등록:** 우리 앱 UID의 AndroidKeyStore에서 하드웨어 P256/ECDH 키를 생성하고 같은 alias를 재사용한다. 기존 키 손상·사용 불가 시 삭제/교체하거나 다른 앱의 폰키를 가져오지 않는다. 공개키를 `PRESENT_KEY` legacy add-key-request에 담아 `ROLE_DRIVER` / `KEY_FORM_FACTOR_ANDROID_DEVICE`로 요청하고 실제 차량의 NFC 키 카드 승인은 사용자가 수행한다. 등록 요청은 기존 CDM 대상 한 대에만 전송하며 자동 재등록/재시도는 없다.
- **등록과 인증 분리:** `registrationReported`는 인증 전 legacy 완료 형식의 응답 관측이다. 실제 저장·키 권한·차량 신원의 증거로 승격하지 않는다. 이후 새 VCSEC handshake의 HMAC·challenge·route/domain을 확인하고 personalized AES-GCM으로 `GET_STATUS` 한 회만 전송한다. `FLAG_ENCRYPT_RESPONSE`는 enum 값이 아닌 비트 마스크이며, 수신은 AEAD·request hash·response counter를 검증한다. 이 버전은 암호화되지 않은 상태 응답을 성공으로 취급하지 않는다.
- **중간 인증 응답:** 요청에 대응하는 명시적 무오류 `OK` ACK는 인증 성공이나 오류가 아니라 기존 기한 안의 대기다. 재전송하거나 단계 기한을 연장하지 않는다. 이후 session info의 서명·태그·파라미터·HMAC 검증을 모두 통과해야 상태 요청을 보낸다.
- **실패 근거:** 키 미등록 fault/session status는 차량 응답의 미등록 **보고**로 표시하며 실제 키 부재의 인증된 증거로 취급하지 않는다. 미지원 status·서명/태그 누락·무결성 실패를 구분하고 로그에는 12개 고정 `TESLA_KEY_AUTH_*` 코드만 남긴다. 원시 응답·예외·식별자를 저장하지 않는다.
- **신뢰 경계:** 공식 handshake는 peer 제공 공개키 기반 무결성 검증이며 차량 인증서 기반 신원 인증이 아니다. 사용자의 현장 대상 차량 확인·실제 NFC 승인과 별개로 표시한다. 공식 앱 등록 폰키를 재사용하는 위임 API나 UWB 폰키 구현이라고 주장하지 않는다.
- **수명/안전:** 표시·잠금 해제·Bluetooth ON·연결 권한·단일 CDM 대상과 전역 배타 예약을 시작/전송/250ms watchdog에서 확인한다. 전체 180초, 카드 대기 120초, 나머지 단계별 제한을 적용한다. HOME·화면/탭 이탈·BT/권한/대상 상실은 전송을 봉인하고 로컬 close한다. close 불명은 Application 예약을 유지하며 Activity 재생성으로 풀지 않는다. 전송 후 취소·오류는 등록 철회/미등록을 보장하지 않는다.
- **예약 표시:** 시작 전 입장 검사와 실행 중 자기 예약을 구분한다. 자기 예약 중에는 재진입을 비활성화하되 외부 충돌 문구를 표시하지 않는다. 실제 외부 예약·정리 실패 차단과 runtime 안전 조건은 유지한다.
- **보관/범위:** VIN은 일회성 RAM 입력이며 시작·이탈 때 비운다. 원시 RX·키·주소·VIN·예외 원문은 상태/로그에 쓰지 않는다. 실제 프렁크/잠금/주행·주차 P 조회·UWB·Fleet API·자동 음성 제어는 추가하지 않았다. 주말 BLE 진단의 RX-only 계약은 그대로 유지한다.

근거는 고정한 [공식 vehicle-command protocol](https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/protocol.md), [SendAddKeyRequestWithRole](https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/vehicle/security.go#L320-L366), [session dispatcher](https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/internal/dispatcher/dispatcher.go), [응답 오류 판정](https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/error.go)이다. 공식 protobuf 5개와 Apache-2.0 license를 보존했고 Java outer class 고정 및 수신 scalar presence만 추가했다. 누락된 잠금/프렁크 값을 enum 0으로 추정하지 않는다. host 회귀·실기기 키 보관·실제 방문 결과 불명과 휴대폰 수정 검증은 [기기 기록](Device-Validation.md#0180-probe--인증-ack-처리와-실패-근거-분리)에 구분한다. 지금 재방문·등록 반복을 권하지 않는다.


## MVP 명령 계약

문서상의 Fleet API 요청은 다음 형식이다. 이 문서는 실행 가능한 `curl` 예제를 제공하지 않으며, 승인 전 실차 요청을 유도하지 않는다.

```http
POST /api/1/vehicles/{vin}/command/actuate_trunk
Content-Type: application/json

{"which_trunk":"front"}
```

이 요청은 프렁크 래치 해제 또는 후드 완전 개방을 보장한다고 주장하지 않는다. 대상 차량 매뉴얼의 직접 조회는 현재 403으로 확인하지 못했고 차량 동작도 미확인이다. 따라서 명령 수락, 래치 해제, 후드 완전 개방은 실차에서 각각 구분해 관찰해야 한다.

## 게이트웨이와 실행 안전성

`VehicleGateway`의 기본 구현은 mock/dry-run이다. mock은 다음을 재현할 수 있어야 하나, 어느 시나리오도 실제 차량 성공으로 표현하지 않는다.

|시나리오|mock/dry-run에서 재현할 계약|실차 확인 상태|
|---|---|---|
|주차 상태|명시적으로 신선한 주차 판정이 없으면 거절|미확인|
|상태 신선도|차량 관측 시간, 수신 유효성, 출처, 연결 상태를 함께 검증; 오래된 cache가 방금 전달됐다고 신선한 상태로 처리하지 않음|미확인|
|권한|토큰 범위·차량 권한 부족을 오류로 반환|미확인|
|취소|전송 전에는 취소가 전송을 막음|미확인|
|만료|만료된 의도는 전송하지 않음|미확인|
|네트워크|단절·시간 초과를 실행 성공으로 처리하지 않음|미확인|
|차량 절전|깨우기 정책 없이는 깨우기 호출을 하지 않음|미확인|
|전송 후 `UNKNOWN`|결과 불명을 성공·실패로 추정하거나 재전송하지 않음|미확인|

명령은 `VALIDATING` 단계에서 검증되고, 해당 모드의 사전 승인 조건과 필요한 음성 확인을 만족한 경우에만 `EXECUTING`으로 진행한다. 전송 전 취소는 전송을 막도록 설계하지만, 전송 후 차량 명령을 철회할 수 있다고 약속하지 않는다. 무제한 재시도, 지연 명령 저장, 결과 불명 명령의 재전송은 금지한다. 차량 깨우기 호출은 사용자가 제한·비용 정책을 명시적으로 승인하기 전에는 하지 않는다.

개발 중 수동 진단 버튼은 초기 검증에만 사용할 수 있으며 운영 필수 경로가 아니다. BLE 키 등록·상태 읽기는 0.16의 별도 경로이고, 실제 프렁크 제어·음성 자동화는 아직 연결하지 않았다.

## BLE와 UWB 감지 조사 — 2026-10-06

S23 Ultra Android 16의 `android.hardware.uwb`는 true다. 설치된 Tesla `4.61.0-4607`의 APK를 읽기 전용으로 분석하고 실제 Bluetooth/UWB 시스템 상태를 조회했다. 공식 앱의 키·사설 저장소·차량 설정은 열거나 변경하지 않았다.

- **BLE:** `BLEService`의 UUID 필터 스캔/PendingIntent 경로와 별도의 주소 필터 LOW_POWER 경로가 있다. API 34 이상 `Peripheral.mAutoConnect=true` 분기를 확인했으며 실제 기기의 Tesla LE 연결 요청 15건도 `isDirect=false`였다. PendingIntent 경로의 ScanSettings는 LOW_LATENCY이므로 이를 저전력 스캔이라고 부르지 않는다. 현재 Tesla의 CDM association은 0개이며 우리 앱의 CDM 출현만 기다리는 경로와 다르다.
- **UWB:** APK의 BLE OOB connector, `ble_fira_capabilities`/`ble_fira_response`/`ble_fira_session_stopped`, GMS UWB controller와 ranging session 구성을 확인했다. 추가 공개 코드 추적으로 FiRa 빌더→BLE 전송 큐→`BluetoothGatt.writeCharacteristic` 호출 체인까지 확인했다. UWB 단독 최초 검색이 아니라 BLE를 통한 세션 협상 구조다. 이것은 코드 분석이며 우리 앱의 실제 FiRa TX·차량 인증 수락·유효 거리·최종 잠금 판단의 실측이 아니다.
- **실제 세션:** 시스템에 남은 Tesla UWB 세션 metric 99개는 각각 startCount 1·startFailureCount 0이다. 그러나 rangingCount/validRangingCount는 모두 0이다. 세션 시작은 확인했지만 유효 거리·각도 보고나 잠금 해제 성공은 확인하지 못했다. 제조사 metric 보고 한계와 실제 ranging 실패를 이 로그만으로 구별할 수 없다.
- **주말 누락과 대조:** 우리 앱의 BLE 출현이 없던 10/4 13:44·16:19, 10/5 09:03·14:23에도 대상 LE 연결과 약 2.1~2.4초 뒤 Tesla UWB 세션 시작이 남았다. 시간상 대응이지 우리 앱 누락의 단일 원인이나 UWB 성공을 증명하지 않는다.

### 우리 앱 UWB 연동의 선행 조건

1. 대상 차량의 실제 펌웨어·폰키 UWB 지원과 업그레이드 상태 확인. Model Y 매뉴얼의 UWB 기능 안내는 검색 색인에서 찾았지만 [본문](https://www.tesla.com/ownersmanual/modely/en_us/GUID-3667D28B-5B3B-49CE-A1C1-3D70AC60D9F6.html)은 HTTP 403이어서 단말/차량별 호환을 확정하지 않았다.
2. 우리 앱이 사용할 승인된 차량 인증·BLE OOB 교환 경로, FiRa 세션 ID·peer address·channel/preamble·STS session key 확보. 공식 앱의 폰키/키/세션을 복사하는 방식은 사용하지 않는다. 현재 진단은 TX·키 등록·인증을 하지 않으므로 이 조건은 아직 충족되지 않았다.
3. 우리 앱 UID에서 UWB 권한·backend·background-ranging/report 제약 확인. [Android 공식 UWB 문서](https://developer.android.com/develop/connectivity/uwb)는 안전한 OOB 매개변수 교환이 필요하고 세션 종료 후 재교환할 수 있으며, 백그라운드에서 세션 유지와 ranging report 수신을 구분한다. 공식 앱의 GMS 경로 동작을 우리 앱에 자동 적용하지 않는다.
4. 실제 유효 거리/각도·peer 종료·권한 철회·잠금·취소 및 자원 정리 검증. 세션 start만으로 거리 표시나 접근 성공 판정을 만들지 않는다.

[공식 vehicle-command 프로토콜](https://github.com/teslamotors/vehicle-command/blob/main/pkg/protocol/protocol.md)은 BLE transport와 도메인별 인증 handshake를 설명한다. 함께 확인한 [공개 VCSEC schema](https://github.com/teslamotors/vehicle-command/blob/main/pkg/protocol/protobuf/vcsec.proto)에는 위 APK의 FiRa payload 항목이 없다. 확인한 공개 계약만으로 Tesla FiRa 연동이 완성됐다고 하거나 제3자 연동이 불가능하다고 단정하지 않는다. **0.15는 별도 UWB 라이브러리 없이 SDK 36 framework 지원 조회를 추가하는 범위이며 거리 UI·가짜 ranging 세션·BLE TX·키 등록은 추가하지 않는다.**

### 공개 APK 추가 추적 — OOB와 인증 경계

보존 APK의 버전은 SDK `apkanalyzer manifest version-name`으로 `4.61.0-4607`을 확인했다. 기존 DEX disassembly와 `apkanalyzer dex code --class … --method … <APK>`의 단일 메서드 출력으로 다음을 확인했다. 공개 코드만 읽었고 공식 앱 사설 저장소·실제 키/주소/세션 값은 접근하지 않았다. 난독화 이름은 이 APK 버전에 한정한다.

|경계|직접 확인한 코드|판정 한계|
|---|---|---|
|FiRa payload 생성|`u1.U0/V0/W0 → ag0.n.n/o/u → oc0.e.E/F/G`가 각각 `vd0.n0/r0/z1` payload를 구성. `CommandActionsKt.getDomain`의 해당 getter `j/k/v`는 `DOMAIN_VEHICLE_SECURITY`로 분기|일반 BLE 데이터나 Fleet OAuth만으로 대체되는 계약이 아님|
|요청 준비·큐|`ag0.n.e → mc0.a.c(ag0.j.c) → ag0.j.P → df0.h.h → ag0.j.S`. source public-key lookup 실패·선택 차량 controller 부재·Bluetooth 비활성은 각각 거절. 큐에 session-info 중간 요청이 있을 수 있음|public-key 존재·큐 enqueue는 차량의 인증 수락/명령 성공이 아님|
|세션 정보·envelope|`ag0.j.x`가 `VehicleSessionInfo`를 builder에 전달. routable은 `fe0.k.a → j$f → BLEService.D0 → u1.y`, legacy는 `fe0.p.b → j$h → BLEService.S0 → u1.m → u1.y`|서명 위임 경계는 확인했으나 구체 서명 모드·차량 검증 결과는 미확인|
|실제 BLE 쓰기 진입점|`u1.y → Peripheral.enqueue → checkCommandQueue → writeToCar → BluetoothGatt.writeCharacteristic`. write type은 2/with-response. `u1.y`는 연결과 내부 readiness flag를 확인|readiness flag는 `connectionEstablished` 등에서 설정되므로 암호학적 인증 완료로 세지 않음|
|차량 인증 요청 처리|`u1.I0`에 auth level·token 길이·`h0.i()` 조건, `se0.h.c → ag0.n.i` 응답, `NO_TOKEN` 거절 분기가 있음|코드상 응답 경로 존재와 차량의 실제 수락은 별개|
|UWB 세션 매개변수|`pg0.f.o`가 UWB_RANGING·지원 config를 확인한 뒤 controller scope의 channel/address와 차량 OOB 입력을 ranging parameters에 넣음. 차량이 STS를 보낸 구형 펌웨어 분기는 거절하고 앱이 STS를 반환하는 분기가 있음|실제 차량의 펌웨어·mode·peer/channel 합의와 종료까지 확인해야 함|
|임시 STS 생성|`og0.h$a.d/c`는 `SecureRandom.nextBytes`를 사용. 공개 mode 매핑은 STATIC 8 bytes, PROVISIONED/PROVISIONED_INDIVIDUAL 16 bytes|장기 등록 폰키와 임시 STS는 다른 재료. 키를 생성한다고 안전한 OOB·폰키 인증이 생기지 않으며 우리 앱은 생성하지 않음|

따라서 후속 실제 연동에는 **우리 앱의 승인된 BLE 키/인증 경로, VCSEC FiRa 메시지·세션 상태 계약, 차량 펌웨어에 맞는 OOB 매개변수 교환, 선택 backend의 실제 ranging/정리 검증**이 필요하다. 공개 코드의 호출 순서와 기본 매개변수만 복사하여 이 조건을 건너뛰지 않는다. 차량 인증 수락·fresh한 거리/각도·잠금 상태 변화는 각각 독립 실측 대상이다.

### framework 지원 조회와 실제 거리 측정의 구분

minSdk 36의 [RangingManager](https://developer.android.com/reference/android/ranging/RangingManager) capability callback만 사용한다. 설치 SDK 36의 register/unregister 계약은 `@RequiresNoPermission`이다. 새 `RANGING`/`UWB_RANGING` 권한을 선언하거나 승인하지 않고 현재 권한 상태를 참고 정보로 읽는다. [실제 ranging 문서](https://developer.android.com/develop/connectivity/ranging)의 거리 세션 권한·OOB·background 제약은 이 조회와 별도다.

하드웨어 feature, framework backend 존재, [RangingCapabilities](https://developer.android.com/reference/android/ranging/RangingCapabilities)의 UWB 원 가용 코드, [UwbRangingCapabilities](https://developer.android.com/reference/android/ranging/uwb/UwbRangingCapabilities)의 공개 지원 항목을 분리한다. backend 없음/unknown/null을 하드웨어 미지원·GMS 미지원·false/0으로 치환하지 않는다. 공식 앱의 GMS capability/helper 경로와 같은 backend라고 가정하지 않는다. 조회 callback 정리·timeout·Activity 이탈을 검증하되 실제 session·peer/address/STS를 만들거나 거리 성공으로 표시하지 않는다.
2026-10-06 S23의 우리 앱 UID에서 framework backend와 공개 capability 응답을 실제 확인했다. `RANGING`/`UWB_RANGING`은 미승인이었고 가용 `3/ENABLED`, 최소 간격 100 ms 및 거리/각도/background 지원 보고를 받았다. 별도 공개 SDK callback과 앱 RAM 값을 대조했다. 상세 결과·취소/Activity 재생성·정리 불명 예약 검증의 범위는 [0.15 기기 기록](Device-Validation.md#0150-probe--차량-방문-전-준비)에 둔다.

slot 목록은 SDK getter Javadoc의 microseconds 설명과 `@SlotDuration` annotation/[UwbRangingParams](https://developer.android.com/reference/android/ranging/uwb/UwbRangingParams)의 `DURATION_1_MS=1`, `DURATION_2_MS=2`가 상충한다. 실측 반환 `2,1`을 µs로 표시하지 않고 SDK enum 원시 코드로 유지한다. update-rate도 enum이며 실제 range sample 또는 선택한 세션 설정이 아니다.



## 주차 판정과 텔레메트리

프렁크 명령 전에는 명시적이고 신선한 `P` 주차 상태가 있어야 한다. `null` 또는 오래된 `P`는 허용하지 않는다. 신선도는 서버 수신 시간만이 아니라 차량 관측 시간, 값의 유효성, 출처, 연결 상태를 확인한다. 공식 Fleet Telemetry 프로토콜에서는 기어 신호가 `ShiftStateP` enum 값으로 보고되지만, 해당 신호가 실제 차량에서 수신되는지와 신선도·전달 지연은 별도의 실차 검증 대상이다. Telemetry가 모든 통합에 무조건 필수라고 단정하지 않는다.

주차 판정의 최소 기록 항목은 차량 식별자를 제외한 상태 출처, 차량 관측 시각, 수신 시각, 검증 시각, 연결 상태, 기어 값, 판정 결과, 거절 사유다. 이 기록은 사용자·차량 식별정보 및 토큰을 포함하지 않는다.

## 인증, 권한, 비밀 관리

Tesla Phone Key는 OAuth 또는 앱 가상 키 권한이 아니다. 사용자 third-party token은 사용자 차량을 대행하는 권한이고, partner token은 앱 등록·설정에 쓰는 권한이다. partner token으로 사용자 승인을 대체하지 않는다. 최소 권한을 목적으로 다음 범위를 검토한다.

|범위|목적|확인 상태|
|---|---|---|
|`vehicle_cmds`|승인된 차량 명령|실제 요구 범위·권한 미확인|
|`vehicle_device_data`|주차 상태 등 차량 상태 조회|실제 필요 신호·수신 경로 미확인|
|`offline_access`|사용자 승인 범위 안의 갱신 토큰 수명 관리|갱신 흐름·보관 방식 미확인|

공개 도메인, 공개키 고정 경로 `https://<app-domain>/.well-known/appspecific/com.tesla.3p.public-key.pem`, 파트너 지역 등록, 계정 지원 여부는 연동 전 각각 확인한다. 개인키와 `client_secret`은 APK, Git 저장소, 로그에 절대 넣거나 배포하지 않는다. UI 접촉 없는 운영을 위해서도 APK에 `client_secret`을 둘 수 없다. 공식 Go 서명 프록시의 요구사항을 먼저 검증하고, 필요성이 확정된 뒤에만 최소 Node.js+TypeScript 서비스의 역할·보관 경계를 설계한다.

갱신 실패, 사용자 취소, 권한 철회가 확인되면 실차 기능을 중지하고 다음 초기 설정 절차를 안내한다.

## 비용·한도 전제

2026-09-22에 확인한 Fleet API 문서 기준 가격·한도 정보는 다음과 같다. 이는 실제 계정·지역·계약에 적용된다는 보장이 아니며, 연동 전 콘솔과 계약 조건으로 재확인한다.

|항목|문서상 단가 또는 한도|
|---|---|
|Commands|1,000건당 USD 1|
|Data|500건당 USD 1|
|Wakes|50건당 USD 1|
|Signals|150,000건당 USD 1|
|월 할인|USD 10|
|기본 한도|0|
|과금 응답|HTTP 500 미만 과금, HTTP 500 이상 비과금|

공식 문서상 결제수단 미설정 또는 사용 한도 초과 시 API 애플리케이션이 비활성화될 수 있다. 한도 초과로 제거된 Fleet Telemetry 설정은 한도 복구만으로 자동 복원되지 않으므로 실제 채택 시 복구 절차를 검증한다. 서버·도메인과 유료 SDK 비용은 별도로 미정이다. 월 할인은 무조건 무료를 뜻하지 않으므로 무료를 약속하지 않는다.

## 온보딩과 승인 구분

초기 계정 설정과 개발 단계 실차 시험에는 사용자의 명시적 승인이 필요하다. 이는 운영 중 매 음성 명령마다 화면 승인을 요구한다는 뜻이 아니다. 운영 중 음성 명령은 위험도와 [상태 머신](State-Machine.md)의 정책에 따른 음성 확인을 사용할 수 있으며, 앱 화면 조작을 매 사용 필수 경로로 두지 않는다.

실제 구현 후 온보딩은 다음 순서로 검증한다.

1. 앱 등록을 확인한다.
2. 파트너 지역 등록을 확인한다.
3. OAuth 사용자 승인을 수행한다.
4. 앱 가상 키를 등록한다.
5. 계정·차량·지역의 지원 여부를 검사한다.
6. 실차 명령 없이 읽기 진단을 수행한다.
7. 신선하고 유효한 `P` 상태를 확인한다.
8. 개별 승인된 실차 시험을 수행한다.
9. 실차 시험의 결과를 확인한 뒤에만 음성 경로와 결합한다.

## 요청 결과와 사용자 피드백

전송과 승인을 하나로 합치지 않는다. 아래는 앱이 실제 관측한 증거 수준에 따른 안내 계약이며, 아직 구현되지 않았다.

| 결과 구분 | 필요한 증거와 TTS 예 |
| --- | --- |
| 미전송 | 게이트웨이 전송 전 차단·취소·만료. “명령을 보내지 않았습니다.” |
| 전송됨 | 전송 시도 사실만 확인. 차량 승인 또는 물리 완료로 안내하지 않음 |
| 차량 요청 승인 | 공식 응답으로 확인한 범위가 차량 승인일 때만 사용. “차량에서 명령을 받아들였습니다. 실제 개방은 확인하지 못했습니다.” |
| 관측 완료 | 별도 상태 관측으로 확인한 물리 상태만 안내. 래치 해제 관측을 후드 완전 개방으로 확대하지 않음 |
| 명시적 거절·실패 | 검증 가능한 오류 근거에 맞게 이유 안내. 통신 응답 유실을 명시적 실패로 추정하지 않음 |
| `UNKNOWN` | 전송 후 실제 결과를 확정할 수 없음. “명령 결과를 확인하지 못했습니다. 다시 보내지 않았습니다.” |

단순 HTTP 성공이나 서버 수락만으로 차량 승인·물리 완료를 확정하지 않는다. 상태 조회를 실제로 하지 않는 동안 “확인 중입니다”라고 안내하지 않으며, 물리 완료 미확인 상태에서 “열렸어요”라고 말하지 않는다.

## 실차 전 승인 게이트

실차 요청으로 진행하려면 다음 조건을 순서대로 충족해야 한다.

1. 사용자가 대상 차량과 명령 효과를 확인하고, 개발 단계의 해당 실차 시험에 명시적으로 승인한다.
2. 인증·권한·도메인·공개키·계정 지원 조건을 실제 계정에서 검증한다.
3. 차량이 신선하고 유효성이 확인된 명시적 `P` 상태임을 확인한다.
4. 사용자 의도가 만료·취소·중복되지 않았고 네트워크 정책 및 차량 깨우기 정책에 부합함을 확인한다.
5. 전송 전 승인·안전 검사를 기록하고, 전송 후 결과는 위 증거 수준에 따라 구분한다.

하나라도 충족하지 못하면 해당 모드에서 안전하게 거절한다. 실차 실패를 mock 성공으로 바꾸지 않으며, mock 시험은 처음부터 명시적으로 선택한 모드에서만 수행한다. 실차 호출은 이 문서의 범위 밖이며 아직 미실행이다.

## 공식 근거

- Tesla Fleet API 인증 개요: <https://developer.tesla.com/docs/fleet-api/authentication/overview>
- 공식 `vehicle-command` Go 도구: <https://github.com/teslamotors/vehicle-command>
- Fleet API vehicle commands endpoint: <https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-commands>
- Tesla virtual keys developer guide: <https://developer.tesla.com/docs/fleet-api/virtual-keys/developer-guide>
- Fleet Telemetry 차량 데이터 프로토콜: <https://github.com/teslamotors/fleet-telemetry/blob/main/protos/vehicle_data.proto>
- Tesla 개발자 포털: <https://developer.tesla.com/>
- Fleet API billing and limits: <https://developer.tesla.com/docs/fleet-api/billing-and-limits>

관련 문서: [요구사항](Requirements.md), [네이티브 아키텍처](Native-Architecture.md), [상태 머신](State-Machine.md), [기기 검증 계획](Device-Validation.md).
