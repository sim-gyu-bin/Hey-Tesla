package com.heytesla.app

import com.google.protobuf.ByteString
import com.tesla.generated.keys.Keys
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 소스만 추가. 테스트 키는 실행 시 JVM JCA로만 생성하며 저장/출력/차량 등록하지 않는다. */
class TeslaBleProtocolTest {
    @Test fun authenticHandshakeProducesOnlyEncryptedReadOnlyStatusRequest() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val decoded = fixture.decryptRequest(request)
            assertEquals(Vcsec.UnsignedMessage.SubMessageCase.INFORMATIONREQUEST, decoded.subMessageCase)
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                decoded.informationRequest.informationRequestType)
            val signature = request.signatureData.getAESGCMPersonalizedData()
            assertTrue((signature.counter.toLong() and 0xffff_ffffL) > 6L)
            assertTrue(signature.expiresAt in 101..130)
            val result = fixture.conversation.receive(fixture.status(request, lock = 1, frontTrunk = 0).toByteArray())
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0)), result)
        }
    }

    @Test fun changedSessionClockWithOriginalHmacCannotAuthorizeStatus() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val authentic = fixture.handshake(hello)
            val info = Signatures.SessionInfo.parseFrom(authentic.sessionInfo).toBuilder().setClockTime(500).build()
            assertFailed(fixture.conversation.receive(authentic.toBuilder().setSessionInfo(info.toByteString()).build().toByteArray()))
            assertFailed(fixture.conversation.receive(authentic.toByteArray()))
        }
    }

    @Test fun changedSessionHmacCannotAuthorizeStatus() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val response = fixture.handshake(hello)
            val changed = response.signatureData.sessionInfoTag.tag.toByteArray().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            val invalid = response.toBuilder().setSignatureData(response.signatureData.toBuilder()
                .setSessionInfoTag(Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(changed)))).build()
            assertFailed(fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun replayedHandshakeFailsEvenWhenRouteAndOptionalRequestUuidAreReplaced() {
        VehicleFixture().use { fixture ->
            val firstHello = fixture.start()
            val firstResponse = fixture.handshake(firstHello)
            val replacement = TeslaBleConversation(fixture.client, fixture.vin) { fixture.clock }
            replacement.use {
                val secondHello = parseSend(replacement.start(false))
                val replay = firstResponse.toBuilder().setToDestination(secondHello.fromDestination).clearRequestUuid().build()
                assertFailed(replacement.receive(replay.toByteArray()))
            }
        }
    }

    @Test fun vcsecMayOmitRequestUuidButSessionChallengeStillMustVerify() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toBuilder().clearRequestUuid().build().toByteArray()))
            val response = fixture.status(request).toBuilder().clearRequestUuid().build()
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0)), fixture.conversation.receive(response.toByteArray()))
        }
    }

    @Test fun foreignDomainRouteAndRequestUuidCannotCompleteCurrentRequest() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val authentic = fixture.status(request)
            val otherDomain = authentic.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)).build()
            val otherRoute = authentic.toBuilder().setToDestination(UniversalMessage.Destination.newBuilder()
                .setRoutingAddress(ByteString.copyFrom(ByteArray(16)))).build()
            val otherUuid = authentic.toBuilder().setRequestUuid(ByteString.copyFrom(ByteArray(16))).build()
            for (crossed in listOf(otherDomain, otherRoute, otherUuid)) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS), fixture.conversation.receive(crossed.toByteArray()))
            }
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0)), fixture.conversation.receive(authentic.toByteArray()))
        }
    }

    @Test fun plaintextStatusIsNeverAcceptedAsProbeSuccess() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val response = fixture.baseResponse(request)
                .setProtobufMessageAsBytes(fixture.vehicleStatus(1, 0).toByteString()).build()
            assertFailed(fixture.conversation.receive(response.toByteArray()))
        }
    }

    @Test fun changedCiphertextFailsAuthentication() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val response = fixture.status(request)
            val ciphertext = response.protobufMessageAsBytes.toByteArray().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            val invalid = response.toBuilder().setProtobufMessageAsBytes(ByteString.copyFrom(ciphertext)).build()
            assertFailed(fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun changedAuthenticatedResponseFlagsFailAuthentication() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val invalid = fixture.status(request).toBuilder().setFlags(2).build()
            assertFailed(fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun wrongRequestHashFailsEvenWithCorrectKeyRouteAndUuid() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val invalid = fixture.encryptResponse(request, fixture.vehicleStatus(1, 0), counter = 8,
                requestHash = ByteArray(17))
            assertFailed(fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun replayedResponseCannotCrossFreshRequestsSharingTheSameEcdhKey() {
        VehicleFixture().use { fixture ->
            val first = fixture.authenticate()
            val firstResponse = fixture.status(first)
            val replacement = TeslaBleConversation(fixture.client, fixture.vin) { fixture.clock }
            replacement.use {
                val secondHello = parseSend(replacement.start(false))
                val second = parseSend(replacement.receive(fixture.handshake(secondHello).toByteArray()))
                val replay = firstResponse.toBuilder().setToDestination(second.fromDestination)
                    .setRequestUuid(second.uuid).build()
                assertFailed(replacement.receive(replay.toByteArray()))
            }
        }
    }

    @Test fun duplicateAuthenticatedCounterCannotBecomeStatusSuccess() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val wait = fixture.encryptResponse(request, waitingResponse(), counter = 8)
            assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS), fixture.conversation.receive(wait.toByteArray()))
            assertFailed(fixture.conversation.receive(fixture.status(request, counter = 8).toByteArray()))
        }
    }

    @Test fun uniqueResponseCountersMayArriveOutOfOrderButCompletionIsOneShot() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS),
                fixture.conversation.receive(fixture.encryptResponse(request, waitingResponse(), counter = 9).toByteArray()))
            val response = fixture.status(request, counter = 8)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0)), fixture.conversation.receive(response.toByteArray()))
            assertFailed(fixture.conversation.receive(response.toByteArray()))
        }
    }

    @Test fun authenticatedFaultIsFailureNotStatusSuccess() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val fault = fixture.encryptResponse(request, fixture.vehicleStatus(1, 0), counter = 8,
                fault = UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES_VALUE)
            assertFailed(fixture.conversation.receive(fault.toByteArray()))
        }
    }

    @Test fun nominalHelloAckKeepsWaitingAndStillAcceptsFollowingSignedSessionInfo() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
            // ACK는 route/request_uuid 상관관계 identity를 회전시키지 않는다.
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val decoded = fixture.decryptRequest(request)
            assertEquals(Vcsec.UnsignedMessage.SubMessageCase.INFORMATIONREQUEST, decoded.subMessageCase)
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                decoded.informationRequest.informationRequestType)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0)),
                fixture.conversation.receive(fixture.status(request).toByteArray()))
        }
    }

    @Test fun repeatedNominalHelloAcksNeverExtendTheHandshakeDeadline() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            fixture.clock += 14_999
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
            // ACK가 phase 시작 시각을 갱신했다면 25_000ms에도 계속 대기했을 것이다.
            fixture.clock += 1
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            fixture.clock += 10_000
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
            // 같은 ACK를 반복해도 대기 유예가 갱신되지 않는다.
            fixture.clock += 10_000
            assertFailed(fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
        }
    }

    @Test fun helloMessageFaultIsRejectedBeforeAckOrSessionInfoJudgement() {
        for ((fault, evidence) in listOf(
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID_VALUE to
                TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INACTIVE_KEY_VALUE to
                TeslaBleAuthEvidence.REQUEST_REJECTED,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_REQUIRES_RESPONSE_ENCRYPTION_VALUE to
                TeslaBleAuthEvidence.REQUEST_REJECTED,
        )) {
            // fault가 있으면 OperationStatus OK를 포함한 ACK 모양도 대기가 아니다.
            assertHelloRejected(evidence) { fixture, hello -> fixture.helloAck(hello, fault = fault) }
        }
        // 서명이 유효한 session info가 함께 와도 fault가 우선한다.
        assertHelloRejected(TeslaBleAuthEvidence.REQUEST_REJECTED) { fixture, hello ->
            val info = fixture.sessionInfo(SESSION_INFO_OK)
            fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello)).toBuilder()
                .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                    .setSignedMessageFaultValue(UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INACTIVE_KEY_VALUE))
                .build()
        }
    }

    @Test fun helloAckShapeWithNonOkOperationStatusOrOtherPayloadIsRejected() {
        for (operation in listOf(
            UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT_VALUE,
            UniversalMessage.OperationStatus_E.OPERATIONSTATUS_ERROR_VALUE,
            7,
        )) {
            assertHelloRejected(TeslaBleAuthEvidence.OPERATION_REJECTED) { fixture, hello ->
                fixture.helloAck(hello, operation = operation)
            }
        }
        // payload도 signedMessageStatus도 없는 응답과, session info 대신 다른 payload는 대기가 아니다.
        assertHelloRejected(TeslaBleAuthEvidence.UNEXPECTED_PAYLOAD) { fixture, hello ->
            fixture.baseResponse(hello).build()
        }
        assertHelloRejected(TeslaBleAuthEvidence.UNEXPECTED_PAYLOAD) { fixture, hello ->
            fixture.baseResponse(hello).setProtobufMessageAsBytes(fixture.vehicleStatus(1, 0).toByteString()).build()
        }
    }

    @Test fun keyNotOnWhitelistSessionInfoFailsAsReportedMissingKeyEvenWithoutSignature() {
        for (signed in listOf(false, true)) {
            // status 1은 인증 전 응답의 미등록 보고이며, 서명이 없어도 인증 성공으로 취급하지 않는다.
            assertHelloRejected(TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED) { fixture, hello ->
                val info = fixture.sessionInfo(Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST_VALUE)
                fixture.sessionInfoResponse(hello, info, if (signed) fixture.sessionInfoTag(info, hello) else null)
            }
        }
    }

    @Test fun unknownSessionInfoStatusIsUnsupportedRatherThanReportedMissingKey() {
        for (status in listOf(2, 7)) {
            // 서명/HMAC이 유효해도 알 수 없는 status를 미등록 보고로 오인하지 않는다.
            assertHelloRejected(TeslaBleAuthEvidence.SESSION_STATUS_UNSUPPORTED) { fixture, hello ->
                val info = fixture.sessionInfo(status)
                fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello))
            }
        }
    }

    @Test fun invalidSignedSessionInfoBoundaryNeverSendsStatusRequest() {
        assertHelloRejected(TeslaBleAuthEvidence.SIGNATURE_MISSING) { fixture, hello ->
            fixture.sessionInfoResponse(hello, fixture.sessionInfo(SESSION_INFO_OK), null)
        }
        assertHelloRejected(TeslaBleAuthEvidence.TAG_MISSING) { fixture, hello ->
            fixture.baseResponse(hello).setSessionInfo(ByteString.copyFrom(fixture.sessionInfo(SESSION_INFO_OK)))
                .setSignatureData(Signatures.SignatureData.getDefaultInstance()).build()
        }
        assertHelloRejected(TeslaBleAuthEvidence.TAG_LENGTH_INVALID) { fixture, hello ->
            val info = fixture.sessionInfo(SESSION_INFO_OK)
            fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello).copyOf(31))
        }
        for (epochBytes in listOf(15, 17)) {
            assertHelloRejected(TeslaBleAuthEvidence.PARAMETERS_INVALID) { fixture, hello ->
                val info = fixture.sessionInfo(SESSION_INFO_OK, epochBytes = epochBytes)
                fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello))
            }
        }
        assertHelloRejected(TeslaBleAuthEvidence.PARAMETERS_INVALID) { fixture, hello ->
            val info = fixture.sessionInfo(SESSION_INFO_OK, publicKeyBytes = 64)
            fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello))
        }
        assertHelloRejected(TeslaBleAuthEvidence.HMAC_MISMATCH) { fixture, hello ->
            fixture.sessionInfoResponse(hello, fixture.sessionInfo(SESSION_INFO_OK), ByteArray(32))
        }
    }

    @Test fun missingAndUnknownStatusFieldsRemainUnknownRatherThanEnumZero() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val absent = fixture.vehicleStatus(null, null)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(null, null)),
                fixture.conversation.receive(fixture.encryptResponse(request, absent, 8).toByteArray()))
        }
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val unknown = fixture.vehicleStatus(99, 99)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(null, null)),
                fixture.conversation.receive(fixture.encryptResponse(request, unknown, 8).toByteArray()))
        }
    }

    @Test fun legacyRegistrationCannotTreatEmptyGenericOrCardWaitAsTerminalSuccess() {
        VehicleFixture().use { fixture ->
            val wire = (fixture.conversation.start(true) as TeslaBleStep.Send).payload
            val envelope = Vcsec.ToVCSECMessage.parseFrom(wire)
            val request = Vcsec.UnsignedMessage.parseFrom(envelope.signedMessage.protobufMessageAsBytes)
            assertEquals(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY, envelope.signedMessage.signatureType)
            assertEquals(Keys.Role.ROLE_DRIVER, request.whitelistOperation.addKeyToWhitelistAndAddPermissions.keyRole)
            assertEquals(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE, request.whitelistOperation.metadataForKey.keyFormFactor)
            for (response in listOf(
                Vcsec.FromVCSECMessage.getDefaultInstance(),
                genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK),
                genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR),
                genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT),
                registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT, 0),
            )) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.WAITING_FOR_CARD),
                    fixture.conversation.receive(response.toByteArray()))
                assertFalse(fixture.conversation.registrationReported)
            }
            val next = fixture.conversation.receive(registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK, 0).toByteArray())
            assertTrue(next is TeslaBleStep.Send && next.phase == TeslaBlePhase.HANDSHAKING)
            assertTrue(fixture.conversation.registrationReported)
            // legacy terminal 보고 뒤에도 새 인증 검증 없이 Complete를 반환하지 않는다.
            assertFailed(fixture.conversation.receive(byteArrayOf(0)))
            assertTrue(fixture.conversation.registrationReported)
        }
    }

    @Test fun duplicateKeyRegistrationIsRejectedRatherThanClaimedPaired() {
        VehicleFixture().use { fixture ->
            fixture.conversation.start(true)
            val refused = registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR,
                Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_ATTEMPTING_TO_ADD_KEY_THAT_IS_ALREADY_ON_THE_WHITELIST_VALUE)
            assertFailed(fixture.conversation.receive(refused.toByteArray()))
            assertFalse(fixture.conversation.registrationReported)
        }
    }

    @Test fun contradictoryRegistrationErrorCannotBecomeSuccess() {
        VehicleFixture().use { fixture ->
            fixture.conversation.start(true)
            assertFailed(fixture.conversation.receive(registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR, 0).toByteArray()))
            assertFalse(fixture.conversation.registrationReported)
        }
    }

    @Test fun delayedHandshakeAndBackwardClockFailWithoutSendingStatus() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val response = fixture.handshake(hello)
            fixture.clock += 180_001
            assertFailed(fixture.conversation.receive(response.toByteArray()))
        }
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            fixture.clock--
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
    }

    @Test fun counterRolloverAndClosedConversationCannotTransmit() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            assertFailed(fixture.conversation.receive(fixture.handshake(hello, counter = -1).toByteArray()))
        }
        VehicleFixture().use { fixture ->
            fixture.start()
            fixture.conversation.close()
            assertFailed(fixture.conversation.start(false))
            assertFailed(fixture.conversation.receive(byteArrayOf(1)))
        }
    }

    @Test fun fragmentedAndCoalescedFramesPreserveBoundariesAtEverySplit() {
        val first = byteArrayOf(1, 2, 3, 4, 5)
        val second = byteArrayOf(6, 7, 8)
        val stream = framed(first) + framed(second)
        for (split in 1 until stream.size) {
            val decoder = TeslaBleFrameDecoder()
            val frames = decoder.append(stream.copyOfRange(0, split)) + decoder.append(stream.copyOfRange(split, stream.size))
            assertEquals(2, frames.size)
            assertArrayEquals(first, frames[0])
            assertArrayEquals(second, frames[1])
        }
    }

    @Test fun invalidFramePoisonsDecoderUntilExplicitClearAndDiscardsPartialBytes() {
        val decoder = TeslaBleFrameDecoder()
        assertTrue(decoder.append(byteArrayOf(0, 3, 1)).isEmpty())
        decoder.clear()
        assertThrows(TeslaBleFrameException::class.java) { decoder.append(byteArrayOf(0x10, 0x01)) }
        assertThrows(TeslaBleFrameException::class.java) { decoder.append(framed(byteArrayOf(2))) }
        decoder.clear()
        assertArrayEquals(byteArrayOf(2), decoder.append(framed(byteArrayOf(2))).single())
        decoder.clear()
        val frames = decoder.append(framed(byteArrayOf()) + framed(byteArrayOf(3)))
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(), frames[0])
        assertArrayEquals(byteArrayOf(3), frames[1])
    }

    /**
     * HELLO 응답이 typed evidence로 실패하고, 상태 GET을 보내지 않으며, 대화를 종결시키는지 검사한다.
     */
    private fun assertHelloRejected(
        expected: TeslaBleAuthEvidence,
        build: (VehicleFixture, UniversalMessage.RoutableMessage) -> UniversalMessage.RoutableMessage,
    ) {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val step = fixture.conversation.receive(build(fixture, hello).toByteArray())
            assertEvidence(expected, step)
            assertFalse("상태 GET을 보내면 안 된다", step is TeslaBleStep.Send)
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
    }

    private class VehicleFixture : AutoCloseable {
        val vin = ByteArray(17) { '0'.code.toByte() }
        val client = JvmCredential(newPair())
        private val vehicle = newPair()
        private val sessionKey = derive(vehicle, client.publicKeyBytes())
        var clock = 10_000L
        val conversation = TeslaBleConversation(client, vin) { clock }
        private val epoch = ByteArray(16) { 7 }

        fun start(): UniversalMessage.RoutableMessage = parseSend(conversation.start(false))

        fun authenticate(): UniversalMessage.RoutableMessage {
            val hello = start()
            return parseSend(conversation.receive(handshake(hello).toByteArray()))
        }

        fun handshake(request: UniversalMessage.RoutableMessage, counter: Int = 6): UniversalMessage.RoutableMessage {
            val info = sessionInfo(SESSION_INFO_OK, counter)
            return sessionInfoResponse(request, info, sessionInfoTag(info, request))
        }

        /** payload 없는 명목 ACK. fault/operation 값만 바꿔 차량 응답 모양을 재현한다. */
        fun helloAck(request: UniversalMessage.RoutableMessage, operation: Int = 0, fault: Int = 0): UniversalMessage.RoutableMessage =
            baseResponse(request).setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                .setOperationStatusValue(operation).setSignedMessageFaultValue(fault)).build()

        fun sessionInfo(status: Int, counter: Int = 6, epochBytes: Int = 16, publicKeyBytes: Int = 65): ByteArray =
            Signatures.SessionInfo.newBuilder().setCounter(counter).setClockTime(100)
                .setPublicKey(ByteString.copyFrom(p256PublicKeyBytes(vehicle.public as ECPublicKey).copyOf(publicKeyBytes)))
                .setEpoch(ByteString.copyFrom(ByteArray(epochBytes) { epoch[it % epoch.size] }))
                .setStatusValue(status).build().toByteArray()

        fun sessionInfoTag(info: ByteArray, request: UniversalMessage.RoutableMessage): ByteArray {
            val derived = testHmac(sessionKey, "session info".toByteArray(Charsets.US_ASCII))
            val metadata = tlv(0 to byteArrayOf(6), 2 to vin, 6 to request.uuid.toByteArray())
            val tag = testHmac(derived, metadata, info)
            derived.fill(0)
            return tag
        }

        fun sessionInfoResponse(request: UniversalMessage.RoutableMessage, info: ByteArray,
            tag: ByteArray?): UniversalMessage.RoutableMessage {
            val builder = baseResponse(request).setSessionInfo(ByteString.copyFrom(info))
            if (tag != null) builder.setSignatureData(Signatures.SignatureData.newBuilder()
                .setSessionInfoTag(Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(tag))))
            return builder.build()
        }

        fun decryptRequest(request: UniversalMessage.RoutableMessage): Vcsec.UnsignedMessage {
            val signature = request.signatureData.getAESGCMPersonalizedData()
            val metadata = tlv(0 to byteArrayOf(5), 1 to byteArrayOf(2), 2 to vin,
                3 to signature.epoch.toByteArray(), 4 to uint(signature.expiresAt),
                5 to uint(signature.counter), 7 to uint(request.flags))
            val plaintext = crypt(Cipher.DECRYPT_MODE, sessionKey, signature.nonce.toByteArray(), metadata,
                request.protobufMessageAsBytes.toByteArray() + signature.tag.toByteArray())
            return Vcsec.UnsignedMessage.parseFrom(plaintext).also { plaintext.fill(0) }
        }

        fun status(request: UniversalMessage.RoutableMessage, lock: Int? = 1, frontTrunk: Int? = 0,
            counter: Int = 8): UniversalMessage.RoutableMessage {
            val status = vehicleStatus(lock, frontTrunk)
            return if (request.flags and (1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE_VALUE) != 0) {
                encryptResponse(request, status, counter)
            } else {
                baseResponse(request).setProtobufMessageAsBytes(status.toByteString()).build()
            }
        }

        fun vehicleStatus(lock: Int?, frontTrunk: Int?): Vcsec.FromVCSECMessage {
            val status = Vcsec.VehicleStatus.newBuilder()
            if (lock != null) status.setVehicleLockStateValue(lock)
            if (frontTrunk != null) status.setClosureStatuses(Vcsec.ClosureStatuses.newBuilder().setFrontTrunkValue(frontTrunk))
            return Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(status).build()
        }

        fun baseResponse(request: UniversalMessage.RoutableMessage): UniversalMessage.RoutableMessage.Builder =
            UniversalMessage.RoutableMessage.newBuilder().setFromDestination(request.toDestination)
                .setToDestination(request.fromDestination).setRequestUuid(request.uuid)

        fun encryptResponse(request: UniversalMessage.RoutableMessage, payload: Vcsec.FromVCSECMessage,
            counter: Int, requestHash: ByteArray = byteArrayOf(5) + request.signatureData.getAESGCMPersonalizedData().tag.toByteArray(),
            fault: Int = 0): UniversalMessage.RoutableMessage {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val metadata = tlv(0 to byteArrayOf(9), 1 to byteArrayOf(2), 2 to vin,
                5 to uint(counter), 7 to uint(0), 8 to requestHash, 9 to uint(fault))
            val encrypted = crypt(Cipher.ENCRYPT_MODE, sessionKey, nonce, metadata, payload.toByteArray())
            return baseResponse(request).setProtobufMessageAsBytes(ByteString.copyFrom(encrypted, 0, encrypted.size - 16))
                .setSignatureData(Signatures.SignatureData.newBuilder().setAESGCMResponseData(
                    Signatures.AES_GCM_Response_Signature_Data.newBuilder().setNonce(ByteString.copyFrom(nonce))
                        .setCounter(counter).setTag(ByteString.copyFrom(encrypted, encrypted.size - 16, 16))))
                .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder().setSignedMessageFaultValue(fault))
                .build()
        }

        override fun close() {
            conversation.close()
            sessionKey.fill(0)
            vin.fill(0)
        }
    }

    private class JvmCredential(private val pair: KeyPair) : TeslaBleCredential {
        override fun publicKeyBytes(): ByteArray = p256PublicKeyBytes(pair.public as ECPublicKey)
        override fun deriveSessionKey(vehiclePublicKey: ByteArray): ByteArray = derive(pair, vehiclePublicKey)
    }

    private companion object {
        const val SESSION_INFO_OK = Signatures.Session_Info_Status.SESSION_INFO_STATUS_OK_VALUE
        fun assertFailed(step: TeslaBleStep) { assertTrue(step is TeslaBleStep.Failed) }
        /** typed evidence까지 검사한다. 실패 이유 문구에는 결속하지 않는다. */
        fun assertEvidence(expected: TeslaBleAuthEvidence, step: TeslaBleStep) {
            assertTrue("expected Failed but was $step", step is TeslaBleStep.Failed)
            assertEquals(expected, (step as TeslaBleStep.Failed).authEvidence)
        }
        fun assertWaiting(phase: TeslaBlePhase, evidence: TeslaBleAuthEvidence?, step: TeslaBleStep) {
            assertTrue("expected Waiting($phase) but was $step", step is TeslaBleStep.Waiting)
            step as TeslaBleStep.Waiting
            assertEquals(phase, step.phase)
            assertEquals(evidence, step.authEvidence)
        }
        fun parseSend(step: TeslaBleStep): UniversalMessage.RoutableMessage {
            assertTrue(step is TeslaBleStep.Send)
            return UniversalMessage.RoutableMessage.parseFrom((step as TeslaBleStep.Send).payload)
        }
        fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun derive(pair: KeyPair, peer: ByteArray): ByteArray {
            val secret = KeyAgreement.getInstance("ECDH").run {
                init(pair.private)
                doPhase(p256PublicKeyFromBytes(peer), true)
                generateSecret()
            }
            val digest = MessageDigest.getInstance("SHA-1").digest(secret)
            secret.fill(0)
            return digest.copyOf(16).also { digest.fill(0) }
        }
        fun testHmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }
        fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, metadata: ByteArray, payload: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MessageDigest.getInstance("SHA-256").digest(metadata))
                doFinal(payload)
            }
        fun uint(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte())
        fun tlv(vararg fields: Pair<Int, ByteArray>): ByteArray = ByteArrayOutputStream().run {
            for ((tag, value) in fields) { write(tag); write(value.size); write(value) }
            write(255)
            toByteArray()
        }
        fun genericRegistrationStatus(operation: Vcsec.OperationStatus_E): Vcsec.FromVCSECMessage =
            Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(Vcsec.CommandStatus.newBuilder().setOperationStatus(operation)).build()
        fun registrationStatus(operation: Vcsec.OperationStatus_E, information: Int): Vcsec.FromVCSECMessage =
            Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(Vcsec.CommandStatus.newBuilder()
                .setOperationStatus(operation).setWhitelistOperationStatus(Vcsec.WhitelistOperation_status.newBuilder()
                    .setOperationStatus(operation).setWhitelistOperationInformationValue(information))).build()
        fun waitingResponse(): Vcsec.FromVCSECMessage = genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT)
        fun framed(payload: ByteArray): ByteArray = byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload
    }
}
