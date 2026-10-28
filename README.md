# Hey Tesla

**차량 근처에서만 작동하는 Android 네이티브 음성 리모컨.** 잠긴 휴대폰을 주머니에서 꺼내지 않고, 차량에 접근해 “헤이 테슬라, 프렁크 열어줘”라고 말하는 사용 경험을 목표로 한다.

> 현재 소스·생성 APK 버전은 **`0.66.0`(`versionCode=66`)**이다. 기능별 패키지 이관 뒤 미사용 구형 실행 경로·빈 패키지·불필요 선언을 정리했다. 앱·계측 APK 빌드, JVM582개·독립 상대편97개·Python 도구176개와 실제 소비자 호스트 smoke를 통과했다. **정리 APK는 폰에 설치하지 않았다.** S23에는 기존 같은 버전의 [좌표 기반 나침반·UWB 병행](wiki/Tesla-Integration.md#v66-좌표-기반-나침반uwb-병행) 설치본이 남아 있으며 마지막 확인 상태는 서비스 OFF다. 실제 차량 위치 응답·UWB 거리 성공은 미검증이다. [검증 기록](wiki/Device-Validation.md)을 따른다.
>
> v0.52에서 추가한 sherpa-onnx 한국어 zipformer는 음성 인식 진단의 **그림자 인식기**로 유지한다. 실제 명령 판정은 Android 온디바이스 `SpeechRecognizer` 결과를 사용하며, 그림자 결과는 차량 제어·실차 음성 시험에 쓰지 않는다.
>
> 호출어·명령 문구의 단일 원본은 `SpeechCommandPhrases`다. 표시용 기본값 두 개에서 정규화 문장·sherpa 보정 문구·로그 마스킹의 기본 허용 어휘·진단 안내를 파생한다. 기존 정확 일치·취소·점수·차량 안전 조건은 유지하며, 호출어 설정 UI와 사용자 호출어의 저장·변경 기능은 아직 없다.
>
> 아래는 v0.50 전환 기록이며 그 음성 경로는 현재도 명령 판정 경로로 유지된다.
>
> v0.50 전환 당시 소스는 **`0.50.0-probe`(`versionCode=50`) Android 내장 온디바이스 `SpeechRecognizer`(`ko-KR`) 전환**이다. v47~v49의 로컬 Moonshine STT(sherpa-onnx)는 v0.50에서 모델·AAR·라이선스 자산과 함께 완전히 제거했다. 앱이 직접 캡처한 16 kHz mono PCM을 `AndroidPcmRecognizer`가 `EXTRA_AUDIO_SOURCE` 파이프와 segmented session으로 인식기에 전달한다. 후보(n-best) 중 하나라도 정규화 후 `헤이테슬라프렁크열어줘`와 정확히 일치하면 수락하고, 하나라도 `취소`/`열지마`를 포함하면 차단한다. 인식기가 점수를 주지 않으면(0.0·−1·null·비유한값) "점수 미제공"으로 기록하고 신뢰도 게이트를 생략하며, 실제 점수는 0.85 이상이어야 한다. STT 일치는 차량 제어 허가가 아니며 P·HANDOFF 5초·명령 10초·안전 동의 게이트는 그대로다. **v0.50 실기·실차 결과는 아직 기록되지 않았다.** [v0.50 검증 절차](wiki/Device-Validation.md#0500-probe--android-온디바이스-speechrecognizer-전환)를 따른다.
>
> 프로젝트 전용 [hey-tesla-ui 스킬](.omp/skills/hey-tesla-ui/SKILL.md)을 적용해 차콜 테마, 상태 중심 홈, 설정 행, 입력 선택·결과 상세를 분리한 진단 화면을 구현했다. 설계 근거와 검증 경계는 [UI 개편 기록](wiki/UI-Design.md)에 둔다.

## 목표와 사용 환경

- 사용자 1명, 차량 1대용 개인 앱으로 시작한다.
- 대상 휴대폰은 Samsung Galaxy S23 Ultra, 차량은 Tesla Model Y 주니퍼 AWD/롱레인지다.
- 실제 Android·One UI·차량 펌웨어 버전은 실기기에서 확인한다.
- Tesla 공식 앱의 폰키 등록은 우리 앱의 차량 제어 권한을 의미하지 않는다.
- 첫 실제 차량 명령은 **프렁크 개방 요청 하나**다. 래치 해제와 후드를 끝까지 들어 올리는 기능은 구분하며, 대상 차량의 물리 동작은 확인 전까지 확정하지 않는다.

```text
원거리 대기: 마이크 OFF · STT OFF
  → 실제 차량 접근 감지
  → 제한된 시간 동안 호출어 대기
  → 짧은 명령 인식
  → 필요 시 음성 확인
  → 권한·신선한 차량 상태·만료·중복 검사
  → 명령 전달 또는 안전하게 거부
  → 확인된 결과만 음성 안내하고 오디오 종료
```

성공 기준은 음성 인식 버튼이 있는 것이 아니라, **잠긴 휴대폰을 주머니에 둔 사용자가 실제 차량 밖에서 접근부터 명령 인식까지 한 번도 화면을 만지지 않는 것**이다. 이후 승인된 실제 연동에서 안전 조건을 만족할 때만 명령을 전달한다.

## 변경한 기술 방향

Android 앱은 React Native가 아닌 **Kotlin 네이티브**로 개발한다. 시스템 기능을 얇은 JS 브리지로 감싸는 대신 Android 서비스와 오디오 수명을 직접 관리한다.

| 영역 | 채택 방향 |
| --- | --- |
| 앱 언어·UI | Kotlin, Jetpack Compose |
| 비동기 처리·상태 전달 | Kotlin Coroutines, Flow/StateFlow |
| 명령 해석·상태 머신·차량 게이트웨이 | Kotlin, UI와 분리한 정책 로직 |
| 설정 | DataStore, 토큰·키 저장과 분리 |
| 시스템 연동 | VoiceInteractionService, 적절한 foreground service, Bluetooth/CDM API의 실기기 검증 |
| 음성 | 접근 세션 내 호출어 감지, Android 내장 온디바이스 `SpeechRecognizer`(`ko-KR`, PCM 파이프·segmented session) STT, Android TTS |
| 테스트 | Kotlin/JVM 파서·상태 전이 테스트, Android 실기기 서비스·오디오 검증 |
| 차량 연동 | 앱 자체 AndroidKeyStore P-256 키·키카드 승인·로컬 BLE 우선. 공식 공개 wire contract와 JCA 사용 |
| 선택 인터넷 경로 | 필요성과 승인 확인 후 Fleet API/OAuth 검토. 서버가 필요하면 Node.js + TypeScript 가능, 서명 프록시는 공식 Go `vehicle-command` 재사용 |

개발 도구와 라이브러리는 아래 빌드 조합으로 고정했다. 호출어 엔진과 접근 경로의 최종 채택은 아직 확정하지 않았다. 진단·실차 음성 STT는 v0.50에서 Android 내장 온디바이스 `SpeechRecognizer`로 전환했으며(v47~v49의 Moonshine 경로는 제거) 실제 인식·실차 경로 검증은 별도다. v0.52에서는 사용자 승인에 따라 sherpa-onnx 1.13.8 런타임과 `sherpa-onnx-zipformer-korean-2024-06-24` 모델을 **진단 화면 전용 그림자 인식**에 한해 추가했다(명령 판정·차량 제어 미사용, 네트워크 전송 없음). 그 밖의 새 외부 음성 SDK·모델, 계정·비용 발생 또는 외부 오디오 전송은 사전 동의 없이 추가하지 않는다.

### 기능별 Kotlin 소스구조

단일 Android `app` 모듈을 유지하며, `app/src/main/java/com/heytesla/app/` 아래 Kotlin 파일을 기능별 폴더와 실제 패키지로 정리한다. `app/`은 앱 런타임·설정, `service/`는 Android 서비스·리시버, `vehicle/`은 `detection/`·`ble/`·`protocol/`·`key/`·`uwb/`·`navigation/`·`frunk/`, `speech/`는 음성, `diagnostics/`는 진단, `logging/`은 로그, `ui/`는 Compose 표현을 담는다. 세부 경계는 [네이티브 아키텍처](wiki/Native-Architecture.md#기능별-실제-소스구조)에 둔다.

Manifest에 등록된 Android component는 파일을 용도별 폴더로 옮겨도 기존 `com.heytesla.app.*` FQCN을 유지한다. 기존 CDM 연결·기본 비서 선택·PendingIntent·런처의 component identity를 보존하기 위한 예외이며 중간 래퍼나 호환 alias를 추가하지 않는다. 패키지 이관 뒤에는 현재 제품 시작점이 없는 구형 BLE 비교·관찰·자동 BODY→DRIVE 조회·자동 마이크 실행 가지와 전용 테스트를 제거했다. 현재 서비스 UWB·좌표 안내·수동 키 조회·음성·프렁크와 공통 송수신·안전 회귀·과거 로그 해석은 보존한다. Nordic 빈 패키지·빈 아이콘 폴더·미사용 Preview 직접 의존성도 제거했다. 앱 ID·namespace·버전66·저장키·현재 서비스 정책·권한은 유지하며 사용자 데이터·로컬 모델·벤더 라이선스는 삭제하지 않는다.

## 반드시 지킬 조건

- 초기 설치·권한·계정·설정 단계 외에는 앱 실행, 알림 터치, 잠금 해제, 마이크 버튼, 매번 지문 인증, 매사용 서비스 수동 시작을 요구하지 않는다.
- 차량과 떨어져 있으면 오디오를 수집하지 않는다. 상시 STT, 무제한 고빈도 BLE 스캔, 주기적 차량 조회·깨우기, 상시 wake lock을 사용하지 않는다.
- 앱 런타임에서 GPT·Codex·OpenAI API를 호출하지 않는다. 제한된 한국어 명령을 명시적인 규칙으로 해석한다.
- “프렁크 열지 마”, “아까 열어달라고 했잖아”, 모호한 “문 열어줘”를 실행하지 않는다. 부분 인식 결과로 먼저 명령을 보내지 않는다.
- 기어 정보 `null`·오래된 데이터·속도 0을 안전한 P 상태로 간주하지 않는다. 호출어와 음성 확인은 사용자 인증 수단이 아니다.
- 원시 오디오는 일반 동작에서 저장·전송하지 않는다. 사용자가 직접 시작한 개발용 마이크 시험은 녹음·최종 인식 원문을 백업 제외 전용 영역에 기본 저장하며 외부로 자동 전송하지 않는다. 민감정보와 토큰·키를 코드·일반 로그·Wiki에 기록하지 않는다.
- 기본값은 **mock/dry-run**이다. 계정 연결·결제·차량 키 등록·실제 프렁크 동작은 승인된 단계에서만 진행한다.
- 취소·만료된 명령을 저장해 나중에 실행하지 않는다. 전송 후 결과가 불명확하면 자동 재전송하거나 “열렸어요”라고 안내하지 않는다.

## 개발 순서

1. **기술 타당성 확인:** 공식 문서와 실제 기기 조건을 분리해 기록한다.
2. **Android 검증 앱:** Tesla 실제 제어 없이 `잠금 + 실제 차량 접근 + 무터치 마이크 + 한 문장 인식 + 종료`를 확인한다.
3. **Mock 연동:** `VehicleGateway` 경계에서 안전 검사·확인·취소·중복·만료·오류·TTS를 검증한다.
4. **로컬 BLE Tesla 연동:** 앱 자체 키·키카드 승인 후 실차 인증·암호 상태 조회와 재연결 키 재사용을 확인한다. 주차 기어 조회 다음은 BLE·UWB 차량 감지 개선을 먼저 진행한다. 신선한 P·감지·안전 게이트를 확보한 뒤 별도 승인으로 수동 프렁크를 시험하고, 통과한 경로만 음성과 결합한다.
5. **배터리·안정성:** 장시간 대기, 접근·이탈, 재연결, 통화, 절전, 네트워크 장애, 프로세스 종료·재부팅을 실기기로 측정한다.

**실차 무터치 검증 전 대규모 서버 개발은 보류한다.** 사용자 요청에 따른 홈·설정·진단 UI 개편은 별도 트랙이며 무터치 동작의 합격을 뜻하지 않는다. 네이티브로 작성해도 Android의 백그라운드 마이크 제한은 사라지지 않는다. 알림을 눌러야 하는 대안을 동일한 성공으로 보고하지 않는다.

**차량 키를 API에서 발급받는 전제는 두지 않는다.** OAuth는 선택 Fleet API 호출용, 앱의 P-256 키는 BLE 인증용, UWB 세션 키·파라미터는 정밀 거리 측정용이다. 공식 앱 폰키를 복사하지 않으며 UWB는 BLE 키 등록과 별도 과제다. v21의 실차 인증·암호 상태 수신·새 BLE 연결에서 같은 키 조회는 두 방문으로 통과했다. 프렁크 제어와 UWB 거리 측정은 미확인이다. 개발 기준과 공식 근거는 [요구사항](wiki/Requirements.md#차량-연동-개발-기준), [Tesla 연동](wiki/Tesla-Integration.md)에 둔다.

## 프로젝트 전용 REA MCP

- `.omp/mcp.json`에 기존 `mobile-mcp`와 함께 REA `6.5.0`을 등록한다. 사용자 전역 MCP·별도 named profile·앱 Gradle 의존성은 변경하지 않는다. 이 프로젝트를 여는 OMP 실행 별칭들이 같은 프로젝트 등록을 읽는다.
- 호스트 전용 런타임은 `~/.local/share/hey-tesla/rea/6.5.0`에 두고 npm 설치 스크립트·선택 의존성은 실행/설치하지 않았다. Android 분석은 JDK21과 별도 `jadx-headless-mcp-0.7.1-all.jar`를 사용하며 공식 고정 SHA-256을 확인했다. 프로젝트 MCP의 Node/JDK/JAR 절대 경로는 다른 개발 환경에서 해당 설치 경로로 맞춰야 한다.
- 이미 열린 OMP에서는 `/mcp reload` 후 `/mcp list`, `/mcp test rea`로 확인한다. 실제 일회 OMP RPC 검증에서 모델 호출 없이 `rea [project]` 발견·207개 도구 연결을 확인했다. 도구 등록은 모든 외부 분석 엔진의 설치·실행 가능성을 뜻하지 않는다.
- Android smoke는 연결된 기기의 Tesla `4.61.6-4647` APK 5개를 읽기 전용 획득하고 base APK의 패키지/Manifest 요약·클래스 검색·메서드 목록·메서드 Java 디컴파일까지 실제 MCP로 확인했다. APK 서명·split 통합·실행 중 BLE 동작은 이 검증에 포함하지 않는다.
- 초기 사용 범위는 APK 정적 분석과 읽기 전용 조회다. REA에는 설치·강제 종료·Frida 같은 변경 도구도 노출되므로 연결됐다는 이유로 사용을 승인한 것으로 취급하지 않는다. 폰키·토큰·사설 저장소 접근과 차량 명령은 하지 않는다. 분석 출력은 사용하는 모델 제공처로 전달될 수 있어 비밀값을 입력하지 않는다.
- 이번 설치에서 승인된 정리 범위는 새 REA 전용 캐시·임시 분석 공간뿐이다. 원 APK·사용자 데이터·기존 현장 로그·차량 키를 삭제하거나 초기화하지 않는다.

## 빌드와 실행

소스와 S23의 기존 설치본은 모두 `com.heytesla.app` / `0.66.0`(`versionCode=66`)이지만, **이번 소스구조 리팩터링은 폰 설치 전**이다. 기존 설치본의 데이터 보존 설치 후 실제 설정 버전·홈 OFF·실행 서비스0을 확인한 기록과 구분하며 사용자 ON/OFF 설정은 바꾸지 않는다. 앱 정보는 BuildConfig 기반 버전 이름·빌드 번호를 표시한다. 홈 ON은 차량 감지·좌표 안내·자동 UWB를 제어하며 `RANGING` 미허용 시 감지는 유지하되 UWB 측정은 차단한다. 기존 VIN·앱 키·등록·녹음·로그를 초기화하지 않는다.

직접 GATT는 지원 OS(minSdk36)에서 `autoConnect=true`로 생성하고, 접근 읽기와 서비스 UWB의 초기133 중 전송·연결 진전 전인 경우에만2초 뒤 같은 handle의 `connect()`를1회 호출한다. 최종 중지/기한 종료에서 disconnect+close하며 기존 연결/탐색/MTU/CCCD/암호 검증 경계를 유지한다. 세대 가드는 재접속 전 이미 진입·게시된 콜백을 거절하지만 Android가 제공하지 않는 연결 요청ID를 만들어 가정하지 않는다. SDK Boolean 접수는 연결·차량 인증 성공이 아니다.

v41은 SDK 201(BUSY)로 **미접수된** 쓰기만 같은 조각으로100ms 뒤 최대2회 추가 발급한다. 원 쓰기5초·전체40초 기한을 유지하며 SDK0 뒤 callback 오류/타임아웃은 같은 쓰기를 재전송하지 않는다.100ms/2회는 Tesla APK의 확정 정책이 아닌 우리 제한값이다. 검증된 BODY 응답의 절전 원 코드를 기록하지만 Infotainment가 깨어 있음·현재 P·제어 허가로 승격하지 않는다. 자동 차량 깨우기는 추가하지 않았다.

v0.50의 실차 경로는 개발자 진단→음성에서 별도 안전 동의 후 직접 시작한다. 앱이 16 kHz·mono·PCM16을 최대 10초(320,000바이트) 캡처하고, 같은 PCM을 `AndroidPcmRecognizer`가 온디바이스 `SpeechRecognizer`(`ko-KR`)에 `EXTRA_AUDIO_SOURCE` 파이프(non-blocking 쓰기)로 전달한다. segmented session(`EXTRA_SEGMENTED_SESSION`)의 `onSegmentResults`를 결합하며, 비segmented 파이프는 S23에서 결과가 null이었다. 파일·무음 대조도 같은 경로를 쓴다. `FrunkVoicePolicy`는 후보 중 하나라도 정규화 후 `헤이테슬라프렁크열어줘`와 정확히 일치하면 수락하고, 하나라도 `취소`/`열지마`를 포함하면 차단한다. 발음 변형 문자열(페이/해이 테슬라, 브렁크/프랑크 등)은 수락하지 않는다. 점수 0.0·−1·null·비유한값은 "점수 미제공"으로 기록하고 신뢰도 게이트를 생략하며, 실제 점수가 오면 0.85 이상이어야 한다. 인식기 실패는 부분 세그먼트가 있어도 명령 없음이다. STT 일치는 차량 제어 허가가 아니며 P·HANDOFF 5초·명령 10초·안전 동의 게이트를 모두 통과해야 전송 단계로 간다. 인식 타임아웃은 운영 15초다. v0.50의 실기·실차 프렁크 결과는 아직 기록되지 않았다.

AltBeacon2.21.2·FGS·스캔 공백0과 별도 읽기 진단의 제한 복구는 유지한다. 제품 UWB는 한 접근당 한 회차이며 반복 광고·자체 GATT·스캔 재개만으로 재시도하지 않는다. 저장 ON 복원은 새 token·빈 후보·새 광고부터 시작하고 이전 조회/명령/음성 동의/UWB 세션은 재생하지 않는다. v0.54의 수동 RAW UWB에 이어 v0.55 제품 서비스도 사용자 승인 `RANGING` 권한을 요구한다. 새 키 등록·자동 깨우기·클라우드·대기 중 wake lock은 추가하지 않았다. Android16/API36 이상, AGP9.3.1·Gradle9.5.0·Compose compiler2.4.10·BOM2026.06.01·protobuf0.10.0/protoc4.36.2를 사용하며 Gradle/Lint는 명령별 JDK21을 지정한다.

1. JDK 21과 Android SDK Platform 36 / Build Tools 36.0.0을 준비한다. 이 저장소의 실제 빌드는 Android Studio bundled JDK 21.0.10으로 수행했다.
2. `JAVA_HOME`을 JDK에 맞추고, Git에서 제외된 `local.properties`에 자신의 `sdk.dir`을 지정한다. `ANDROID_HOME`과 `ANDROID_SDK_ROOT`를 함께 설정했다면 동일한 SDK를 가리키게 하거나 빌드 명령의 환경에서 일치시킨다. 전역 환경을 자동 변경하지 않는다.
3. 새 클론은 아래 모델 준비 안내를 먼저 따른다. 로컬 모델·AAR이 준비되면 다음 명령으로 APK·단위 테스트·Lint를 실행한다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

v0.50~v0.51은 앱에 STT 모델·native AAR을 패키지하지 않았다. 명령 판정 인식은 지금도 기기에 설치된 Android 온디바이스 음성 인식 서비스(S23은 Google 인식 서비스)와 그 `ko-KR` 언어 팩에 의존한다.

v0.52부터는 진단 그림자 인식용으로 sherpa-onnx 1.13.8 AAR(`.local-models/sherpa-onnx-1.13.8.aar`)과 zipformer asset(`.local-models/sherpa-assets/zipformer-ko/`)을 패키지한다. `.local-models/`는 Git에서 제외되므로 **새 클론에서는 빌드 전에 `python3 tools/prepare_sherpa_ko.py --download`를 실행**해 AAR·모델을 내려받고 sha256을 맞춘 뒤 `bpe.vocab`를 만든다. 크기는 모델 asset 약 76.5 MB(onnx 무압축), arm64-v8a native 약 32 MB이며 APK에는 arm64-v8a만 넣는다. 미사용 `.local-models/assets/moonshine-ko/`는 보존하지만 빌드에 쓰지 않는다.

라이선스: sherpa-onnx 런타임 Apache-2.0, AAR에 포함된 ONNX Runtime MIT, zipformer 모델 Apache-2.0(원 모델 카드 표기). 학습 데이터 KsponSpeech(AI Hub)의 이용 조건은 별도일 수 있다. 고지문은 `app/src/main/proto/licenses/`의 `sherpa-onnx-LICENSE`, `onnxruntime-LICENSE`, `sherpa-onnx-zipformer-korean-2024-06-24-NOTICE`로 APK에 동봉한다.

호스트 로그 분석기 회귀는 `python3 -B -m unittest discover -s tools -p 'test_*.py'`로 실행한다. 단말 계측 APK는 `./gradlew :app:assembleDebugAndroidTest --no-daemon`으로 빌드한다. 계측은 시험별 데이터 격리·권한·기기 조건을 확인한 뒤 실행하며, 프로세스 재시작 전용 시험은 일반 전체 실행과 별도로 수행한다. 컴파일·호스트 회귀는 실차·잠금·오디오 동작 검증을 대신하지 않는다.

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. 승인된 USB Android 기기가 한 대인 환경에서는 다음과 같이 설치·실행할 수 있다. `-g` 또는 `pm grant`로 권한을 대신 승인하지 않는다.

```sh
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -n com.heytesla.app/.MainActivity
```
**설정 → 개발자 진단 → UWB**에서 **UWB 지원 확인**과 **거리 진단 시작**을 구분한다. 지원 확인은 기존 capability 조회만 하며 RF 거리 세션·BLE TX를 시작하지 않는다. 거리 진단은 서비스 OFF·저장 VIN·시스템 차량 등록·기존 앱 키·사용자가 허용한 `RANGING` 권한을 요구한다. 실제 backend 지원을 확인하고 BLE 세션 HMAC 검증→암호화 FiRa 응답→RAW UWB 세션을 진행하며, peer별 실제 거리(m)·confidence·유효/무효 횟수와 종료 사유를 표시한다. `onOpened`/`onStarted`는 거리 수신 성공이 아니고 종료 후 값은 과거 결과다. 거리·원시 framework 시각·callback 수신 시각은 비민감 순환 로그와 분석기에 보존하며 VIN·주소·STS는 기록하지 않는다. capabilities 비트 의미·차량 수락·실거리는 아직 미확인이다. [구현 계약과 한계](wiki/Tesla-Integration.md#v54-수동-tesla-uwb-거리-진단)를 따른다.

**설정 → 개발자 진단 → 차량 키**는 제품 자동 제어와 별도의 수동 진단이다. v21의 실차 앱 키 인증·암호 상태 조회·같은 키의 새 BLE 연결 조회는 통과했으므로 키 등록을 반복하지 않는다. 새 대상의 초기 등록에만 직접 동의한 **키 추가 요청 · 카드 승인 후 상태 1회 조회**와 실제 키카드 승인이 필요하다. 앱은 Driver 역할의 Android 키만 추가하며 기존 폰키를 제거·교체하지 않는다. **우리 앱 키로 인증·상태 1회 조회**와 **주차 기어 상태 1회 조회**는 등록 요청과 별개이며 개발자 진단 상단의 저장 VIN을 사용한다.

키 생성과 차량 등록, 인증 전 등록 보고와 검증된 암호 상태 수신은 별개다. 중간 인증 `OK` 응답은 기존 기한 안의 대기이며 서명·HMAC 검증을 생략하지 않는다. 키 미등록 응답도 실제 미등록의 인증된 증거가 아닌 보고로 표시한다. 진행 중에는 단계·취소를 표시하고 공통 VIN 변경·동의·모든 시작 버튼을 잠근다. 자기 예약을 다른 진단 충돌로 표시하지 않지만 실제 외부 예약과 정리 실패 차단은 유지한다. 잠금·프렁크 값과 기어는 지난 응답이며 현재 P·제어 준비를 추정하지 않는다. VIN·주소·키·원시 응답은 로그에 쓰지 않는다. 저장 VIN은 유지하되 편집 입력은 제출·화면 이탈 때 비운다. 전송 뒤 취소/시간 초과와 로컬 연결 종료는 차량 등록 철회를 보장하지 않는다. [현재 실측과 한계](wiki/Device-Validation.md)



차량 감지는 **홈 → 서비스 사용 ON/OFF**에서 제어한다. 저장 VIN으로 광고명을 계산하는 감지 전용 모드이며 비교 프리셋·BLE 시험·접근 관찰 조작은 개발자 진단에서 제거했다. 켜짐 설정과 실제 감시 실행은 별도 표시하고, Bluetooth·스캔·위치·알림 등의 준비가 부족하면 이유를 표시한다. 사용자가 OFF로 끄면 저장 설정과 실제 감지를 함께 종료하며, 실행 실패로 서비스 owner가 없어도 OFF를 저장한다.

기존 v28 접근 인증·상태 조회 구현은 내부에 남지만, 홈 감지 서비스와 부팅 복원에서는 실행하지 않는다. 수동 차량 키·상태 읽기는 **설정 → 개발자 진단 → 차량 키**에 유지한다. 부팅 완료·패키지 교체의 기존 `BleDetectionRecoveryReceiver`는 저장 ON일 때 새 감지 세션만 요청하며, VIN 복원이 끝나기 전의 OFF도 존중한다. 사용자 강제 종료·권한 상실·OS 시작 거절을 우회하거나 이전 후보·음성·차량 명령을 복원하지 않는다.

작업 순서는 [AGENTS.md](AGENTS.md)의 **구현·검증·데이터 보존 설치 → 사용자 실차 테스트 → USB 로그 분석 → 다음 단계 판단**을 따른다. USB 없이도 내부 로그는 기록된다. 현재 조회 ON 여부와 미검증은 [기기 기록](wiki/Device-Validation.md)을 확인한다.

AltBeacon이 등록 차량 BLE 감시용 **connectedDevice FGS**와 알림을 소유한다. v66 좌표 안내는 같은 `BeaconService`의 **connectedDevice|location 실제 type ACK** 뒤에만 준비하며 새 접근에서만 휴대전화 위치·센서를 수집한다. `ObservationService`는 bound-only이며 중복 FGS 알림을 만들지 않는다. 선택 백그라운드 위치 권한은 사용자가 Android 설정에서 직접 허용한다. 미허용 재부팅 복원은 BLE/UWB를 유지하되 좌표 안내만 앱에서 명시적으로 재개한다. [수집 수명·권한 계약](wiki/Tesla-Integration.md#v66-좌표-기반-나침반uwb-병행)을 따른다.

AltBeacon 스캔 기간은 기본 foreground 1,100ms·background 30,000ms를 유지하고, 두 모드의 betweenScanPeriod를 명시적으로 0ms로 설정한다. 이전 기본 background 5분 대기는 제거했다. 기본 무선 모드와 콜백 주기는 별개이며 OS/OEM 지연·무선 duty cycle까지 없어진다는 보장은 아니다. `longScanForcingEnabled` 기본값은 유지한다. stock `NonBeaconLeScanCallback`은 원 수신 timestamp와 정확한 이름 필터 주입을 제공하지 않으므로 고정 소스의 좁은 어댑터를 사용한다. 가짜 BeaconParser·주소 고정·서비스 UUID 광고 포함을 가정하지 않는다. 상세 변경 범위는 [아키텍처](wiki/Native-Architecture.md)를 따른다.

기존 `events.jsonl`은 보존하고 `events-v2.jsonl`과 `events-v2.1.jsonl`~`events-v2.3.jsonl`만 순환한다. 파일당 512KiB·합계 2MiB, 큐 512개이며 쓰기 실패는 표시하되 감지를 중단하지 않는다. VIN·광고명/hash·MAC·키·RX 원문은 기록하지 않는다. `ScanResult.timestampNanos`와 전달 시각·역순/중복/이전 세대 거절·스캐너 중단을 분리한다. 이전 PendingIntent receiver는 고아 등록 정리 전용이다.

명시적 ON/OFF와 실행 상태를 분리한다. 프로세스 초기화·재부팅·업데이트·Bluetooth ON에서 감지만 복원하며 이전 근접 상태·명령은 재생하지 않는다. Android background/while-in-use FGS 제한·사용자 강제 종료 때문에 즉시 복원은 보장하지 않는다. FGS fallback은 정상 실시간 감지로 표시하지 않는다. 대기 wake lock은 추가하지 않았고 기존 짧은 GATT wake lock은 그대로다.

`0.15` APK·110개 회귀 통과, Lint 오류 0·경고 13개다. 이전 `0.14` S23 실측에서는 실제 Compose 조작·감지만 후보 1/GATT 0·자기 BT 억제·실제 GATT 타임아웃 2회 제한·백그라운드 연결 요청 정리·알림 중지·계측 HOME/화면 OFF 15초 유지를 확인했다. 사용자 수동 SCAN 승인 뒤 실제 스캔 40초 종료/120초 재개·GATT 중 정지·최종 중지 뒤 재개 없음도 확인했다. 합성 입력은 실차 방문이 아니며 광고 match·차량 인증·UWB 거리를 증명하지 않는다. [0.14 실측과 한계](wiki/Device-Validation.md#0140-probe--ble-감지-대조-시험), [0.15 지원 조회 실측](wiki/Device-Validation.md#0150-probe--차량-방문-전-준비)
현장 분석기는 Python 표준 라이브러리만 사용하는 `tools/analyze_field_log.py`다. `python3 -B tools/analyze_field_log.py LOG --json`으로 보존 JSONL을 읽기 전용 분석한다. v1~v17·null/SDK0·실제 완료 callback·부분 조회·최종 회차·본시험/정리를 구분한다. v17은 `NATIVE`와 실제 SDK/callback 관측을 사용하고 과거 Nordic 필드는 v14~v16 행에서만 읽는다. 이전 버전의 미지원 값을 false/정상으로 채우거나 library 전달 시각을 native/RF 시각으로 바꾸지 않는다. 합성 실행은 `--exclude-trial UUID`를 반복해 제외한다. `--visits CSV --observer-offset-ms INTEGER`는 실제 관찰자 기록과 측정한 시계 차이만 전달한다. `tools/field_visits.csv`는 헤더만 있는 양식이며 빈 기록·시계 미확인에서는 비율을 만들지 않는다. [대조 조건·시각·제외 목록](wiki/Device-Validation.md#실행-조건과-관찰자-csv)을 먼저 확인한다. 오류0은 로그 완전성·물리 감지 성공의 증거가 아니다.
휴대폰 단독 실측은 일반 앱 FGS의 35분 생존·30분 heartbeat, 별도 65초 `Dozing` 유지, Bluetooth OFF 종료/ON 후 자동 복원 없음, 사용자 잠금 해제 후 실제 `USER_STOP`까지 확인했다. **35분 화면 OFF는 입증되지 않았고**, 비충전 deep Doze·배터리 비용·실차 감지율은 미확인이다. 시험 종료 시 서비스/오디오/GATT wake lock 없음·Bluetooth ON, 1,277행 로그와 이전 1,156행 전체 prefix 보존을 확인했다.



`0.12.0-probe`는 **설정 → 개발자 진단 → BLE 연결**에 등록 차량의 GATT 연결·Tesla 서비스/TX/RX 확인·RX 구독·짧은 관찰·취소/정리를 추가했다. 원격 해제는 CCCD `[0, 0]` 읽기 성공으로만 확인한다. 키·인증·TX characteristic 쓰기·차량 명령·Fleet API는 없다. APK·전체 71개 회귀·Lint(오류 0, 경고 11) 통과. S23 실제 화면에서 등록 주소 해석 오류 수정 후 GATT 시작·연결 타임아웃·취소/HOME·로컬 close와 예약 해제를 확인했으며, 실제 연결/구독 성공은 확인하지 못했다. [BLE 실측과 한계](wiki/Device-Validation.md#0120-probe--수동-ble-연결-진단)

`0.11.0-probe`는 **최종 STT 판정 → 한 번의 로컬 dry-run → 입력 해제 → 한국어 오프라인 음성 안내 → 출력·포커스 해제**를 같은 진단 소유권에 연결했다. 차량 전송 경로는 없고, 안내도 실제 차량 승인·개방으로 표현하지 않는다. 빌드·58개 테스트 통과, Lint 오류 0·경고 11개. S23에서 정답 처리·거절 입력 7종·중복 시작 차단·입력 및 안내 중 취소/HOME·실제 진단 UI의 처리/안내 완료 표시를 확인했다. 모든 14회차 종료 기록에서 예약 잔존 없음과 입력·출력·포커스 해제를 확인했으며 기존 로그는 보존됐다. 제품 전체의 잠금·접근·실차 게이트 합격은 아니다. [통합 실측](wiki/Device-Validation.md#0110-probe--로컬-dry-run과-오프라인-음성-안내)

`0.10.0-probe`는 진단 버튼으로 시작한 온디바이스 STT의 **최종 한 문장**에서 호출어·명령을 판정한다. 문장 시작의 “헤이 테슬라” 뒤 정확한 “프렁크 열어줘”만 후보로 표시하고, 호출어 없음·명령 없음·허용 외·취소/부정을 구분한다. 차량 전송·상시 STT·자동 접근 시작은 없다. 전체 47개 테스트, APK 빌드·Lint(오류 0, 경고 11) 및 컴파일된 판정 5분기·분절 연결·v3 파일 sink의 호스트 실행을 확인했다. S23 데이터 보존 업데이트·로그 보존·합성 문장 후보의 파일 기록·실제 UI 표시를 확인했다. 직접 정답 발화 재시험 2회 모두 문장 일치·명령 후보(4,262ms / 4,099ms), 호출어 없는 발화는 거절됐다. 추가 TTS 8종의 실제 STT는 정답만 후보·나머지 7종 거절이었다. 세부 판정은 6/8 기대 일치이며 부정·호출어 단독 입력의 세부 분류 차이는 미해결이다. 실제 발화 추가 시험은 사용자 요청으로 보류했다. [실측과 한계](wiki/Device-Validation.md#직접-발화와-tts-경계-입력-자동-실측)

`0.9.0-probe`는 STT 종료 요약의 파일 저장을 추가했다. `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 성공, 전체 36개 테스트 통과, Lint 오류 0·경고 11개다. 별도 JVM 두 프로세스에서 실제 파일 sink를 실행해 기존 v1 행 보존·v2 추가·JSON 파싱·회차별 값 분리·신뢰도 `0.0`/`null` 보존을 확인했다. 이후 USB 재연결하여 S23에 데이터 보존 업데이트·정상 실행을 확인했다. 사용자 실제 발화 2회는 직접 마이크에서 모두 시험 문장 일치·최종 결과 수신에 성공했고 상세 결과가 파일에 저장됐다(4,420ms / 4,732ms, 신뢰도 각각 0.0, 예약 잔존 없음). 이는 해당 문장의 2회 인식 성공이며 차량 명령 안전성 합격은 아니다. 근거·한계는 [기기 검증](wiki/Device-Validation.md)에 기록했다.

2026-09-30 `0.8.0-probe`의 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`가 성공했다. 전체 32개 테스트, Lint 오류 0·경고 11개다. S23 데이터 보존 업데이트 후 관찰 FGS·CDM 수락, HOME·짧은 화면 꺼짐 중 유지, 앱·실제 알림 버튼 종료와 로그 코드, 활성 관찰을 강제 종료한 뒤 재실행 OFF·등록 및 기존 로그 보존을 확인했다. 자동 탭이 무시되어 일부 시작·앱 종료는 사용자 조작으로 수행했다. 캡처 보호를 유지했으며 픽셀 검증·실차 접근·장기 생존은 미완료다.

2026-09-29 `0.7.0-probe`의 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`가 성공했다. 전체 26개 테스트(파일 로그 12개 포함), Lint 오류 0·경고 11개다. 컴파일된 파일 sink를 별도 JVM 두 개에서 실행해 재시작 보존·JSON 파싱을 확인했고, 용량 제한·쓰기 실패도 직접 실행했다. USB 재연결 후 S23에 `adb install -r` 업데이트 설치·앱 실행을 완료했다. 홈·설정의 등록 유지와 USB JSONL 읽기, 강제 종료·재실행 후 기존 바이트 보존 및 새 프로세스 이벤트 추가를 확인했다. 이후 실차 시험 후 로그에서 BLE 출현·이탈 각 1회를 확인했다. 진단 상세 화면 전환의 자동화 검증과 사용자가 보고한 카운트 표시 지연 원인 확인은 남아 있다.

이전 `0.1.0-probe`는 수동 마이크의 10초 만료·즉시 종료·화면 이탈 뒤 서비스 해제를, `0.4.0-probe`는 합성 파일 인식·무음 대조·취소·재실행을 S23에서 실측했다. `0.5.0-probe`의 홈·설정 펼침·진단 지원 조회는 확인했으며, 활성 시험의 탭 전환 취소와 재진입 회귀는 아직 확인하지 못했다.

### 퇴근길 접근·이탈 관찰

`0.8.0-probe` 설치 후 **설정 → 개발자 진단 → 접근**에서 시험한다. 차량에서 떨어진 곳에서 **관찰 전용 · 마이크 시작 안 함**을 확인하고 **접근 관찰 시작**을 누른다. 등록 차량 1대·Bluetooth 권한 및 ON·알림 권한과 관찰 알림 허용이 필요하다. 기본 비서 변경이나 마이크 동의는 필요 없다. **관찰 서비스 실행 중**과 **관찰 요청 수락 true**를 구분해서 확인한다. 잠금·주머니 상태로 차량에 접근한 뒤 안전하게 정지한 곳에서 출현·이탈 기록을 확인하며, 운전 중에는 조작하지 않는다.

관찰 요청 수락은 실제 감지 성공이 아니다. 앱의 **접근 관찰 중지** 또는 지속 알림을 펼친 뒤 **관찰 즉시 종료**로 끝낸다. 프로세스 재생성·사용자 강제 중지 뒤 자동 재시작하지 않으며 과거 감지를 현재 감지로 복원하지 않는다. 화면 횟수는 프로세스 누적이고 저장 완료된 비민감 로그는 보존된다. 시험 후 파일 기록 대기·실패를 확인하고 USB로 회수한다. 자동 마이크는 별도 동의이며 오늘 시험에서는 켜지 않는다. 로그 정책과 회수 명령은 [기기 검증](wiki/Device-Validation.md#시험-로그-보관usb-회수)을 따른다.

### 온디바이스 인식 지원 확인과 음성 진단

USB 없이도 코드 작업·빌드·정책 검증을 할 수 있다. 새 APK를 기기에 설치한 뒤 **설정 → 개발자 진단 → 음성**에 들어가면 `createOnDeviceSpeechRecognizer()`의 `checkRecognitionSupport()`로 `ko-KR` 온디바이스 지원 메타데이터를 조회한다. 지원 조회 결과는 준비 상태일 뿐 실제 인식 성공이 아니며, 이 조회는 마이크·인식을 시작하지 않는다. VIN 입력·기본 비서 변경은 필요 없다.

과거 `0.2.0-probe`의 시스템 지원 메타데이터 측정(온디바이스 서비스 있음, `ko-KR` 설치 완료 보고, 약 134 ms·79 ms 응답)은 **당시 버전의 기록**이며 v0.50 인식 성능의 근거가 아니다. 현재 엔진은 Android 내장 온디바이스 `SpeechRecognizer`(`ko-KR`)이며 v47~v49의 Moonshine 경로는 제거됐다.

### 무음 파일 인식과 수동 마이크 진단

**설정 → 개발자 진단 → 음성**에서 사용한다. 접근 진단 OFF·기존 마이크 종료·마이크 권한·화면 표시 상태에서만 시작한다. 입력을 선택한 뒤 **선택한 입력으로 시험 시작**을 누른다. 선택만으로 입력이 시작되지 않는다. 모든 입력은 같은 PCM 파이프로 **Android 온디바이스 `SpeechRecognizer`(`ko-KR`)**에 전달된다.

1. **합성 음성**이 기본 선택이다. 앱에 포함한 “헤이 테슬라 프렁크 열어줘” 합성 WAV의 PCM을 RAM으로 읽어 같은 엔진에 전달한다. 스피커 재생과 앱의 `AudioRecord` 시작이 없다.
2. **무음 대조:** 같은 길이의 PCM을 0으로 채워 전달한다. 명령 없음이 기대 결과다. 무음 대조 파일을 쓸 수 있다는 것은 준비 상태이며 인식 성공·실패의 증거가 아니다. v0.50의 실제 결과는 시험 뒤에만 기록한다.
3. **직접 마이크 / 실차 동의 후 시작:** 실제 발화가 가능한 때만 사용한다. 앱이 16 kHz·mono·PCM16을 최대 10초 캡처하며 같은 PCM을 segmented session 인식기에 파이프로 전달한다. 결과는 세그먼트 결과를 결합한 최종 후보 목록이다. 입력 종료와 취소는 진행 중 진단 상단에 고정된다.
4. **최종 결과 수신·후보(n-best) 일치·호출어/명령 판정·신뢰도**를 표시한다. `0.51.0-probe`부터 인식 후보 원문도 최대 3개까지 점수와 함께 표시하고, 후보가 더 많으면 전체 개수를, 실차 음성 시험에는 판정·차단 사유를 함께 표시한다. 후보 원문은 RAM에만 두고 다음 같은 시험 시작·앱 프로세스 종료 시 지우며 일반 로그·이벤트·새 파일에 쓰지 않는다. 합성·무음 입력에도 표시하고 사용자 취소 회차는 표시하지 않는다. 주변 대화도 보일 수 있으며 표시된 후보는 차량 실행 허가가 아니다. S23 Google 인식 서비스는 `MAX_RESULTS`와 무관하게 2~3개 후보를 주고 confidence는 항상 0.0이었다. 이런 값은 "점수 미제공"으로 기록하고 신뢰도 게이트를 생략하며, 실제 점수는 0.85 이상이어야 한다. 후보 중 하나라도 정확 일치하면 수락, 하나라도 `취소`/`열지마`를 포함하면 차단한다. 인식기 실패는 부분 세그먼트가 있어도 명령 없음이다.

`0.9.0-probe`부터 각 시험 종료 이벤트의 `speechTrial` 객체에 입력 모드·종료 상태/사유·최종 응답 여부·문장 일치·신뢰도·처리 시간·샘플/전송 바이트·정리 후 해당 시험의 오디오 예약 잔존 여부를 저장한다. `0.10.0-probe`의 새 행은 `version: 3`이고 같은 객체에 호출어·명령 판정 `commandDecision`을 이름으로만 더한다. 판정에는 인식 원문이 담기지 않으며 실제 차량 전송과 무관하다. 문장 일치 미판정과 신뢰도 미제공/유효하지 않음은 `null`, 유효한 신뢰도 `0.0`은 그대로 남긴다. USB 없이 시험해도 파일에 기록되며 앱 재시작 뒤에도 USB 회수로 회차별 비교가 가능하다. 과거 결과를 화면에 복원하는 기능은 추가하지 않았다. 시험 후 **이벤트 → 파일 기록 대기 0·실패/유실 없음**을 확인한다. 이전 `0.8.0-probe` 시험의 상세 수치는 소급 복원할 수 없으므로 **새 APK 설치 후** 시험한다. 저장 경로·상한·회수는 [기기 검증](wiki/Device-Validation.md#시험-로그-보관usb-회수)을 따른다.

`0.11.0-probe`의 새 행은 `version: 4`이며 `dryRunResult`, `speechResponseStatus`, `speechResponseReason`, `inputReleased`, `outputReleased`, `audioFocusReleased`, `offlineVoiceSelected`, `speechResponseCleanupFailed`를 더한다. 이전 v1~v3 행은 수정하지 않는다. 로컬 처리 뒤 안내 취소·실패를 처리 미실행으로 덮지 않도록 판정·로컬 결과와 안내 상태를 분리한다. 로그에는 enum·측정값만 담고 음성 안내 문장·인식 원문은 저장하지 않는다.

`0.47.0-probe`~`0.49.0-probe`(당시 Moonshine 경로)의 음성 시험도 같은 `speechTrial`·`commandDecision` 기록 구조를 썼으며 당시 신뢰도는 항상 `null`이었다. v0.50은 같은 구조에 인식기 점수가 0.0·−1·null·비유한값이면 "점수 미제공"으로 기록한다. 일반 로그의 원문 마스킹은 유지하며 마스킹 어휘에 "열어", "줘", "해이", "페이", "브렁크", "프랑크"를 추가했다. `0.49.0-probe`부터 사용자가 직접 시작한 개발용 마이크 시험은 녹음·최종 원문을 백업 제외 전용 저장소에 기본 보관한다. 별도 저장 동의 체크는 없으며 실차 전송 안전 조건은 바꾸지 않는다.

2026-09-22 S23 **`0.4.0-probe`(당시 시스템 음성 인식기)** 실측에서는 16 kHz·mono·PCM16, 35,549샘플·71,098바이트·약 2.22초의 합성 파일을 두 번 주입해 모두 문장 일치, 진단 경과 596 ms·442 ms를 확인했다. 이는 미리 생성한 파일의 진단 시작부터 정리까지이며 실시간 발화 지연이 아니다. 신뢰도는 두 번 모두 `0.0000`이다. 같은 길이의 무음 대조는 164 ms에 `NO_MATCH(7)`, 진행 중 HOME 이탈은 `USER_CANCELED`였고 예약은 해제됐다. AppOps의 기존 녹음 기록은 갱신되지 않았고 최종 실행 서비스도 없었다. 픽셀 레이아웃 검증이 아닌 실제 UI 계층·OS 관찰이며 새 UI 검증으로 소급하지 않는다. **이 기록은 v0.50 PCM 파이프 경로의 인식 성능 근거가 아니다.**

`0.3.0-probe`의 스피커 재생 시험은 당시 시스템 인식기 경로에서 직접·녹음 후 PCM 모두 `NO_MATCH`였다. 과거 시험을 소급 합격 처리하지 않는다. 실제 사용자 발음·주변 소음·마이크·잠금·주머니 조건에서 **v0.50 인식은 아직 미검증**이다.

개발용 마이크 시험은 시작 버튼 근처에 기본 저장을 고지하며, 사용자가 버튼을 눌러야 녹음한다. 화면 진입·복원·복귀만으로 시작하지 않는다. WAV·원문 결과는 앱 내부 `no_backup/speech-recordings/<UUID>/`에 평문으로 저장되므로 개인정보가 포함될 수 있다. 자동 업로드·백업·자동 삭제는 하지 않는다. 일반 로그에는 기존 마스킹만 남긴다. 합성·무음 파일 시험은 자동 저장하지 않으며, 고정 합성 WAV `app/src/main/res/raw/speech_trial_ko.wav`는 비개인 시험 자산이다. 취소·화면 이탈·오류·시간 초과에 캡처·추론 객체·RAM을 정리하고 해제 불명은 새 시험을 막는다. 실차 프렁크 1회 안전 동의는 별도로 유지한다. 런타임 모델 다운로드·클라우드 대체는 없으며 API 키·계정이 필요 없다.

### USB 녹음 회수와 휴대폰 재시험

디버그 앱과 계측 APK가 설치된 USB 기기가 필요하다. 아래 실행예제·도구는 소스 이관 뒤의 `com.heytesla.app.speech.SpeechRecordingReplayDeviceTest`를 대상으로 하므로, 기존 설치본에 실행하기 전에 이관본 앱·계측 APK를 함께 데이터 보존 설치해야 한다. PC에서 음성 모델을 실행하지 않으며, 저장 PCM은 휴대폰의 Android 온디바이스 `SpeechRecognizer`로만 재시험한다(재생 타임아웃 30초). 마이크·BLE·차량 명령은 실행하지 않는다.

> **경고:** `connectedAndroidTest`는 실행 후 앱을 삭제해 저장 녹음을 포함한 앱 데이터를 지우므로 사용하지 않는다. `adb install -r`로 앱 APK와 테스트 APK를 데이터 보존 설치한 뒤 `am instrument`로만 실행한다.

```sh
python3 -B tools/speech_recordings.py --serial DEVICE_SERIAL list
python3 -B tools/speech_recordings.py --serial DEVICE_SERIAL fetch --id RECORDING_UUID --output .local-recordings
python3 -B tools/speech_recordings.py --serial DEVICE_SERIAL replay --id RECORDING_UUID
```

`replay`는 계측 테스트 `com.heytesla.app.speech.SpeechRecordingReplayDeviceTest#replaySavedRecording`(인자 `recordingId`)을 실행해 저장 녹음을 재인식하고 결과를 해당 녹음의 `runs/`에 추가한다. 재시험 뒤 같은 `fetch` 명령으로 새 결과만 추가 회수한다. 기존 다른 파일은 덮어쓰지 않고 원본은 지우지 않는다. 회수물은 Git 제외 영역인 `.local-recordings/`에만 보관한다. 녹음의 생성 시각·ID로 마스킹 순환 로그의 해당 시험 시각과 비교한다.

고정 합성 fixture와 저장된 모든 녹음을 현재 정책에 한 번에 통과시켜 보려면 `replayAll`을 쓴다. 결과는 `HTSTT` 태그 로그로만 남고 저장하지 않는다.

```sh
adb shell am instrument -w -e class com.heytesla.app.speech.SpeechRecordingReplayDeviceTest#replayAll com.heytesla.app.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s HTSTT:I
```

마이크·Bluetooth 상태·알림 권한은 사용자가 직접 승인한다. 기본 비서는 음성 진단에 필요하지 않으며, 선택하면 기존 비서를 대체한다. 기본 비서 등록용 인식 서비스는 일반 `SpeechRecognizer` 요청을 여전히 명시적 비지원 오류로 거절하며, 진단 STT는 `AndroidPcmRecognizer`를 통해 시스템 온디바이스 인식기를 직접 사용한다. 실제 차량의 VIN은 앱에만 입력하며, CDM 선택·기본 비서 변경·실제 접근은 [기기 검증 절차](wiki/Device-Validation.md)에 따라 별도로 수행한다. VIN 보호로 화면 캡처는 차단되며 UI 요소 조회로 진단할 수 있다.

시험 후 기능은 OFF로 두었다. 프로세스 재생성 시 실행도 OFF이므로 현재 앱을 무인 자동 복구까지 완성된 제품으로 해석하지 않는다. 디버그 키는 개발용이며 공개 배포용 서명은 아직 구성하지 않았다.

## Wiki

| 문서 | 내용 |
| --- | --- |
| [Wiki 홈](wiki/Home.md) | 전체 문서 지도와 단계별 진행 조건 |
| [18단계 구현 계획](wiki/Implementation-Plan.md) | 승인된 5단계 18항목, 통과 기준, 의존성 및 승인 경계 |
| [제품 요구사항](wiki/Requirements.md) | 사용자 시나리오, MVP와 비목표, 안전 요구사항 |
| [UI 검토와 프로젝트 디자인 스킬](wiki/UI-Design.md) | 작업 이력 요약, Tesla 공식 화면 분석, 홈·설정·진단 개편안과 로컬 스킬 |
| [네이티브 아키텍처](wiki/Native-Architecture.md) | Kotlin 서비스·오디오·도메인 구조 |
| [상태 머신과 안전](wiki/State-Machine.md) | 전이·취소·만료·결과 불명과 테스트 계약 |
| [기술 타당성과 근거](wiki/Feasibility.md) | 문서 확인 사실·가설·실기기 미확인 사항 |
| [초기 설정 및 실기기 검증](wiki/Device-Validation.md) | 권한, 체크리스트, 배터리와 복구 시험 |
| [Tesla 연동과 비용](wiki/Tesla-Integration.md) | 로컬 BLE 키·등록·인증, 선택 Fleet API/OAuth, UWB와 안전·비용 경계 |

Wiki 원본은 루트의 `wiki/`에서 관리한다. 문서의 커밋·푸시와 별개로 **GitHub Wiki 웹 게시 여부는 따로 관리하며, 아직 게시하지 않았다.**

2026-10-08 [공개 저장소](https://github.com/sim-gyu-bin/Hey-Tesla)를 조회했을 때 기본 브랜치는 README·Wiki만 있는 이전 문서 상태였다. 위 빌드와 기능 설명은 **현재 로컬 소스·단말 설치** 기준이며 공개 APK 배포를 뜻하지 않는다. GitHub Wiki URL도 저장소 홈으로 이동했다. 로컬 빌드에는 공개 릴리스 서명·서버 배포 구성이 없으며, 커밋·푸시·Wiki 게시를 자동 수행하지 않는다.

## 비용과 현재 제약

우선 경로는 Fleet API를 호출하지 않는 로컬 BLE 등록·인증·제어다. API 호출 과금·결제카드·월 무료 크레딧을 이 경로의 전제로 두지 않는다. 선택 Fleet API를 채택하면 사용량 과금·월 할인·결제수단·한도 조건을 별도로 적용하며, 서버·도메인·선택 음성 SDK 비용은 도입 전 승인받는다. 제품 전체의 무조건 무료나 실차 성공을 약속하지 않는다. 공식 출처와 조건은 [Tesla 연동 문서](wiki/Tesla-Integration.md)에 정리한다.

다음 사항은 아직 검증되지 않았다.

- 목표 차량을 탑승 전에 안정적으로 감지하는 신호.
- S23 Ultra의 실제 OS·One UI에서 잠금 상태 오디오 시작과 장시간 복구.
- 주머니·바람·차량 소음 속 한국어 호출어와 연속 명령의 온디바이스 인식.
- 신선한 차량 안전 상태 확보, 실제 프렁크 동작과 완료 판정.
- 배터리 사용량, 반복 접근 성공률, 차량 절전 영향.

Hey Tesla는 Tesla 공식 앱이나 Tesla가 인증한 제품이 아닌 개인 개발 프로젝트다.
