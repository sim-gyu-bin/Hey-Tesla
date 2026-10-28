package com.heytesla.app.vehicle.key

import com.heytesla.app.BleDetectionPreference
import com.heytesla.app.DiagnosticApp
import com.heytesla.app.DiagnosticRuntime
import com.heytesla.app.ObservationService
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.uwb.TeslaServiceUwbRequest
import com.heytesla.app.bleDetectionRequiresExplicitStart
import com.heytesla.app.vehicle.detection.BleEventEvidence
import com.heytesla.app.vehicle.detection.BleEvidenceKind
import com.heytesla.app.vehicle.detection.BleScanTarget
import com.heytesla.app.vehicle.detection.BleSupplementalScanner
import com.heytesla.app.vehicle.detection.TeslaBleAdvertisement
import com.heytesla.app.vehicle.protocol.TeslaBleAuthEvidence
import com.heytesla.app.vehicle.protocol.TeslaBleConversation
import com.heytesla.app.vehicle.protocol.TeslaBlePhase
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaBleStep

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

    @Test fun homeServiceOnRequiresVisibleUiBeforeSavingIntentOrStartingWork() {
        val fixture = fixture("service-hidden")
        fixture.store.save(FIRST)
        val runtime = restoredRuntime(fixture)
        onMain {
            runtime.activityVisible = false
            assertEquals("VISIBLE_UI_REQUIRED", runtime.vehicleServiceBlockedReason())
            runtime.setVehicleServiceEnabled(true)
            assertEquals("VISIBLE_UI_REQUIRED", runtime.state.value.bleFieldTrialStopReason)
            assertFalse(runtime.state.value.bleDetectionEnabled)
            assertFalse(fixture.activation.enabled())
            assertNoWork(runtime)
        }
    }

    /** 실제 복원 경로를 실행하되 독립 runtime에서 RF/생산 서비스 owner를 만들지 않는다. */
    @Test fun savedServiceOnWaitsForVinRestoreAndKeepsIntentWhenActualWorkIsBlocked() {
        val fixture = fixture("service-restore-on")
        fixture.store.save(FIRST)
        assertTrue(fixture.activation.setEnabled(true))
        lateinit var runtime: DiagnosticRuntime
        onMain {
            runtime = fixture.runtime()
            runtime.activityVisible = false
            assertEquals(VehicleVinStatus.LOADING, runtime.state.value.vehicleVin.status)
            assertTrue(runtime.state.value.bleDetectionEnabled)
            assertEquals("VIN_LOADING", runtime.vehicleServiceBlockedReason())
            runtime.restoreBleDetection()
            assertEquals(if (userRequestedExit(runtime, fixture.activation)) "BLE_DETECTION_USER_STOP_REQUIRES_START" else "VIN_LOADING",
                runtime.state.value.bleFieldTrialStopReason)
            assertNoWork(runtime)
            // VIN ready callback이 끝나도 복원은 실제 정리 실패를 통과해서는 안 된다.
            runtime.event("OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED")
        }
        await(runtime) { it == VehicleVinStatus.READY }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(if (userRequestedExit(runtime, fixture.activation)) "BLE_DETECTION_USER_STOP_REQUIRES_START"
                else "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED", runtime.state.value.bleFieldTrialStopReason)
            assertEquals("LOCAL_CLEANUP_FAILED_RESTART_REQUIRED", runtime.vehicleServiceBlockedReason())
            assertTrue(runtime.state.value.bleDetectionEnabled)
            assertTrue(fixture.activation.enabled())
            assertNoWork(runtime)
            runtime.restoreBleDetection()
            assertNoWork(runtime)
            runtime.setVehicleServiceEnabled(false)
            assertFalse(runtime.state.value.bleDetectionEnabled)
            assertFalse(fixture.activation.enabled())
        }
    }

    @Test fun serviceOffBeforeVinRestoreStaysOffAfterRuntimeRecreation() {
        val fixture = fixture("service-restore-off")
        fixture.store.save(FIRST)
        assertTrue(fixture.activation.setEnabled(true))
        lateinit var runtime: DiagnosticRuntime
        onMain {
            runtime = fixture.runtime()
            assertEquals(VehicleVinStatus.LOADING, runtime.state.value.vehicleVin.status)
            assertTrue(runtime.state.value.bleDetectionEnabled)
            runtime.setVehicleServiceEnabled(false)
            assertFalse(runtime.state.value.bleDetectionEnabled)
            assertFalse(fixture.activation.enabled())
            assertNoWork(runtime)
        }
        await(runtime) { it == VehicleVinStatus.READY }
        instrumentation.waitForIdleSync()
        onMain {
            runtime.restoreBleDetection()
            assertFalse(runtime.state.value.bleDetectionEnabled)
            assertEquals("DISABLED", runtime.state.value.bleFieldTrialStopReason)
            assertNoWork(runtime)
        }
        val recreated = restoredRuntime(fixture)
        instrumentation.waitForIdleSync()
        onMain {
            assertFalse(recreated.state.value.bleDetectionEnabled)
            recreated.restoreBleDetection()
            assertNoWork(recreated)
            assertFalse(fixture.activation.enabled())
        }
    }

    @Test fun serviceOffAfterRejectedStartClearsSavedIntentWithoutAServiceOwner() {
        val fixture = fixture("service-failed-start-off")
        assertTrue(fixture.activation.setEnabled(true))
        lateinit var runtime: DiagnosticRuntime
        onMain { runtime = fixture.runtime() }
        await(runtime) { it == VehicleVinStatus.NOT_REGISTERED }
        instrumentation.waitForIdleSync()
        onMain {
            runtime.setVehicleServiceEnabled(true)
            assertEquals("VIN_NOT_REGISTERED", runtime.state.value.bleFieldTrialStopReason)
            assertTrue(runtime.state.value.bleDetectionEnabled)
            assertNoWork(runtime)
            runtime.setVehicleServiceEnabled(false)
            assertFalse(runtime.state.value.bleDetectionEnabled)
            assertFalse(fixture.activation.enabled())
            runtime.restoreBleDetection()
            assertEquals("DISABLED", runtime.state.value.bleFieldTrialStopReason)
            assertNoWork(runtime)
        }
    }

    @Test fun repeatedServiceOnPreservesStartingAndRunningConsumerState() {
        val fixture = fixture("service-duplicate-on")
        fixture.store.save(FIRST)
        val runtime = restoredRuntime(fixture)
        onMain {
            val original = runtime.state.value
            try {
                for (running in listOf(false, true)) {
                    val snapshot = original.copy(bleDetectionEnabled = true,
                        bleFieldTrialStarting = !running, bleFieldTrialActive = running,
                        enabled = running, observing = running, observationStartPending = !running,
                        observationServiceRunning = running)
                    runtime.update { snapshot }
                    runtime.setVehicleServiceEnabled(true)
                    runtime.setVehicleServiceEnabled(true)
                    assertEquals(snapshot, runtime.state.value)
                    assertNull(runtime.policy.current)
                    assertNull(runtime.microphone)
                    assertFalse(runtime.teslaKeyReserved())
                }
            } finally { runtime.update { original } }
            assertNoWork(runtime)
            assertFalse(fixture.activation.enabled())
        }
    }

    @Test fun notificationStopClearsSavedIntentBeforeAnyServiceOwnerOrVinRestore() {
        val fixture = fixture("service-notification-off")
        fixture.store.save(FIRST)
        assertTrue(fixture.activation.setEnabled(true))
        lateinit var runtime: DiagnosticRuntime
        onMain {
            runtime = fixture.runtime()
            runtime.stopBleFieldTrial("BLE_FIELD_NOTIFICATION_STOP")
            assertFalse(runtime.state.value.bleDetectionEnabled)
            assertFalse(fixture.activation.enabled())
            assertNoWork(runtime)
        }
        await(runtime) { it == VehicleVinStatus.READY }
        instrumentation.waitForIdleSync()
        onMain {
            runtime.restoreBleDetection()
            assertEquals("BLE_FIELD_NOTIFICATION_STOP", runtime.state.value.bleFieldTrialStopReason)
            assertFalse(runtime.state.value.bleDetectionEnabled)
            assertFalse(fixture.activation.enabled())
            assertNoWork(runtime)
        }
    }

    @Test fun explicitActivationSupersedesOldUserExitButRestoreNeverAcknowledgesANewExit() {
        val activation = fixture("service-user-exit").activation
        val userExit = android.app.ApplicationExitInfo.REASON_USER_REQUESTED
        assertTrue(bleDetectionRequiresExplicitStart(userExit, 1L, activation.lastUserEnabledWallMs()))
        assertTrue(activation.setEnabled(true))
        val startedAt = activation.lastUserEnabledWallMs()
        assertTrue(startedAt > 0L)
        assertFalse(bleDetectionRequiresExplicitStart(userExit, startedAt - 1, startedAt))
        assertTrue(bleDetectionRequiresExplicitStart(userExit, startedAt, startedAt))
        assertTrue(bleDetectionRequiresExplicitStart(userExit, startedAt + 1, startedAt))
        assertTrue(activation.setEnabled(true, userInitiated = false))
        assertEquals(startedAt, activation.lastUserEnabledWallMs())
        assertTrue(bleDetectionRequiresExplicitStart(userExit, startedAt + 1, activation.lastUserEnabledWallMs()))
        assertFalse(bleDetectionRequiresExplicitStart(android.app.ApplicationExitInfo.REASON_SIGNALED,
            startedAt + 1, activation.lastUserEnabledWallMs()))
        assertTrue(activation.setEnabled(false))
        assertFalse(activation.enabled())
    }



    @Test fun unarmedServiceOwnerCannotAcquireBackgroundLeaseOrStartKeyWork() {
        val fixture = fixture("service-owner")
        fixture.store.save(FIRST)
        val runtime = restoredRuntime(fixture)
        onMain {
            runtime.activityVisible = false
            val device = requireNotNull(app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter)
                .getRemoteDevice("AA:BB:CC:DD:EE:03")
            val request = TeslaServiceUwbRequest(ObservationService(), "unarmed-service-request", 1, 1, 1,
                device, runtime.now() * 1_000_000, runtime.now() + 180_000)
            assertSame(device, request.device)
            assertEquals("FIELD_OWNER_NOT_ARMED", runtime.serviceUwbOwnerReason(request))
            assertNull(runtime.acquireServiceUwb(request))
            var finished: BleProbeState? = null
            runtime.startServiceUwb(request) { state, retained ->
                assertFalse(retained)
                finished = state
            }
            assertEquals(BleProbeStatus.BLOCKED, finished?.status)
            assertEquals("FIELD_OWNER_NOT_ARMED", finished?.reason)
            assertTrue(finished?.localClosed == true)
            assertNull(request.device)
            assertFalse(fixture.keyStore.hasKey())
            assertNoWork(runtime)
        }
    }

    @Test fun manualReadAndRegistrationRemainVisibleOnlyAndCannotCreateAKeyWhenHidden() {
        val fixture = fixture("manual-hidden")
        fixture.store.save(FIRST)
        val runtime = restoredRuntime(fixture)
        onMain {
            runtime.activityVisible = false
            for (register in listOf(false, true)) {
                runtime.startTeslaKey(register)
                assertEquals(TeslaKeyStage.BLOCKED, runtime.teslaKeyState.value.stage)
                assertEquals("VISIBLE_UI_REQUIRED", runtime.teslaKeyState.value.reason)
                assertFalse(runtime.teslaKeyState.value.transmissionAttempted)
                assertNoWork(runtime)
            }
            assertFalse(fixture.keyStore.hasKey())
        }
    }

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
            runtime.setVehicleServiceEnabled(true)
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
            runtime.policy.finish(session.id)
            for (snapshot in listOf(
                runtime.state.value.copy(enabled = true),
                runtime.state.value.copy(observationStartPending = true),
                runtime.state.value.copy(observationServiceRunning = true),
                runtime.state.value.copy(observing = true),
                runtime.state.value.copy(bleFieldTrialStarting = true),
                runtime.state.value.copy(bleFieldTrialStopping = true),
                runtime.state.value.copy(speechDiagnosticActive = true),
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
                assertNotNull(runtime.bleFieldTrialBlockedReason())
                runtime.manualStart(app)
                runtime.setVehicleServiceEnabled(true)
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
            assertTrue(target.matches(name))
            assertFalse(target.matches(replacementName))
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
        runtime.startBleFieldTrial()
        assertEquals(reason, runtime.state.value.bleFieldTrialStopReason)
        assertNoWork(runtime)
        runtime.setVehicleServiceEnabled(true)
        assertEquals(reason, runtime.vehicleServiceBlockedReason())
        assertEquals(reason, runtime.state.value.bleFieldTrialStopReason)
        assertFalse(runtime.state.value.bleDetectionEnabled)
        assertNoWork(runtime)
    }

    private fun assertNoWork(runtime: DiagnosticRuntime) {
        val state = runtime.state.value
        assertFalse(state.enabled)
        assertFalse(state.observing)
        assertFalse(state.observationStartPending)
        assertFalse(state.observationServiceRunning)
        assertFalse(state.bleFieldTrialStarting)
        assertFalse(state.bleFieldTrialActive)
        assertFalse(state.bleFieldTrialStopping)
        assertFalse(state.bleFieldScanRunning)
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
    private fun userRequestedExit(runtime: DiagnosticRuntime, activation: BleDetectionPreference): Boolean =
        bleDetectionRequiresExplicitStart(runtime.state.value.previousExitReason,
            runtime.state.value.previousExitWallMs, activation.lastUserEnabledWallMs())


    private fun fixture(name: String): Fixture = Fixture("$name-${UUID.randomUUID()}")
    private inner class Fixture(id: String) {
        val file = File(app.noBackupFilesDir, "vehicle-vin-tests/$id.enc")
        val store = VehicleVinStore(app, file, "heytesla.vin.test.$id")
        val keyStore = TeslaBleKeyStore(app, "heytesla.key.test.$id")
        private val markerName = "vehicle_vin_runtime_test_$id"
        val activation = BleDetectionPreference(app, "${markerName}_activation")
        fun runtime() = DiagnosticRuntime(app, store, keyStore, markerName).apply { activityVisible = true }
    }

    private companion object {
        const val FIRST = "5YJ3E1EA7KF000000"
        const val SECOND = "5YJ3E1EA7KF000001"
    }
}
