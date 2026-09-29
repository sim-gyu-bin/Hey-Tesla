# Android 네이티브 아키텍처

> 제품 전체 설계와 현재 구현을 구분한다. S23의 `0.4.0-probe`에서 고정 TTS 파일의 외부 PCM 주입으로 한국어 시험 문장 일치 2회를 확인했다. 같은 길이의 무음 대조는 `NO_MATCH(7)`였고 취소·해제·재실행도 확인했다. 반환 신뢰도는 `0.0`이다. 실제 사용자 발화·음향 조건·네트워크 차단·자동 호출어·차량 접근·잠금·주머니 합격과 구분하며 상세 근거는 [검증 기록](Device-Validation.md)에 남긴다.

[Wiki 홈](Home.md) · [요구사항](Requirements.md) · [상태 머신](State-Machine.md)

## 설계 결정

React Native나 JavaScript 브리지는 사용하지 않는다. UI, 권한, Android 서비스 수명, 오디오 캡처, 상태 전이는 Kotlin으로 소유한다. Compose UI, Coroutines와 StateFlow, DataStore, Android Keystore를 사용한 설계안이다.

초기 구현은 단일 `app` 모듈이며 과도한 멀티 모듈화·DI 프레임워크·서버 선행 구현을 피한다. 다음은 제품 전체의 책임 구분이다. 호출어·명령 파서·VehicleGateway·비밀정보 저장은 아직 구현 범위 밖이므로 표의 모든 영역이 존재한다고 해석하지 않는다.

| 영역 | 책임 |
| --- | --- |
| UI | Compose 화면, 초기 설정, 상태 표시, 명시 승인 입력. 서비스 재생성과 독립적으로 StateFlow를 구독한다. |
| platform | `VoiceInteractionService` 후보, 권한, Bluetooth/접근 신호, foreground service, 오디오 포커스 및 Android 수명 경계. |
| audio | 호출어 탐지, `AudioRecord`, STT 세션, PCM pre-roll, TTS와 자기 재인식 차단. |
| domain | 명령 파서, 안전 정책, 상태 머신, 만료·취소·중복 규칙. |
| gateway | mock/dry-run 기본 구현 및 향후 실제 VehicleGateway 계약. 실제 VehicleGateway 호출 구현은 작성하지 않는다. |
| settings | DataStore 기반 비민감 설정과 Keystore 기반 별도 비밀정보 저장. |

### 현재 검증 앱 구현

| 파일 | 실제 책임 |
| --- | --- |
| `MainActivity.kt` | Activity 소유 probe와 상태 수집, 사용자 권한·설정 진입, VIN을 RAM에서만 처리하는 정확 광고명 CDM chooser. 화면 이탈 시 수동 진단 정리 |
| `ui/AppActions.kt` / `ui/HeyTeslaApp.kt` | UI 효과의 기존 Activity 콜백 연결, 홈·설정·진단 목적지와 뒤로 이동. 진단 목적지를 떠나기 전에 명시적 취소 호출 |
| `ui/HomeScreen.kt` / `ui/SettingsScreen.kt` | 실제 런타임 상태의 짧은 요약·다음 행동, 차량 등록과 권한·기본 비서 설정. 가짜 차량 상태·명령 버튼 없음 |
| `ui/DiagnosticsScreen.kt` | 음성·마이크·접근·이벤트 탭, 합성 파일 기본 선택, 고정 중지 영역, 결과 요약·펼친 기술 상세. 탭 전환 전에도 수동 진단 정리 |
| `ui/AppTheme.kt` / `ui/AppComponents.kt` | 차콜 색상·타이포의 단일 원본과 설정 행·제목·상세 행의 공통 표현 및 접근성 의미 |
| `DiagnosticApp.kt` | 프로세스 내 상태·이벤트, CDM 관찰, 기본 비서·권한 조건, DataStore의 비민감 활성화 선호. 수동 STT 예약과 기존 접근·캡처의 상호 배제 |
| `FieldEventLog.kt` | 허용된 비민감 이벤트의 JSONL 인코딩·단일 IO writer·제한된 큐·2 MiB append 전용 파일. 저장 대기·유실·한도·실패 상태 제공 |
| `AccessServices.kt` | 시스템 BLE presence 콜백과 기본 비서 경로, 음성 비서 진단 안내 |
| `MicrophoneService.kt` | microphone FGS, 16 kHz PCM 입력의 개수·RMS 요약, silenced·만료·종료 처리. 오디오 저장·STT 없음 |
| `SessionPolicy.kt` | 단일 세션, 1.5초 debounce, 자동 90초/수동 10초 정책, 실제 이탈과 30초 cooldown, 세션 ID 경계 |
| `UnsupportedRecognitionService.kt` | Android 비서 등록에 필수인 인식 서비스. 인식·지원 검사에는 명시적 비지원 오류를 반환하고 캡처·모델 다운로드·외부 인식을 시작하지 않음 |
| `SessionPolicyTest.kt` | 정책 경계 8개 회귀 테스트. 감지 기준 초기화 시 대기 세션 폐기·실제 이탈 및 cooldown 유지 포함. 실제 OS·차량 접근 시험의 대체물이 아님 |
| `SpeechSupportProbe.kt` | Activity 소유의 명시적 지원 메타데이터 조회. 온디바이스 API만 사용하고 요청 ID·10초 타임아웃·취소·화면 이탈·destroy를 관리. 녹음·모델 다운로드 없음 |
| `SpeechSupportPolicy.kt` / `SpeechSupportPolicyTest.kt` | 네 지원 분류와 정확한 `ko-KR`을 구분. 대기·다운로드 가능·온라인 보고의 설치 승격과 일반 한국어 태그 오인을 방어하는 테스트 2개 |
| `SpeechRecognitionProbe.kt` | Activity 소유 STT. 직접 마이크·캡처 종료 후 RAM PCM·고정 TTS 파일·무음 대조, 최종/분절 결과의 시험문장 일치·신뢰도 요약, 취소·시간 제한·오디오/FD 해제 및 실패 시 재시작 요구 |
| `SpeechPcmFixture.kt` / `SpeechPcmFixtureTest.kt` | 크기 제한 WAV 디코더와 4개 경계 테스트. PCM16·16 kHz·mono만 허용하며 filler/padding·잘림·초과 데이터·취소 처리 |
| `res/raw/speech_trial_ko.wav` | 설치된 macOS Yuna로 생성한 고정 비개인 시험 문장. 음성 모델·개인 녹음이 아닌 재현용 합성 데이터 |

`0.5.0-probe`에서 UI 책임을 위와 같이 분리했다. probe는 화면 재구성마다 만들지 않고 계속 Activity가 소유한다. `onPause`/`onDestroy` 정리를 보존하며 같은 Activity의 진단 이탈은 `leaveDiagnostics()`로 지원 조회·STT·수동 마이크를 정리한다. 자동 접근 세션의 독립 수명은 바꾸지 않는다. 목적지 복원은 진단 자동 재시작이 아니며 VIN은 저장 가능한 UI 상태로 옮기지 않는다. 설정 상세는 하나만 펼치며 전환·제출 시 VIN 입력을 비운다. 2026-09-29 새 화면의 S23 설치·일부 조작을 확인했으며 전체 회귀와 구분한 근거는 [실측 기록](Device-Validation.md)을 따른다.

`0.6.0-probe`의 접근 진단은 기본적으로 관찰 전용이다. `automaticMicrophoneEnabled`는 프로세스 기본값 false·비영속이며, 관찰과 세션·음성 예약이 모두 꺼진 상태에서만 별도 변경한다. 관찰 전용 출현은 정책 debounce를 만들기 전에 반환하고 `automaticAllowed()`도 별도 동의를 요구한다. 진단 비활성화 시 동의·지연 작업을 해제한다. 활성화 시 이전 현재 감지를 지우되 실제 이탈·cooldown 조건은 보존한다. 출현·이탈 콜백 횟수와 최근 이벤트는 비민감 RAM 상태로만 누적하며 중복 콜백도 포함한다.

`0.7.0-probe`는 별도 시험 파일을 `noBackupFilesDir/field-diagnostics/events.jsonl`에 보관한다. 프로세스·시험 UUID와 시각·고정 이벤트 코드·허용 상태만 기록하며 기존 RAM 표시와 구분한다. 파일은 자동 삭제·회전·덮어쓰지 않고 프로세스 재시작 후 추가 기록한다. USB 디버깅의 `run-as`로 디버그 APK 파일을 회수한다. 별도 상주 서비스·마이크·깨우기·외부 전송은 추가하지 않는다. 프로세스 생존이나 수신하지 못한 콜백의 복원을 보장하지 않는다.

현재 앱은 프로세스 재생성 시 실행 OFF다. 저장된 선호를 표시하되 자동으로 마이크나 접근 관찰을 복원하지 않는다. 이는 검증 앱의 현재 제한이며 최종 무터치 운영 요구를 충족했다는 뜻이 아니다. `0.1.0-probe`에서 수동 10초 만료·즉시 종료·화면 이탈과 미등록 차량 차단을 확인했고, 후속 지원 조회와 STT·PCM 진단은 아래에 별도로 구분한다.

`0.2.0-probe`의 한국어 지원 진단은 `UnsupportedRecognitionService`의 인식 제공 기능과 별개다. 전자는 OS의 온디바이스 서비스에 지원 정보만 묻고, 후자는 우리 앱에 들어온 인식 요청을 계속 비지원 오류로 거절한다. 지원 조회는 기존 오디오 세션의 소유권이나 종료 사유를 바꾸지 않는다. 완료·오류·시간 초과·취소마다 인식 객체를 해제하고, 완료된 메타데이터는 화면 이탈만으로 지우지 않는다. Activity 재생성 시에는 미조회 상태로 시작한다.

VIN 광고명 계산은 [공식 Tesla BLE 코드의 `VehicleLocalName`](https://github.com/teslamotors/vehicle-command/blob/main/pkg/connector/ble/ble.go)을 따른다. CDM은 이름 필터로 사용자의 최초 선택을 받은 뒤 association 기반 presence를 요청한다. 광고 노출·주소 회전·장기 관찰 가능성은 미검증이며, 이 식별자는 차량 인증이나 발화자 인증이 아니다.

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
- 화면 이탈·취소는 콜백을 먼저 무효화한다. worker 종료, 인식 객체·FD 해제, RAM 덮어쓰기 뒤 예약을 해제한다. 실제 자원 해제가 실패하면 `RESTART_REQUIRED`와 예약 유지로 새 입력을 막으며 성공으로 숨기지 않는다.
- 일반 최종 결과 또는 최종 분절별 첫 후보를 고정 시험 문장과 비교한다. 공백·구두점 차이만 무시한다. 분절 사이에는 일치 여부·누적 문자 수만 유지하고, 원문·후보 목록을 state·로그·파일에 남기지 않는다. 분절 하나는 유효한 첫 신뢰도를 표시하되 여러 분절의 점수를 문장 신뢰도로 합성하지 않는다. 누락·음수·범위 밖·낮은 점수를 성공 값으로 보충하지 않으며 문장 일치도 명령 실행 허가가 아니다.

`0.3.0-probe`의 스피커 재생 시험에서는 두 마이크 경로가 `NO_MATCH(7)`였고 진행 중 이탈 취소·해제를 확인했다. `0.4.0-probe`의 무음 파일 시험에서는 처음 일반 최종 콜백만 사용했을 때 결과가 없었으나, 위 분절 계약 적용 뒤 고정 문장 일치를 두 번 확인했다. 같은 길이의 무음 입력은 `NO_MATCH(7)`, 즉시 HOME은 `USER_CANCELED`였으며 그 뒤 재실행도 성공했다. 새 앱 마이크 사용은 관측되지 않았다. 반환 신뢰도 `0.0`, 실제 발음·마이크·주변 소음·네트워크 차단·연속 문장 등은 최종 채택 전 별도 검증 항목이다.

### 외부 PCM과 한 문장 보존의 별도 게이트

호출어 끝과 STT 시작 사이의 잘림을 줄이려면 동일 접근 세션 안에서만 짧은 RAM 내 PCM 버퍼를 인계하는 방식을 검토한다. [EXTRA_AUDIO_SOURCE 공식 계약](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)에 따르면 이 옵션이 없거나 인식기가 지원하지 않으면 **인식기가 자체 마이크를 연다**. 플래그를 넣었다는 사실이나 언어 지원 조회 성공만으로 기존 `AudioRecord`를 소비한다고 보장할 수 없다.

고정 합성 파일의 외부 PCM 인식은 이제 실측했지만, 호출어와 연결한 캡처 스트림의 첫 음절 보존·단일 오디오 소유권까지 입증한 것은 아니다. 이 조건을 확인하기 전에는 접근 마이크와 시스템 STT를 병렬로 실행하지 않는다. 원거리 버퍼 수집은 금지다. 인계가 불가능하면 사용자가 두 번 말해야 하는 흐름으로 합격 처리하지 않고 동일 스트림을 처리할 온디바이스 대안을 검토한다.

`CONFIDENCE_SCORES`는 선택 사항이며 `-1`은 미제공 값이다. 점수가 없거나 낮은 결과에 임의의 높은 신뢰도를 부여하지 않는다. 호출어 감지는 발화자 인증이 아니고 지원 메타데이터는 명령 실행 권한이 아니다.

## 차량 게이트웨이 경계

실제 Tesla 차량 명령을 앱에 직접 구현하지 않는다. 서버가 필요하다고 확정된 후에만 Node.js와 TypeScript 서버를 검토할 수 있다. 서명은 새로 구현하지 않고 공식 Go `vehicle-command`의 재사용을 우선 검토한다. 이 결정은 서버 필요성·인증 모델·차량 명령 검증이 끝나기 전에는 확정 구현이 아니다.

실제 게이트웨이와 독립된 mock/dry-run 계약은 미전송·거절·요청 승인·관측된 완료·`UNKNOWN`을 구분해야 한다. dry-run 안내는 “실제 차량 명령은 보내지 않았습니다”로 실제 성공과 구분한다. 실제 연동 실패 때 mock 성공으로 조용히 전환하지 않는다. 상세 권한·서명·비용은 [Tesla 연동](Tesla-Integration.md)을 따른다.
