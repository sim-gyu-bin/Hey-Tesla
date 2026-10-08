package com.heytesla.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 저장·runtime 입장·암호학/BLE target 소비 경계. 라디오/GATT/TX/마이크/UWB 실행은 없다. */
@RunWith(AndroidJUnit4::class)
class VehicleVinRuntimeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DiagnosticApp

    @Test fun loadingAndMissingVinBlockEveryVehicleStartBeforeAnyLeaseOrService() {
        val fixture = fixture("missing")
        lateinit var runtime: DiagnosticRuntime
        onMain {
            runtime = fixture.runtime()
            assertEquals(VehicleVinStatus.LOADING, runtime.state.value.vehicleVin.status)
            assertBlockedVehicleStarts(runtime, "VIN_LOADING")
        }
        await(runtime) { it == VehicleVinStatus.NOT_REGISTERED }
        onMain {
            assertBlockedVehicleStarts(runtime, "VIN_NOT_REGISTERED")
            assertNoWork(runtime)
        }
    }

    @Test fun restoringCorruptStorageCannotStartWorkOrExposeAReadySnapshot() {
        val fixture = fixture("restore-failed")
        fixture.store.save(FIRST)
        fixture.file.writeBytes(byteArrayOf(0, 1, 2))
        lateinit var runtime: DiagnosticRuntime
        onMain { runtime = fixture.runtime() }
        await(runtime) { it == VehicleVinStatus.FAILED }
        onMain {
            assertEquals("VIN_RESTORE_FAILED", runtime.state.value.vehicleVin.reason)
            assertBlockedVehicleStarts(runtime, "VIN_RESTORE_FAILED")
            assertNoWork(runtime)
        }
    }

    @Test fun invalidSaveKeepsTheReadyTargetAndNeverStartsWork() {
        val fixture = fixture("invalid-runtime")
        fixture.store.save(FIRST)
        val before = fixture.file.readBytes()
        val runtime = restoredRuntime(fixture)
        onMain {
            runtime.saveVehicleVin("5YJ3E1EA7KF00000I")
            assertEquals(VehicleVinStatus.READY, runtime.state.value.vehicleVin.status)
            assertEquals("VIN_FORMAT_INVALID", runtime.state.value.vehicleVin.reason)
            assertTrue(TeslaBleAdvertisement.localName(requireNotNull(runtime.vehicleVinSnapshot())) ==
                TeslaBleAdvertisement.localName(FIRST))
            assertNoWork(runtime)
        }
        assertTrue(before.contentEquals(fixture.file.readBytes()))
    }

    @Test fun savingSerializesAdmissionAndWriteFailureBlocksReuseButPreservesOldDiskData() {
        val fixture = fixture("save-failed")
        fixture.store.save(FIRST)
        val before = fixture.file.readBytes()
        assertTrue(File(fixture.file.parentFile, fixture.file.name + ".pending").mkdir())
        val runtime = restoredRuntime(fixture)
        onMain {
            runtime.saveVehicleVin(SECOND)
            assertEquals(VehicleVinStatus.SAVING, runtime.state.value.vehicleVin.status)
            assertEquals("VIN_SAVING", runtime.vehicleVinChangeBlockedReason())
            assertBlockedVehicleStarts(runtime, "VIN_SAVING")
            assertFalse(runtime.acquireUwbSupport())
            assertNull(runtime.acquireSpeechDiagnostic {})
            runtime.manualStart(app)
            runtime.setEnabled(true)
            assertNoWork(runtime)
        }
        await(runtime) { it == VehicleVinStatus.FAILED }
        onMain {
            assertEquals("VIN_SAVE_FAILED", runtime.state.value.vehicleVin.reason)
            assertBlockedVehicleStarts(runtime, "VIN_SAVE_FAILED")
            assertNoWork(runtime)
        }
        assertTrue(before.contentEquals(fixture.file.readBytes()))
        assertNotNull(fixture.store.restore())
        val reopened = restoredRuntime(fixture)
        onMain { assertNoWork(reopened) }
    }

    @Test fun activeUwbSpeechObservationAndChooserReservationsRejectVinChanges() {
        val fixture = fixture("reservations")
        fixture.store.save(FIRST)
        val before = fixture.file.readBytes()
        val runtime = restoredRuntime(fixture)
        onMain {
            assertTrue(runtime.acquireUwbSupport())
            assertRejectedChange(runtime, "VIN_CHANGE_BLOCKED_ACTIVE_WORK")
            runtime.releaseUwbSupport(cleanupFailed = false)
            val session = requireNotNull(runtime.policy.startDiagnostic(runtime.now()))
            assertRejectedChange(runtime, "VIN_CHANGE_BLOCKED_ACTIVE_WORK")
            runtime.policy.finish(session.id, runtime.now())
            for (snapshot in listOf(
                runtime.state.value.copy(enabled = true),
                runtime.state.value.copy(observationStartPending = true),
                runtime.state.value.copy(observationServiceRunning = true),
                runtime.state.value.copy(observing = true),
                runtime.state.value.copy(bleFieldTrialStarting = true),
                runtime.state.value.copy(bleFieldTrialStopping = true),
                runtime.state.value.copy(speechDiagnosticActive = true),
                runtime.state.value.copy(bleDiagnosticActive = true),
            )) {
                val original = runtime.state.value
                runtime.update { snapshot }
                assertRejectedChange(runtime, "VIN_CHANGE_BLOCKED_ACTIVE_WORK")
                runtime.update { original }
            }
            runtime.vehicleChooserPending = true
            assertRejectedChange(runtime, "VIN_CHANGE_BLOCKED_VEHICLE_CHOOSER")
            runtime.vehicleChooserPending = false
            assertNoWork(runtime)
        }
        assertTrue(before.contentEquals(fixture.file.readBytes()))
    }

    @Test fun cleanupUncertaintyRemainsBlockedEvenAfterActivityAndStatusFlagsReset() {
        for (kind in 0..4) {
            val fixture = fixture("cleanup-$kind")
            fixture.store.save(FIRST)
            val before = fixture.file.readBytes()
            val runtime = restoredRuntime(fixture)
            onMain {
                when (kind) {
                    0 -> {
                        assertTrue(runtime.acquireUwbSupport())
                        runtime.releaseUwbSupport(cleanupFailed = true)
                    }
                    1 -> runtime.event("OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED")
                    2 -> runtime.event("SPEECH_SUPPORT_DESTROY_FAILED")
                    3 -> runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED,
                        scanFailureCode = BleSupplementalScanner.FAILURE_STOP))
                    else -> runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_STOPPED,
                        accepted = false, scanRunning = false))
                }
                runtime.activityVisible = false
                runtime.activityVisible = true
                assertRejectedChange(runtime, "VIN_CHANGE_BLOCKED_CLEANUP")
            }
            assertTrue(before.contentEquals(fixture.file.readBytes()))
        }
    }

    @Test fun cleanupFailureBlocksEveryNewDiagnosticAfterUiReentry() {
        for (kind in 0..4) {
            val fixture = fixture("cleanup-admission-$kind")
            fixture.store.save(FIRST)
            val runtime = restoredRuntime(fixture)
            onMain {
                assertTrue(runtime.acquireUwbSupport())
                runtime.releaseUwbSupport(cleanupFailed = false)
                when (kind) {
                    0 -> runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED,
                        scanFailureCode = BleSupplementalScanner.FAILURE_STOP))
                    1 -> runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_STOPPED, accepted = false))
                    2 -> runtime.event("SPEECH_SUPPORT_DESTROY_FAILED")
                    3 -> runtime.event("OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED")
                    else -> runtime.event("BLE_FIELD_WAKE_LOCK_RELEASE_FAILED")
                }
                runtime.activityVisible = false
                runtime.activityVisible = true
                assertFalse(runtime.acquireUwbSupport())
                assertNull(runtime.acquireSpeechDiagnostic {})
                assertNull(runtime.acquireTeslaKey())
                assertNotNull(runtime.bleFieldTrialBlockedReason(BleFieldConfig.VEHICLE_NAME_DETECTION))
                assertNotNull(runtime.observationBlockedReason())
                runtime.manualStart(app)
                runtime.setEnabled(true)
                assertNoWork(runtime)
                assertRejectedChange(runtime, "VIN_CHANGE_BLOCKED_CLEANUP")
            }
        }
    }

    @Test fun realSavedSnapshotsKeepBleFiltersAndBothAuthenticatedQueryTargetsFixed() {
        val fixture = fixture("consumers")
        fixture.store.save(FIRST)
        val runtime = restoredRuntime(fixture)
        val credential = fixture.keyStore.getOrCreate()
        lateinit var name: String
        lateinit var target: BleScanTarget
        lateinit var body: TeslaBleConversation
        lateinit var drive: TeslaBleConversation
        onMain {
            val snapshot = requireNotNull(runtime.vehicleVinSnapshot())
            name = requireNotNull(TeslaBleAdvertisement.localName(snapshot))
            target = BleScanTarget.vehicleName(name)
            val bytes = snapshot.toByteArray(Charsets.US_ASCII)
            try {
                body = TeslaBleConversation(credential, bytes, TeslaBleQuery.BODY_STATUS) { 10_000L }
                drive = TeslaBleConversation(credential, bytes, TeslaBleQuery.DRIVE_STATE) { 10_000L }
            } finally { bytes.fill(0) }
            assertTrue(runtime.acquireUwbSupport())
            assertRejectedChange(runtime, "VIN_CHANGE_BLOCKED_ACTIVE_WORK")
            runtime.releaseUwbSupport(cleanupFailed = false)
            runtime.saveVehicleVin(SECOND)
        }
        await(runtime) { it == VehicleVinStatus.READY }
        try {
            val replacementName = requireNotNull(TeslaBleAdvertisement.localName(SECOND))
            assertTrue(target.matches(null, name))
            assertFalse(target.matches(null, replacementName))
            onMain {
                assertTrue(TeslaBleAdvertisement.localName(requireNotNull(runtime.vehicleVinSnapshot())) == replacementName)
                assertFalse(runtime.state.value.toString().contains(FIRST))
                assertFalse(runtime.state.value.toString().contains(SECOND))
                assertNoWork(runtime)
            }
            // 원문 필드 전달을 재핀하지 않고 실제 HMAC 소비자가 시작 대상만 승인하는지 검증한다.
            for (conversation in listOf(body, drive)) {
                val hello = UniversalMessage.RoutableMessage.parseFrom((conversation.start(false) as TeslaBleStep.Send).payload)
                val wrong = handshake(credential, hello, SECOND)
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.HMAC_MISMATCH),
                    conversation.receive(wrong))
                assertTrue(conversation.receive(handshake(credential, hello, FIRST)) is TeslaBleStep.Send)
            }
        } finally {
            body.close()
            drive.close()
            target.clear()
        }
    }

    private fun handshake(credential: TeslaBleCredential, hello: UniversalMessage.RoutableMessage, vin: String): ByteArray {
        val vehicle = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(vehicle.private)
            doPhase(p256PublicKeyFromBytes(credential.publicKeyBytes()), true)
            generateSecret()
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(secret)
        secret.fill(0)
        val session = digest.copyOf(16)
        digest.fill(0)
        val derived = hmac(session, "session info".toByteArray(Charsets.US_ASCII))
        session.fill(0)
        val material = vin.toByteArray(Charsets.US_ASCII)
        val metadata = ByteArrayOutputStream().run {
            for ((tag, bytes) in listOf(0 to byteArrayOf(6), 2 to material, 6 to hello.uuid.toByteArray())) {
                write(tag); write(bytes.size); write(bytes)
            }
            write(255)
            toByteArray()
        }
        material.fill(0)
        val info = Signatures.SessionInfo.newBuilder().setCounter(6).setClockTime(100)
            .setPublicKey(ByteString.copyFrom(p256PublicKeyBytes(vehicle.public as ECPublicKey)))
            .setEpoch(ByteString.copyFrom(ByteArray(16) { 7 }))
            .setStatusValue(Signatures.Session_Info_Status.SESSION_INFO_STATUS_OK_VALUE).build().toByteArray()
        val tag = hmac(derived, metadata, info)
        derived.fill(0)
        metadata.fill(0)
        return try {
            UniversalMessage.RoutableMessage.newBuilder().setFromDestination(hello.toDestination)
                .setToDestination(hello.fromDestination).setRequestUuid(hello.uuid).setSessionInfo(ByteString.copyFrom(info))
                .setSignatureData(Signatures.SignatureData.newBuilder().setSessionInfoTag(
                    Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(tag)))).build().toByteArray()
        } finally { tag.fill(0) }
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(::update)
        doFinal()
    }

    private fun assertRejectedChange(runtime: DiagnosticRuntime, reason: String) {
        assertEquals(reason, runtime.vehicleVinChangeBlockedReason())
        runtime.saveVehicleVin(SECOND)
        assertEquals(VehicleVinStatus.READY, runtime.state.value.vehicleVin.status)
        assertEquals(reason, runtime.state.value.vehicleVin.reason)
    }

    private fun assertBlockedVehicleStarts(runtime: DiagnosticRuntime, reason: String) {
        assertNull(runtime.vehicleVinSnapshot())
        for ((register, query) in listOf(false to TeslaBleQuery.BODY_STATUS, true to TeslaBleQuery.BODY_STATUS,
            false to TeslaBleQuery.DRIVE_STATE)) {
            runtime.startTeslaKey(register, query)
            assertEquals(TeslaKeyStage.BLOCKED, runtime.teslaKeyState.value.stage)
            assertEquals(reason, runtime.teslaKeyState.value.reason)
            assertFalse(runtime.teslaKeyState.value.transmissionAttempted)
        }
        for (config in listOf(BleFieldConfig.BASELINE, BleFieldConfig.IMPROVED, BleFieldConfig.VEHICLE_NAME_DETECTION)) {
            runtime.startBleFieldTrial(config)
            assertEquals(reason, runtime.state.value.bleFieldTrialStopReason)
            assertNoWork(runtime)
        }
    }

    private fun assertNoWork(runtime: DiagnosticRuntime) {
        val state = runtime.state.value
        assertFalse(state.enabled)
        assertFalse(state.observing)
        assertFalse(state.observationStartPending)
        assertFalse(state.observationServiceRunning)
        assertFalse(state.automaticMicrophoneEnabled)
        assertFalse(state.bleFieldTrialStarting)
        assertFalse(state.bleFieldTrialActive)
        assertFalse(state.bleDiagnosticActive)
        assertFalse(state.speechDiagnosticActive)
        assertFalse(runtime.teslaKeyReserved())
        assertNull(runtime.policy.current)
        assertNull(runtime.microphone)
    }

    private fun restoredRuntime(fixture: Fixture): DiagnosticRuntime {
        lateinit var runtime: DiagnosticRuntime
        onMain { runtime = fixture.runtime() }
        await(runtime) { it == VehicleVinStatus.READY }
        return runtime
    }

    private fun await(runtime: DiagnosticRuntime, expected: (VehicleVinStatus) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (expected(runtime.state.value.vehicleVin.status)) return
            Thread.sleep(20)
        }
        fail("VIN_STATE_TIMEOUT")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun fixture(name: String): Fixture = Fixture("$name-${UUID.randomUUID()}")
    private inner class Fixture(id: String) {
        val file = File(app.noBackupFilesDir, "vehicle-vin-tests/$id.enc")
        val store = VehicleVinStore(app, file, "heytesla.vin.test.$id")
        val keyStore = TeslaBleKeyStore(app, "heytesla.key.test.$id")
        fun runtime() = DiagnosticRuntime(app, store, keyStore).apply { activityVisible = true }
    }

    private companion object {
        const val FIRST = "5YJ3E1EA7KF000000"
        const val SECOND = "5YJ3E1EA7KF000001"
    }
}
