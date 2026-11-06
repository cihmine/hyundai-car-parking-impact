package com.cihmine.parkingimpact.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 진행 방향 기준 차량 구역. 화면 좌우가 아니라 운전석에 앉아 앞을 볼 때의 좌우다.
 * 확정 파손 부위가 아니라 "확인 권장 구역"이다.
 */
@Serializable
enum class Region(val label: String) {
    FRONT("앞쪽"),
    LEFT_FRONT_DOOR("왼쪽 앞문"),
    LEFT_REAR_DOOR("왼쪽 뒷문"),
    RIGHT_FRONT_DOOR("오른쪽 앞문"),
    RIGHT_REAR_DOOR("오른쪽 뒷문"),
    REAR("뒤쪽"),

    /** 신호가 비슷하거나 정보가 부족해 한 구역으로 좁히지 않음 */
    UNKNOWN("판단 어려움"),
}

/**
 * 추정 근거의 양. 규칙상 정보량일 뿐 실제 정확도 측정값이 아니다(화면에 그렇게 설명한다).
 */
@Serializable
enum class Evidence(val label: String) {
    SUFFICIENT("근거 충분"),
    LIMITED("근거 제한적"),
    UNDETERMINED("판단 어려움"),
}

@Serializable
enum class Source(val label: String) {
    /** 시연용 가상 센서 입력. 실제 차량 센서 값이 아니다. */
    SIMULATED("모의 입력"),
}

@Serializable
data class ImpactSample(
    val channelId: String,
    val relativeTimeMs: Long,
    /** 모의 정규화 값(0~1). 실측 g 나 충격 에너지가 아니다. */
    val normalizedPeak: Double,
)

/** 가상 센서 이벤트(demo/scenarios 폴더의 JSON 형식). 채널명·값은 모두 가상이다. */
@Serializable
data class RawImpactEvent(
    val schemaVersion: Int,
    val source: Source,
    val eventId: String,
    val parkingSessionId: String? = null,
    /** ISO-8601 시각 문자열 */
    val occurredAt: String,
    val samples: List<ImpactSample>,
    /** 시나리오 설명(시연 패널 표시용, 판정에는 쓰지 않음) */
    val description: String? = null,
)

/** 시나리오 JSON 해석 결과. 형식이 틀리면 이유를 남기고 저장하지 않는다. */
sealed interface ParsedEvent {
    data class Ok(val event: RawImpactEvent) : ParsedEvent
    data class Invalid(val reason: String) : ParsedEvent
}

object ImpactJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(text: String): ParsedEvent = runCatching { json.decodeFromString(RawImpactEvent.serializer(), text) }
        .fold({ ParsedEvent.Ok(it) }, { ParsedEvent.Invalid("형식 오류: ${it.message?.lineSequence()?.firstOrNull()?.take(80)}") })
}

/** 가상 채널 6개(구역마다 하나). 차량에 이 위치의 센서가 있다는 뜻이 아니다. */
enum class VirtualChannel(val id: String, val region: Region) {
    FRONT("front_virtual", Region.FRONT),
    LEFT_FRONT_DOOR("left_front_door_virtual", Region.LEFT_FRONT_DOOR),
    LEFT_REAR_DOOR("left_rear_door_virtual", Region.LEFT_REAR_DOOR),
    RIGHT_FRONT_DOOR("right_front_door_virtual", Region.RIGHT_FRONT_DOOR),
    RIGHT_REAR_DOOR("right_rear_door_virtual", Region.RIGHT_REAR_DOOR),
    REAR("rear_virtual", Region.REAR),
    ;

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class Estimate(
    val region: Region,
    val evidence: Evidence,
    /** 사용자에게 보여 줄 추정 이유 */
    val reason: String,
    /** 구역별 점수(진단용) */
    val scores: Map<Region, Double> = emptyMap(),
    /** UNKNOWN 일 때 가능성이 있는 구역들 */
    val candidates: List<Region> = emptyList(),
)
