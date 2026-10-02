package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    @Test fun departureDuringDebounceNeverStartsMicrophone() {
        val policy = SessionPolicy()
        assertTrue(policy.appeared(0))
        policy.disappeared()
        assertNull(policy.startAutomatic(1_500))
    }

    @Test fun duplicatePresenceCannotExtendDeadline() {
        val policy = SessionPolicy()
        policy.appeared(0)
        val session = policy.startAutomatic(1_500)!!
        assertFalse(policy.appeared(30_000))
        assertNull(policy.startAutomatic(31_500))
        assertFalse(policy.expired(session.id, 91_499))
        assertTrue(policy.expired(session.id, 91_500))
    }

    @Test fun expiryNeedsActualDepartureEvenAfterCooldown() {
        val policy = SessionPolicy()
        policy.appeared(0)
        val session = policy.startAutomatic(1_500)!!
        policy.finish(session.id, 91_500)
        assertFalse(policy.appeared(150_000))
        assertNull(policy.startAutomatic(151_500))
        policy.disappeared()
        assertTrue(policy.appeared(152_000))
        assertNotNull(policy.startAutomatic(153_500))
    }

    @Test fun departureAloneDoesNotBypassCooldown() {
        val policy = SessionPolicy()
        policy.appeared(0)
        val session = policy.startAutomatic(1_500)!!
        policy.disappeared()
        policy.finish(session.id, 2_000)
        assertFalse(policy.appeared(31_999))
        assertNull(policy.startAutomatic(33_500))
        policy.disappeared()
        assertTrue(policy.appeared(34_000))
        assertNotNull(policy.startAutomatic(35_500))
    }

    @Test fun lateStopAndExpiryCannotMutateReplacementSession() {
        val policy = SessionPolicy()
        val old = policy.startManual(0)!!
        assertTrue(policy.finish(old.id, 10_000))
        val current = policy.startManual(11_000)!!
        assertFalse(policy.accepts(old.id))
        assertFalse(policy.expired(old.id, 99_000))
        assertFalse(policy.finish(old.id, 99_000))
        assertTrue(policy.accepts(current.id))
        assertNull(policy.startManual(12_000))
    }

    @Test fun optInResetCannotReusePreviousAppearance() {
        val policy = SessionPolicy()
        assertTrue(policy.appeared(20_000))
        policy.resetPresence()
        assertNull(policy.startAutomatic(21_500))
        assertTrue(policy.appeared(22_000))
        assertNotNull(policy.startAutomatic(23_500))
    }

    @Test fun optInResetKeepsCooldownFromRealSession() {
        val policy = SessionPolicy()
        policy.appeared(0)
        val session = policy.startAutomatic(1_500)!!
        policy.finish(session.id, 2_000)
        policy.disappeared()
        policy.resetPresence()
        assertFalse(policy.appeared(3_000))
        assertNull(policy.startAutomatic(4_500))
        policy.disappeared()
        assertTrue(policy.appeared(32_000))
        assertNotNull(policy.startAutomatic(33_500))
    }

    @Test fun resetCannotReplaceRealDepartureAfterSession() {
        val policy = SessionPolicy()
        policy.appeared(0)
        val session = policy.startAutomatic(1_500)!!
        policy.finish(session.id, 2_000)
        policy.resetPresence()
        assertFalse(policy.appeared(40_000))
        assertNull(policy.startAutomatic(41_500))
        policy.disappeared()
        assertTrue(policy.appeared(42_000))
        assertNotNull(policy.startAutomatic(43_500))
    }
    @Test fun diagnosticOwnershipBlocksMicrophoneUntilExplicitReleaseEvenAfterExpiry() {
        val policy = SessionPolicy()
        policy.appeared(0)
        val diagnostic = policy.startDiagnostic(100)!!
        assertTrue(diagnostic.diagnostic)
        assertNull(policy.startAutomatic(1_500))
        assertNull(policy.startManual(1_501))
        assertNull(policy.startDiagnostic(1_502))
        assertEquals(diagnostic, policy.current)
        assertTrue(policy.expired(diagnostic.id, diagnostic.deadline))
        assertNull(policy.startManual(diagnostic.deadline + 1))
        assertEquals(diagnostic, policy.current)
        assertTrue(policy.finish(diagnostic.id, diagnostic.deadline + 2))
        assertNotNull(policy.startManual(diagnostic.deadline + 3))
    }

    @Test fun manualSessionCannotBeReplacedByDiagnostic() {
        val policy = SessionPolicy()
        val manual = policy.startManual(0)!!
        assertNull(policy.startDiagnostic(1))
        assertEquals(manual, policy.current)
    }

    @Test fun oldDiagnosticFinishCannotReleaseReplacementOwnership() {
        val policy = SessionPolicy()
        val old = policy.startDiagnostic(0)!!
        policy.finish(old.id, 1)
        val replacement = policy.startDiagnostic(2)!!
        assertFalse(policy.finish(old.id, 3))
        assertNull(policy.startManual(4))
        assertEquals(replacement, policy.current)
    }
}
