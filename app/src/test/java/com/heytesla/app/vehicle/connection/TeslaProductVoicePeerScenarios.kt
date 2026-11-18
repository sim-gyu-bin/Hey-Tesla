package com.heytesla.app.vehicle.connection

import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.speech.KoreanSpeechCleanup
import com.heytesla.app.speech.KoreanSpeechStreamResult
import com.heytesla.app.speech.product.ProductVoiceCompletion
import com.heytesla.app.speech.product.ProductVoiceSession
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy
import com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaGear

/** Synthetic final text and cleanup reports, not a microphone/STT or Android-service exercise. */
internal object TeslaProductVoicePeerScenarios {
    val cases: List<Pair<String, () -> Unit>> = buildList {
        for (pendingMonitor in listOf(false, true)) {
            add("product-synthetic-final-text-gate-same-gatt-${if (pendingMonitor) "drain" else "idle"}-single-frunk" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive(); h.replyDrive()
                    val nativeHandle = checkNotNull(h.owner.handle)
                    val generation = h.owner.connectionGeneration
                    val voice = startVoice(h)
                    if (pendingMonitor) h.advance(TeslaParkMonitor.POLL_MS)
                    prepareRecognition(voice)
                    check(command(voice, h) == ProductVoiceSession.Decision.ACCEPTED)
                    val completion = checkNotNull(voice.complete(h.clock.elapsed))
                    check(completion.reason == null && completion.cleanupConfirmed && completion.microphoneReleased)
                    check(handoff(completion, h))
                    check(!handoff(completion, h) && voice.complete(h.clock.elapsed) == null)
                    h.settle()
                    if (pendingMonitor) {
                        check(h.requests.size == 3 && h.requests.last().kind == OfficialTeslaPeer.RequestKind.DRIVE)
                        h.replyDrive() // The monitor reply is consumed, not reused as the command's P.
                    }
                    check(h.owner.handle === nativeHandle && h.owner.connectionGeneration == generation)
                    check(h.requests.last().kind == OfficialTeslaPeer.RequestKind.DRIVE)
                    check(h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
                    h.replyDrive()
                    check(h.requests.last().kind == OfficialTeslaPeer.RequestKind.FRUNK)
                    check(h.owner.handle === nativeHandle && h.owner.connectionGeneration == generation)
                    h.inject(h.peer.encryptedResponse(h.requests.last().id, byteArrayOf())); h.settle()
                    check(h.requests.last().kind == OfficialTeslaPeer.RequestKind.BODY)
                    h.replyBody()
                    check(h.stopStage == TeslaKeyStage.COMPLETE && h.session.frunkPolicy?.openObserved == true)
                    check(h.requests.count { it.kind == OfficialTeslaPeer.RequestKind.FRUNK } == 1)
                    check(!h.command() && !handoff(completion, h))
                }
            })
        }
        for (failure in listOf("microphone", "capture-cleanup", "recognizer-cleanup")) {
            add("product-synthetic-final-text-$failure-failure-has-no-gate-or-actuator" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive(); h.replyDrive()
                    val voice = startVoice(h)
                    if (failure == "recognizer-cleanup") {
                        voice.captureFinished(released = true, clean = true)
                        check(voice.beginRecognition())
                        check(command(voice, h, cleanupConfirmed = false) == ProductVoiceSession.Decision.STOP)
                    } else {
                        voice.captureFinished(released = failure != "microphone", clean = false)
                        check(!voice.beginRecognition())
                    }
                    check(command(voice, h) == ProductVoiceSession.Decision.STOP)
                    val completion = checkNotNull(voice.complete(h.clock.elapsed))
                    check(completion.gate == null && !completion.cleanupConfirmed)
                    check(completion.reason == ProductVoiceSession.CLEANUP_FAILED)
                    check(!handoff(completion, h))
                    h.session.cancel(reason = completion.reason!!)
                    assertNoActuator(h)
                }
            })
        }
        for (afterCompletion in listOf(false, true)) {
            add("product-synthetic-final-text-cancel-${if (afterCompletion) "after-completion" else "before-final"}-cannot-dispatch" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive(); h.replyDrive()
                    val voice = startVoice(h)
                    prepareRecognition(voice)
                    val completion = if (afterCompletion) {
                        check(command(voice, h) == ProductVoiceSession.Decision.ACCEPTED)
                        checkNotNull(voice.complete(h.clock.elapsed))
                    } else null
                    h.session.cancel(reason = "SERVICE_OFF")
                    check(command(voice, h) == ProductVoiceSession.Decision.STOP)
                    val stopped = completion ?: checkNotNull(voice.complete(h.clock.elapsed))
                    check(!handoff(stopped, h))
                    assertNoActuator(h)
                }
            })
        }
        for (accepted in listOf(false, true)) {
            add("product-synthetic-final-text-park-to-drive-${if (accepted) "accepted-gate" else "late-result"}-zero-actuator" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive(); h.replyDrive()
                    val voice = startVoice(h)
                    prepareRecognition(voice)
                    val completion = if (accepted) {
                        check(command(voice, h) == ProductVoiceSession.Decision.ACCEPTED)
                        checkNotNull(voice.complete(h.clock.elapsed))
                    } else null
                    h.advance(TeslaParkMonitor.POLL_MS)
                    h.replyDrive(TeslaGear.D)
                    check(voice.stopReason.get() == "CONNECTION_EXPLICIT_P_REQUIRED")
                    check(h.parkStates.last().first?.gear == TeslaGear.D)
                    check(command(voice, h) == ProductVoiceSession.Decision.STOP)
                    check(!handoff(completion ?: checkNotNull(voice.complete(h.clock.elapsed)), h))
                    assertNoActuator(h)
                }
            })
        }
        add("product-synthetic-final-text-expired-handoff-cannot-renew-park-or-actuate" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive()
                val voice = startVoice(h)
                prepareRecognition(voice)
                check(command(voice, h) == ProductVoiceSession.Decision.ACCEPTED)
                val completion = checkNotNull(voice.complete(h.clock.elapsed))
                // Keep receiving genuine fresh P, so the rejection is the original voice handoff deadline.
                repeat(2) { h.advance(TeslaParkMonitor.POLL_MS); h.replyDrive() }
                h.advance(FrunkVoicePolicy.HANDOFF_MS - 2 * TeslaParkMonitor.POLL_MS)
                check(!h.session.sealed && !handoff(completion, h))
                check(completion.gate?.reason == "FRUNK_VOICE_EXPIRED")
                check(!handoff(completion, h))
                h.session.cancel(reason = "FRUNK_VOICE_EXPIRED")
                assertNoActuator(h)
            }
        })
        add("product-synthetic-final-text-expired-park-and-late-rx-cannot-dispatch" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive()
                val voice = startVoice(h)
                prepareRecognition(voice)
                check(command(voice, h) == ProductVoiceSession.Decision.ACCEPTED)
                val completion = checkNotNull(voice.complete(h.clock.elapsed))
                h.advance(TeslaParkMonitor.POLL_MS)
                val late = h.peer.encryptedResponse(h.requests.last().id, h.driveBody())
                h.advance(TeslaFrunkPolicy.PARK_RECEIPT_MS - TeslaParkMonitor.POLL_MS + 1)
                check(voice.stopReason.get() == "CONNECTION_P_RECEIPT_STALE")
                h.inject(late); h.settle()
                check(!handoff(completion, h) && voice.complete(h.clock.elapsed) == null)
                assertNoActuator(h)
            }
        })
        add("product-synthetic-final-text-handoff-command-drive-rechecks-nonpark-before-actuator" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive()
                val voice = startVoice(h)
                prepareRecognition(voice)
                check(command(voice, h) == ProductVoiceSession.Decision.ACCEPTED)
                val completion = checkNotNull(voice.complete(h.clock.elapsed))
                check(handoff(completion, h)); h.settle()
                h.replyDrive(TeslaGear.D)
                check(!handoff(completion, h))
                assertNoActuator(h)
            }
        })
    }

    private fun startVoice(h: VehiclePeerHarness): ProductVoiceSession {
        check(h.parkStates.last().first?.gear == TeslaGear.P && h.parkStates.last().second == null)
        return ProductVoiceSession().also { voice ->
            h.stoppingObserver = { reason ->
                check(h.session.sealed) // Mirrors the parent's immediate stop callback boundary.
                voice.stop(reason)
            }
            check(voice.start() && voice.beginPreparation())
            voice.preparationFinished(prepared = true, clean = false, failure = null)
            check(voice.beginCapture())
        }
    }

    private fun prepareRecognition(voice: ProductVoiceSession) {
        voice.captureFinished(released = true, clean = true)
        check(voice.beginRecognition())
    }

    private fun command(voice: ProductVoiceSession, h: VehiclePeerHarness, cleanupConfirmed: Boolean = true):
        ProductVoiceSession.Decision {
        val decision = voice.acceptRecognition(
            KoreanSpeechStreamResult("헤이 테슬라 프렁크 열어줘", failure = null, decodeMs = 1, cleanupMs = 1,
                consumedBytes = 32_000, decodeCompleted = true, streamCleanupConfirmed = cleanupConfirmed,
                modelReused = false),
            expectedBytes = 32_000, startedAt = h.clock.elapsed, now = h.clock.elapsed)
        // Native 호출 자체는 fixture다. 제품 정책은 stream 완료와 owner full-close를 구분한다.
        voice.recognizerClosed(KoreanSpeechCleanup(cleanupConfirmed, true, 1))
        return decision
    }

    private fun handoff(completion: ProductVoiceCompletion, h: VehiclePeerHarness): Boolean {
        val gate = completion.gate ?: return false
        if (!gate.takeForDispatch(h.clock.elapsed, completion.microphoneReleased, completion.cleanupConfirmed)) return false
        val acceptedAt = checkNotNull(gate.commandAcceptedAtElapsedMs)
        return h.session.dispatchServiceFrunk(TeslaFrunkPolicy(acceptedAt, { h.clock.elapsed }, { h.wallEpochMs }))
    }

    private fun assertNoActuator(h: VehiclePeerHarness) {
        check(h.session.sealed && h.stopCount == 1)
        check(h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
        check(h.session.frunkPolicy?.commandSent != true)
    }
}
