package com.cihmine.parkingimpact.core

import kotlinx.serialization.Serializable

@Serializable
data class ParkingSession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val source: Source = Source.SIMULATED,
)

@Serializable
enum class RecordStatus { UNREVIEWED, REVIEWED }

/** 충격 의심 기록 하나. "확인 완료"는 사용자가 읽었다는 뜻이지 파손이 없다는 뜻이 아니다. */
@Serializable
data class ImpactRecord(
    val id: String,
    val rawEventIds: List<String>,
    val sessionId: String,
    val source: Source,
    val occurredAt: String,
    val receivedAtMs: Long,
    val estimate: Estimate,
    val status: RecordStatus = RecordStatus.UNREVIEWED,
    val note: String = "",
    val acknowledgedAtMs: Long? = null,
)

/** 저장하지 않은 입력(약한 신호·형식 오류·중복·세션 없음)의 이유. 진단용으로 최근 것만 남긴다. */
@Serializable
data class IgnoredInput(val eventId: String, val atMs: Long, val reason: String)

/** 재탑승 요약 알림 상태. 닫기(dismiss)와 확인 완료는 다르다 — 닫아도 미확인 기록은 남는다. */
@Serializable
data class NoticeState(
    val sessionId: String,
    val recordIds: List<String>,
    val dismissed: Boolean = false,
)

/** 앱 전체 저장 상태. 바뀔 때마다 통째로 저장한다(원자적 쓰기). */
@Serializable
data class MonitorState(
    val session: ParkingSession? = null,
    val pastSessions: List<ParkingSession> = emptyList(),
    val records: List<ImpactRecord> = emptyList(),
    /** 받은 적 있는 모든 eventId(삭제한 기록 포함). 같은 이벤트 재전송으로 기록·알림이 다시 생기지 않게 한다. */
    val seenEventIds: Set<String> = emptySet(),
    val ignored: List<IgnoredInput> = emptyList(),
    val notice: NoticeState? = null,
    val nextId: Long = 1,
) {
    val unreviewed: List<ImpactRecord> get() = records.filter { it.status == RecordStatus.UNREVIEWED }
    val isParking: Boolean get() = session != null
}

sealed interface ReceiveResult {
    data class Recorded(val record: ImpactRecord) : ReceiveResult
    data class Duplicate(val eventId: String) : ReceiveResult
    data class NoEvent(val reason: String) : ReceiveResult
    data class Rejected(val reason: String) : ReceiveResult
    data object NoSession : ReceiveResult
}

/** 재탑승 때 알릴 내용. 미확인 기록이 없으면 null(불필요한 알림을 내지 않는다). */
data class ReturnSummary(val count: Int, val latest: ImpactRecord, val recordIds: List<String>)

/**
 * 주차 충격 확인의 상태 전이. 시각(nowMs)은 호출하는 쪽이 넣는다.
 * IDLE(세션 없음) → PARKING(세션 있음, 이벤트 수신) → 재탑승(세션 종료, 미확인 요약) → 사건별 확인 완료.
 */
class ParkingMonitor(
    state: MonitorState = MonitorState(),
    private val estimator: RegionEstimator = RegionEstimator(),
) {
    var state: MonitorState = state
        private set

    /** 주차 시작. 이미 주차 중이면 그대로 둔다. */
    fun startParking(nowMs: Long): ParkingSession {
        state.session?.let { return it }
        val session = ParkingSession(id = "session-${state.nextId}", startedAtMs = nowMs)
        state = state.copy(session = session, nextId = state.nextId + 1)
        return session
    }

    /**
     * 가상 센서 이벤트 수신. 같은 eventId 는 한 번만 기록한다(삭제한 기록이어도 다시 만들지 않는다).
     * 주차 중이 아니면 기록하지 않는다.
     */
    fun receive(event: RawImpactEvent, nowMs: Long): ReceiveResult {
        val session = state.session ?: return ignore(event.eventId, nowMs, "주차 중이 아님", ReceiveResult.NoSession)
        if (event.eventId in state.seenEventIds) {
            return ignore(event.eventId, nowMs, "이미 받은 이벤트", ReceiveResult.Duplicate(event.eventId))
        }
        return when (val r = estimator.estimate(event)) {
            is EstimationResult.Rejected -> ignore(event.eventId, nowMs, r.reason, ReceiveResult.Rejected(r.reason))
            is EstimationResult.NoEvent -> {
                state = state.copy(seenEventIds = state.seenEventIds + event.eventId)
                ignore(event.eventId, nowMs, r.reason, ReceiveResult.NoEvent(r.reason))
            }
            is EstimationResult.Detected -> {
                val record = ImpactRecord(
                    id = "record-${state.nextId}", rawEventIds = listOf(event.eventId), sessionId = session.id,
                    source = event.source, occurredAt = event.occurredAt, receivedAtMs = nowMs, estimate = r.estimate,
                )
                state = state.copy(
                    records = state.records + record, seenEventIds = state.seenEventIds + event.eventId,
                    nextId = state.nextId + 1,
                )
                ReceiveResult.Recorded(record)
            }
        }
    }

    /** JSON 해석부터 실패한 입력도 이유를 남긴다(정상 이벤트로 저장하지 않음). */
    fun recordInvalid(label: String, reason: String, nowMs: Long) {
        ignore(label, nowMs, reason, ReceiveResult.Rejected(reason))
    }

    /** 저장에 실패했을 때 바꾸기 전 상태로 되돌린다(화면·디스크·메모리를 어긋나지 않게). */
    fun rollback(previous: MonitorState) {
        state = previous
    }

    private fun <T : ReceiveResult> ignore(eventId: String, nowMs: Long, reason: String, result: T): T {
        state = state.copy(ignored = (state.ignored + IgnoredInput(eventId, nowMs, reason)).takeLast(MAX_IGNORED))
        return result
    }

    /**
     * 재탑승. 주차 세션을 끝내고 미확인 기록이 있으면 요약을 돌려준다(앱 재시작 전 기록 포함).
     * 미확인 기록이 없으면 null — 충격 경고를 내지 않는다.
     */
    fun returnToCar(nowMs: Long): ReturnSummary? {
        val session = state.session
        if (session != null) {
            state = state.copy(session = null, pastSessions = (state.pastSessions + session.copy(endedAtMs = nowMs)).takeLast(MAX_SESSIONS))
        }
        val pending = state.unreviewed
        if (pending.isEmpty()) {
            state = state.copy(notice = null)
            return null
        }
        val ids = pending.map { it.id }
        // 새 주차 없이 재탑승을 다시 처리하면(같은 미확인 목록) 닫은 알림을 다시 만들지 않는다.
        val prev = state.notice
        if (session == null && prev != null && prev.recordIds.toSet() == ids.toSet()) {
            return if (prev.dismissed) null else summaryOf(pending)
        }
        state = state.copy(notice = NoticeState(session?.id ?: prev?.sessionId ?: "-", ids))
        return summaryOf(pending)
    }

    /** 지금 미확인 기록 요약(화면이 저장 상태에서 다시 계산할 때도 쓴다). 없으면 null */
    fun currentSummary(): ReturnSummary? = state.unreviewed.takeIf { it.isNotEmpty() }?.let(::summaryOf)

    private fun summaryOf(pending: List<ImpactRecord>) =
        ReturnSummary(pending.size, pending.maxBy { occurredMs(it) }, pending.map { it.id })

    /** 요약 알림 닫기. 기록과 미확인 상태는 그대로 남는다. */
    fun dismissNotice() {
        state.notice?.let { state = state.copy(notice = it.copy(dismissed = true)) }
    }

    /** 사건 확인 완료(메모 포함). 이미 확인한 기록도 메모는 고칠 수 있다. */
    fun acknowledge(recordId: String, note: String, nowMs: Long): Boolean {
        val target = state.records.firstOrNull { it.id == recordId } ?: return false
        val updated = target.copy(
            status = RecordStatus.REVIEWED, note = note.trim().take(MAX_NOTE),
            acknowledgedAtMs = target.acknowledgedAtMs ?: nowMs,
        )
        state = state.copy(records = state.records.map { if (it.id == recordId) updated else it })
        clearNoticeIfDone()
        return true
    }

    /** 기록 삭제. 같은 이벤트가 다시 와도 되살리지 않는다(seenEventIds 유지). */
    fun delete(recordId: String): Boolean {
        if (state.records.none { it.id == recordId }) return false
        state = state.copy(
            records = state.records.filterNot { it.id == recordId },
            notice = state.notice?.let { n -> n.copy(recordIds = n.recordIds - recordId) },
        )
        clearNoticeIfDone()
        return true
    }

    private fun clearNoticeIfDone() {
        val n = state.notice ?: return
        val stillPending = n.recordIds.any { id -> state.records.any { it.id == id && it.status == RecordStatus.UNREVIEWED } }
        if (!stillPending) state = state.copy(notice = null)
    }

    companion object {
        const val MAX_IGNORED = 50
        const val MAX_SESSIONS = 30
        const val MAX_NOTE = 200
    }
}

/** 저장 형식(JSON). 모르는 필드는 무시해 이후 버전과 호환한다. */
object MonitorCodec {
    fun encode(state: MonitorState): String = ImpactJson.json.encodeToString(MonitorState.serializer(), state)
    fun decode(text: String): MonitorState = ImpactJson.json.decodeFromString(MonitorState.serializer(), text)
}

/** 발생 시각(ms). 문자열 비교는 소수점 유무에 따라 순서가 뒤바뀌므로 시각으로 바꿔 비교한다. */
fun occurredMs(r: ImpactRecord): Long = runCatching { java.time.Instant.parse(r.occurredAt).toEpochMilli() }.getOrDefault(r.receivedAtMs)
