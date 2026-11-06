package com.cihmine.parkingimpact.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/PLAN.md 9절 P-T22: 사용자에게 알리는 충격 의심 문구 */
class ImpactNoticeTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val noon = Instant.parse("2026-11-06T03:32:00Z") // 서울 오후 12:32

    private fun record(estimate: Estimate, at: Instant = noon) = ImpactRecord(
        id = "r",
        rawEventIds = listOf("e"),
        sessionId = "s",
        source = Source.SIMULATED,
        occurredAt = at.toString(),
        receivedAtMs = at.toEpochMilli(),
        estimate = estimate,
    )

    private fun at(region: Region, candidates: List<Region> = emptyList()) =
        Estimate(region, Evidence.SUFFICIENT, "이유", candidates = candidates)

    private val later = noon.plusSeconds(3_600).toEpochMilli()

    @Test
    fun `P-T22 구역과 시각을 넣어 충격 의심을 알린다`() {
        assertEquals(
            "오른쪽 앞문에 오후 12:32분 쯤 충격 의심이 감지되었습니다",
            Texts.impactNotice(record(at(Region.RIGHT_FRONT_DOOR)), later, zone),
        )
    }

    @Test
    fun `구역을 좁히지 못하면 후보를 쓰고 후보도 없으면 차량으로 쓴다`() {
        val both = at(Region.UNKNOWN, listOf(Region.LEFT_FRONT_DOOR, Region.LEFT_REAR_DOOR))
        assertTrue(Texts.impactNotice(record(both), later, zone).startsWith("왼쪽 앞문 또는 왼쪽 뒷문 쪽에 "))
        assertTrue(Texts.impactNotice(record(at(Region.UNKNOWN)), later, zone).startsWith("차량에 "))
    }

    @Test
    fun `오늘이 아닌 기록은 날짜도 쓴다`() {
        val nextDay = noon.plusSeconds(86_400).toEpochMilli()
        assertEquals(
            "뒤쪽에 11월 6일 오후 12:32분 쯤 충격 의심이 감지되었습니다",
            Texts.impactNotice(record(at(Region.REAR)), nextDay, zone),
        )
    }

    @Test
    fun `시각을 읽지 못해도 문구가 깨지지 않는다`() {
        val broken = record(at(Region.FRONT)).copy(occurredAt = "잘못된 시각", receivedAtMs = noon.toEpochMilli())
        assertEquals("앞쪽에 오후 12:32분 쯤 충격 의심이 감지되었습니다", Texts.impactNotice(broken, later, zone))
    }

    @Test
    fun `음성 안내도 같은 내용과 확인 권유를 말한다`() {
        val first = record(at(Region.RIGHT_FRONT_DOOR))
        val one = Texts.summarySpeech(ReturnSummary(1, first, listOf("r")), later, zone)
        assertTrue("오른쪽 앞문에" in one && "오후 12시 32분쯤" in one && "감지되었습니다" in one)
        assertTrue(Texts.IMPACT_CHECK_NOTE in one)
        assertFalse("건 더" in one)
        assertTrue("1건 더" in Texts.summarySpeech(ReturnSummary(2, first, listOf("r", "q")), later, zone))
    }

    @Test
    fun `오늘이 아닌 기록은 음성에도 날짜를 말한다`() {
        val nextDay = noon.plusSeconds(86_400).toEpochMilli()
        val speech = Texts.summarySpeech(ReturnSummary(1, record(at(Region.REAR)), listOf("r")), nextDay, zone)
        assertTrue("11월 6일 오후 12시 32분쯤" in speech)
    }
}
