package com.heytesla.app.vehicle.protocol

import com.heytesla.app.vehicle.key.TeslaBleCredential
import com.heytesla.app.vehicle.key.p256PublicKeyFromBytes

import com.google.protobuf.ByteString
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.keys.Keys
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal enum class TeslaBlePhase { WAITING_FOR_CARD, HANDSHAKING, READING_STATUS }

internal enum class TeslaBleQuery {
    BODY_STATUS, DRIVE_STATE;

    val verifiedEventCode: String
        get() = when (this) {
            BODY_STATUS -> "TESLA_KEY_STATUS_VERIFIED"
            DRIVE_STATE -> "TESLA_KEY_DRIVE_STATE_VERIFIED"
        }
}

internal enum class TeslaBlePurpose { READ_ONLY, FRUNK_OPEN, VEHICLE_WAKE }

internal enum class TeslaFrunkReply { ACKNOWLEDGED, REJECTED, UNCONFIRMED }
internal enum class TeslaWakeReply { ACKNOWLEDGED, REJECTED, UNCONFIRMED, NOMINAL_ACK }

enum class TeslaGear { P, R, N, D, UNKNOWN }

/** 인증된 읽기 결과일 뿐 차량 감지·주차 안전허가·제어 권한이 아니다. */
internal data class TeslaDriveState(
    val gear: TeslaGear,
    val sourceTimestampEpochMillis: Long?,
    val receivedAtElapsedRealtime: Long,
)

internal data class TeslaReadOnlyStatus(
    val lockState: Int? = null,
    val frontTrunkState: Int? = null,
    val driveState: TeslaDriveState? = null,
    /** 이번 BODY 수신 시점의 원 enum code. DRIVE 준비 여부나 제어 허가가 아니다. */
    val bodyVehicleSleepStatus: Int? = null,
    /** AEAD 검증을 통과한 BODY frame의 원 ingress 시각. 처리 시각이나 HELLO 시각이 아니다. */
    val bodyReceivedAtElapsedRealtime: Long? = null,
    /** 상관 일치·AEAD 검증된 DRIVE의 명시 INVALID만 표시한다. UNKNOWN 전체나 진단 문자열이 아니다. */
    val driveShiftStateInvalid: Boolean = false,
)

/**
 * 차량 인증 대기·실패의 typed 근거. eventCode는 고정 literal이며 화면/로그 이벤트 이름이다.
 * 실패 이유 문구가 아니라 이 값으로 원인을 구분한다. 문구는 조정될 수 있다.
 */
enum class TeslaBleAuthEvidence(val eventCode: String) {
    ACKNOWLEDGED("TESLA_KEY_AUTH_ACKNOWLEDGED"),
    REQUEST_REJECTED("TESLA_KEY_AUTH_REQUEST_REJECTED"),
    /** 인증 전 응답이 미등록을 주장한 경우. 차량 신원이나 실제 미등록을 증명하지 않는다. */
    KEY_NOT_PAIRED_REPORTED("TESLA_KEY_AUTH_KEY_NOT_PAIRED_REPORTED"),
    OPERATION_REJECTED("TESLA_KEY_AUTH_OPERATION_REJECTED"),
    UNEXPECTED_PAYLOAD("TESLA_KEY_AUTH_UNEXPECTED_PAYLOAD"),
    SESSION_STATUS_UNSUPPORTED("TESLA_KEY_AUTH_SESSION_STATUS_UNSUPPORTED"),
    SIGNATURE_MISSING("TESLA_KEY_AUTH_SIGNATURE_MISSING"),
    TAG_MISSING("TESLA_KEY_AUTH_TAG_MISSING"),
    TAG_LENGTH_INVALID("TESLA_KEY_AUTH_TAG_LENGTH_INVALID"),
    PARAMETERS_INVALID("TESLA_KEY_AUTH_PARAMETERS_INVALID"),
    HMAC_MISMATCH("TESLA_KEY_AUTH_HMAC_MISMATCH"),
    SESSION_INFO_INVALID("TESLA_KEY_AUTH_SESSION_INFO_INVALID"),
}

internal sealed interface TeslaBleStep {
    data class Send(
        val payload: ByteArray,
        val phase: TeslaBlePhase,
        val actuator: Boolean = false,
        val wake: Boolean = false,
    ) : TeslaBleStep {
        init { require(!actuator || !wake) }
        override fun toString(): String = "Send(phase=$phase, actuator=$actuator, wake=$wake)"
    }
    /** 현재 conversation 시작 전 수신분. phase·인증·응답 대기 상태를 갱신하지 않는다. */
    data object Ignored : TeslaBleStep
    data class Waiting(val phase: TeslaBlePhase, val authEvidence: TeslaBleAuthEvidence? = null) : TeslaBleStep
    data class Complete(val status: TeslaReadOnlyStatus) : TeslaBleStep
    data class FrunkResult(val reply: TeslaFrunkReply) : TeslaBleStep
    /** 응답 분류일 뿐 실제 기상, DRIVE 준비나 P의 증거가 아니다. NOMINAL_ACK는 비인증이다. */
    data class WakeResult(val reply: TeslaWakeReply) : TeslaBleStep
    data class Failed(val reason: String, val authEvidence: TeslaBleAuthEvidence? = null) : TeslaBleStep
}

/**
 * 공식 wire contract: vehicle-command a4b43c1eff0e09d77deb9f2dce97031141fe8c8a.
 * pkg/vehicle/security.go SendAddKeyRequestWithRole: raw legacy PRESENT_KEY envelope.
 * pkg/protocol/protocol.md: fresh domain별 handshake, HMAC, personalized AES-GCM, response hash.
 * pkg/vehicle/state.go·infotainment.go: DRIVE_STATE는 domain3 GetVehicleData.getDriveState만 요청.
 * now는 elapsedRealtime 같은 monotonic milliseconds. 세션 캐시 없음. 자동 재전송은 없고,
 * sessionRetransmitWindowMs를 받은 READ_ONLY DRIVE_STATE만 HELLO 대기를 그 창으로 늘리고 호출자가
 * retransmitSessionRequest로 같은 route/UUID의 sessionInfo 요청을 다시 보낼 수 있다(wake 직후 domain3 기동 대기).
 * HELLO에서 상관관계가 맞는 fault0·operation_status OK ACK는 성공이 아니라 대기이며
 * phase/전체 deadline을 연장하지 않는다(공식 internal/dispatcher/dispatcher.go tryStartSession).
 * 등록 보고는 인증 전의 비인증 legacy 관측이며, 차량에 키가 저장됐다는 증거가 아니다.
 * 공식 handshake는 peer 제공 공개키 기반 무결성을 검증한다. 차량 인증서/신원 인증은
 * 제공하지 않으며, CDM·현장 차량/NFC 확인을 대체하지 않는다.
 */
internal class TeslaBleConversation(
    credential: TeslaBleCredential,
    vin: ByteArray,
    private val query: TeslaBleQuery = TeslaBleQuery.BODY_STATUS,
    private val purpose: TeslaBlePurpose = TeslaBlePurpose.READ_ONLY,
    private val authorizeFrunk: (() -> String?)? = null,
    private val frunkDeadline: Long? = null,
    private val authorizeWake: (() -> String?)? = null,
    private val wakeDeadline: Long? = null,
    private val onResponseObservation: ((TeslaBleResponseObservation) -> Unit)? = null,
    private val sessionRetransmitWindowMs: Long? = null,
    private val now: () -> Long,
) : Closeable {
    private enum class State { NEW, CARD, HELLO, STATUS, TERMINAL, CLOSED }
    private enum class ActionReply { ACKNOWLEDGED, REJECTED, UNCONFIRMED }
    private var state = State.NEW
    private val domain = when (query) {
        TeslaBleQuery.BODY_STATUS -> UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY
        TeslaBleQuery.DRIVE_STATE -> UniversalMessage.Domain.DOMAIN_INFOTAINMENT
    }
    private var credential: TeslaBleCredential? = credential
    private val personalization = vin.copyOf()
    private val random = SecureRandom()
    private var publicKey = ByteArray(0)
    private var route = ByteArray(0)
    private var requestUuid = ByteArray(0)
    // 비인증 header 관측용 직전 생성 identity만 RAM 보존한다. SDK 송신 완료를 뜻하지 않는다.
    private var previousRoute: ByteArray? = null
    private var previousRequestUuid: ByteArray? = null
    private var requestIdentityActive = false
    private var sessionKey = ByteArray(0)
    private var epoch = ByteArray(0)
    private var requestHash = ByteArray(0)
    private var startedAt = 0L
    private var phaseStartedAt = 0L
    private var lastNow = 0L
    private var sessionAt = 0L
    private var vehicleClock = 0L
    private var commandCounter = 0L
    private val responseCounters = HashSet<Long>()
    private var responseDisposition = "RESPONSE_INVALID"
    private var responseFromPresent: Boolean? = null
    private var responseFromKind: String? = null
    private var responseFromDomain: Int? = null
    private var responseToPresent: Boolean? = null
    private var responseToKind: String? = null
    private var responsePayloadKind: String? = null
    private var responseDomainMismatchReason: String? = null
    private var responseDomainMismatchVcsecSleepStatus: Int? = null
    private var responseRequestUuidPresent: Boolean? = null
    private var responseRequestUuidMatches: Boolean? = null
    private var responseToRouteMatches: Boolean? = null
    private var responseSignedStatusPresent: Boolean? = null
    private var responseSignedMessageFault: Int? = null
    private var responseOperationStatus: Int? = null
    private var responseInputKind: TeslaBleRxInputKind? = null
    private var responseAead = TeslaBleRxAead.NOT_ATTEMPTED
    private var responseRequestCorrelation: TeslaBleRxRequestCorrelation? = null
    private var responseDriveShiftState: String? = null
    private var responseDriveTimestampStatus: String? = null

    @Volatile
    var registrationReported: Boolean = false
        private set

    @Synchronized
    fun start(register: Boolean): TeslaBleStep {
        if (state != State.NEW) return fail("키 시험 작업이 이미 시작되었거나 종료되었습니다")
        return guarded {
            if (purpose == TeslaBlePurpose.FRUNK_OPEN &&
                (register || query != TeslaBleQuery.BODY_STATUS || authorizeFrunk == null || frunkDeadline == null)
            ) return@guarded fail("FRUNK_PURPOSE_INVALID")
            if (purpose == TeslaBlePurpose.VEHICLE_WAKE &&
                (register || query != TeslaBleQuery.BODY_STATUS || authorizeWake == null || wakeDeadline == null ||
                    authorizeFrunk != null || frunkDeadline != null)
            ) return@guarded fail("WAKE_PURPOSE_INVALID")
            if (sessionRetransmitWindowMs != null && (register || query != TeslaBleQuery.DRIVE_STATE ||
                    purpose != TeslaBlePurpose.READ_ONLY || sessionRetransmitWindowMs !in
                    RESPONSE_TIMEOUT_MS..SESSION_RETRANSMIT_WINDOW_MAX_MS)
            ) return@guarded fail("SESSION_RETRANSMIT_INVALID")
            if (register && query != TeslaBleQuery.BODY_STATUS) {
                return@guarded fail("앱 키 등록은 기본 차량 상태 조회에서만 허용됩니다")
            }
            require(personalization.size == 17 && personalization.all {
                it in '0'.code.toByte()..'9'.code.toByte() ||
                    it in 'A'.code.toByte()..'Z'.code.toByte() &&
                    it != 'I'.code.toByte() && it != 'O'.code.toByte() && it != 'Q'.code.toByte()
            })
            startedAt = now()
            require(startedAt >= 0)
            lastNow = startedAt
            if (purpose == TeslaBlePurpose.VEHICLE_WAKE && startedAt >= checkNotNull(wakeDeadline)) {
                return@guarded fail("WAKE_EXPIRED")
            }
            publicKey = requireNotNull(credential).publicKeyBytes()
            p256PublicKeyFromBytes(publicKey)
            if (register) {
                state = State.CARD
                phaseStartedAt = startedAt
                TeslaBleStep.Send(addKeyRequest(), TeslaBlePhase.WAITING_FOR_CARD)
            } else beginHandshake(startedAt)
        }
    }

    @Synchronized
    fun receive(payload: ByteArray, receivedAtElapsedRealtime: Long = now()): TeslaBleStep {
        resetResponseObservation()
        val step = receiveResponse(payload, receivedAtElapsedRealtime)
        recordResponseObservation(step)
        return step
    }

    private fun receiveResponse(payload: ByteArray, receivedAtElapsedRealtime: Long): TeslaBleStep {
        if (state == State.NEW || state == State.TERMINAL || state == State.CLOSED) {
            return fail("활성 키 시험 작업이 없습니다")
        }
        return guarded {
            require(payload.size <= TESLA_BLE_MAX_FRAME_BYTES)
            val current = now()
            if (current < lastNow) return@guarded fail("키 시험 시간 기준이 변경되었습니다")
            lastNow = current
            require(receivedAtElapsedRealtime in 0..current)
            if (current - startedAt >= TOTAL_TIMEOUT_MS ||
                state == State.HELLO && current - phaseStartedAt >= (sessionRetransmitWindowMs ?: RESPONSE_TIMEOUT_MS) ||
                state == State.STATUS && current - phaseStartedAt >= RESPONSE_TIMEOUT_MS
            ) return@guarded fail("키 시험 응답 시간이 초과되었습니다")
            // 원 ingress 시각을 보존한다. 선행 수신분은 파싱·인증·명령 처리에 쓰지 않는다.
            // 실제 시계 역행과 원래 기한의 만료는 위에서 먼저 거부한다.
            if (receivedAtElapsedRealtime < startedAt) {
                responseDisposition = "RESPONSE_BEFORE_START"
                return@guarded TeslaBleStep.Ignored
            }
            when (state) {
                State.CARD -> receiveRegistration(payload, current)
                State.HELLO -> receiveHandshake(payload, current, receivedAtElapsedRealtime)
                State.STATUS -> receiveStatus(payload, receivedAtElapsedRealtime)
                else -> fail("활성 키 시험 작업이 없습니다")
            }
        }
    }

    private fun resetResponseObservation() {
        responseDisposition = if (state == State.CARD) "RESPONSE_WAITING" else "RESPONSE_INVALID"
        if (onResponseObservation == null) return
        responseFromPresent = null
        responseFromKind = null
        responseFromDomain = null
        responseToPresent = null
        responseToKind = null
        responsePayloadKind = null
        responseDomainMismatchReason = null
        responseDomainMismatchVcsecSleepStatus = null
        responseRequestUuidPresent = null
        responseRequestUuidMatches = null
        responseToRouteMatches = null
        responseSignedStatusPresent = null
        responseSignedMessageFault = null
        responseOperationStatus = null
        responseInputKind = null
        responseAead = TeslaBleRxAead.NOT_ATTEMPTED
        responseRequestCorrelation = null
        responseDriveShiftState = null
        responseDriveTimestampStatus = null
    }

    private fun recordResponseObservation(step: TeslaBleStep) {
        if (step is TeslaBleStep.Failed && responseDisposition == "RESPONSE_WAITING") {
            responseDisposition = "RESPONSE_REJECTED"
        }
        val verifiedDrive = onResponseObservation != null && step is TeslaBleStep.Complete &&
            responseDisposition == "STATUS_VERIFIED" && domain.number == 3 &&
            responseFromPresent == true && responseFromKind == "DOMAIN" && responseFromDomain == 3 &&
            responseToPresent == true && responseToKind == "ROUTING_ADDRESS" &&
            responseRequestUuidPresent == true && responseRequestUuidMatches == true && responseToRouteMatches == true &&
            responseInputKind == TeslaBleRxInputKind.ENCRYPTED_RESPONSE && responseAead == TeslaBleRxAead.VERIFIED
        // 판정 완료 뒤 관측만 전달한다. callback 예외는 보안 검증 실패로 위장하지 않는다.
        try {
            onResponseObservation?.invoke(TeslaBleResponseObservation(
                disposition = responseDisposition, expectedDomain = domain.number,
                fromPresent = responseFromPresent, fromKind = responseFromKind, fromDomain = responseFromDomain,
                toPresent = responseToPresent, toKind = responseToKind, payloadKind = responsePayloadKind,
                domainMismatchReason = responseDomainMismatchReason,
                domainMismatchVcsecSleepStatus = responseDomainMismatchVcsecSleepStatus,
                requestUuidPresent = responseRequestUuidPresent, requestUuidMatches = responseRequestUuidMatches,
                toRouteMatches = responseToRouteMatches,
                signedStatusPresent = responseSignedStatusPresent,
                signedMessageFault = responseSignedMessageFault, operationStatus = responseOperationStatus,
                step = when (step) {
                    TeslaBleStep.Ignored -> TeslaBleRxStep.IGNORED
                    is TeslaBleStep.Waiting -> TeslaBleRxStep.WAITING
                    is TeslaBleStep.Send -> TeslaBleRxStep.SEND
                    is TeslaBleStep.Complete -> TeslaBleRxStep.COMPLETE
                    is TeslaBleStep.FrunkResult -> TeslaBleRxStep.FRUNK_RESULT
                    is TeslaBleStep.WakeResult -> TeslaBleRxStep.WAKE_RESULT
                    is TeslaBleStep.Failed -> TeslaBleRxStep.FAILED
                },
                inputKind = responseInputKind, aead = responseAead,
                authEvidence = when (step) {
                    is TeslaBleStep.Waiting -> step.authEvidence
                    is TeslaBleStep.Failed -> step.authEvidence
                    else -> null
                },
                requestCorrelation = responseRequestCorrelation,
                driveShiftState = responseDriveShiftState.takeIf { verifiedDrive },
                driveTimestampStatus = responseDriveTimestampStatus.takeIf { verifiedDrive },
            ))
        } catch (failure: Exception) {
            if (step is TeslaBleStep.Send) step.payload.fill(0)
            throw failure
        }
    }

    private inline fun guarded(action: () -> TeslaBleStep): TeslaBleStep = try {
        action()
    } catch (_: Exception) {
        fail("차량 키 보안 메시지를 검증할 수 없습니다")
    }

    private fun addKeyRequest(): ByteArray {
        val operation = Vcsec.WhitelistOperation.newBuilder()
            .setAddKeyToWhitelistAndAddPermissions(
                Vcsec.PermissionChange.newBuilder()
                    .setKey(Vcsec.PublicKey.newBuilder().setPublicKeyRaw(ByteString.copyFrom(publicKey)))
                    .setKeyRole(Keys.Role.ROLE_DRIVER),
            )
            .setMetadataForKey(
                Vcsec.KeyMetadata.newBuilder()
                    .setKeyFormFactor(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE),
            )
        val unsigned = Vcsec.UnsignedMessage.newBuilder().setWhitelistOperation(operation).build()
        return Vcsec.ToVCSECMessage.newBuilder().setSignedMessage(
            Vcsec.SignedMessage.newBuilder()
                .setProtobufMessageAsBytes(unsigned.toByteString())
                .setSignatureType(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY),
        ).build().toByteArray()
    }

    private fun receiveRegistration(payload: ByteArray, current: Long): TeslaBleStep {
        // 일부 펌웨어는 legacy FromVCSEC를, 일부는 universal에 담긴 VCSEC 응답을 보낸다.
        // legacy에는 request correlation/authentication이 없으므로 보고와 인증을 분리한다.
        val envelope = UniversalMessage.RoutableMessage.parseFrom(payload)
        captureResponseObservation(envelope)
        val wrapped = envelope.hasFromDestination() || envelope.hasToDestination() ||
            envelope.payloadCase != UniversalMessage.RoutableMessage.PayloadCase.PAYLOAD_NOT_SET
        val response = if (wrapped) {
            if (!envelope.hasFromDestination() ||
                envelope.fromDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.DOMAIN ||
                envelope.fromDestination.domain != UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY ||
                envelope.payloadCase != UniversalMessage.RoutableMessage.PayloadCase.PROTOBUF_MESSAGE_AS_BYTES
            ) return TeslaBleStep.Waiting(TeslaBlePhase.WAITING_FOR_CARD)
            if (envelope.hasSignatureData() || envelope.signedMessageStatus.signedMessageFaultValue != 0) {
                return fail("차량 키 등록 응답을 확인할 수 없습니다")
            }
            Vcsec.FromVCSECMessage.parseFrom(envelope.protobufMessageAsBytes)
        } else Vcsec.FromVCSECMessage.parseFrom(payload)
        if (response.hasNominalError()) return fail("차량이 키 등록을 거부했습니다")
        if (!response.hasCommandStatus()) return TeslaBleStep.Waiting(TeslaBlePhase.WAITING_FOR_CARD)
        val status = response.commandStatus
        if (!status.hasWhitelistOperationStatus()) {
            // 일반 OK/ERROR나 빈 메시지는 whitelist terminal이 아니다.
            return TeslaBleStep.Waiting(TeslaBlePhase.WAITING_FOR_CARD)
        }
        val whitelist = status.whitelistOperationStatus
        if (whitelist.whitelistOperationInformationValue != 0) return fail("차량이 키 등록을 거부했습니다")
        if (status.operationStatus == Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT ||
            whitelist.operationStatus == Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT
        ) return TeslaBleStep.Waiting(TeslaBlePhase.WAITING_FOR_CARD)
        if (status.operationStatus != Vcsec.OperationStatus_E.OPERATIONSTATUS_OK ||
            whitelist.operationStatus != Vcsec.OperationStatus_E.OPERATIONSTATUS_OK
        ) return fail("차량 키 등록 결과가 불명확합니다")
        registrationReported = true
        return beginHandshake(current)
    }

    private fun beginHandshake(current: Long): TeslaBleStep {
        state = State.HELLO
        phaseStartedAt = current
        newRequestIdentity()
        val request = requestBuilder().setSessionInfoRequest(
            UniversalMessage.SessionInfoRequest.newBuilder().setPublicKey(ByteString.copyFrom(publicKey)),
        ).build()
        return TeslaBleStep.Send(request.toByteArray(), TeslaBlePhase.HANDSHAKING)
    }

    /**
     * wake 직후 domain3가 첫 HELLO를 놓쳤을 때만 쓴다. identity·phaseStartedAt을 바꾸지 않으므로
     * SESSION_INFO HMAC metadata와 상관관계는 그대로이고 HELLO 창도 연장되지 않는다.
     */
    @Synchronized
    fun retransmitSessionRequest(): TeslaBleStep {
        if (sessionRetransmitWindowMs == null || state != State.HELLO || !requestIdentityActive) return TeslaBleStep.Ignored
        return guarded {
            val current = now()
            if (current < lastNow) return@guarded fail("키 시험 시간 기준이 변경되었습니다")
            lastNow = current
            if (current - startedAt >= TOTAL_TIMEOUT_MS || current - phaseStartedAt >= sessionRetransmitWindowMs) {
                return@guarded fail("키 시험 응답 시간이 초과되었습니다")
            }
            val request = requestBuilder().setSessionInfoRequest(
                UniversalMessage.SessionInfoRequest.newBuilder().setPublicKey(ByteString.copyFrom(publicKey)),
            ).build()
            TeslaBleStep.Send(request.toByteArray(), TeslaBlePhase.HANDSHAKING)
        }
    }

    private fun receiveHandshake(payload: ByteArray, current: Long, receivedAtElapsedRealtime: Long): TeslaBleStep {
        val response = UniversalMessage.RoutableMessage.parseFrom(payload)
        captureResponseObservation(response)
        if (!matchesRequest(response)) return TeslaBleStep.Waiting(TeslaBlePhase.HANDSHAKING)
        // 공식 pkg/protocol/error.go GetError: fault가 있으면 다른 어떤 판정보다 먼저 실패다.
        val fault = response.signedMessageStatus.signedMessageFaultValue
        if (fault != 0) {
            responseDisposition = "RESPONSE_REJECTED"
            val evidence = if (fault == UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID_VALUE) {
                TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED
            } else TeslaBleAuthEvidence.REQUEST_REJECTED
            return if (evidence == TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED) {
                fail("차량 응답이 앱 키 미등록을 보고했습니다", evidence)
            } else {
                fail("차량이 키 인증 요청을 거부했습니다", evidence)
            }
        }
        if (response.payloadCase != UniversalMessage.RoutableMessage.PayloadCase.SESSION_INFO) {
            // 공식 internal/dispatcher/dispatcher.go tryStartSession은 payload 없는 GetError(nil)
            // ACK를 받고도 readySignal(session info)을 계속 기다린다. 이는 성공이 아니라 대기다.
            if (isNominalAck(response)) {
                responseDisposition = "ACK_WAITING"
                return TeslaBleStep.Waiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED)
            }
            val ackShaped = response.payloadCase == UniversalMessage.RoutableMessage.PayloadCase.PAYLOAD_NOT_SET &&
                response.hasSignedMessageStatus()
            return if (ackShaped) {
                responseDisposition = "RESPONSE_REJECTED"
                fail("차량이 키 인증 작업을 완료하지 않았습니다", TeslaBleAuthEvidence.OPERATION_REJECTED)
            } else {
                fail("차량 키 인증 응답이 올바르지 않습니다", TeslaBleAuthEvidence.UNEXPECTED_PAYLOAD)
            }
        }
        responseDisposition = "SESSION_INFO_INVALID"
        val encodedInfo = response.sessionInfo.toByteArray()
        val info = try {
            Signatures.SessionInfo.parseFrom(encodedInfo)
        } catch (_: Exception) {
            encodedInfo.fill(0)
            return discardHandshake(TeslaBleAuthEvidence.SESSION_INFO_INVALID)
        }
        if (info.status == Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST) {
            responseDisposition = "SESSION_INFO_AUTH_REJECTED"
            // 인증 전 응답의 미등록 보고이며, 차량 신원이나 실제 미등록을 증명하지 않는다.
            // 서명 검증 이전이라도 인증 성공으로 취급하지 않는다.
            encodedInfo.fill(0)
            return fail("차량 응답이 앱 키 미등록을 보고했습니다", TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED)
        }
        if (info.status != Signatures.Session_Info_Status.SESSION_INFO_STATUS_OK) {
            // 알 수 없는 status는 미등록 보고로 오인하지 않는다.
            encodedInfo.fill(0)
            return fail("차량 세션 상태를 지원하지 않습니다", TeslaBleAuthEvidence.SESSION_STATUS_UNSUPPORTED)
        }
        if (!response.hasSignatureData()) {
            encodedInfo.fill(0)
            return discardHandshake(TeslaBleAuthEvidence.SIGNATURE_MISSING)
        }
        if (!response.signatureData.hasSessionInfoTag()) {
            encodedInfo.fill(0)
            return discardHandshake(TeslaBleAuthEvidence.TAG_MISSING)
        }
        val tag = response.signatureData.sessionInfoTag.tag.toByteArray()
        if (tag.size != SESSION_INFO_TAG_BYTES) {
            tag.fill(0)
            encodedInfo.fill(0)
            return discardHandshake(TeslaBleAuthEvidence.TAG_LENGTH_INVALID)
        }
        if (info.epoch.size() != 16 || info.publicKey.size() != 65) {
            tag.fill(0)
            encodedInfo.fill(0)
            return discardHandshake(TeslaBleAuthEvidence.PARAMETERS_INVALID)
        }
        val peerKey = info.publicKey.toByteArray()
        var candidate = ByteArray(0)
        var hmacKey = ByteArray(0)
        var metadata = ByteArray(0)
        var expected = ByteArray(0)
        try {
            candidate = requireNotNull(credential).deriveSessionKey(peerKey)
            if (candidate.size != SESSION_KEY_BYTES) {
                return fail("차량 세션 키 길이가 올바르지 않습니다", TeslaBleAuthEvidence.SESSION_INFO_INVALID)
            }
            hmacKey = hmac(candidate, SESSION_INFO_LABEL)
            metadata = Metadata()
                .byte(0, Signatures.SignatureType.SIGNATURE_TYPE_HMAC_VALUE)
                .bytes(2, personalization).bytes(6, requestUuid).finish()
            expected = hmac(hmacKey, metadata, encodedInfo)
            if (!MessageDigest.isEqual(expected, tag)) {
                return discardHandshake(TeslaBleAuthEvidence.HMAC_MISMATCH)
            }
            sessionKey = candidate
            candidate = ByteArray(0)
            epoch = info.epoch.toByteArray()
            vehicleClock = unsigned(info.clockTime)
            commandCounter = unsigned(info.counter)
            sessionAt = receivedAtElapsedRealtime
        } catch (_: Exception) {
            return discardHandshake(TeslaBleAuthEvidence.SESSION_INFO_INVALID)
        } finally {
            peerKey.fill(0)
            candidate.fill(0)
            hmacKey.fill(0)
            metadata.fill(0)
            expected.fill(0)
            tag.fill(0)
            encodedInfo.fill(0)
        }
        val next = sendStatusRequest(current)
        responseDisposition = if (purpose == TeslaBlePurpose.VEHICLE_WAKE && next is TeslaBleStep.Failed) {
            "RESPONSE_REJECTED"
        } else "SESSION_READY"
        return next
    }

    // 공식 dispatcher.checkForSessionUpdate처럼 검증 불가능한 세션 응답만 버린다.
    // 키·epoch·counter·route와 phase 시작 시각을 바꾸지 않으며 새 요청도 전송하지 않는다.
    private fun discardHandshake(evidence: TeslaBleAuthEvidence): TeslaBleStep {
        responseDisposition = when (evidence) {
            TeslaBleAuthEvidence.SIGNATURE_MISSING, TeslaBleAuthEvidence.TAG_MISSING,
            TeslaBleAuthEvidence.TAG_LENGTH_INVALID, TeslaBleAuthEvidence.HMAC_MISMATCH ->
                "SESSION_INFO_AUTH_REJECTED"
            else -> "SESSION_INFO_INVALID"
        }
        return TeslaBleStep.Waiting(TeslaBlePhase.HANDSHAKING, evidence)
    }

    /**
     * 상관관계가 맞는, 차량이 보낸 명목 OK ACK. session info가 아직 없으므로 성공이 아니다.
     * 대기로 처리하며 phaseStartedAt과 전체 deadline을 연장하지 않는다.
     */
    private fun isNominalAck(response: UniversalMessage.RoutableMessage): Boolean =
        response.payloadCase == UniversalMessage.RoutableMessage.PayloadCase.PAYLOAD_NOT_SET &&
            response.hasSignedMessageStatus() &&
            response.signedMessageStatus.signedMessageFaultValue == 0 &&
            response.signedMessageStatus.operationStatus == UniversalMessage.OperationStatus_E.OPERATIONSTATUS_OK

    private fun sendStatusRequest(current: Long): TeslaBleStep {
        var requestTime = current
        if (purpose == TeslaBlePurpose.FRUNK_OPEN) {
            checkNotNull(authorizeFrunk).invoke()?.let { return fail(it) }
        } else if (purpose == TeslaBlePurpose.VEHICLE_WAKE) {
            checkNotNull(authorizeWake).invoke()?.let { return fail(it) }
            // 권한 callback 중 흘러간 시간도 TTL에 반영한다. 원 deadline을 연장하지 않는다.
            requestTime = now()
            if (requestTime < lastNow) return fail("WAKE_CLOCK_CHANGED")
            lastNow = requestTime
            if (requestTime >= checkNotNull(wakeDeadline)) return fail("WAKE_EXPIRED")
        }
        val plaintext = when (purpose) {
            TeslaBlePurpose.FRUNK_OPEN ->
                // pinned actions.go OpenFrunk → vcsec.go executeClosureAction.
                Vcsec.UnsignedMessage.newBuilder().setClosureMoveRequest(
                    Vcsec.ClosureMoveRequest.newBuilder()
                        .setFrontTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE),
                ).build().toByteArray()
            TeslaBlePurpose.VEHICLE_WAKE ->
                // pinned vcsec.go wakeupRKE → executeRKEAction. 다른 RKE 권한은 생성하지 않는다.
                Vcsec.UnsignedMessage.newBuilder()
                    .setRKEAction(Vcsec.RKEAction_E.RKE_ACTION_WAKE_VEHICLE).build().toByteArray()
            TeslaBlePurpose.READ_ONLY -> when (query) {
                TeslaBleQuery.BODY_STATUS -> Vcsec.UnsignedMessage.newBuilder().setInformationRequest(
                    Vcsec.InformationRequest.newBuilder()
                        .setInformationRequestType(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS),
                ).build().toByteArray()
                TeslaBleQuery.DRIVE_STATE -> CarServer.Action.newBuilder().setVehicleAction(
                    CarServer.VehicleAction.newBuilder().setGetVehicleData(
                        CarServer.GetVehicleData.newBuilder().setGetDriveState(CarServer.GetDriveState.getDefaultInstance()),
                    ),
                ).build().toByteArray()
            }
        }
        return encryptRequest(requestTime, plaintext)
    }

    private fun encryptRequest(current: Long, plaintext: ByteArray): TeslaBleStep {
        // Every request owns and clears the supplied plaintext.
        try {
            return encryptOwnedRequest(current, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encryptOwnedRequest(current: Long, plaintext: ByteArray): TeslaBleStep {
        require(sessionKey.size == SESSION_KEY_BYTES)
        require(commandCounter < UINT32_MAX)
        commandCounter++
        val currentVehicleClock = vehicleClock + (current - sessionAt) / 1_000L
        val expires = if (purpose == TeslaBlePurpose.FRUNK_OPEN) {
            val remaining = checkNotNull(frunkDeadline) - current
            if (remaining < 1_000L) return fail("FRUNK_COMMAND_EXPIRED")
            minOf(currentVehicleClock + COMMAND_TTL_SECONDS,
                vehicleClock + (checkNotNull(frunkDeadline) - sessionAt) / 1_000L)
        } else if (purpose == TeslaBlePurpose.VEHICLE_WAKE) {
            val deadline = checkNotNull(wakeDeadline)
            if (current >= deadline || deadline - current < 1_000L) return fail("WAKE_EXPIRED")
            minOf(currentVehicleClock + COMMAND_TTL_SECONDS,
                vehicleClock + (deadline - sessionAt) / 1_000L)
        } else currentVehicleClock + COMMAND_TTL_SECONDS
        require(expires in 1..MAX_EPOCH_SECONDS)
        newRequestIdentity()
        val metadata = Metadata()
            .byte(0, Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE)
            .byte(1, domain.number)
            .bytes(2, personalization).bytes(3, epoch).uint(4, expires)
            .uint(5, commandCounter).uint(7, ENCRYPT_RESPONSE_FLAG.toLong()).finish()
        val nonce = randomBytes(12)
        val aad = MessageDigest.getInstance("SHA-256").digest(metadata)
        var encrypted = ByteArray(0)
        try {
            encrypted = aesGcm(Cipher.ENCRYPT_MODE, sessionKey, nonce, aad, plaintext)
            val tagOffset = encrypted.size - 16
            require(tagOffset >= 0)
            val signature = Signatures.AES_GCM_Personalized_Signature_Data.newBuilder()
                .setEpoch(ByteString.copyFrom(epoch)).setNonce(ByteString.copyFrom(nonce))
                .setCounter(commandCounter.toInt()).setExpiresAt(expires.toInt())
                .setTag(ByteString.copyFrom(encrypted, tagOffset, 16))
            requestHash.fill(0)
            requestHash = ByteArray(17)
            requestHash[0] = Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()
            encrypted.copyInto(requestHash, 1, tagOffset)
            val request = requestBuilder().setFlags(ENCRYPT_RESPONSE_FLAG)
                .setProtobufMessageAsBytes(ByteString.copyFrom(encrypted, 0, tagOffset))
                .setSignatureData(
                    Signatures.SignatureData.newBuilder()
                        .setSignerIdentity(
                            Signatures.KeyIdentity.newBuilder().setPublicKey(ByteString.copyFrom(publicKey)),
                        )
                        .setAESGCMPersonalizedData(signature),
                ).build()
            state = State.STATUS
            phaseStartedAt = current
            return TeslaBleStep.Send(request.toByteArray(), TeslaBlePhase.READING_STATUS,
                actuator = purpose == TeslaBlePurpose.FRUNK_OPEN,
                wake = purpose == TeslaBlePurpose.VEHICLE_WAKE)
        } finally {
            metadata.fill(0)
            aad.fill(0)
            nonce.fill(0)
            encrypted.fill(0)
        }
    }

    private fun receiveStatus(payload: ByteArray, receivedAtElapsedRealtime: Long): TeslaBleStep {
        val response = UniversalMessage.RoutableMessage.parseFrom(payload)
        captureResponseObservation(response)
        if (onResponseObservation != null && response.hasSignatureData()) {
            responseInputKind = TeslaBleRxInputKind.ENCRYPTED_RESPONSE
        }
        if (!matchesRequest(response)) return TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS)
        if (purpose == TeslaBlePurpose.VEHICLE_WAKE && !response.hasSignatureData()) {
            if (response.signedMessageStatus.signedMessageFaultValue != 0 ||
                response.hasSignedMessageStatus() &&
                response.signedMessageStatus.operationStatus == UniversalMessage.OperationStatus_E.OPERATIONSTATUS_ERROR
            ) return finishAction(ActionReply.REJECTED)
            if (response.hasSignedMessageStatus() &&
                response.signedMessageStatus.operationStatus == UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT
            ) return TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS)
            // 정확한 payload 없는 명목 OK만 분리한다. 인증된 깨움 ACK로 승격하지 않는다.
            return if (isNominalAck(response)) finishWake(TeslaWakeReply.NOMINAL_ACK)
                else finishAction(ActionReply.UNCONFIRMED)
        }
        if (purpose == TeslaBlePurpose.FRUNK_OPEN && !response.hasSignatureData()) {
            // 공식 closure terminal은 payload 없는 nominal ACK도 허용한다. 이는 AEAD 인증된
            // ACK나 OPEN 증거가 아니므로 별도 fresh BODY 조회로만 넘어간다.
            return finishAction(ActionReply.UNCONFIRMED)
        }
        if (purpose == TeslaBlePurpose.READ_ONLY && query == TeslaBleQuery.BODY_STATUS) {
            // 공식 vcsec.go unmarshalVCSECResponse는 GetError를 먼저 확인하고 payload 없는
            // nominal 응답을 허용한다. 여기서는 실제 암호화 BODY가 오기 전에는 완료하지 않는다.
            // 이 header는 비인증 관측일 수 있으므로 인증 실패 evidence로 승격하지 않는다.
            if (response.signedMessageStatus.signedMessageFaultValue != 0 ||
                response.hasSignedMessageStatus() &&
                response.signedMessageStatus.operationStatus != UniversalMessage.OperationStatus_E.OPERATIONSTATUS_OK
            ) {
                responseDisposition = "RESPONSE_REJECTED"
                return fail("차량 응답이 상태 조회 오류를 보고했습니다")
            }
            if (isNominalAck(response) && !response.hasSignatureData()) {
                // ACK는 counter를 소비하거나 identity·phase/전체 기한을 갱신하지 않는다.
                responseDisposition = "ACK_WAITING"
                return TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS)
            }
        }
        if (response.payloadCase != UniversalMessage.RoutableMessage.PayloadCase.PROTOBUF_MESSAGE_AS_BYTES ||
            !response.hasSignatureData() || !response.signatureData.hasAESGCMResponseData()
        ) return fail("암호화된 차량 상태 응답이 필요합니다")
        val signature = response.signatureData.getAESGCMResponseData()
        require(signature.nonce.size() == 12 && signature.tag.size() == 16)
        val counter = unsigned(signature.counter)
        if (counter in responseCounters) {
            responseDisposition = "RESPONSE_REJECTED"
            return fail("중복 차량 상태 응답을 거부했습니다")
        }
        if (responseCounters.size >= MAX_RESPONSES) {
            responseDisposition = "RESPONSE_REJECTED"
            return fail("차량 상태 응답 한도를 초과했습니다")
        }
        val metadata = Metadata()
            .byte(0, Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE_VALUE)
            .byte(1, domain.number)
            .bytes(2, personalization).uint(5, counter).uint(7, unsigned(response.flags))
            .bytes(8, requestHash).uint(9, unsigned(response.signedMessageStatus.signedMessageFaultValue)).finish()
        val aad = MessageDigest.getInstance("SHA-256").digest(metadata)
        val encrypted = ByteArray(response.protobufMessageAsBytes.size() + 16)
        response.protobufMessageAsBytes.copyTo(encrypted, 0)
        signature.tag.copyTo(encrypted, response.protobufMessageAsBytes.size())
        val nonce = signature.nonce.toByteArray()
        var plaintext = ByteArray(0)
        try {
            plaintext = try {
                aesGcm(Cipher.DECRYPT_MODE, sessionKey, nonce, aad, encrypted)
            } catch (failure: Exception) {
                if (onResponseObservation != null) responseAead = TeslaBleRxAead.FAILED
                throw failure
            }
            if (onResponseObservation != null) responseAead = TeslaBleRxAead.VERIFIED
            responseCounters.add(counter) // 검증에 성공한 counter만 소비한다.
            if (purpose == TeslaBlePurpose.FRUNK_OPEN || purpose == TeslaBlePurpose.VEHICLE_WAKE) {
                if (response.signedMessageStatus.signedMessageFaultValue != 0) {
                    return finishAction(ActionReply.REJECTED)
                }
                if (response.hasSignedMessageStatus() &&
                    response.signedMessageStatus.operationStatus != UniversalMessage.OperationStatus_E.OPERATIONSTATUS_OK
                ) {
                    return if (response.signedMessageStatus.operationStatus == UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT) {
                        TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS)
                    } else finishAction(ActionReply.REJECTED)
                }
                return receiveActionResult(plaintext)
            }
            if (response.signedMessageStatus.signedMessageFaultValue != 0) {
                responseDisposition = "RESPONSE_REJECTED"
                return fail("차량이 상태 조회 요청을 거부했습니다")
            }
            return when (query) {
                TeslaBleQuery.BODY_STATUS -> receiveBodyStatus(plaintext, receivedAtElapsedRealtime)
                TeslaBleQuery.DRIVE_STATE -> receiveDriveState(plaintext, receivedAtElapsedRealtime)
            }
        } finally {
            metadata.fill(0)
            aad.fill(0)
            encrypted.fill(0)
            nonce.fill(0)
            plaintext.fill(0)
        }
    }

    private fun receiveActionResult(plaintext: ByteArray): TeslaBleStep {
        val response = Vcsec.FromVCSECMessage.parseFrom(plaintext)
        if (response.hasNominalError()) return finishAction(ActionReply.REJECTED)
        if (response.hasCommandStatus()) {
            // pinned executeClosureAction/executeRKEAction.done: CommandStatus는 terminal이 아니다.
            // WAIT/OK는 원 기한 안에서 대기하고 명시 ERROR만 거절한다.
            val status = response.commandStatus
            if (status.operationStatus == Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR ||
                status.hasSignedMessageStatus() && status.signedMessageStatus.signedMessageInformationValue != 0
            ) return finishAction(ActionReply.REJECTED)
            if (status.operationStatus != Vcsec.OperationStatus_E.OPERATIONSTATUS_OK &&
                status.operationStatus != Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT
            ) return finishAction(ActionReply.UNCONFIRMED)
            return TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS)
        }
        // 빈 AEAD plaintext는 nominal terminal이다. 물리 기상이나 OPEN의 증거는 아니다.
        return finishAction(ActionReply.ACKNOWLEDGED)
    }

    private fun finishAction(reply: ActionReply): TeslaBleStep {
        val step = when (purpose) {
            TeslaBlePurpose.FRUNK_OPEN -> {
                responseDisposition = when (reply) {
                    ActionReply.ACKNOWLEDGED -> "FRUNK_ACKNOWLEDGED"
                    ActionReply.REJECTED -> "RESPONSE_REJECTED"
                    ActionReply.UNCONFIRMED -> "FRUNK_UNCONFIRMED"
                }
                TeslaBleStep.FrunkResult(when (reply) {
                    ActionReply.ACKNOWLEDGED -> TeslaFrunkReply.ACKNOWLEDGED
                    ActionReply.REJECTED -> TeslaFrunkReply.REJECTED
                    ActionReply.UNCONFIRMED -> TeslaFrunkReply.UNCONFIRMED
                })
            }
            TeslaBlePurpose.VEHICLE_WAKE -> return finishWake(when (reply) {
                ActionReply.ACKNOWLEDGED -> TeslaWakeReply.ACKNOWLEDGED
                ActionReply.REJECTED -> TeslaWakeReply.REJECTED
                ActionReply.UNCONFIRMED -> TeslaWakeReply.UNCONFIRMED
            })
            TeslaBlePurpose.READ_ONLY -> error("Read-only conversation cannot finish an action")
        }
        state = State.TERMINAL
        eraseEphemeral()
        return step
    }

    private fun finishWake(reply: TeslaWakeReply): TeslaBleStep.WakeResult {
        responseDisposition = when (reply) {
            TeslaWakeReply.ACKNOWLEDGED -> "WAKE_ACKNOWLEDGED"
            TeslaWakeReply.REJECTED -> "RESPONSE_REJECTED"
            TeslaWakeReply.UNCONFIRMED -> "WAKE_UNCONFIRMED"
            TeslaWakeReply.NOMINAL_ACK -> "WAKE_NOMINAL_ACK"
        }
        state = State.TERMINAL
        eraseEphemeral()
        return TeslaBleStep.WakeResult(reply)
    }

    private fun receiveBodyStatus(plaintext: ByteArray, receivedAtElapsedRealtime: Long): TeslaBleStep {
        val vcsec = Vcsec.FromVCSECMessage.parseFrom(plaintext)
        if (vcsec.hasNominalError()) {
            responseDisposition = "RESPONSE_REJECTED"
            return fail("차량이 상태 조회를 거부했습니다")
        }
        if (!vcsec.hasVehicleStatus()) {
            responseDisposition = "STATUS_INCOMPLETE"
            return TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS)
        }
        val status = vcsec.vehicleStatus
        val lock = if (status.hasVehicleLockState() && status.vehicleLockStateValue in 0..3) {
            status.vehicleLockStateValue
        } else null
        val closures = status.closureStatuses
        val frontTrunk = if (status.hasClosureStatuses() && closures.hasFrontTrunk() &&
            closures.frontTrunkValue in 0..6
        ) closures.frontTrunkValue else null
        return complete(TeslaReadOnlyStatus(lock, frontTrunk,
            bodyVehicleSleepStatus = status.vehicleSleepStatusValue.takeIf { it >= 0 },
            bodyReceivedAtElapsedRealtime = receivedAtElapsedRealtime))
    }

    private fun receiveDriveState(plaintext: ByteArray, receivedAtElapsedRealtime: Long): TeslaBleStep {
        val response = CarServer.Response.parseFrom(plaintext)
        // 공식 getCarServerResponse의 proto3 계약: ActionStatus 생략은 result 기본값 OK다.
        // AEAD 검증과 실제 DriveState 존재는 별도로 필수이며 알 수 없는 result는 거부한다.
        if (response.actionStatus.result != CarServer.OperationStatus_E.OPERATIONSTATUS_OK) {
            responseDisposition = "RESPONSE_REJECTED"
            return fail("차량이 주행 상태 조회를 완료하지 않았습니다")
        }
        // 차량이 제공한 result_reason/plain_text는 식별자 등을 포함할 수 있어 노출하지 않는다.
        if (!response.hasVehicleData() || !response.vehicleData.hasDriveState()) {
            responseDisposition = "STATUS_INCOMPLETE"
            return fail("차량 응답에 주행 상태가 없습니다")
        }
        val drive = response.vehicleData.driveState
        val gear = when (drive.shiftState.typeCase) {
            Vehicle.ShiftState.TypeCase.P -> TeslaGear.P
            Vehicle.ShiftState.TypeCase.R -> TeslaGear.R
            Vehicle.ShiftState.TypeCase.N -> TeslaGear.N
            Vehicle.ShiftState.TypeCase.D -> TeslaGear.D
            else -> TeslaGear.UNKNOWN
        }
        val timestamp = if (drive.hasTimestamp() &&
            drive.timestamp.seconds in MIN_TIMESTAMP_SECONDS..MAX_TIMESTAMP_SECONDS &&
            drive.timestamp.nanos in 0..999_999_999
        ) drive.timestamp.seconds * 1_000L + drive.timestamp.nanos / 1_000_000L else null
        if (onResponseObservation != null) {
            responseDriveShiftState = if (!drive.hasShiftState()) "ABSENT" else when (drive.shiftState.typeCase) {
                Vehicle.ShiftState.TypeCase.INVALID -> "INVALID"
                Vehicle.ShiftState.TypeCase.P -> "P"
                Vehicle.ShiftState.TypeCase.R -> "R"
                Vehicle.ShiftState.TypeCase.N -> "N"
                Vehicle.ShiftState.TypeCase.D -> "D"
                Vehicle.ShiftState.TypeCase.SNA -> "SNA"
                // lite의 unknown 필드는 직접 읽을 수 없다. 재직렬화 없이 빈 oneof와 미지원 wire 필드를 구분한다.
                else -> if (drive.shiftState.serializedSize == 0) "EMPTY" else "UNRECOGNIZED"
            }
            responseDriveTimestampStatus = when {
                !drive.hasTimestamp() -> "ABSENT"
                timestamp == null -> "INVALID"
                timestamp <= 0 -> "NON_POSITIVE"
                else -> "VALID"
            }
        }
        return complete(TeslaReadOnlyStatus(
            driveState = TeslaDriveState(gear, timestamp, receivedAtElapsedRealtime),
            driveShiftStateInvalid = drive.shiftState.typeCase == Vehicle.ShiftState.TypeCase.INVALID,
        ))
    }


    private fun complete(status: TeslaReadOnlyStatus): TeslaBleStep.Complete {
        responseDisposition = "STATUS_VERIFIED"
        state = State.TERMINAL
        eraseEphemeral()
        return TeslaBleStep.Complete(status)
    }

    private fun captureResponseObservation(message: UniversalMessage.RoutableMessage) {
        if (onResponseObservation == null) return
        responseInputKind = TeslaBleRxInputKind.ROUTABLE_MESSAGE
        val fromPresent = message.hasFromDestination()
        val toPresent = message.hasToDestination()
        val signedStatusPresent = message.hasSignedMessageStatus()
        responseSignedStatusPresent = signedStatusPresent
        responseSignedMessageFault = if (signedStatusPresent) message.signedMessageStatus.signedMessageFaultValue else null
        responseOperationStatus = if (signedStatusPresent) message.signedMessageStatus.operationStatusValue else null
        responseFromPresent = fromPresent
        responseFromKind = if (fromPresent) destinationKind(message.fromDestination) else null
        responseFromDomain = if (fromPresent &&
            message.fromDestination.subDestinationCase == UniversalMessage.Destination.SubDestinationCase.DOMAIN
        ) message.fromDestination.domainValue else null
        responseToPresent = toPresent
        responseToKind = if (toPresent) destinationKind(message.toDestination) else null
        // 다음 Send의 identity 회전·종료 시 소거 전에 수신 시점의 비인증 일치 여부만 보존한다.
        val uuidPresent = !message.requestUuid.isEmpty
        responseRequestUuidPresent = uuidPresent
        responseRequestUuidMatches = if (uuidPresent && requestUuid.isNotEmpty()) {
            bytesMatch(message.requestUuid, requestUuid)
        } else null
        responseToRouteMatches = if (toPresent && route.isNotEmpty()) {
            message.toDestination.subDestinationCase == UniversalMessage.Destination.SubDestinationCase.ROUTING_ADDRESS &&
                bytesMatch(message.toDestination.routingAddress, route)
        } else null
        responseRequestCorrelation = try {
            observedRequestCorrelation(message)
        } catch (_: Exception) {
            null
        }
        responsePayloadKind = when (message.payloadCase) {
            UniversalMessage.RoutableMessage.PayloadCase.SESSION_INFO -> "SESSION_INFO"
            UniversalMessage.RoutableMessage.PayloadCase.SESSION_INFO_REQUEST -> "SESSION_INFO_REQUEST"
            UniversalMessage.RoutableMessage.PayloadCase.PROTOBUF_MESSAGE_AS_BYTES -> "PROTOBUF_MESSAGE_AS_BYTES"
            UniversalMessage.RoutableMessage.PayloadCase.PAYLOAD_NOT_SET -> "UNSET"
            else -> "UNKNOWN"
        }
    }

    /** header-only 분류다. 이전 요청을 수락하거나 이전 인증 context로 복호하지 않는다. */
    private fun observedRequestCorrelation(message: UniversalMessage.RoutableMessage): TeslaBleRxRequestCorrelation {
        if (!message.hasFromDestination() ||
            message.fromDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.DOMAIN ||
            message.fromDestination.domain != domain ||
            !message.hasToDestination() ||
            message.toDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.ROUTING_ADDRESS
        ) return TeslaBleRxRequestCorrelation.UNMATCHED
        if (requestIdentityActive && observedIdentityMatches(message, route, requestUuid)) {
            return TeslaBleRxRequestCorrelation.CURRENT
        }
        val oldRoute = previousRoute
        val oldUuid = previousRequestUuid
        return if (oldRoute != null && oldUuid != null && observedIdentityMatches(message, oldRoute, oldUuid)) {
            TeslaBleRxRequestCorrelation.PREVIOUS
        } else TeslaBleRxRequestCorrelation.UNMATCHED
    }

    private fun observedIdentityMatches(
        message: UniversalMessage.RoutableMessage,
        identityRoute: ByteArray,
        identityUuid: ByteArray,
    ): Boolean = bytesMatch(message.toDestination.routingAddress, identityRoute) &&
        (bytesMatch(message.requestUuid, identityUuid) ||
            domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY && message.requestUuid.isEmpty)

    /** 동일 길이는 끝까지 비교한다. 상관관계 확인에도 ByteString·배열 복사나 해시를 만들지 않는다. */
    private fun bytesMatch(observed: ByteString, expected: ByteArray): Boolean {
        if (observed.size() != expected.size) return false
        var difference = 0
        var index = 0
        while (index < expected.size) {
            difference = difference or (observed.byteAt(index).toInt() xor expected[index].toInt())
            index++
        }
        return difference == 0
    }

    private fun destinationKind(destination: UniversalMessage.Destination): String =
        when (destination.subDestinationCase) {
            UniversalMessage.Destination.SubDestinationCase.DOMAIN -> "DOMAIN"
            UniversalMessage.Destination.SubDestinationCase.ROUTING_ADDRESS -> "ROUTING_ADDRESS"
            UniversalMessage.Destination.SubDestinationCase.SUBDESTINATION_NOT_SET -> "UNSET"
            else -> "UNKNOWN"
        }

    private fun captureDomainMismatch(message: UniversalMessage.RoutableMessage) {
        if (onResponseObservation == null) return
        responseDomainMismatchReason = when {
            !message.hasFromDestination() -> "FROM_MISSING"
            message.fromDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.DOMAIN ->
                "FROM_NOT_DOMAIN"
            else -> "DOMAIN_DIFFERENT"
        }
        responseDomainMismatchVcsecSleepStatus = vcsecBroadcastSleepStatus(message)
    }

    /**
     * 진단 전용: domain2(VCSEC)의 UUID 없는 DOMAIN 수신처 broadcast에서 sleep 상태 enum 정수만 읽는다.
     * 비인증 값이므로 P 판정·기상 판정·제어 허용에 절대 쓰지 않는다. 파싱 실패는 무시한다.
     */
    private fun vcsecBroadcastSleepStatus(message: UniversalMessage.RoutableMessage): Int? {
        if (!message.hasFromDestination() ||
            message.fromDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.DOMAIN ||
            message.fromDestination.domain != UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY ||
            !message.hasToDestination() ||
            message.toDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.DOMAIN ||
            !message.uuid.isEmpty ||
            message.payloadCase != UniversalMessage.RoutableMessage.PayloadCase.PROTOBUF_MESSAGE_AS_BYTES
        ) return null
        val vcsec = try {
            Vcsec.FromVCSECMessage.parseFrom(message.protobufMessageAsBytes)
        } catch (_: Exception) {
            return null
        }
        return if (vcsec.hasVehicleStatus()) vcsec.vehicleStatus.vehicleSleepStatusValue else null
    }

    private fun matchesRequest(message: UniversalMessage.RoutableMessage): Boolean {
        if (!message.hasFromDestination() ||
            message.fromDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.DOMAIN ||
            message.fromDestination.domain != domain
        ) {
            responseDisposition = "DOMAIN_MISMATCH"
            captureDomainMismatch(message)
            return false
        }
        if (!message.hasToDestination() ||
            message.toDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.ROUTING_ADDRESS ||
            !bytesMatch(message.toDestination.routingAddress, route)
        ) {
            responseDisposition = "ROUTE_MISMATCH"
            return false
        }
        // 공식 dispatcher.process/Send: UUID 생략 특례는 VCSEC(domain2)만 허용한다.
        // Infotainment(domain3)는 HELLO와 응답 모두 정확한 request_uuid가 필요하다.
        if (bytesMatch(message.requestUuid, requestUuid) ||
            domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY && message.requestUuid.isEmpty
        ) return true
        responseDisposition = "REQUEST_UUID_MISMATCH"
        return false
    }

    private fun newRequestIdentity() {
        previousRoute?.fill(0)
        previousRequestUuid?.fill(0)
        previousRoute = null
        previousRequestUuid = null
        if (onResponseObservation != null && requestIdentityActive) {
            previousRoute = route
            previousRequestUuid = requestUuid
        } else {
            route.fill(0)
            requestUuid.fill(0)
        }
        requestIdentityActive = false
        route = randomBytes(16)
        requestUuid = randomBytes(16)
        requestIdentityActive = true
        // 공식 protocol.md Counter verification은 동일 요청의 중복만 금지한다.
        // 새 요청은 route/UUID/request hash로 구분하며 이전 응답의 counter를 상속하지 않는다.
        responseCounters.clear()
    }

    private fun requestBuilder(): UniversalMessage.RoutableMessage.Builder =
        UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                UniversalMessage.Destination.newBuilder().setDomain(domain),
            )
            .setFromDestination(UniversalMessage.Destination.newBuilder().setRoutingAddress(ByteString.copyFrom(route)))
            .setUuid(ByteString.copyFrom(requestUuid))

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    private fun fail(reason: String, authEvidence: TeslaBleAuthEvidence? = null): TeslaBleStep.Failed {
        state = State.TERMINAL
        eraseEphemeral()
        return TeslaBleStep.Failed(reason, authEvidence)
    }

    private fun eraseEphemeral() {
        personalization.fill(0)
        publicKey.fill(0)
        route.fill(0)
        requestUuid.fill(0)
        requestIdentityActive = false
        previousRoute?.fill(0)
        previousRequestUuid?.fill(0)
        previousRoute = null
        previousRequestUuid = null
        sessionKey.fill(0)
        epoch.fill(0)
        requestHash.fill(0)
        credential = null
        responseCounters.clear()
        vehicleClock = 0
        commandCounter = 0
        sessionAt = 0
    }

    @Synchronized
    override fun close() {
        state = State.CLOSED
        eraseEphemeral()
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 180_000L
        const val RESPONSE_TIMEOUT_MS = 15_000L
        const val SESSION_RETRANSMIT_WINDOW_MAX_MS = 30_000L
        const val COMMAND_TTL_SECONDS = 10L
        const val UINT32_MAX = 0xffff_ffffL
        const val MAX_EPOCH_SECONDS = 1L shl 30
        const val MAX_RESPONSES = 32
        // google.protobuf.Timestamp의 정규 범위. millis 변환은 이 범위에서 overflow하지 않는다.
        const val MIN_TIMESTAMP_SECONDS = -62_135_596_800L
        const val MAX_TIMESTAMP_SECONDS = 253_402_300_799L
        const val ENCRYPT_RESPONSE_FLAG = 1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE_VALUE
        const val SESSION_KEY_BYTES = 16
        const val SESSION_INFO_TAG_BYTES = 32
        val SESSION_INFO_LABEL = "session info".toByteArray(Charsets.US_ASCII)
    }
}

private fun unsigned(value: Int): Long = value.toLong() and 0xffff_ffffL

private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        data.forEach(::update)
        doFinal()
    }

private fun aesGcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(input)
    }

/** 공식 TLV metadata만 직렬화한다. 공개 메시지는 lite parser, FiRa body는 별도 bounded codec을 사용. */
private class Metadata {
    private val buffer = ByteArray(128)
    private var offset = 0
    private var previousTag = -1

    private fun header(tag: Int, length: Int) {
        require(tag > previousTag && tag < 255 && length <= 255 && offset + length + 3 <= buffer.size)
        previousTag = tag
        buffer[offset++] = tag.toByte()
        buffer[offset++] = length.toByte()
    }

    fun byte(tag: Int, value: Int): Metadata {
        require(value in 0..255)
        header(tag, 1)
        buffer[offset++] = value.toByte()
        return this
    }

    fun uint(tag: Int, value: Long): Metadata {
        require(value in 0..0xffff_ffffL)
        header(tag, 4)
        buffer[offset++] = (value ushr 24).toByte()
        buffer[offset++] = (value ushr 16).toByte()
        buffer[offset++] = (value ushr 8).toByte()
        buffer[offset++] = value.toByte()
        return this
    }

    fun bytes(tag: Int, value: ByteArray): Metadata {
        header(tag, value.size)
        value.copyInto(buffer, offset)
        offset += value.size
        return this
    }

    fun finish(): ByteArray {
        buffer[offset++] = 255.toByte()
        val result = buffer.copyOf(offset)
        buffer.fill(0)
        return result
    }
}

internal const val TESLA_BLE_MAX_FRAME_BYTES = 4_096
internal class TeslaBleFrameException : IllegalArgumentException("차량 BLE 프레임이 올바르지 않습니다")

/** 2-byte BE framing. 프레임 오류 후 clear 전에는 재동기화하지 않는다. */
internal class TeslaBleFrameDecoder {
    private val buffer = ByteArray(TESLA_BLE_MAX_FRAME_BYTES)
    private var headerBytes = 0
    private var expected = 0
    private var received = 0
    private var poisoned = false

    @Synchronized
    fun append(bytes: ByteArray): List<ByteArray> {
        if (poisoned) throw TeslaBleFrameException()
        if (bytes.size > (TESLA_BLE_MAX_FRAME_BYTES + 2) * 16) malformed()
        if (bytes.isEmpty()) return emptyList()
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            while (headerBytes < 2 && offset < bytes.size) {
                expected = (expected shl 8) or (bytes[offset++].toInt() and 255)
                headerBytes++
            }
            if (headerBytes < 2) break
            if (expected !in 0..TESLA_BLE_MAX_FRAME_BYTES) malformed(frames)
            val count = minOf(expected - received, bytes.size - offset)
            bytes.copyInto(buffer, received, offset, offset + count)
            received += count
            offset += count
            if (received == expected) {
                if (frames.size == 16) malformed(frames)
                frames.add(buffer.copyOf(expected))
                buffer.fill(0, 0, expected)
                headerBytes = 0
                expected = 0
                received = 0
            }
        }
        return frames
    }

    /** 관측 전용. poison·버퍼·길이·재동기화 정책을 바꾸지 않는다. */
    @Synchronized
    fun hasIncompleteFrame(): Boolean = headerBytes != 0

    private fun malformed(frames: List<ByteArray> = emptyList()): Nothing {
        frames.forEach { it.fill(0) }
        clear()
        poisoned = true
        throw TeslaBleFrameException()
    }

    @Synchronized
    fun clear() {
        buffer.fill(0)
        headerBytes = 0
        expected = 0
        received = 0
        poisoned = false
    }
}
