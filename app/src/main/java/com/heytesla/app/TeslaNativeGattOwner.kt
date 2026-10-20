package com.heytesla.app

internal enum class TeslaNativeGattStage { CONNECTING, SERVICES, MTU, CCCD, READY, SEALED }
internal enum class TeslaNativeGattRequestKind { SERVICES, MTU, CCCD, WRITE }

/** Android 객체를 대체하지 않고 production adapter의 소유권·직렬 요청 경계만 보존한다. */
internal class TeslaNativeGattOwner<Handle : Any> {
    internal class Request internal constructor(
        val kind: TeslaNativeGattRequestKind,
        val issuedElapsedMs: Long,
    ) {
        internal var accepted = false
    }

    var handle: Handle? = null
        private set
    var allocating = false
        private set
    var allocationUncertain = false
        private set
    var closeFailed = false
        private set
    var connectionProgressed = false
        private set
    @Volatile var stage = TeslaNativeGattStage.CONNECTING
        private set
    @Volatile var pending: Request? = null
        private set
    @Volatile var connectionGeneration = 0L
        private set
    private var reconnectIssued = false

    val canReconnect: Boolean
        get() = stage == TeslaNativeGattStage.CONNECTING && !connectionProgressed &&
            !reconnectIssued && !allocating && !allocationUncertain && !closeFailed &&
            pending == null && handle != null
    private var started = false

    fun beginConnect(): Boolean {
        if (started || stage == TeslaNativeGattStage.SEALED) return false
        started = true
        connectionGeneration++
        allocating = true
        allocationUncertain = true
        return true
    }

    /** 봉인 뒤에도 실제 SDK 반환 handle을 부착해야 close 대상을 놓치지 않는다. */
    fun attach(returned: Handle?) {
        check(allocating)
        handle = returned
        allocating = false
        allocationUncertain = returned == null
    }

    fun allocationFailed() {
        check(allocating)
        allocating = false
        allocationUncertain = true
    }

    fun accepts(source: Handle): Boolean = stage != TeslaNativeGattStage.SEALED &&
        !allocating && !allocationUncertain && handle === source

    /** SDK에는 요청 ID가 없다. native 진입 뒤 이미 게시된 콜백만 generation으로 구분한다. */
    fun accepts(source: Handle, capturedGeneration: Long): Boolean =
        capturedGeneration == connectionGeneration && accepts(source)

    /** null은 소유권/진전 경계 거절이며 SDK를 호출하지 않았다는 뜻이다. */
    fun reconnect(connect: (Handle) -> Boolean): Boolean? {
        if (!canReconnect) return null
        val native = checkNotNull(handle)
        reconnectIssued = true
        connectionGeneration++
        return try {
            connect(native).also { if (!it) seal() }
        } catch (exception: Exception) {
            seal()
            throw exception
        }
    }

    fun connected(source: Handle, capturedGeneration: Long = connectionGeneration): Boolean {
        if (!accepts(source, capturedGeneration) || stage != TeslaNativeGattStage.CONNECTING ||
            connectionProgressed) return false
        connectionProgressed = true
        return true
    }

    fun beginRequest(kind: TeslaNativeGattRequestKind, issuedElapsedMs: Long): Request? {
        if (handle == null || allocating || allocationUncertain || pending != null) return null
        val expected = when (kind) {
            TeslaNativeGattRequestKind.SERVICES -> TeslaNativeGattStage.CONNECTING
            TeslaNativeGattRequestKind.MTU -> TeslaNativeGattStage.SERVICES
            TeslaNativeGattRequestKind.CCCD -> TeslaNativeGattStage.MTU
            TeslaNativeGattRequestKind.WRITE -> TeslaNativeGattStage.READY
        }
        if (stage != expected || !connectionProgressed) return null
        stage = when (kind) {
            TeslaNativeGattRequestKind.SERVICES -> TeslaNativeGattStage.SERVICES
            TeslaNativeGattRequestKind.MTU -> TeslaNativeGattStage.MTU
            TeslaNativeGattRequestKind.CCCD -> TeslaNativeGattStage.CCCD
            TeslaNativeGattRequestKind.WRITE -> TeslaNativeGattStage.READY
        }
        return Request(kind, issuedElapsedMs).also { pending = it }
    }

    /** Boolean SDK는 접수 여부만 0/-1로 전달한다. 진단 native status로 기록하지 않는다. */
    fun recordRequestResult(request: Request, status: Int): Boolean {
        if (stage == TeslaNativeGattStage.SEALED || pending !== request) return false
        // 201은 WRITE 미접수다. 해당 토큰만 버리고 다음 SDK 호출은 새 Request로 발급한다.
        if (status == 201 && request.kind == TeslaNativeGattRequestKind.WRITE &&
            !request.accepted && stage == TeslaNativeGattStage.READY) {
            pending = null
            return false
        }
        if (status != 0) {
            seal()
            return false
        }
        request.accepted = true
        return true
    }

    /** captured는 처리 시점이 아니라 반드시 native callback 진입에서 읽은 pending이다. */
    fun completeRequest(source: Handle, captured: Request?, kind: TeslaNativeGattRequestKind,
        enteredElapsedMs: Long): Boolean {
        if (!accepts(source) || captured == null || pending !== captured || captured.kind != kind ||
            !captured.accepted || enteredElapsedMs < captured.issuedElapsedMs) return false
        pending = null
        return true
    }

    fun ready(): Boolean {
        if (stage != TeslaNativeGattStage.CCCD || pending != null) return false
        stage = TeslaNativeGattStage.READY
        return true
    }

    fun serviceChanged(source: Handle): Boolean {
        if (!accepts(source)) return false
        seal()
        return true
    }

    fun seal() {
        stage = TeslaNativeGattStage.SEALED
        pending = null
    }

    /** true는 close 호출의 정상 반환뿐이다. 원격 ACK가 아니며 실패/할당 불명은 예약을 보존한다. */
    fun closeConfirmed(disconnect: (Handle) -> Unit, close: (Handle) -> Unit): Boolean {
        seal()
        if (allocating || allocationUncertain || closeFailed) return false
        val native = handle ?: return true
        try { disconnect(native) } catch (_: Exception) { /* local close와 독립된 best effort */ }
        return try {
            close(native)
            handle = null
            true
        } catch (_: Exception) {
            closeFailed = true
            false
        }
    }
}
