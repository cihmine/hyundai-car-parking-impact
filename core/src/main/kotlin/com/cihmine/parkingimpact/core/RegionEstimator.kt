package com.cihmine.parkingimpact.core

/**
 * 구역 추정 설정. **모두 시연용 값**이며 실측 데이터로 정한 값이 아니다.
 */
data class EstimatorConfig(
    val supportedSchema: Int = 1,
    /** 한 이벤트 안 샘플의 상대 시각 허용 범위(ms) */
    val windowMs: Long = 2_000,
    /** 모든 채널 최대값이 이보다 작으면 기록할 충격 없음 */
    val noEventThreshold: Double = 0.4,
    /** 이웃 구역(차체를 따라 맞닿은 양옆 구역) 신호를 점수에 더하는 비율. 맞닿지 않은 구역은 더하지 않는다. */
    val neighborWeight: Double = 0.25,
    /** 1위와 2위 점수 차이(1위 대비 비율)가 이보다 작으면 한 구역으로 좁히지 않는다 */
    val unknownMargin: Double = 0.2,
    /** 차이가 이 이상이면 "근거 충분", 그 사이는 "근거 제한적" */
    val sufficientMargin: Double = 0.4,
) {
    companion object {
        val DEMO = EstimatorConfig()
    }
}

sealed interface EstimationResult {
    data class Detected(val estimate: Estimate) : EstimationResult

    /** 신호가 약해 기록할 충격이 없음 */
    data class NoEvent(val reason: String) : EstimationResult

    /** 형식·값 오류. 정상 이벤트로 저장하지 않는다. */
    data class Rejected(val reason: String) : EstimationResult
}

/**
 * 입력 채널 값으로 충격 의심 구역을 고르는 명시적 규칙. 도착 시간차는 쓰지 않는다
 * (동기화된 고해상도 측정과 차체 보정 없이 콜백 시각 차이로 위치를 계산할 수 없다).
 */
class RegionEstimator(private val config: EstimatorConfig = EstimatorConfig.DEMO) {

    fun estimate(event: RawImpactEvent): EstimationResult {
        validate(event)?.let { return EstimationResult.Rejected(it) }

        val peaks: Map<VirtualChannel, Double> = event.samples.associate { VirtualChannel.byId(it.channelId)!! to it.normalizedPeak }
        // 채널이 빠졌으면 나머지가 약해도 "충격 없음"이라고 단정하지 않는다.
        val missing = VirtualChannel.entries - peaks.keys
        if (missing.isNotEmpty()) {
            return EstimationResult.Detected(
                Estimate(
                    Region.UNKNOWN, Evidence.UNDETERMINED,
                    "정보 부족: ${missing.joinToString { it.region.label }} 채널 값이 없어 구역을 좁히지 않음",
                    candidates = emptyList(),
                ),
            )
        }

        val maxPeak = peaks.values.max()
        if (maxPeak <= 0.0 || maxPeak < config.noEventThreshold) {
            return EstimationResult.NoEvent("모든 채널 신호가 기록 기준(${config.noEventThreshold})보다 작음")
        }

        val scores = Region.entries.filter { it != Region.UNKNOWN }.associateWith { score(it, peaks) }
        val ranked = scores.entries.sortedByDescending { it.value }
        val (top, topScore) = ranked[0].toPair()
        val secondScore = ranked[1].value
        val margin = (topScore - secondScore) / topScore

        if (margin < config.unknownMargin) {
            val candidates = ranked.filter { it.value >= topScore * (1 - config.unknownMargin) }.map { it.key }
            return EstimationResult.Detected(
                Estimate(
                    Region.UNKNOWN, Evidence.UNDETERMINED,
                    "여러 구역 신호가 비슷함(${candidates.joinToString { it.label }})",
                    scores, candidates,
                ),
            )
        }
        val evidence = if (margin >= config.sufficientMargin) Evidence.SUFFICIENT else Evidence.LIMITED
        // 판단 기준은 1위 대비 차이(margin)지만, 안내는 읽기 쉬운 "2위보다 몇 % 높음"(2위 대비 증가율)으로 쓴다.
        val detail = if (secondScore <= 0.0) "다른 구역 신호 없음" else "다음 구역보다 점수가 ${((topScore / secondScore - 1) * 100).toInt()}% 높음"
        return EstimationResult.Detected(
            Estimate(top, evidence, "${top.label} 채널 신호가 가장 큼($detail)", scores),
        )
    }

    private fun score(region: Region, peaks: Map<VirtualChannel, Double>): Double {
        val own = peaks.getValue(channelOf(region))
        val neighbors = neighborsOf(region).sumOf { peaks.getValue(channelOf(it)) }
        return own + config.neighborWeight * neighbors
    }

    private fun channelOf(region: Region) = VirtualChannel.entries.first { it.region == region }

    private fun neighborsOf(region: Region): List<Region> = when (region) {
        // 차체 둘레를 따라 앞쪽 → 왼쪽 앞문 → 왼쪽 뒷문 → 뒤쪽 → 오른쪽 뒷문 → 오른쪽 앞문 → 앞쪽으로 이어진다.
        Region.FRONT -> listOf(Region.LEFT_FRONT_DOOR, Region.RIGHT_FRONT_DOOR)
        Region.LEFT_FRONT_DOOR -> listOf(Region.FRONT, Region.LEFT_REAR_DOOR)
        Region.LEFT_REAR_DOOR -> listOf(Region.LEFT_FRONT_DOOR, Region.REAR)
        Region.REAR -> listOf(Region.LEFT_REAR_DOOR, Region.RIGHT_REAR_DOOR)
        Region.RIGHT_REAR_DOOR -> listOf(Region.REAR, Region.RIGHT_FRONT_DOOR)
        Region.RIGHT_FRONT_DOOR -> listOf(Region.RIGHT_REAR_DOOR, Region.FRONT)
        Region.UNKNOWN -> emptyList()
    }

    /** 형식 오류 이유. 정상이면 null */
    private fun validate(event: RawImpactEvent): String? {
        if (event.schemaVersion != config.supportedSchema) return "지원하지 않는 형식 버전(${event.schemaVersion})"
        if (event.eventId.isBlank()) return "이벤트 ID 없음"
        if (runCatching { java.time.Instant.parse(event.occurredAt) }.isFailure) return "발생 시각 형식 오류(${event.occurredAt})"
        if (event.samples.isEmpty()) return "샘플 없음"
        val ids = event.samples.map { it.channelId }
        ids.firstOrNull { VirtualChannel.byId(it) == null }?.let { return "알 수 없는 채널($it)" }
        if (ids.size != ids.toSet().size) return "같은 채널이 두 번 들어옴"
        for (s in event.samples) {
            val v = s.normalizedPeak
            if (v.isNaN() || v.isInfinite() || v < 0.0 || v > 1.0) return "값 범위 오류(${s.channelId}=$v)"
            if (s.relativeTimeMs !in 0..config.windowMs) return "시간 범위 오류(${s.channelId}=${s.relativeTimeMs}ms)"
        }
        return null
    }
}
