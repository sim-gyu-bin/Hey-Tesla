package com.heytesla.app.vehicle.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaNativeGattOwnerTest {
    @Test fun cancelDuringAllocationClosesTheActualReturnedHandle() {
        val owner = TeslaNativeGattOwner<Any>()
        val native = Any()
        assertTrue(owner.beginConnect())
        owner.seal()
        var disconnects = 0
        var closes = 0
        assertFalse(owner.closeConfirmed({ disconnects++ }, { closes++ }))
        assertEquals(0, closes)
        owner.attach(native)
        assertFalse(owner.accepts(native))
        assertTrue(owner.closeConfirmed({ assertSame(native, it); disconnects++ }, {
            assertSame(native, it)
            closes++
        }))
        assertEquals(1, disconnects)
        assertEquals(1, closes)
        assertNull(owner.handle)
        assertFalse(owner.beginConnect())
    }

    @Test fun unknownAllocationCannotBeReportedLocallyClosed() {
        for (nullReturn in listOf(true, false)) {
            val owner = TeslaNativeGattOwner<Any>()
            assertTrue(owner.beginConnect())
            if (nullReturn) owner.attach(null) else owner.allocationFailed()
            var closes = 0
            assertFalse(owner.closeConfirmed({}, { closes++ }))
            assertTrue(owner.allocationUncertain)
            assertEquals(0, closes)
            assertFalse(owner.beginConnect())
        }
    }

    @Test fun initializationRejectsOverlapWrongSourceWrongKindAndUnacceptedCallbacks() {
        val native = Any()
        val wrong = Any()
        val owner = connectedOwner(native)
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.MTU, 10L))
        val services = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, 10L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.MTU, 11L))
        assertFalse(owner.completeRequest(native, services, TeslaNativeGattRequestKind.SERVICES, 11L))
        assertTrue(owner.recordRequestResult(services, 0))
        assertFalse(owner.completeRequest(wrong, services, TeslaNativeGattRequestKind.SERVICES, 11L))
        assertFalse(owner.completeRequest(native, services, TeslaNativeGattRequestKind.MTU, 11L))
        assertSame(services, owner.pending)
        assertTrue(owner.completeRequest(native, services, TeslaNativeGattRequestKind.SERVICES, 11L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 12L))
        assertFalse(owner.ready())
        val mtu = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.MTU, 12L))
        assertTrue(owner.recordRequestResult(mtu, 0))
        assertTrue(owner.completeRequest(native, mtu, TeslaNativeGattRequestKind.MTU, 13L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 14L))
        assertFalse(owner.ready())
        val cccd = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.CCCD, 14L))
        assertTrue(owner.recordRequestResult(cccd, 0))
        assertFalse(owner.ready())
        assertTrue(owner.completeRequest(native, cccd, TeslaNativeGattRequestKind.CCCD, 15L))
        assertTrue(owner.ready())
        assertEquals(TeslaNativeGattStage.READY, owner.stage)
    }

    @Test fun synchronousRejectionSealsBeforeAnyLateCallbackCanAdvance() {
        val native = Any()
        val owner = connectedOwner(native)
        val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, 10L))
        val capturedAtNativeEntry = owner.pending
        assertFalse(owner.recordRequestResult(request, -1))
        assertEquals(TeslaNativeGattStage.SEALED, owner.stage)
        assertFalse(owner.completeRequest(native, capturedAtNativeEntry, TeslaNativeGattRequestKind.SERVICES, 11L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.MTU, 12L))
        assertFalse(owner.ready())
        assertSame(native, owner.handle)
    }

    @Test fun queuedDuplicateDoesNotConsumeTheNextWriteAndEntryTimeCannotPrecedeIssue() {
        val native = Any()
        val owner = readyOwner(native)
        val first = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
        val synchronousEntry = owner.pending
        assertSame(first, synchronousEntry)
        assertTrue(owner.recordRequestResult(first, 0))
        assertTrue(owner.completeRequest(native, synchronousEntry, TeslaNativeGattRequestKind.WRITE, 100L))
        val second = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 120L))
        assertTrue(owner.recordRequestResult(second, 0))
        assertFalse(owner.completeRequest(native, synchronousEntry, TeslaNativeGattRequestKind.WRITE, 130L))
        assertFalse(owner.completeRequest(native, second, TeslaNativeGattRequestKind.WRITE, 119L))
        assertSame(second, owner.pending)
        assertTrue(owner.completeRequest(native, second, TeslaNativeGattRequestKind.WRITE, 121L))
        assertNull(owner.pending)
    }

    @Test fun previousOwnerRequestCannotCompleteAReplacementOwnerRequest() {
        val previousNative = Any()
        val previous = readyOwner(previousNative)
        val oldRequest = checkNotNull(previous.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
        assertTrue(previous.recordRequestResult(oldRequest, 0))
        previous.seal()
        val native = Any()
        val owner = readyOwner(native)
        val current = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
        assertTrue(owner.recordRequestResult(current, 0))
        assertFalse(owner.completeRequest(previousNative, oldRequest, TeslaNativeGattRequestKind.WRITE, 101L))
        assertFalse(owner.completeRequest(native, oldRequest, TeslaNativeGattRequestKind.WRITE, 101L))
        assertSame(current, owner.pending)
        assertTrue(owner.completeRequest(native, current, TeslaNativeGattRequestKind.WRITE, 101L))
    }

    @Test fun serviceChangedSealsTheCurrentOwnerWithoutReplayingAuthentication() {
        val native = Any()
        val owner = readyOwner(native)
        val write = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
        assertTrue(owner.recordRequestResult(write, 0))
        assertFalse(owner.serviceChanged(Any()))
        assertSame(write, owner.pending)
        assertTrue(owner.serviceChanged(native))
        assertEquals(TeslaNativeGattStage.SEALED, owner.stage)
        assertFalse(owner.completeRequest(native, write, TeslaNativeGattRequestKind.WRITE, 101L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, 102L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 102L))
        assertFalse(owner.serviceChanged(native))
    }

    @Test fun closeFailureRetainsNativeOwnershipAndCannotBeRetriedIntoSuccess() {
        val native = Any()
        val owner = readyOwner(native)
        var closes = 0
        assertFalse(owner.closeConfirmed({}, { closes++; throw IllegalStateException() }))
        assertSame(native, owner.handle)
        assertTrue(owner.closeFailed)
        assertFalse(owner.accepts(native))
        assertFalse(owner.closeConfirmed({}, { closes++ }))
        assertEquals(1, closes)
        assertSame(native, owner.handle)
    }

    @Test fun disconnectFailureDoesNotHideSuccessfulLocalClose() {
        val native = Any()
        val owner = connectedOwner(native)
        var closed: Any? = null
        assertTrue(owner.closeConfirmed({ throw SecurityException() }, { closed = it }))
        assertSame(native, closed)
        assertNull(owner.handle)
        assertFalse(owner.closeFailed)
    }

    @Test fun unsolicitedMtuCannotConsumeCccdOrRestartInitialization() {
        val native = Any()
        val owner = connectedOwner(native)
        val services = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, 10L))
        assertTrue(owner.recordRequestResult(services, 0))
        assertTrue(owner.completeRequest(native, services, TeslaNativeGattRequestKind.SERVICES, 11L))
        val mtu = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.MTU, 12L))
        assertTrue(owner.recordRequestResult(mtu, 0))
        assertTrue(owner.completeRequest(native, mtu, TeslaNativeGattRequestKind.MTU, 13L))
        val cccd = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.CCCD, 14L))
        assertTrue(owner.recordRequestResult(cccd, 0))
        val capturedByUnsolicitedMtu = owner.pending
        assertFalse(owner.completeRequest(native, capturedByUnsolicitedMtu, TeslaNativeGattRequestKind.MTU, 15L))
        assertSame(cccd, owner.pending)
        assertTrue(owner.completeRequest(native, cccd, TeslaNativeGattRequestKind.CCCD, 16L))
        assertTrue(owner.ready())
        assertFalse(owner.completeRequest(native, mtu, TeslaNativeGattRequestKind.MTU, 17L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.CCCD, 18L))
        assertFalse(owner.ready())
        assertEquals(TeslaNativeGattStage.READY, owner.stage)
    }

    @Test fun reconnectReusesHandleAndInvalidatesOnlyPreviouslyEnteredConnectionCallbacks() {
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        assertTrue(owner.beginConnect())
        owner.attach(native)
        val previousEntry = owner.connectionGeneration
        var reconnectEntry = 0L
        assertEquals(true, owner.reconnect {
            assertSame(native, it)
            reconnectEntry = owner.connectionGeneration
            assertFalse(owner.accepts(native, previousEntry))
            true
        })
        assertSame(native, owner.handle)
        assertFalse(owner.connected(native, previousEntry))
        assertTrue(owner.accepts(native, reconnectEntry))
        assertNull(owner.reconnect { throw AssertionError("두 번째 SDK 재연결은 금지") })
        assertFalse(owner.beginConnect())
        assertTrue(owner.connected(native, reconnectEntry))
        assertFalse(owner.connected(native, reconnectEntry))
        val discovery = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, 2_000L))
        assertTrue(owner.recordRequestResult(discovery, 0))
        assertSame(discovery, owner.pending)
        var disconnects = 0
        var closes = 0
        repeat(2) {
            assertTrue(owner.closeConfirmed({ assertSame(native, it); disconnects++ },
                { assertSame(native, it); closes++ }))
        }
        assertEquals(1, disconnects)
        assertEquals(1, closes)
    }

    @Test fun rejectedReconnectSealsLateSuccessWithoutAllocatingReplacement() {
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        assertTrue(owner.beginConnect())
        owner.attach(native)
        var entered = 0L
        assertEquals(false, owner.reconnect {
            assertSame(native, it)
            entered = owner.connectionGeneration
            false
        })
        assertEquals(TeslaNativeGattStage.SEALED, owner.stage)
        assertFalse(owner.connected(native, entered))
        assertFalse(owner.beginConnect())
        assertNull(owner.reconnect { throw AssertionError("SDK 거절 뒤 재연결 금지") })
        assertSame(native, owner.handle)
        var closes = 0
        assertTrue(owner.closeConfirmed({}, { closes++ }))
        assertTrue(owner.closeConfirmed({}, { closes++ }))
        assertEquals(1, closes)
    }

    @Test fun securityExceptionPropagatesButRetainsHandleForFinalClose() {
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        assertTrue(owner.beginConnect())
        owner.attach(native)
        val exception = SecurityException("permission revoked")
        var entered = 0L
        try {
            owner.reconnect {
                assertSame(native, it)
                entered = owner.connectionGeneration
                throw exception
            }
            throw AssertionError("SDK 예외가 보존되어야 함")
        } catch (actual: SecurityException) {
            assertSame(exception, actual)
        }
        assertFalse(owner.connected(native, entered))
        assertSame(native, owner.handle)
        assertNull(owner.reconnect { throw AssertionError("보안 예외 뒤 재연결 금지") })
        var closes = 0
        repeat(2) { assertTrue(owner.closeConfirmed({ throw SecurityException() }, { closes++ })) }
        assertEquals(1, closes)
    }

    @Test fun reconnectCannotUseMissingAllocatingOrUncertainHandle() {
        val missing = TeslaNativeGattOwner<Any>()
        val allocating = TeslaNativeGattOwner<Any>().also { assertTrue(it.beginConnect()) }
        val nullReturned = TeslaNativeGattOwner<Any>().also {
            assertTrue(it.beginConnect())
            it.attach(null)
        }
        val allocationFailed = TeslaNativeGattOwner<Any>().also {
            assertTrue(it.beginConnect())
            it.allocationFailed()
        }
        for (owner in listOf(missing, allocating, nullReturned, allocationFailed)) {
            val generation = owner.connectionGeneration
            assertFalse(owner.canReconnect)
            assertNull(owner.reconnect { throw AssertionError("기존 handle 없는 SDK 재연결 금지") })
            assertEquals(generation, owner.connectionGeneration)
        }
    }

    @Test fun successBeforeReconnectAndSealedOrCloseFailedOwnersNeverReconnect() {
        val native = Any()
        val progressed = connectedOwner(native)
        val sealed = TeslaNativeGattOwner<Any>().also {
            assertTrue(it.beginConnect())
            it.attach(native)
            it.seal()
        }
        val closeFailed = TeslaNativeGattOwner<Any>().also {
            assertTrue(it.beginConnect())
            it.attach(native)
            assertFalse(it.closeConfirmed({}, { throw IllegalStateException() }))
        }
        for (owner in listOf(progressed, sealed, closeFailed)) {
            assertSame(native, owner.handle)
            assertFalse(owner.canReconnect)
            assertNull(owner.reconnect { throw AssertionError("진전/봉인/close 불명 뒤 SDK 재연결 금지") })
            assertFalse(owner.beginConnect())
        }
    }

    @Test fun onlyUnacceptedBusyWriteReleasesPendingAndRequiresANewRequestToken() {
        val native = Any()
        val owner = readyOwner(native)
        val rejected = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
        val enteredBeforeBusyReturn = owner.pending
        assertFalse(owner.recordRequestResult(rejected, 201))
        assertEquals(TeslaNativeGattStage.READY, owner.stage)
        assertNull(owner.pending)
        assertFalse(owner.completeRequest(native, enteredBeforeBusyReturn, TeslaNativeGattRequestKind.WRITE, 101L))
        val enteredDuringBusyWait = owner.pending
        val retry = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 200L))
        assertFalse(rejected === retry)
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 201L))
        assertTrue(owner.recordRequestResult(retry, 0))
        assertFalse(owner.recordRequestResult(rejected, 201))
        assertFalse(owner.completeRequest(native, enteredBeforeBusyReturn, TeslaNativeGattRequestKind.WRITE, 202L))
        assertFalse(owner.completeRequest(native, enteredDuringBusyWait, TeslaNativeGattRequestKind.WRITE, 202L))
        assertSame(retry, owner.pending)
        assertTrue(owner.completeRequest(native, retry, TeslaNativeGattRequestKind.WRITE, 202L))
        assertFalse(owner.completeRequest(native, retry, TeslaNativeGattRequestKind.WRITE, 203L))
    }

    @Test fun busyIsNotAnInitializationRetryOrAnAcceptedWriteRelease() {
        val native = Any()
        val initialization = connectedOwner(native)
        val services = checkNotNull(initialization.beginRequest(TeslaNativeGattRequestKind.SERVICES, 10L))
        assertFalse(initialization.recordRequestResult(services, 201))
        assertEquals(TeslaNativeGattStage.SEALED, initialization.stage)
        assertNull(initialization.beginRequest(TeslaNativeGattRequestKind.SERVICES, 20L))
        val owner = readyOwner(native)
        val accepted = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
        assertTrue(owner.recordRequestResult(accepted, 0))
        assertFalse(owner.recordRequestResult(accepted, 201))
        assertEquals(TeslaNativeGattStage.SEALED, owner.stage)
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 200L))
        assertFalse(owner.completeRequest(native, accepted, TeslaNativeGattRequestKind.WRITE, 201L))
    }

    @Test fun nonBusyWriteRejectionAndExceptionSentinelKeepTheExistingSeal() {
        for (status in listOf(-1, 1, 133, 200, 202)) {
            val native = Any()
            val owner = readyOwner(native)
            val write = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
            assertFalse(owner.recordRequestResult(write, status))
            assertEquals(TeslaNativeGattStage.SEALED, owner.stage)
            assertNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 200L))
            assertFalse(owner.completeRequest(native, write, TeslaNativeGattRequestKind.WRITE, 201L))
            assertSame(native, owner.handle)
        }
    }

    @Test fun cleanupDuringBusyWaitCannotBeUndoneByOldResultOrCallback() {
        val native = Any()
        val owner = readyOwner(native)
        val write = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 100L))
        assertFalse(owner.recordRequestResult(write, 201))
        owner.seal()
        assertFalse(owner.recordRequestResult(write, 0))
        assertFalse(owner.recordRequestResult(write, 201))
        assertFalse(owner.completeRequest(native, write, TeslaNativeGattRequestKind.WRITE, 200L))
        assertNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, 200L))
        var closes = 0
        assertTrue(owner.closeConfirmed({}, { assertSame(native, it); closes++ }))
        assertEquals(1, closes)
        assertFalse(owner.accepts(native))
    }

    private fun connectedOwner(native: Any): TeslaNativeGattOwner<Any> = TeslaNativeGattOwner<Any>().also {
        check(it.beginConnect())
        it.attach(native)
        check(it.connected(native))
    }

    private fun readyOwner(native: Any): TeslaNativeGattOwner<Any> = connectedOwner(native).also { owner ->
        for ((index, kind) in listOf(TeslaNativeGattRequestKind.SERVICES,
            TeslaNativeGattRequestKind.MTU, TeslaNativeGattRequestKind.CCCD).withIndex()) {
            val issued = index.toLong() * 2L
            val request = checkNotNull(owner.beginRequest(kind, issued))
            check(owner.recordRequestResult(request, 0))
            check(owner.completeRequest(native, request, kind, issued + 1L))
        }
        check(owner.ready())
    }
}
