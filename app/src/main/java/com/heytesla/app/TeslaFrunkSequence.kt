package com.heytesla.app

/** 읽기 전용 접근 시퀀스와 분리된 DRIVE → actuator → BODY. 어떤 전이도 명령을 재발급하지 않는다. */
internal class TeslaFrunkSequence(
    private var credential: TeslaBleCredential?,
    private val vin: ByteArray,
    val policy: TeslaFrunkPolicy,
    private val now: () -> Long,
) : AutoCloseable {
    private enum class Stage { NEW, DRIVE, ACTION, BODY, TERMINAL, CLOSED }
    private var stage = Stage.NEW
    private var conversation: TeslaBleConversation? = null
    var cleanupFailed = false
        private set
    var responseUnknown = false
        private set
    val query: TeslaBleQuery get() = if (stage == Stage.DRIVE) TeslaBleQuery.DRIVE_STATE else TeslaBleQuery.BODY_STATUS
    val awaitingActionReply: Boolean get() = stage == Stage.ACTION && policy.commandFrameComplete

    fun start(): TeslaBleStep {
        if (stage != Stage.NEW) return TeslaBleStep.Failed("FRUNK_SEQUENCE_ALREADY_STARTED")
        policy.readinessReason()?.let { return TeslaBleStep.Failed(it) }
        return begin(Stage.DRIVE)
    }

    private fun begin(next: Stage): TeslaBleStep {
        try { conversation?.close() } catch (failure: Exception) {
            cleanupFailed = true
            throw failure
        }
        conversation = null
        policy.readinessReason()?.let { return TeslaBleStep.Failed(it) }
        stage = next
        conversation = TeslaBleConversation(checkNotNull(credential), vin, query,
            purpose = if (next == Stage.ACTION) TeslaBlePurpose.FRUNK_OPEN else TeslaBlePurpose.READ_ONLY,
            authorizeFrunk = if (next == Stage.ACTION) policy::authorizeActuator else null,
            frunkDeadline = policy.deadline.takeIf { next == Stage.ACTION },
            now = now)
        return checkNotNull(conversation).start(false)
    }

    fun receive(payload: ByteArray, receivedAt: Long): TeslaBleStep {
        if (stage == Stage.NEW || stage == Stage.TERMINAL || stage == Stage.CLOSED) {
            return TeslaBleStep.Failed("FRUNK_SEQUENCE_INACTIVE")
        }
        policy.readinessReason()?.let { return TeslaBleStep.Failed(it) }
        val step = checkNotNull(conversation).receive(payload, receivedAt)
        return when (stage) {
            Stage.DRIVE -> {
                if (step !is TeslaBleStep.Complete) step else {
                    policy.acceptDrive(step.status.driveState)?.let { return TeslaBleStep.Failed(it) }
                    begin(Stage.ACTION)
                }
            }
            Stage.ACTION -> when (step) {
                is TeslaBleStep.FrunkResult -> {
                    if (!policy.commandFrameComplete) return TeslaBleStep.Failed("FRUNK_COMMAND_NOT_WRITTEN")
                    policy.recordReply(step.reply)
                    if (step.reply == TeslaFrunkReply.REJECTED) {
                        stage = Stage.TERMINAL
                        TeslaBleStep.Failed("FRUNK_ACTION_REJECTED")
                    } else {
                        responseUnknown = step.reply != TeslaFrunkReply.ACKNOWLEDGED
                        begin(Stage.BODY)
                    }
                }
                is TeslaBleStep.Failed -> {
                    if (policy.commandFrameComplete) observeAfterMissingReply() else step
                }
                else -> step
            }
            Stage.BODY -> {
                if (step is TeslaBleStep.Complete) {
                    policy.observeBody(step.status)
                    stage = Stage.TERMINAL
                }
                step
            }
            else -> TeslaBleStep.Failed("FRUNK_SEQUENCE_INACTIVE")
        }
    }

    /** 응답 불명 뒤 허용되는 것은 새 BODY 읽기 한 번뿐이다. 전체 기한과 동의를 연장하지 않는다. */
    fun observeAfterMissingReply(): TeslaBleStep {
        if (!awaitingActionReply) return TeslaBleStep.Failed("FRUNK_COMMAND_NOT_WRITTEN")
        responseUnknown = true
        return begin(Stage.BODY)
    }

    override fun close() {
        if (stage == Stage.CLOSED) return
        policy.cancel()
        try { conversation?.close() } finally {
            credential = null
            vin.fill(0)
        }
        conversation = null
        stage = Stage.CLOSED
    }
}
