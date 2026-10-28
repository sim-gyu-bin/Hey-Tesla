package com.heytesla.app.logging

import com.heytesla.app.diagnostics.BleCandidateDiagnostics
import com.heytesla.app.diagnostics.BleCandidateDiagnosticsJson
import com.heytesla.app.diagnostics.BleTrialSummary
import com.heytesla.app.diagnostics.BleTrialSummaryJson
import com.heytesla.app.diagnostics.SpeechTrialSummary
import com.heytesla.app.diagnostics.SpeechTrialSummaryJson
import com.heytesla.app.diagnostics.TeslaApproachReadSummary
import com.heytesla.app.diagnostics.TeslaApproachReadSummaryJson
import com.heytesla.app.vehicle.detection.BleEventEvidence
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognition
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognitionResult
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaBleAuthEvidence
import com.heytesla.app.vehicle.uwb.TeslaUwbGattEvent
import com.heytesla.app.vehicle.uwb.TeslaUwbStage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 영속 로그 경로 조각. `Context.noBackupFilesDir` 기준 상대 경로다. */
const val FIELD_LOG_RELATIVE_PATH = "field-diagnostics/events-v2.jsonl"

/**
 * JSONL 레코드 스키마 버전.
 *
 * 27 = 미분류 UWB 본문의 값 없는 bounded 구조 요약과 header-only 요청 상관 관측.
 * 26 = 서비스 UWB notification/frame/protocol 분모·decoder·실제 반환 step/인증/FiRa/AEAD의 nullable 관측.
 * 25 = 서비스 UWB GATT 단계·SDK 접수·원 callback status·상대 시각의 nullable snapshot.
 * 24 = 같은 UWB 거리 표본의 raw azimuth/elevation·독립 confidence와 세션 축별 capability. 단위 해석 없음.
 * 23 = 수동 UWB의 실제 거리(m)·confidence·원시 framework 시각·수신 elapsed·시작/종료 분리 관측.
 * 22 = 명시 동의한 실차 음성 시험의 최종 후보·신뢰도·정책 판정. 시험 어휘만 보존하고 나머지는 마스킹.
 * 21 = 실제 프렁크 요청·전송·응답·열림 관측 및 정리의 분리된 Boolean 상태.
 * 20 = SDK 201 미접수 쓰기의 실제 추가 발급 횟수·검증된 BODY의 절전 원 코드. 접수 후 재전송 금지.
 * 19 = 실제 GATT autoConnect·CREATE/기존 handle RECONNECT·SDK 접수 Boolean 관측.
 * 18 = 인증 읽기의 이탈 대기60초 제거·같은 후보 내 정리/새 광고 기반 제한 복구.
 * 17 = 직접 GATT 전환. NATIVE 실행 계층·실제 SDK 접수/반환·native callback 시각, library 전용 필드 제거.
 * 16 = 비인증 signedMessageStatus 존재·fault/operation 원 코드 및 Nordic 쓰기 실패 원 코드.
 * 15 = 유효 반복 광고 집계·후보 만료 관측과 비인증 request UUID/route 일치 Boolean.
 * 14 = Nordic GATT 실행 경계·연결 준비 대기와 라이브러리 enqueue 시각. native 발급/반환과 구분.
 * 13 = 수신 header 존재/kind·기대/실제 domain·payload case·domain 제외 세부 분류.
 * 12 = 연결·CCCD native 결과와 callback 밖 단일 쓰기 예약/제출 관측.
 * 11 = native RX·완성 프레임·프로토콜 입력의 누적 관측과 응답 제외/진행 분류.
 * 10 = 쓰기 SDK 반환·예외·native 완료 콜백을 구분한 nullable 관측.
 * 9 = 접근당 BODY/DRIVE 인증 읽기의 단계·부분 결과·정리·원 시각 및 읽기 모드 Boolean.
 * 8 = 스캔 예약 지연·제한 복구와 광고 전달 처리 시점의 화면/잠금 상태.
 * 7 = BLE 광고명 필터 사용 여부의 비민감 Boolean. VIN·광고명·해시는 기록하지 않는다.
 * 6 = BLE 후보·스캔·GATT 요청/콜백의 enum 기반 `bleEvidence`, 비교 조건 및 오류 분리.
 * 5 = 서비스 소유 주말 BLE 회차의 typed `bleTrial` 증거 요약.
 * 4 = 로컬 dry-run·오프라인 TTS 결과와 입력·출력·포커스 해제 요약.
 * 3 = `speechTrial.commandDecision`, 2 = 판정 없는 `speechTrial`, 1 = 요약 없는 과거 행.
 * 앱은 항상 현재 버전으로만 덧붙이고 기존 행을 고치지 않으므로 이전 버전과 공존한다.
 * 파서는 행마다 `version`을 보고, 없어진 필드를 가정하지 않는다.
 */
const val FIELD_LOG_RECORD_VERSION = 27

/** 새 로그 전체 상한. active와 archive 3개에 각각 512 KiB를 배정한다. 과거 events.jsonl은 포함하지 않는다. */
const val FIELD_LOG_LIMIT_BYTES = 2L * 1024L * 1024L

private const val ACTIVE_FILE_NAME = "events-v2.jsonl"
private const val ARCHIVE_COUNT = 3

private const val QUEUE_CAPACITY = 512
private const val MAX_CODE_LENGTH = 256
private const val NEWLINE_INT = '\n'.code

enum class FieldLogHealth { OK, LIMIT_REACHED, QUEUE_OVERFLOW, WRITE_FAILED }

/** 실패 코드. 소스에 고정된 값만 쓰며 예외 메시지·경로 원문은 담지 않는다. */
object FieldLogFailureCode {
    const val OPEN_FAILED = "OPEN_FAILED"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val SYNC_FAILED = "SYNC_FAILED"
    const val LIMIT_REACHED = "LIMIT_REACHED"
    const val QUEUE_OVERFLOW = "QUEUE_OVERFLOW"
}

/**
 * 영속 로그 상태. 실패는 프로세스가 끝날 때까지 유지된다. 정상 회전은 실패가 아니다.
 * [fileBytes]는 active와 archive의 합계, [written]은 이 sink에서 sync를 통과한 누적 건수다.
 * 보존 기간이 지난 archive의 제거는 기록 실패가 아니므로 [dropped]에 포함하지 않는다.
 *
 * [pending]은 허용되어 아직 디스크에 확정되지 않은 레코드 수다. 큐 대기뿐 아니라 writer가
 * 집어 든 뒤 아직 `fd.sync`를 통과하지 못한 레코드도 포함한다.
 */
data class FieldLogStatus(
    val health: FieldLogHealth = FieldLogHealth.OK,
    val failureCode: String? = null,
    val path: String = "",
    val limitBytes: Long = FIELD_LOG_LIMIT_BYTES,
    val fileBytes: Long = 0L,
    val pending: Int = 0,
    val written: Long = 0L,
    val dropped: Long = 0L,
    val rejected: Long = 0L,
) {
    val failed: Boolean get() = health != FieldLogHealth.OK
}

/**
 * 한 줄로 기록할 사건. `state` 값은 Boolean·Int·Long·String과 [FieldStateKeys]의 고정 키만 허용한다.
 * VIN·association ID·BT 주소·위치·녹음·인식 원문·예외 메시지는 어떤 필드로도 담지 않는다.
 * 예외는 명시 동의한 실차 세션의 유효 최종 결과인 [frunkVoiceRecognition]뿐이며,
 * 원문이 아닌 고정 시험 어휘·마스킹 문장과 신뢰도·고정 정책 사유만 전용 이벤트에 기록한다.
 *
 * [trialId]는 기존 접근 관찰 세션 식별자를 재사용한다. STT 회차 ID를 새로 저장하지 않으며,
 * 음성 시험은 이 행의 `event`·`wallMs`·`elapsedMs`와 전용 요약으로 구분한다.
 *
 * [speechTrial]은 회차를 끝내는 그 행에만 붙는 상세요약이다. 시작·게이트·다른 사건 행은 null이므로
 * 직전 회차 값이 다음 행으로 이어지지 않는다. 요약도 문자열 allowlist와 유한한 신뢰도만 통과시킨다.
 * [bleTrial]도 해당 사건의 회차 증거만 담는다. 없는 행은 null이고 다른 회차 값을 자동 재사용하지 않는다.
 * [teslaApproachRead]는 현재 접근의 읽기 관측만 담는다. 단계 행은 최종 완료를 뜻하지 않는다.
 */
data class FieldRecord(
    val processId: String,
    val trialId: String?,
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long,
    val appVersion: String,
    val event: String,
    val state: Map<String, Any?>,
    val speechTrial: SpeechTrialSummary? = null,
    val version: Int = FIELD_LOG_RECORD_VERSION,
    val bleTrial: BleTrialSummary? = null,
    val bleEvidence: BleEventEvidence? = null,
    val teslaApproachRead: TeslaApproachReadSummary? = null,
    val candidateDiagnostics: BleCandidateDiagnostics? = null,
    val frunkVoiceRecognition: FrunkVoiceRecognitionResult? = null,
)

/** JSON `state` 객체에 쓸 수 있는 키. 여기 없는 키는 인코딩 단계에서 버린다. */
object FieldStateKeys {
    const val ENABLED = "enabled"
    const val AUTOMATIC_MIC = "autoMic"
    const val OBSERVATION_SERVICE_RUNNING = "observationServiceRunning"
    const val OBSERVATION_START_PENDING = "observationStartPending"
    const val OBSERVING = "observing"
    const val PRESENT = "present"
    const val BLUETOOTH = "bluetooth"
    const val ASSISTANT = "assistant"
    const val MIC_PERMISSION = "micPermission"
    const val BLUETOOTH_PERMISSION = "bluetoothPermission"
    const val NOTIFICATION_PERMISSION = "notificationPermission"
    const val ASSOCIATIONS = "associations"
    const val SESSION_ACTIVE = "sessionActive"
    const val STOP_REASON = "stopReason"
    const val BLE_FIELD_TRIAL_ACTIVE = "bleFieldTrialActive"
    const val BLE_FIELD_TRIAL_STARTING = "bleFieldTrialStarting"
    const val BLE_FIELD_TRIAL_STOPPING = "bleFieldTrialStopping"
    const val BLE_FIELD_TRIAL_WAITING_FOR_DEPARTURE = "bleFieldTrialWaitingForDeparture"
    const val BLE_FIELD_TRIAL_ATTEMPT_COUNT = "bleFieldTrialAttemptCount"
    const val BLE_FIELD_TRIAL_COMPLETED_COUNT = "bleFieldTrialCompletedCount"
    const val BLE_FIELD_TRIAL_STOP_REASON = "bleFieldTrialStopReason"
    const val BLE_SCAN_PERMISSION = "bluetoothScanPermission"
    const val BLE_FIELD_OBSERVATION_ONLY = "bleFieldObservationOnly"
    const val BLE_FIELD_SUPPLEMENTAL_SCAN = "bleFieldSupplementalScan"
    const val BLE_FIELD_BT_ASSIST = "bleFieldBtAssist"
    const val BLE_FIELD_BACKGROUND_CONNECT = "bleFieldBackgroundConnect"
    const val BLE_FIELD_RETRY_ENABLED = "bleFieldRetryEnabled"
    const val BLE_FIELD_ADVERTISED_NAME_FILTER = "bleFieldAdvertisedNameFilter"
    const val BLE_FIELD_AUTHENTICATED_READ = "bleFieldAuthenticatedRead"
    const val BLE_FIELD_CANDIDATE_COUNT = "bleFieldCandidateCount"
    const val BLE_FIELD_SCAN_RUNNING = "bleFieldScanRunning"
    const val BLE_FIELD_SCAN_FAILURE = "bleFieldScanFailure"
    const val FRUNK_COMMAND_REQUESTED = "frunkCommandRequested"
    const val FRUNK_COMMAND_SENT = "frunkCommandSent"
    const val FRUNK_COMMAND_ACKNOWLEDGED = "frunkCommandAcknowledged"
    const val FRUNK_OPEN_OBSERVED = "frunkOpenObserved"
    const val FRUNK_LOCAL_CLOSED = "frunkLocalClosed"
    const val FRUNK_CLEANUP_FAILED = "frunkCleanupFailed"
    const val UWB_STAGE = "uwbStage"
    const val UWB_BACKEND_OPENED = "uwbBackendOpened"
    const val UWB_AZIMUTH_SUPPORTED = "uwbAzimuthSupported"
    const val UWB_ELEVATION_SUPPORTED = "uwbElevationSupported"
    const val UWB_PEER_COUNT = "uwbPeerCount"
    const val UWB_STARTED_PEER_COUNT = "uwbStartedPeerCount"
    const val UWB_VALID_DISTANCE_COUNT = "uwbValidDistanceCount"
    const val UWB_INVALID_RESULT_COUNT = "uwbInvalidResultCount"
    const val UWB_LAST_PEER_INDEX = "uwbLastPeerIndex"
    const val UWB_LAST_DISTANCE_METERS = "uwbLastDistanceMeters"
    const val UWB_LAST_CONFIDENCE = "uwbLastConfidence"
    const val UWB_LAST_AZIMUTH_RAW = "uwbLastAzimuthRaw"
    const val UWB_LAST_AZIMUTH_CONFIDENCE = "uwbLastAzimuthConfidence"
    const val UWB_LAST_ELEVATION_RAW = "uwbLastElevationRaw"
    const val UWB_LAST_ELEVATION_CONFIDENCE = "uwbLastElevationConfidence"
    const val UWB_LAST_FRAMEWORK_TIMESTAMP_MS = "uwbLastFrameworkTimestampMs"
    const val UWB_LAST_RECEIVED_ELAPSED_MS = "uwbLastReceivedElapsedMs"
    const val UWB_CLOSED_REASON = "uwbClosedReason"
    const val UWB_OPEN_FAILED_REASON = "uwbOpenFailedReason"
    const val UWB_REASON = "uwbReason"
    const val UWB_GATT_STAGE = "uwbGattStage"
    const val UWB_GATT_EVENT = "uwbGattEvent"
    const val UWB_GATT_OPERATION_STARTED_ELAPSED_MS = "uwbGattOperationStartedElapsedMs"
    const val UWB_GATT_STAGE_STARTED_ELAPSED_MS = "uwbGattStageStartedElapsedMs"
    const val UWB_GATT_OBSERVED_ELAPSED_MS = "uwbGattObservedElapsedMs"
    const val UWB_GATT_CONNECT_ISSUED_ELAPSED_MS = "uwbGattConnectIssuedElapsedMs"
    const val UWB_GATT_CONNECT_RETURNED_ELAPSED_MS = "uwbGattConnectReturnedElapsedMs"
    const val UWB_GATT_AUTO_CONNECT = "uwbGattAutoConnect"
    const val UWB_GATT_CONNECT_ACTION = "uwbGattConnectAction"
    const val UWB_GATT_CONNECT_ACCEPTED = "uwbGattConnectAccepted"
    const val UWB_GATT_ADVERTISEMENT_AGE_AT_CONNECT_MS = "uwbGattAdvertisementAgeAtConnectMs"
    const val UWB_GATT_CONNECTION_STATUS = "uwbGattConnectionStatus"
    const val UWB_GATT_CONNECTION_STATE = "uwbGattConnectionState"
    const val UWB_GATT_CONNECTION_CALLBACK_ELAPSED_MS = "uwbGattConnectionCallbackElapsedMs"
    const val UWB_GATT_DESCRIPTOR_REQUEST_STATUS = "uwbGattDescriptorRequestStatus"
    const val UWB_GATT_DESCRIPTOR_CALLBACK_STATUS = "uwbGattDescriptorCallbackStatus"
    const val UWB_GATT_DESCRIPTOR_CALLBACK_ELAPSED_MS = "uwbGattDescriptorCallbackElapsedMs"
    const val UWB_GATT_FIRST_RX_ELAPSED_MS = "uwbGattFirstRxElapsedMs"
    const val UWB_GATT_LAST_RX_ELAPSED_MS = "uwbGattLastRxElapsedMs"
    const val UWB_GATT_CONNECTED_OBSERVED = "uwbGattConnectedObserved"
    const val UWB_GATT_SERVICE_FOUND = "uwbGattServiceFound"
    const val UWB_GATT_TX_FOUND = "uwbGattTxFound"
    const val UWB_GATT_RX_FOUND = "uwbGattRxFound"
    const val UWB_GATT_SUBSCRIPTION_CONFIRMED = "uwbGattSubscriptionConfirmed"
    const val UWB_GATT_RX_NOTIFICATION_COUNT = "uwbGattRxNotificationCount"
    const val UWB_RX_FRAME_COUNT = "uwbRxFrameCount"
    const val UWB_RX_PROTOCOL_COUNT = "uwbRxProtocolCount"
    const val UWB_RX_NOTIFICATION_FRAME_COUNT = "uwbRxNotificationFrameCount"
    const val UWB_RX_DECODER_INCOMPLETE = "uwbRxDecoderIncomplete"
    const val UWB_RX_DECODER_FAILED = "uwbRxDecoderFailed"
    const val UWB_RX_PROTOCOL_RECEIVED_ELAPSED_MS = "uwbRxProtocolReceivedElapsedMs"
    const val UWB_RX_PROTOCOL_OBSERVED_ELAPSED_MS = "uwbRxProtocolObservedElapsedMs"
    const val UWB_RX_STEP = "uwbRxStep"
    const val UWB_RX_INPUT_KIND = "uwbRxInputKind"
    const val UWB_RX_AEAD = "uwbRxAead"
    const val UWB_RX_FIRA_KIND = "uwbRxFiraKind"
    const val UWB_RX_AUTH_EVIDENCE = "uwbRxAuthEvidence"
    const val UWB_RX_DISPOSITION = "uwbRxDisposition"
    const val UWB_RX_EXPECTED_DOMAIN = "uwbRxExpectedDomain"
    const val UWB_RX_FROM_PRESENT = "uwbRxFromPresent"
    const val UWB_RX_FROM_KIND = "uwbRxFromKind"
    const val UWB_RX_FROM_DOMAIN = "uwbRxFromDomain"
    const val UWB_RX_TO_PRESENT = "uwbRxToPresent"
    const val UWB_RX_TO_KIND = "uwbRxToKind"
    const val UWB_RX_PAYLOAD_KIND = "uwbRxPayloadKind"
    const val UWB_RX_DOMAIN_MISMATCH_REASON = "uwbRxDomainMismatchReason"
    const val UWB_RX_REQUEST_UUID_PRESENT = "uwbRxRequestUuidPresent"
    const val UWB_RX_REQUEST_UUID_MATCHES = "uwbRxRequestUuidMatches"
    const val UWB_RX_TO_ROUTE_MATCHES = "uwbRxToRouteMatches"
    const val UWB_RX_SIGNED_STATUS_PRESENT = "uwbRxSignedStatusPresent"
    const val UWB_RX_SIGNED_MESSAGE_FAULT = "uwbRxSignedMessageFault"
    const val UWB_RX_OPERATION_STATUS = "uwbRxOperationStatus"
    const val UWB_RX_BODY_BYTES = "uwbRxBodyBytes"
    const val UWB_RX_BODY_STATUS = "uwbRxBodyStatus"
    const val UWB_RX_BODY_FIELDS = "uwbRxBodyFields"
    const val UWB_RX_REQUEST_CORRELATION = "uwbRxRequestCorrelation"

    val UWB_RX_BODY_KEYS = setOf(UWB_RX_BODY_BYTES, UWB_RX_BODY_STATUS, UWB_RX_BODY_FIELDS)
    val UWB_RX_SUMMARY_KEYS = UWB_RX_BODY_KEYS + UWB_RX_REQUEST_CORRELATION

    val UWB_RX_BOOLS = setOf(UWB_RX_DECODER_INCOMPLETE, UWB_RX_DECODER_FAILED,
        UWB_RX_FROM_PRESENT, UWB_RX_TO_PRESENT, UWB_RX_REQUEST_UUID_PRESENT,
        UWB_RX_REQUEST_UUID_MATCHES, UWB_RX_TO_ROUTE_MATCHES, UWB_RX_SIGNED_STATUS_PRESENT)
    val UWB_RX_INTS = setOf(UWB_RX_EXPECTED_DOMAIN, UWB_RX_FROM_DOMAIN,
        UWB_RX_SIGNED_MESSAGE_FAULT, UWB_RX_OPERATION_STATUS)
    val UWB_RX_TIMES = setOf(UWB_RX_PROTOCOL_RECEIVED_ELAPSED_MS, UWB_RX_PROTOCOL_OBSERVED_ELAPSED_MS)
    val UWB_RX_COUNTS = setOf(UWB_RX_FRAME_COUNT, UWB_RX_PROTOCOL_COUNT)
    val UWB_RX_ENUMS = mapOf(
        UWB_RX_STEP to setOf("WAITING", "SEND", "COMPLETE", "FRUNK_RESULT", "FAILED", "UWB_READY", "UWB_INPUT"),
        UWB_RX_INPUT_KIND to setOf("ROUTABLE_MESSAGE", "RAW_FIRA", "WRAPPED_PLAINTEXT", "ENCRYPTED_RESPONSE"),
        UWB_RX_AEAD to setOf("NOT_ATTEMPTED", "VERIFIED", "FAILED"),
        UWB_RX_FIRA_KIND to setOf("REQUEST", "STOP", "UNRECOGNIZED", "INVALID"),
        UWB_RX_AUTH_EVIDENCE to TeslaBleAuthEvidence.entries.map { it.name }.toSet(),
        UWB_RX_DISPOSITION to setOf("DOMAIN_MISMATCH", "ROUTE_MISMATCH", "REQUEST_UUID_MISMATCH",
            "ACK_WAITING", "SESSION_INFO_INVALID", "SESSION_INFO_AUTH_REJECTED", "SESSION_READY",
            "STATUS_VERIFIED", "STATUS_INCOMPLETE", "RESPONSE_REJECTED", "RESPONSE_INVALID",
            "RESPONSE_WAITING", "FIRA_REQUEST", "FIRA_STOP", "FIRA_UNRECOGNIZED", "FIRA_INVALID"),
        UWB_RX_FROM_KIND to setOf("DOMAIN", "ROUTING_ADDRESS", "UNSET", "UNKNOWN"),
        UWB_RX_TO_KIND to setOf("DOMAIN", "ROUTING_ADDRESS", "UNSET", "UNKNOWN"),
        UWB_RX_PAYLOAD_KIND to setOf("SESSION_INFO", "SESSION_INFO_REQUEST", "PROTOBUF_MESSAGE_AS_BYTES", "UNSET", "UNKNOWN"),
        UWB_RX_DOMAIN_MISMATCH_REASON to setOf("FROM_MISSING", "FROM_NOT_DOMAIN", "DOMAIN_DIFFERENT"),
    )
    val UWB_RX_KEYS = listOf(UWB_RX_FRAME_COUNT, UWB_RX_PROTOCOL_COUNT,
        UWB_RX_NOTIFICATION_FRAME_COUNT, UWB_RX_DECODER_INCOMPLETE, UWB_RX_DECODER_FAILED,
        UWB_RX_PROTOCOL_RECEIVED_ELAPSED_MS, UWB_RX_PROTOCOL_OBSERVED_ELAPSED_MS,
        UWB_RX_STEP, UWB_RX_INPUT_KIND, UWB_RX_AEAD, UWB_RX_FIRA_KIND, UWB_RX_AUTH_EVIDENCE,
        UWB_RX_DISPOSITION, UWB_RX_EXPECTED_DOMAIN, UWB_RX_FROM_PRESENT, UWB_RX_FROM_KIND,
        UWB_RX_FROM_DOMAIN, UWB_RX_TO_PRESENT, UWB_RX_TO_KIND, UWB_RX_PAYLOAD_KIND,
        UWB_RX_DOMAIN_MISMATCH_REASON, UWB_RX_REQUEST_UUID_PRESENT, UWB_RX_REQUEST_UUID_MATCHES,
        UWB_RX_TO_ROUTE_MATCHES, UWB_RX_SIGNED_STATUS_PRESENT, UWB_RX_SIGNED_MESSAGE_FAULT,
        UWB_RX_OPERATION_STATUS)

    val UWB_GATT_BOOLS = setOf(UWB_GATT_AUTO_CONNECT, UWB_GATT_CONNECT_ACCEPTED,
        UWB_GATT_CONNECTED_OBSERVED, UWB_GATT_SERVICE_FOUND, UWB_GATT_TX_FOUND,
        UWB_GATT_RX_FOUND, UWB_GATT_SUBSCRIPTION_CONFIRMED)
    val UWB_GATT_INTS = setOf(UWB_GATT_CONNECTION_STATUS, UWB_GATT_CONNECTION_STATE,
        UWB_GATT_DESCRIPTOR_REQUEST_STATUS, UWB_GATT_DESCRIPTOR_CALLBACK_STATUS)
    val UWB_GATT_TIMES = setOf(UWB_GATT_OPERATION_STARTED_ELAPSED_MS,
        UWB_GATT_STAGE_STARTED_ELAPSED_MS, UWB_GATT_OBSERVED_ELAPSED_MS,
        UWB_GATT_CONNECT_ISSUED_ELAPSED_MS, UWB_GATT_CONNECT_RETURNED_ELAPSED_MS,
        UWB_GATT_ADVERTISEMENT_AGE_AT_CONNECT_MS, UWB_GATT_CONNECTION_CALLBACK_ELAPSED_MS,
        UWB_GATT_DESCRIPTOR_CALLBACK_ELAPSED_MS, UWB_GATT_FIRST_RX_ELAPSED_MS,
        UWB_GATT_LAST_RX_ELAPSED_MS)
    val UWB_GATT_KEYS = listOf(UWB_GATT_STAGE, UWB_GATT_EVENT,
        UWB_GATT_OPERATION_STARTED_ELAPSED_MS, UWB_GATT_STAGE_STARTED_ELAPSED_MS,
        UWB_GATT_OBSERVED_ELAPSED_MS, UWB_GATT_CONNECT_ISSUED_ELAPSED_MS,
        UWB_GATT_CONNECT_RETURNED_ELAPSED_MS, UWB_GATT_AUTO_CONNECT, UWB_GATT_CONNECT_ACTION,
        UWB_GATT_CONNECT_ACCEPTED, UWB_GATT_ADVERTISEMENT_AGE_AT_CONNECT_MS,
        UWB_GATT_CONNECTION_STATUS, UWB_GATT_CONNECTION_STATE, UWB_GATT_CONNECTION_CALLBACK_ELAPSED_MS,
        UWB_GATT_DESCRIPTOR_REQUEST_STATUS, UWB_GATT_DESCRIPTOR_CALLBACK_STATUS,
        UWB_GATT_DESCRIPTOR_CALLBACK_ELAPSED_MS, UWB_GATT_FIRST_RX_ELAPSED_MS,
        UWB_GATT_LAST_RX_ELAPSED_MS, UWB_GATT_CONNECTED_OBSERVED, UWB_GATT_SERVICE_FOUND,
        UWB_GATT_TX_FOUND, UWB_GATT_RX_FOUND, UWB_GATT_SUBSCRIPTION_CONFIRMED,
        UWB_GATT_RX_NOTIFICATION_COUNT)

    /** 기록 순서를 고정한 허용 키 목록. */
    val ORDER: List<String> = listOf(
        ENABLED,
        AUTOMATIC_MIC,
        OBSERVATION_SERVICE_RUNNING,
        OBSERVATION_START_PENDING,
        OBSERVING,
        PRESENT,
        BLUETOOTH,
        ASSISTANT,
        MIC_PERMISSION,
        BLUETOOTH_PERMISSION,
        NOTIFICATION_PERMISSION,
        ASSOCIATIONS,
        SESSION_ACTIVE,
        STOP_REASON,
        BLE_FIELD_TRIAL_ACTIVE,
        BLE_FIELD_TRIAL_STARTING,
        BLE_FIELD_TRIAL_STOPPING,
        BLE_FIELD_TRIAL_WAITING_FOR_DEPARTURE,
        BLE_FIELD_TRIAL_ATTEMPT_COUNT,
        BLE_FIELD_TRIAL_COMPLETED_COUNT,
        BLE_FIELD_TRIAL_STOP_REASON,
        BLE_SCAN_PERMISSION,
        BLE_FIELD_OBSERVATION_ONLY,
        BLE_FIELD_SUPPLEMENTAL_SCAN,
        BLE_FIELD_BT_ASSIST,
        BLE_FIELD_BACKGROUND_CONNECT,
        BLE_FIELD_RETRY_ENABLED,
        BLE_FIELD_ADVERTISED_NAME_FILTER,
        BLE_FIELD_AUTHENTICATED_READ,
        BLE_FIELD_CANDIDATE_COUNT,
        BLE_FIELD_SCAN_RUNNING,
        BLE_FIELD_SCAN_FAILURE,
        FRUNK_COMMAND_REQUESTED,
        FRUNK_COMMAND_SENT,
        FRUNK_COMMAND_ACKNOWLEDGED,
        FRUNK_OPEN_OBSERVED,
        FRUNK_LOCAL_CLOSED,
        FRUNK_CLEANUP_FAILED,
        UWB_STAGE,
        UWB_BACKEND_OPENED,
        UWB_AZIMUTH_SUPPORTED,
        UWB_ELEVATION_SUPPORTED,
        UWB_PEER_COUNT,
        UWB_STARTED_PEER_COUNT,
        UWB_VALID_DISTANCE_COUNT,
        UWB_INVALID_RESULT_COUNT,
        UWB_LAST_PEER_INDEX,
        UWB_LAST_DISTANCE_METERS,
        UWB_LAST_CONFIDENCE,
        UWB_LAST_AZIMUTH_RAW,
        UWB_LAST_AZIMUTH_CONFIDENCE,
        UWB_LAST_ELEVATION_RAW,
        UWB_LAST_ELEVATION_CONFIDENCE,
        UWB_LAST_FRAMEWORK_TIMESTAMP_MS,
        UWB_LAST_RECEIVED_ELAPSED_MS,
        UWB_CLOSED_REASON,
        UWB_OPEN_FAILED_REASON,
        UWB_REASON,
    ) + UWB_GATT_KEYS + UWB_RX_KEYS + UWB_RX_SUMMARY_KEYS
}

/**
 * event code 단일 allowlist. 소스에 실제로 있는 code만 허용하며, 정규식으로 문자열 모양만 보고 통과시키지 않는다.
 * 새 code를 추가할 때는 여기에도 추가해야 한다. 목록 밖 code는 파일에 쓰지 않고
 * [FieldLogStatus.rejected]로 집계한다.
 */
object FieldEvents {
    private val fixedCodes = setOf(
        "TESLA_UWB_DISTANCE_RECEIVED",
        "TESLA_UWB_GATT_OBSERVATION",
        "TESLA_UWB_STARTED",
        "TESLA_UWB_BLOCKED",
        "TESLA_UWB_AUTHENTICATING",
        "TESLA_UWB_AUTHENTICATED",
        "TESLA_UWB_CAPABILITIES_UNSPECIFIED",
        "TESLA_UWB_CAPABILITIES_RECEIVED",
        "TESLA_UWB_REQUEST_RECEIVED",
        "TESLA_UWB_RESPONSE_SENT",
        "TESLA_UWB_BACKEND_OPENED",
        "TESLA_UWB_PEER_STARTED",
        "TESLA_UWB_INVALID_RESULT",
        "TESLA_UWB_PEER_STOPPED",
        "TESLA_UWB_PEERS_ENDED",
        "TESLA_UWB_ENDPOINT_CLOSING",
        "TESLA_UWB_VEHICLE_STOP_RECEIVED",
        "TESLA_UWB_BACKEND_CLOSED",
        "TESLA_UWB_SESSION_STOPPED_SENT",
        "TESLA_UWB_SESSION_STOPPED_NOT_SENT",
        "TESLA_UWB_CLEANING_UP",
        "TESLA_UWB_COMPLETE",
        "TESLA_UWB_CANCELED",
        "TESLA_UWB_FAILED",
        "TESLA_UWB_TIMED_OUT",
        "TESLA_UWB_CLEANUP_FAILED",
        "TESLA_UWB_LOCAL_CLOSED",
        "TESLA_FRUNK_BLOCKED",
        "TESLA_FRUNK_COMMAND_REQUESTED",
        "TESLA_FRUNK_FRESH_P_VERIFIED",
        "TESLA_FRUNK_COMMAND_SENT",
        "TESLA_FRUNK_COMMAND_ACKNOWLEDGED",
        "TESLA_FRUNK_OPEN_OBSERVED",
        "TESLA_FRUNK_ACTION_REJECTED",
        "TESLA_FRUNK_RESULT_UNKNOWN",
        "TESLA_FRUNK_CANCELED",
        "TESLA_FRUNK_COMPLETE",
        "TESLA_FRUNK_FAILED",
        "TESLA_FRUNK_CLEANUP_FAILED",
        "TESLA_FRUNK_LOCAL_CLOSED",
        "FRUNK_VOICE_HANDOFF_BLOCKED",
        "FRUNK_VOICE_CORE_STARTED",
        "FRUNK_VOICE_CORE_UNKNOWN",
        "FRUNK_VOICE_SOURCE_BLOCKED",
        "FRUNK_VOICE_GRAMMAR_BLOCKED",
        "FRUNK_VOICE_CANCELED",
        "FRUNK_VOICE_CONFIDENCE_BLOCKED",
        "FRUNK_VOICE_CONFIDENCE_UNAVAILABLE",
        "FRUNK_VOICE_CLEANUP_FAILED",
        "FRUNK_VOICE_EXPIRED",
        "FRUNK_VOICE_ADMISSION_BLOCKED",
        "FRUNK_VOICE_CONSENT_REQUIRED",
        "FRUNK_VOICE_MODEL_REQUIRED",
        "FRUNK_VOICE_STARTED",
        "FRUNK_VOICE_COMMAND_ACCEPTED",
        FrunkVoiceRecognition.EVENT,
        "FRUNK_VOICE_RECOGNITION_FAILED",
        "FRUNK_VOICE_READINESS_LOST",
        "FRUNK_VOICE_FINAL_REQUIRED",
        "BLE_CANDIDATE_DIAGNOSTICS",
        "TESLA_APPROACH_READ_STARTED",
        "TESLA_APPROACH_READ_STAGE",
        "TESLA_APPROACH_BODY_VERIFIED",
        "TESLA_APPROACH_DRIVE_VERIFIED",
        "TESLA_APPROACH_READ_FINISHED",
        "TESLA_APPROACH_READ_BLOCKED",
        "TESLA_APPROACH_READ_CLEANUP_FAILED",
        "TESLA_APPROACH_WRITE_REQUESTED",
        "TESLA_APPROACH_WRITE_RESULT",
        "TESLA_APPROACH_WRITE_CALLBACK",
        "TESLA_APPROACH_RX_NOTIFICATION",
        "TESLA_APPROACH_RX_FRAME",
        "TESLA_APPROACH_RX_RESPONSE",
        "TESLA_APPROACH_GATT_CONNECTION_CALLBACK",
        "TESLA_APPROACH_GATT_CONNECT_RESULT",
        "TESLA_APPROACH_GATT_DESCRIPTOR_RESULT",
        "TESLA_APPROACH_GATT_DESCRIPTOR_CALLBACK",
        "TESLA_APPROACH_WRITE_QUEUED",
        "TESLA_KEY_STARTED",
        "TESLA_KEY_START_BLOCKED",
        "TESLA_KEY_CREDENTIAL_READY",
        "TESLA_KEY_SUBSCRIBED",
        "TESLA_KEY_TX_ATTEMPTED",
        "TESLA_KEY_REGISTRATION_REPORTED",
        "TESLA_KEY_STATUS_VERIFIED",
        "TESLA_KEY_DRIVE_STATE_VERIFIED",
        "TESLA_KEY_RESULT_UNKNOWN",
        "TESLA_KEY_CANCELED",
        "TESLA_KEY_TIMEOUT",
        "TESLA_KEY_COMPLETE",
        "TESLA_KEY_FAILED",
        "TESLA_KEY_CLEANUP_FAILED",
        "TESLA_KEY_LOCAL_CLOSED",
        "TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED",
        "TESLA_KEY_AUTH_ACKNOWLEDGED",
        "TESLA_KEY_AUTH_REQUEST_REJECTED",
        "TESLA_KEY_AUTH_KEY_NOT_PAIRED_REPORTED",
        "TESLA_KEY_AUTH_OPERATION_REJECTED",
        "TESLA_KEY_AUTH_UNEXPECTED_PAYLOAD",
        "TESLA_KEY_AUTH_SESSION_STATUS_UNSUPPORTED",
        "TESLA_KEY_AUTH_SIGNATURE_MISSING",
        "TESLA_KEY_AUTH_TAG_MISSING",
        "TESLA_KEY_AUTH_TAG_LENGTH_INVALID",
        "TESLA_KEY_AUTH_PARAMETERS_INVALID",
        "TESLA_KEY_AUTH_HMAC_MISMATCH",
        "TESLA_KEY_AUTH_SESSION_INFO_INVALID",
        "UWB_SUPPORT_REQUESTED",
        "UWB_SUPPORT_DIAGNOSTIC_BUSY",
        "UWB_SUPPORT_BACKEND_UNAVAILABLE",
        "UWB_SUPPORT_REQUEST_FAILED",
        "UWB_SUPPORT_METADATA_RECEIVED",
        "UWB_SUPPORT_METADATA_FAILED",
        "UWB_SUPPORT_TIMEOUT",
        "UWB_SUPPORT_CANCELED",
        "UWB_SUPPORT_CLEANUP_FAILED",
        "UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED",
        "BLE_EVIDENCE",
        "BLUETOOTH_SCAN_PERMISSION_REQUIRED",
        "BLE_LOCATION_PERMISSION_REQUIRED",
        "BLE_LOCATION_DISABLED",
        "BLE_SCAN_LOCATION_PERMISSION_REQUIRED",
        "BLE_SCAN_LOCATION_SERVICES_DISABLED",
        "BLE_SCAN_FGS_FALLBACK",
        "BLE_SCAN_FGS_NOT_RUNNING",
        "BLE_SCAN_NAME_UNAVAILABLE",
        "BLE_SCAN_DELIVERY_FAILED",
        "BLE_SCAN_ORPHAN_CLEANUP_FAILED",
        "BLE_DETECTION_RESTORE_REQUESTED",
        "BLE_DETECTION_RESTORE_BLOCKED",
        "BLE_DETECTION_PREFERENCE_FAILED",
        "BLE_SCAN_ADDRESS_UNAVAILABLE",
        "BLE_SCAN_PERMISSION_REQUIRED",
        "BLE_SCAN_PERMISSION_REVOKED",
        "BLE_SCAN_NO_OFFLOADED_FILTER",
        "BLE_SCAN_UNAVAILABLE",
        "BLE_SCAN_START_FAILED",
        "BLE_SCAN_STOP_FAILED",
        "BLE_SCAN_CALLBACK_FAILED",
        "BLE_FIELD_START_REQUESTED",
        "BLE_FIELD_RUNNING",
        "BLE_FIELD_WAITING",
        "BLE_FIELD_APPEARED",
        "BLE_FIELD_DISAPPEARED",
        "BLE_FIELD_DUPLICATE_APPEARANCE",
        "BLE_FIELD_STAGE",
        "BLE_FIELD_TRIAL_FINISHED",
        "BLE_FIELD_HEARTBEAT",
        "BLE_FIELD_STOP_REQUESTED",
        "BLE_FIELD_STOPPED",
        "BLE_FIELD_PREVIOUS_RUN_UNCLOSED",
        "BLE_FIELD_START_REJECTED",
        "BLE_FIELD_CLEANUP_FAILED",
        "BLE_PROBE_STARTED",
        "BLE_PROBE_BUSY",
        "BLE_PROBE_RESTART_REQUIRED",
        "BLE_PROBE_BLOCKED",
        "BLE_PROBE_COMPLETE",
        "BLE_PROBE_CANCELED",
        "BLE_PROBE_TIMED_OUT",
        "BLE_PROBE_FAILED",
        "BLE_PROBE_CLEANUP_FAILED",
        "BLE_SUPPORT_CLEANUP_BLOCKED",
        "BLE_PROBE_ENABLE_BLOCKED",
        "BLE_PROBE_AUTOMATIC_MIC_BLOCKED",
        "BLE_PROBE_APPROACH_BLOCKED",
        "BLE_PROBE_MANUAL_BLOCKED",
        "BLE_PROBE_MICROPHONE_BLOCKED",
        "DIAGNOSTIC_OBSERVE_BLOCKED",
        "BLE_FIELD_MARKER_READ_FAILED",
        "BLE_FIELD_MARKER_WRITE_FAILED",
        "BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED",
        "BLE_FIELD_NOTIFICATION_STOP",
        "BLE_FIELD_WAKE_LOCK_UNAVAILABLE",
        "BLE_FIELD_WAKE_LOCK_RELEASE_FAILED",
        "ASSOCIATION_CREATED",
        "ASSOCIATION_FAILED",
        "ASSOCIATION_READ_FAILED",
        "ASSOCIATION_REMOVED",
        "ASSOCIATION_REQUEST_FAILED",
        "ASSOCIATION_REQUIRED",
        "ASSOCIATION_SECURITY_DENIED",
        "APPROACH_PREREQUISITE_MISSING",
        "ASSISTANT_CHANGED",
        "ASSISTANT_DESTROYED",
        "ASSISTANT_NOT_ACTIVE",
        "ASSISTANT_NOT_READY",
        "ASSISTANT_READY",
        "AUDIO_INITIALIZATION_FAILED",
        "AUDIO_READ_ERROR",
        "AUDIO_RECORD_STARTED_NOT_RECOGNITION",
        "AUDIO_WORKER_FAILED",
        "AUTO_PREREQUISITE_LOST",
        "AUTO_SESSION_REQUESTED",
        "AUTOMATIC_MIC_BLOCKED_SESSION_RUNNING",
        "AUTOMATIC_MIC_DISABLED",
        "AUTOMATIC_MIC_ENABLED_NEXT_APPEARANCE_ONLY",
        "AUTOMATIC_MIC_OPTED_IN",
        "AUTOMATIC_MIC_REQUIRES_OBSERVATION_OFF",
        "BLUETOOTH_OFF",
        "BLUETOOTH_STATE_CHANGED",
        "BT_CONNECTED_NOT_APPROACH_TRIGGER",
        "BT_DISCONNECTED",
        "CDM_UNAVAILABLE",
        "CDM_UNSUPPORTED",
        "CHOOSER_APPROVED",
        "CHOOSER_CANCELED_OR_FAILED",
        "CHOOSER_LAUNCH_FAILED",
        "DEPARTED",
        "DISABLED",
        "DUPLICATE_OR_COOLDOWN_APPROACH_IGNORED",
        "FEATURE_DISABLED",
        "FEATURE_ENABLED_THIS_PROCESS",
        "FGS_BACKGROUND_START_DENIED",
        "FGS_SECURITY_WHILE_IN_USE_DENIED",
        "FGS_START_FAILED",
        "FGS_START_TIMEOUT",
        "INPUT_SILENCED",
        "MANUAL_DIAGNOSTICS_HIDDEN",
        "MANUAL_REQUIRES_VISIBLE_UI_AND_MIC_PERMISSION",
        "MANUAL_SESSION_REQUESTED",
        "MANUAL_UI_HIDDEN",
        "MIC_FGS_START_DENIED",
        "MIC_PERMISSION_REVOKED",
        "MIC_SECURITY_WHILE_IN_USE_DENIED",
        "MIC_SERVICE_DESTROYED",
        "MULTIPLE_ASSOCIATIONS_UNSUPPORTED",
        "NOTIFICATION_STOP",
        "OBSERVATION_ONLY",
        "OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED",
        "OBSERVATION_FGS_BACKGROUND_START_DENIED",
        "OBSERVATION_FGS_RUNNING",
        "OBSERVATION_FGS_SECURITY_DENIED",
        "OBSERVATION_FGS_START_FAILED",
        "OBSERVATION_FGS_START_REQUESTED",
        "OBSERVATION_FGS_START_TIMEOUT",
        "OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED",
        "OBSERVATION_NOTIFICATION_STOP",
        "OBSERVATION_NOTIFICATIONS_BLOCKED",
        "OBSERVATION_REQUIRES_VISIBLE_UI",
        "OBSERVATION_SERVICE_DESTROYED",
        "OBSERVATION_STALE_START_IGNORED",
        "OBSERVE_ONLY_CAPTURE_SUPPRESSED",
        "OBSERVE_REQUEST_ACCEPTED_NOT_PRESENCE_PROOF",
        "OBSERVE_REQUIRES_ENABLE",
        "OBSERVE_SECURITY_DENIED",
        "OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED",
        "OBSERVE_STOPPED",
        "OBSERVE_UNAVAILABLE",
        "PRESENCE_IGNORED_NOT_ARMED",
        "PROCESS_START_OFF",
        "PROCESS_EXIT_OBSERVED",
        "REAL_BLE_APPEARED",
        "REAL_BLE_DISAPPEARED",
        "RUNTIME_PERMISSION_RESULT",
        "SESSION_ALREADY_RUNNING",
        "SESSION_EXPIRED",
        "SETTINGS_READ_FAILED",
        "SETTINGS_SCREEN_UNAVAILABLE",
        "SETTINGS_WRITE_FAILED",
        "SPEECH_SUPPORT_REQUESTED",
        "SPEECH_TRIAL_APPROACH_BLOCKED",
        "SPEECH_TRIAL_AUTOMATIC_MIC_BLOCKED",
        "SPEECH_TRIAL_BUSY",
        "SPEECH_TRIAL_ENABLE_BLOCKED",
        "SPEECH_TRIAL_MANUAL_BLOCKED",
        "SPEECH_TRIAL_MICROPHONE_BLOCKED",
        "SPEECH_TRIAL_RESTART_REQUIRED",
        "STT_MODEL_DOWNLOAD_UNSUPPORTED",
        "UI_PAUSED",
        "UI_RESUMED",
        "UNSUPPORTED_PRESENCE_EVENT",
        "USER_STOP",
        "VIN_FORMAT_INVALID",
    )

    /** SpeechSupportProbe가 만드는 `SPEECH_SUPPORT_<사유>` 사유. */
    private val supportReasons = setOf(
        "CANCELED",
        "DESTROY_FAILED",
        "METADATA_FAILED",
        "METADATA_RECEIVED",
        "ON_DEVICE_API_UNAVAILABLE",
        "ON_DEVICE_UNAVAILABLE",
        "PERMISSION_CHANGED",
        "PERMISSION_REQUIRED",
        "REQUEST_FAILED",
        "SECURITY_DENIED",
        "TIMEOUT",
        "UNEXPECTED_RECOGNITION_EVENT",
    )

    /** SpeechRecognitionProbe가 거부할 때 쓰는 `SPEECH_TRIAL_<사유>` 사유. 모드 접미사가 붙지 않는다. */
    private val trialRejectionReasons = setOf(
        "KO_KR_NOT_INSTALLED",
        "LEASE_UNAVAILABLE",
        "MIC_PERMISSION_REQUIRED",
        "ON_DEVICE_UNAVAILABLE",
    )

    /** SpeechRecognitionProbe 시험 결과의 `SPEECH_TRIAL_<사유>_<모드>` 사유. */
    private val trialOutcomeReasons = setOf(
        "API_FAILURE",
        "AUDIO_CALLBACK_RELEASE_FAILED",
        "AUDIO_CAPTURE_FAILED",
        "AUDIO_FORMAT_UNAVAILABLE",
        "AUDIO_READ_FAILED",
        "AUDIO_RELEASE_FAILED",
        "AUDIO_STOP_FAILED",
        "EMPTY_PCM",
        "FINAL_RECEIVED",
        "INPUT_SILENCED",
        "INTERNAL_FAILURE",
        "MIC_PERMISSION_REVOKED",
        "MIC_PREREQUISITE_LOST",
        "NO_FINAL_RESULT",
        "PCM_FIXTURE_CLOSE_FAILED",
        "PCM_FIXTURE_LOAD_FAILED",
        "PCM_WRITE_FAILED",
        "RECOGNITION_TIMEOUT",
        "STOP_FAILED",
        "USER_CANCELED",
        "SESSION_EXPIRED",
        "VOICE_GUIDANCE_FAILED",
        "TTS_RELEASE_FAILED_RESTART_REQUIRED",
    )

    /** 정리 실패를 `_AND_`로 이어 붙일 때 쓰는 조각. */
    private val trialCleanupTokens = setOf(
        "AUDIO_CALLBACK_RELEASE_FAILED",
        "AUDIO_RELEASE_FAILED",
        "AUDIO_STOP_FAILED",
        "PCM_FIXTURE_CLOSE_FAILED",
        "PCM_READ_FD_CLOSE_FAILED",
        "PCM_WRITE_FD_CLOSE_FAILED",
        "RECOGNIZER_CANCEL_FAILED",
        "RECOGNIZER_DESTROY_FAILED",
    )

    private val trialModes = setOf("DIRECT_MIC", "BUFFERED_PCM", "TTS_PCM", "SILENT_PCM")

    private val cleanupSuffixes = listOf("_RESTART_REQUIRED", "_RESOURCES_RELEASED")

    fun isAllowed(code: String): Boolean {
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return false
        if (code in fixedCodes) return true
        if (numericCode(code, "ASSOCIATION_FAILED_CODE_")) return true
        if (numericCode(code, "SPEECH_SUPPORT_SUPPORT_ERROR_")) return true
        if (numericCode(code, "SPEECH_SUPPORT_RECOGNIZER_ERROR_")) return true
        suffix(code, "SPEECH_SUPPORT_")?.let { return it in supportReasons }
        suffix(code, "SPEECH_TRIAL_START_")?.let { return it in trialModes }
        suffix(code, "SPEECH_TRIAL_")?.let { return trialOutcomeAllowed(it) }
        return false
    }

    private fun trialOutcomeAllowed(value: String): Boolean {
        if (value in trialRejectionReasons) return true
        val mode = trialModes.firstOrNull { value.endsWith("_$it") } ?: return false
        val reason = value.removeSuffix("_$mode")
        if (reason.isEmpty()) return false
        if (reason in trialOutcomeReasons) return true
        if (numericCode(reason, "RECOGNIZER_ERROR_")) return true
        if (numericCode(reason, "SEGMENTS_RECEIVED_")) return true
        return cleanupFailure(reason)
    }

    private fun cleanupFailure(reason: String): Boolean {
        val suffixText = cleanupSuffixes.firstOrNull { reason.endsWith(it) } ?: return false
        val tokens = reason.removeSuffix(suffixText).split("_AND_")
        return tokens.isNotEmpty() && tokens.all { it in trialCleanupTokens }
    }

    private fun suffix(code: String, prefix: String): String? =
        if (code.startsWith(prefix) && code.length > prefix.length) code.substring(prefix.length) else null

    /**
     * 정수 접미사 code. 호출부가 넘기는 오류 코드·조각 수·연결 오류 코드는 작은 정수이고,
     * 접미사 자체는 앞에 붙을 수 있는 `-` 하나와 숫자만 허용하므로 임의 문자열이 통과하지 않는다.
     * `AssociationRequest.Callback.onFailure`처럼 음수를 넘기는 호출부의 코드를 유실 없이 남기기 위한 범위다.
     */
    private fun numericCode(code: String, prefix: String): Boolean {
        val value = suffix(code, prefix) ?: return false
        val digits = if (value.startsWith('-')) value.substring(1) else value
        return digits.length in 1..6 && digits.all { it in '0'..'9' }
    }
}

/**
 * JSONL 인코딩. 한 레코드는 반드시 한 줄이며 제어문자를 escape한다.
 * `state` 문자열은 고정 allowlist만 허용하며, 본문 구조는 값 없는 canonical numeric triple만 별도 검증한다.
 */
internal object FieldJson {
    private val bodyStatuses = setOf("COMPLETE", "TRUNCATED", "MALFORMED", "UNSUPPORTED")
    private val requestCorrelations = setOf("CURRENT", "PREVIOUS", "UNMATCHED")
    private val uwbReasons = setOf(
        "BACKEND_UNAVAILABLE", "CAPABILITIES_TIMEOUT", "CAPABILITIES_REQUEST_FAILED",
        "CAPABILITIES_CALLBACK_FAILED", "CAPABILITIES_CHANGED", "CAPABILITIES_INVALID",
        "CAPABILITIES_UNSPECIFIED", "MEASUREMENT_SCHEDULE_FAILED", "INVALID_RANGING_RESULT",
        "NATIVE_CLOSE_TIMEOUT", "NATIVE_START_FAILED", "NATIVE_OPEN_FAILED", "NATIVE_BACKEND_FAILED",
        "NATIVE_CLEANUP_FAILED", "NO_DISTANCE_RECEIVED", "DISTANCE_RECEIVE_TIMEOUT",
        "NEGOTIATION_FAILED", "UWB_NOT_ENABLED",
        "DISTANCE_UNSUPPORTED", "CHANNEL_INTERSECTION_EMPTY", "PREAMBLE_INTERSECTION_EMPTY",
        "SLOT_DURATION_UNSUPPORTED", "CONFIG_UNSUPPORTED", "UPDATE_RATE_UNSUPPORTED",
        "NEGOTIATION_ALREADY_ACTIVE", "EMPTY_REQUEST", "DUPLICATE_ENDPOINT", "STOP_WITHOUT_SESSION",
        "INVALID_STOP_ENDPOINT", "UNKNOWN_STOP_ENDPOINT", "UNSUPPORTED_ENDPOINT",
        "INVALID_PEER_ADDRESSES",
        "VEHICLE_STS_UNSUPPORTED", "STS_MODE_UNSUPPORTED", "INVALID_FIRA_MESSAGE",
        "AMBIGUOUS_FIRA_MESSAGE", "UWB_PURPOSE_INVALID", "UWB_AUTHENTICATED_SESSION_REQUIRED",
        "UWB_SESSION_EXPIRED", "UWB_ENCRYPTED_RESPONSE_REJECTED", "UWB_PROTOCOL_RESPONSE_INVALID",
        "UWB_VIN_SNAPSHOT_CHANGED", "RANGING_PERMISSION_REQUIRED", "RANGING_PERMISSION_DENIED",
        "UI_PAUSED", "UI_DESTROYED", "DIAGNOSTICS_HIDDEN", "USER_STOP",
    )
    fun encode(record: FieldRecord): String = buildString(if (record.teslaApproachRead == null && record.candidateDiagnostics == null) 384 else 4096) {
        append("{\"version\":").append(record.version)
        append(",\"processId\":").append(quote(record.processId))
        append(",\"trialId\":").append(record.trialId?.let { quote(it) } ?: "null")
        append(",\"wallMs\":").append(record.wallTimeMs)
        append(",\"elapsedMs\":").append(record.elapsedRealtimeMs)
        append(",\"appVersion\":").append(quote(record.appVersion))
        append(",\"event\":").append(quote(record.event))
        // 요약은 이 행에만 붙는다. 없는 행은 명시적 null이라 이전 회차 값으로 해석될 여지가 없다.
        append(",\"speechTrial\":").append(record.speechTrial?.let { SpeechTrialSummaryJson.encode(it) } ?: "null")
        append(",\"frunkVoiceRecognition\":").append(
            record.frunkVoiceRecognition?.takeIf {
                record.version >= 22 && record.event == FrunkVoiceRecognition.EVENT
            }?.let { FrunkVoiceRecognition.encode(it) } ?: "null",
        )
        append(",\"bleTrial\":").append(record.bleTrial?.let { BleTrialSummaryJson.encode(it) } ?: "null")
        append(",\"bleEvidence\":").append(record.bleEvidence?.encode() ?: "null")
        append(",\"teslaApproachRead\":").append(record.teslaApproachRead?.let { TeslaApproachReadSummaryJson.encode(it) } ?: "null")
        append(",\"candidateDiagnostics\":").append(record.candidateDiagnostics?.let { BleCandidateDiagnosticsJson.encode(it) } ?: "null")
        append(",\"state\":{")
        var first = true
        val bodySummaryValid = record.version >= 27 && validBodySummary(record.state)
        for (key in FieldStateKeys.ORDER) {
            if (!record.state.containsKey(key)) continue
            if (!first) append(',')
            first = false
            append(quote(key)).append(':').append(
                if ((key in FieldStateKeys.UWB_GATT_KEYS && record.version < 25) ||
                    (key in FieldStateKeys.UWB_RX_KEYS && record.version < 26) ||
                    (key in FieldStateKeys.UWB_RX_SUMMARY_KEYS && record.version < 27) ||
                    (key in FieldStateKeys.UWB_RX_BODY_KEYS && !bodySummaryValid) ||
                    (key == FieldStateKeys.UWB_GATT_EVENT && record.version < 26 && record.state[key] == "PROTOCOL")) "null"
                else primitive(key, record.state[key], record.elapsedRealtimeMs),
            )
        }
        append("}}")
    }

    /** 부분 요약은 저장하지 않는다. 원 wire encoding의 정확한 길이를 복원한다고 주장하지 않는다. */
    private fun validBodySummary(state: Map<String, Any?>): Boolean {
        val bytes = when (val value = state[FieldStateKeys.UWB_RX_BODY_BYTES]) {
            is Int -> value.toLong()
            is Long -> value
            else -> return false
        }
        if (bytes !in 0L..4096L) return false
        val status = state[FieldStateKeys.UWB_RX_BODY_STATUS]
        if (status !is String || status !in bodyStatuses) return false
        val fields = state[FieldStateKeys.UWB_RX_BODY_FIELDS]
        if (fields !is String) return false
        val minimumBytes = bodyFieldsMinimumBytes(fields, status) ?: return false
        return if (status == "COMPLETE") {
            minimumBytes <= bytes && (fields.isNotEmpty() || bytes == 0L)
        } else {
            minimumBytes < bytes // 중단을 일으킨 미기록 tag/본문이 적어도 한 바이트 남아 있어야 한다.
        }
    }

    /** 분할·원문 복사 없이 ASCII 숫자/구분자·wire별 길이·상태별 기록 개수를 검증한다. */
    private fun bodyFieldsMinimumBytes(fields: String, status: String): Int? {
        if (fields.length > 135) return null
        if (fields.isEmpty()) return if (status == "TRUNCATED") null else 0
        var cursor = 0
        var count = 0
        var minimumBytes = 0
        while (cursor < fields.length) {
            if (++count > 8) return null
            var fieldNumber = 0
            var wireType = 0
            for (component in 0..2) {
                val start = cursor
                val maximum = when (component) { 0 -> 536870911; 1 -> 5; else -> 4096 }
                var number = 0
                while (cursor < fields.length && fields[cursor] in '0'..'9') {
                    if (cursor > start && fields[start] == '0') return null
                    val digit = fields[cursor] - '0'
                    if (number > (maximum - digit) / 10 || digit > maximum) return null
                    number = number * 10 + digit
                    cursor++
                }
                if (cursor == start) return null
                when (component) {
                    0 -> {
                        if (number == 0) return null
                        fieldNumber = number
                    }
                    1 -> {
                        if (number != 0 && number != 1 && number != 2 && number != 5) return null
                        wireType = number
                    }
                    2 -> {
                        if (when (wireType) {
                            0 -> number !in 1..10
                            1 -> number != 8
                            2 -> false
                            else -> number != 4
                        }) return null
                        minimumBytes += minimumVarintBytes(fieldNumber.toLong() shl 3) + number
                        if (wireType == 2) minimumBytes += minimumVarintBytes(number.toLong())
                    }
                }
                if (component < 2) {
                    if (cursor >= fields.length || fields[cursor++] != '/') return null
                } else if (cursor < fields.length) {
                    if (fields[cursor++] != ',' || cursor == fields.length) return null
                }
            }
        }
        if (status == "TRUNCATED" && count != 8) return null
        if ((status == "MALFORMED" || status == "UNSUPPORTED") && count == 8) return null
        return minimumBytes
    }

    private fun minimumVarintBytes(value: Long): Int {
        var remaining = value
        var size = 1
        while (remaining >= 128) {
            remaining = remaining ushr 7
            size++
        }
        return size
    }

    /** 광고명 필터·인증 읽기 옵션은 Boolean만 허용한다. 나머지는 허용 타입·문자열 allowlist를 따른다. */
    private fun primitive(key: String, value: Any?, elapsedMs: Long): String {
        when {
            key == FieldStateKeys.UWB_RX_BODY_BYTES ->
                return value.toString() // 세 요약 키를 함께 검증한 뒤에만 도달한다.
            key == FieldStateKeys.UWB_RX_BODY_STATUS ->
                return if (value is String && value in bodyStatuses) quote(value) else "null"
            key == FieldStateKeys.UWB_RX_BODY_FIELDS ->
                return if (value is String) quote(value) else "null" // 전용 group 검증을 이미 통과했다.
            key == FieldStateKeys.UWB_RX_REQUEST_CORRELATION ->
                return if (value is String && value in requestCorrelations) quote(value) else "null"
            key in FieldStateKeys.UWB_RX_ENUMS ->
                return if (value is String && value in FieldStateKeys.UWB_RX_ENUMS.getValue(key)) quote(value) else "null"
            key == FieldStateKeys.UWB_GATT_STAGE ->
                return if (value is String && TeslaKeyStage.entries.any { it.name == value }) quote(value) else "null"
            key == FieldStateKeys.UWB_GATT_EVENT ->
                return if (value is String && TeslaUwbGattEvent.entries.any { it.name == value }) quote(value) else "null"
            key == FieldStateKeys.UWB_GATT_CONNECT_ACTION ->
                return if (value is String && (value == "CREATE" || value == "RECONNECT")) quote(value) else "null"
            key in FieldStateKeys.UWB_GATT_BOOLS || key in FieldStateKeys.UWB_RX_BOOLS ->
                return if (value is Boolean) value.toString() else "null"
            key in FieldStateKeys.UWB_GATT_INTS || key in FieldStateKeys.UWB_RX_INTS -> {
                val number = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
                return if (number != null && number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) number.toString() else "null"
            }
            key in FieldStateKeys.UWB_GATT_TIMES || key in FieldStateKeys.UWB_RX_TIMES ||
                key in FieldStateKeys.UWB_RX_COUNTS || key == FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT ||
                key == FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT -> {
                val number = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
                val upper = when (key) {
                    FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT -> 16L
                    FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT -> Long.MAX_VALUE
                    in FieldStateKeys.UWB_RX_COUNTS -> Long.MAX_VALUE
                    else -> elapsedMs
                }
                return if (number != null && number >= 0 && number <= upper) number.toString() else "null"
            }
        }
        if (key == FieldStateKeys.UWB_STAGE) {
            return if (value is String && TeslaUwbStage.entries.any { it.name == value }) quote(value) else "null"
        }
        if (key == FieldStateKeys.UWB_REASON) {
            return if (value is String && (value in uwbReasons || BleTrialSummaryJson.isAllowedReason(value))) quote(value) else "null"
        }
        if (key == FieldStateKeys.UWB_LAST_DISTANCE_METERS) {
            return if (value is Double && value.isFinite() && value >= 0.0) value.toString() else "null"
        }
        when (key) {
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW, FieldStateKeys.UWB_LAST_ELEVATION_RAW ->
                return if (value is Double && value.isFinite()) value.toString() else "null"
            FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE, FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE ->
                return if ((value is Int && value in 0..2) || (value is Long && value in 0L..2L)) {
                    value.toString()
                } else "null"
            FieldStateKeys.UWB_BACKEND_OPENED,
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED, FieldStateKeys.UWB_ELEVATION_SUPPORTED ->
                return if (value is Boolean) value.toString() else "null"
            FieldStateKeys.UWB_PEER_COUNT, FieldStateKeys.UWB_STARTED_PEER_COUNT,
            FieldStateKeys.UWB_VALID_DISTANCE_COUNT, FieldStateKeys.UWB_INVALID_RESULT_COUNT,
            FieldStateKeys.UWB_LAST_PEER_INDEX, FieldStateKeys.UWB_LAST_CONFIDENCE,
            FieldStateKeys.UWB_LAST_FRAMEWORK_TIMESTAMP_MS, FieldStateKeys.UWB_LAST_RECEIVED_ELAPSED_MS,
            FieldStateKeys.UWB_CLOSED_REASON, FieldStateKeys.UWB_OPEN_FAILED_REASON ->
                return if (value is Int || value is Long) value.toString() else "null"
        }
        if ((key == FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER ||
                key == FieldStateKeys.BLE_FIELD_AUTHENTICATED_READ) && value !is Boolean) return "null"
        return when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is Int -> value.toString()
            is Long -> value.toString()
            is String -> if (
                if (key == FieldStateKeys.BLE_FIELD_TRIAL_STOP_REASON) BleTrialSummaryJson.isAllowedReason(value)
                else FieldEvents.isAllowed(value)
            ) quote(value) else "null"
            else -> "null"
        }
    }

    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> {
                    append("\\u00")
                    append(HEX[ch.code shr 4])
                    append(HEX[ch.code and 0x0f])
                }
                else -> append(ch)
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * 단일 IO 스레드가 소유하는 용량 제한·회전 JSONL sink.
 *
 * - [enqueue]는 allowlist 검사·큐 적재·원자 상태 갱신만 한다. 디스크 접근도, handle 조작도 하지 않는다.
 * - 파일 열기·쓰기·회전·`fd.sync`·handle close·파일 크기 조회는 writer 전용이다. 매 행을 sync한 뒤 확정한다.
 * - active는 `events-v2.jsonl`, archive는 같은 디렉터리의 `events-v2.1.jsonl`~`events-v2.3.jsonl`뿐이다.
 *   1이 가장 최근 archive다. 기존 `events.jsonl`·다른 이름·디렉터리·심볼릭 링크는 수정하거나 지우지 않는다.
 * - 각 파일 상한은 [limitBytes] / 4다. 행을 나누지 않고 다음 행이 넘치기 전에 가장 오래된 archive를 제거한다.
 * - 재개 시 잘린 마지막 행은 그대로 두고, 공간이 있으면 줄바꿈만 덧붙인다. 꽉 찬 active는 다음 기록 전에 회전한다.
 * - 한 행 자체가 파일 상한을 넘는 경우·IO 실패·큐 초과는 영구 실패로 드러낸다. 정상 회전으로 기록을 중단하지 않는다.
 * - Android API를 쓰지 않으므로 실제 파일 동작을 JVM 테스트로 검증한다. [start]를 호출해야 writer가 동작한다.
 */
class FieldEventSink internal constructor(
    private val file: File,
    val limitBytes: Long,
    queueCapacity: Int,
    private val openFile: (File) -> RandomAccessFile,
) {
    constructor(
        file: File,
        limitBytes: Long = FIELD_LOG_LIMIT_BYTES,
        queueCapacity: Int = QUEUE_CAPACITY,
    ) : this(file, limitBytes, queueCapacity, { RandomAccessFile(it, "rw") })

    private class Command(val record: FieldRecord?, val latch: CountDownLatch?)

    init {
        require(limitBytes in (ARCHIVE_COUNT + 1L)..FIELD_LOG_LIMIT_BYTES)
    }

    private val segmentBytes = limitBytes / (ARCHIVE_COUNT + 1L)
    private val archives = Array(ARCHIVE_COUNT) { index ->
        File(file.absoluteFile.parentFile, "events-v2.${index + 1}.jsonl")
    }
    private val queue = LinkedBlockingQueue<Command>(queueCapacity)
    private val running = AtomicBoolean(false)
    private val statusRef = AtomicReference(FieldLogStatus(path = file.absolutePath, limitBytes = limitBytes))

    /** producer가 세우는 실패 요청. 실제 handle close·크기 확정은 writer가 수행한다. */
    private val failureRequest = AtomicReference<String?>(null)

    private var handle: RandomAccessFile? = null
    private var writtenBytes = 0L
    private var archivedBytes = 0L
    private var finalized = false

    /** 상태 콜백. 인자를 넘기지 않으므로 구독자는 실행 시점의 [status]를 읽어 최신값을 반영한다. */
    @Volatile
    var onStatusChanged: (() -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread(Runnable { runWriter() }, "field-event-log")
        thread.isDaemon = true
        thread.start()
    }

    fun status(): FieldLogStatus = statusRef.get()

    /**
     * 기록을 큐에 넣는다. allowlist 밖 code·이미 실패한 sink·큐 초과는 넣지 않고 집계만 갱신한다.
     * 이 함수는 어느 스레드에서 불러도 디스크 IO를 하지 않는다.
     *
     * pending은 큐 적재 **전에** 올린다. 그래야 writer가 먼저 감소시켜 pending이 음수나 중간 0이 되는
     * 구간이 생기지 않는다. offer가 실패하면 같은 publish에서 pending을 되돌리고 유실로 집계한다.
     */
    fun enqueue(record: FieldRecord): Boolean {
        if (!FieldEvents.isAllowed(record.event)) {
            recordRejected()
            return false
        }
        if (statusRef.get().failed || failureRequest.get() != null) {
            drop(1)
            return false
        }
        publish { it.copy(pending = it.pending + 1) }
        if (!queue.offer(Command(record, null))) {
            publish { it.copy(pending = it.pending - 1, dropped = it.dropped + 1) }
            requestFailure(FieldLogFailureCode.QUEUE_OVERFLOW)
            return false
        }
        return true
    }

    /** allowlist 밖 code. 파일에는 쓰지 않고 집계만 한다. */
    fun recordRejected() {
        publish { it.copy(rejected = it.rejected + 1) }
    }

    /**
     * producer 쪽에서 기록이 유실된 경우(허용 code를 만들지 못한 예상 밖 예외 등) 고정 실패로 확정하고 집계만 남긴다.
     * 화면에서 보이지 않는 유실을 성공처럼 넘기지 않도록 [FieldLogFailureCode.WRITE_FAILED] 상시 실패 표시를 함께 세운다.
     * 디스크 접근도, handle 조작도 하지 않는다.
     */
    fun recordDroppedLocally() {
        requestFailure(FieldLogFailureCode.WRITE_FAILED)
        drop(1)
    }

    /** 앞선 기록 확정을 기다린다. 큐 적재와 배리어 대기는 하나의 시간 예산을 쓰며 UI 경로에서 호출하지 않는다. */
    fun sync(timeoutMs: Long = 5_000L): Boolean {
        if (!running.get()) return false
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val startedNanos = System.nanoTime()
        val latch = CountDownLatch(1)
        return try {
            val offerNanos = timeoutNanos - (System.nanoTime() - startedNanos)
            if (!queue.offer(Command(null, latch), offerNanos, TimeUnit.NANOSECONDS)) {
                false
            } else {
                val remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos)
                latch.await(remainingNanos, TimeUnit.NANOSECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runWriter() {
        while (true) {
            val command = try {
                queue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                handleCommand(command)
            } catch (_: Throwable) {
                // writer가 죽으면 대기 기록이 조용히 쌓인다. 어떤 예외든 고정 실패로 확정하고 계속 돈다.
                failWriter(FieldLogFailureCode.WRITE_FAILED, command.record)
                command.latch?.countDown()
            }
        }
    }

    private fun handleCommand(command: Command) {
        if (failureRequest.get() != null) {
            finalizeFailure()
            settleLost(command)
            return
        }
        val latch = command.latch
        if (latch != null) {
            latch.countDown()
            return
        }
        val record = command.record ?: return
        writeOne(record)
    }

    /** 레코드 하나를 쓰고 `fd.sync`까지 마친 뒤에만 확정으로 집계한다. 회전 중에도 pending을 유지한다. */
    private fun writeOne(record: FieldRecord) {
        if (failureRequest.get() != null) {
            finalizeFailure()
            settleRecord()
            return
        }
        val line = try {
            FieldJson.encode(record).toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            failWriter(FieldLogFailureCode.WRITE_FAILED, record)
            return
        }
        val requiredBytes = line.size + 1L
        if (requiredBytes > segmentBytes) {
            // 불가능한 한 행 때문에 기존 archive를 지우지 않는다.
            failWriter(FieldLogFailureCode.LIMIT_REACHED, record)
            return
        }
        var out = open()
        if (out == null) {
            settleRecord()
            return
        }
        if (writtenBytes > segmentBytes - requiredBytes) {
            try {
                // 재개 시 추가한 줄바꿈도 rename 전에 확정한다.
                out.fd.sync()
            } catch (_: Exception) {
                failWriter(FieldLogFailureCode.SYNC_FAILED, record)
                return
            }
            try {
                rotate()
            } catch (_: Exception) {
                failWriter(FieldLogFailureCode.WRITE_FAILED, record)
                return
            }
            out = open()
            if (out == null) {
                settleRecord()
                return
            }
        }
        if (failureRequest.get() != null) {
            finalizeFailure()
            settleRecord()
            return
        }
        try {
            out.write(line)
            out.write(NEWLINE_INT)
        } catch (_: Exception) {
            failWriter(FieldLogFailureCode.WRITE_FAILED, record)
            return
        }
        try {
            out.fd.sync()
        } catch (_: Exception) {
            failWriter(FieldLogFailureCode.SYNC_FAILED, record)
            return
        }
        writtenBytes += requiredBytes
        publish {
            it.copy(fileBytes = writtenBytes + archivedBytes, written = it.written + 1, pending = it.pending - 1)
        }
    }

    /** writer 전용. 새 namespace만 열고 보존 파일의 크기를 합산한다. */
    private fun open(): RandomAccessFile? {
        handle?.let { return it }
        return try {
            // 호출자가 과거 경로를 전달해도 append·회전 대상으로 삼지 않는다.
            if (file.name != ACTIVE_FILE_NAME) throw IOException()
            val parent = file.absoluteFile.parentFile ?: throw IOException()
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException()
            val existing = ownedFileSize(file)
            var retained = 0L
            var oversized = existing > segmentBytes
            for (archive in archives) {
                val size = ownedFileSize(archive)
                retained += size
                oversized = oversized || size > segmentBytes
            }
            if (oversized) {
                failWriter(FieldLogFailureCode.LIMIT_REACHED, null)
                return null
            }
            val out = openFile(file)
            // 이후 seek/read/write 단계가 실패해도 failWriter가 닫을 수 있도록 생성 즉시 handle로 넘긴다.
            handle = out
            var length = existing
            if (existing > 0L) {
                out.seek(existing - 1L)
                if (out.read() != NEWLINE_INT && existing < segmentBytes) {
                    out.seek(existing)
                    out.writeByte(NEWLINE_INT)
                    length = existing + 1L
                }
            }
            out.seek(length)
            writtenBytes = length
            archivedBytes = retained
            publish { it.copy(fileBytes = length + retained) }
            out
        } catch (_: Exception) {
            failWriter(FieldLogFailureCode.OPEN_FAILED, null)
            null
        }
    }

    /** 고정된 네 이름만 검사한다. 링크·디렉터리는 열거나 삭제하지 않는다. */
    private fun ownedFileSize(target: File): Long {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException()
        return Files.size(path)
    }

    /** writer 전용. 실패 시 남은 파일을 임의 초기화하지 않고 영구 실패로 전환한다. */
    private fun rotate() {
        ownedFileSize(file)
        for (archive in archives) ownedFileSize(archive)
        handle?.close()
        handle = null
        Files.deleteIfExists(archives.last().toPath())
        for (index in archives.lastIndex downTo 1) {
            val source = archives[index - 1].toPath()
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) Files.move(source, archives[index].toPath())
        }
        Files.move(file.toPath(), archives[0].toPath())
        writtenBytes = 0L
        archivedBytes = 0L
        for (archive in archives) archivedBytes += ownedFileSize(archive)
        publish { it.copy(fileBytes = archivedBytes) }
    }

    private fun settleLost(command: Command) {
        // 실패 확정 뒤에도 sync() 같은 배리어 명령은 반드시 풀어 준다.
        command.latch?.countDown()
        if (command.record != null) settleRecord()
    }

    private fun settleRecord() {
        publish { it.copy(dropped = it.dropped + 1, pending = it.pending - 1) }
    }

    /** producer가 요청한 실패를 writer가 확정한다. handle close와 파일 크기 조회는 여기서만 일어난다. */
    private fun finalizeFailure() {
        if (finalized) return
        val code = failureRequest.get() ?: return
        finalized = true
        failWriter(code, null)
    }

    private fun failWriter(code: String, lost: FieldRecord?) {
        // 먼저 확정된 원인을 유지한다. producer 요청과 writer 실패가 겹쳐도 health와 failureCode가 서로 어긋나지 않는다.
        val first = failureRequest.compareAndSet(null, code)
        closeHandle()
        val length = try {
            var total = 0L
            if (file.name == ACTIVE_FILE_NAME) {
                val activePath = file.toPath()
                if (Files.isRegularFile(activePath, LinkOption.NOFOLLOW_LINKS)) total += Files.size(activePath)
                for (archive in archives) {
                    val archivePath = archive.toPath()
                    if (Files.isRegularFile(archivePath, LinkOption.NOFOLLOW_LINKS)) total += Files.size(archivePath)
                }
            }
            total
        } catch (_: Exception) {
            0L
        }
        val lostCount = if (lost == null) 0 else 1
        val effective = if (first) code else failureRequest.get() ?: code
        publish {
            it.copy(
                health = healthFor(effective),
                failureCode = effective,
                fileBytes = length,
                dropped = it.dropped + lostCount,
                pending = it.pending - lostCount,
            )
        }
    }

    private fun closeHandle() {
        val out = handle ?: return
        handle = null
        try {
            out.close()
        } catch (_: Exception) {
            // 닫기 실패는 상태에 남길 새 정보가 없다. 이미 실패 상태이거나 프로세스가 끝난다.
        }
    }

    /** producer 전용. handle을 건드리지 않고 원자 상태만 실패로 표시한다. */
    private fun requestFailure(code: String) {
        failureRequest.compareAndSet(null, code)
        val effective = failureRequest.get() ?: code
        publish { it.copy(health = healthFor(effective), failureCode = effective) }
    }

    private fun drop(count: Int) {
        publish { it.copy(dropped = it.dropped + count) }
    }

    private fun healthFor(code: String) = when (code) {
        FieldLogFailureCode.LIMIT_REACHED -> FieldLogHealth.LIMIT_REACHED
        FieldLogFailureCode.QUEUE_OVERFLOW -> FieldLogHealth.QUEUE_OVERFLOW
        else -> FieldLogHealth.WRITE_FAILED
    }

    private fun publish(block: (FieldLogStatus) -> FieldLogStatus) {
        var current = statusRef.get()
        while (true) {
            val updated = block(current)
            if (statusRef.compareAndSet(current, updated)) {
                // 구독자 예외가 writer를 죽이지 않게 한다.
                try {
                    onStatusChanged?.invoke()
                } catch (_: Throwable) {
                    // 콜백 실패는 상태에 남길 정보가 없다.
                }
                return
            }
            current = statusRef.get()
        }
    }
}
