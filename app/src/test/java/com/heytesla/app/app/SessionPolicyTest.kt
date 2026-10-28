package com.heytesla.app.app

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    @Test fun lateStopAndExpiryCannotMutateReplacementSession() {
        val policy = SessionPolicy()
        val old = policy.startManual(0)!!
        assertTrue(policy.finish(old.id))
        val current = policy.startManual(11_000)!!
        assertFalse(policy.accepts(old.id))
        assertFalse(policy.expired(old.id, 99_000))
        assertFalse(policy.finish(old.id))
        assertTrue(policy.accepts(current.id))
        assertNull(policy.startManual(12_000))
    }

    @Test fun diagnosticOwnershipBlocksMicrophoneUntilExplicitReleaseEvenAfterExpiry() {
        val policy = SessionPolicy()
        val diagnostic = policy.startDiagnostic(100)!!
        assertTrue(diagnostic.diagnostic)
        assertNull(policy.startManual(1_501))
        assertNull(policy.startDiagnostic(1_502))
        assertEquals(diagnostic, policy.current)
        assertTrue(policy.expired(diagnostic.id, diagnostic.deadline))
        assertNull(policy.startManual(diagnostic.deadline + 1))
        assertEquals(diagnostic, policy.current)
        assertTrue(policy.finish(diagnostic.id))
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
        policy.finish(old.id)
        val replacement = policy.startDiagnostic(2)!!
        assertFalse(policy.finish(old.id))
        assertNull(policy.startManual(4))
        assertEquals(replacement, policy.current)
    }

    @Test fun manualDeadlineCannotBeExtendedByDuplicateStartOrReleaseItself() {
        val policy = SessionPolicy()
        val manual = checkNotNull(policy.startManual(1_000))
        assertEquals(11_000L, manual.deadline)
        assertFalse(policy.expired(manual.id, 10_999))
        assertNull(policy.startManual(10_999))
        assertTrue(policy.expired(manual.id, 11_000))
        assertNull(policy.startDiagnostic(11_001))
        assertNull(policy.startManual(11_001))
        assertEquals(manual, policy.current)
        assertTrue(policy.finish(manual.id))
        assertNotNull(policy.startManual(11_002))
    }
}
