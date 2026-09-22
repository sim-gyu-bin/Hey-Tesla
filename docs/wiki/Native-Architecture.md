# Android 네이티브 아키텍처

> 이 페이지는 Android 네이티브 설계 문서다. 앱 구현과 실기기 검증은 아직 완료되지 않았다.

[Wiki 홈](Home.md) · [요구사항](Requirements.md) · [상태 머신](State-Machine.md)

## 설계 결정

React Native나 JavaScript 브리지는 사용하지 않는다. UI, 권한, Android 서비스 수명, 오디오 캡처, 상태 전이는 Kotlin으로 소유한다. Compose UI, Coroutines와 StateFlow, DataStore, Android Keystore를 사용한 설계안이다.

초기 구현은 과도한 멀티 모듈화·DI 프레임워크·서버 선행 구현을 피한다. 필요하다면 단일 `app` 모듈 안에서 다음 책임을 논리적으로 분리한다. 이는 제안 구조이며 실제 디렉터리가 존재한다는 뜻이 아니다.

| 영역 | 책임 |
| --- | --- |
| UI | Compose 화면, 초기 설정, 상태 표시, 명시 승인 입력. 서비스 재생성과 독립적으로 StateFlow를 구독한다. |
| platform | `VoiceInteractionService` 후보, 권한, Bluetooth/접근 신호, foreground service, 오디오 포커스 및 Android 수명 경계. |
| audio | 호출어 탐지, `AudioRecord`, STT 세션, PCM pre-roll, TTS와 자기 재인식 차단. |
| domain | 명령 파서, 안전 정책, 상태 머신, 만료·취소·중복 규칙. |
| gateway | mock/dry-run 기본 구현 및 향후 실제 VehicleGateway 계약. 실제 VehicleGateway 호출 구현은 작성하지 않는다. |
| settings | DataStore 기반 비민감 설정과 Keystore 기반 별도 비밀정보 저장. |

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

Android 버전, target SDK, 라이브러리 버전은 아직 확정하지 않았다. 첫 프로젝트 생성 시 실제 기기의 버전과 지원 API, 현재 안정 버전의 도구 호환성을 확인해 고정하고 대상 조합을 시험한다. 제한을 피하려고 오래된 target SDK로 낮추지 않는다.

## 호출어와 STT

소프트웨어 호출어 엔진의 한국어 지원·모델, 정확도, 오프라인 가능 여부, 라이선스는 아직 선정하지 않았다. 외부 음성 서비스·유료 SDK·오디오 전송은 승인 전 도입하지 않는다.

명령 STT는 Android의 on-device `SpeechRecognizer`를 후보로 하되, 온디바이스 엔진 존재와 한국어 요청·모델 설치 지원을 따로 검사한다. `EXTRA_PREFER_OFFLINE` 플래그만으로 오프라인 처리를 보장하지 않는다. 호출어 끝과 STT 시작 사이의 잘림을 줄이려면 동일 접근 세션의 RAM 내 PCM pre-roll를 인계하는 방식을 검토한다. 인식기가 외부 오디오 입력을 지원하는 경우에만 사용하며, 원거리에서는 버퍼용 오디오도 수집하지 않는다.

외부 입력이나 한 문장 연속 인계가 지원되지 않으면 음절 손실을 숨기거나 사용자에게 두 번 말하도록 요구해 합격 처리하지 않는다. 다른 온디바이스 엔진·동일 스트림 처리 경로를 검토하고 비용·라이선스·개인정보 변경은 승인받는다. 음성 수집 종료 후 RAM 버퍼는 보유하지 않는다.

## 차량 게이트웨이 경계

실제 Tesla 차량 명령을 앱에 직접 구현하지 않는다. 서버가 필요하다고 확정된 후에만 Node.js와 TypeScript 서버를 검토할 수 있다. 서명은 새로 구현하지 않고 공식 Go `vehicle-command`의 재사용을 우선 검토한다. 이 결정은 서버 필요성·인증 모델·차량 명령 검증이 끝나기 전에는 확정 구현이 아니다.

실제 게이트웨이와 독립된 mock/dry-run 계약은 미전송·거절·요청 승인·관측된 완료·`UNKNOWN`을 구분해야 한다. dry-run 안내는 “실제 차량 명령은 보내지 않았습니다”로 실제 성공과 구분한다. 실제 연동 실패 때 mock 성공으로 조용히 전환하지 않는다. 상세 권한·서명·비용은 [Tesla 연동](Tesla-Integration.md)을 따른다.
