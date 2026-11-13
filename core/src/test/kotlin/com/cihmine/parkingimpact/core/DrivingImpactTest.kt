package com.cihmine.parkingimpact.core

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** docs/PLAN.md 9절 P-T23~25: 주행 중 작은 충격은 화면 없이 음성으로만 알리고 기록해 둔다 */
class DrivingImpactTest {
    private fun scenario(name: String): RawImpactEvent =
        assertIs<ParsedEvent.Ok>(ImpactJson.parse(File("../demo/scenarios/$name.json").readText())).event

    @Test
    fun `P-T23 주행 중 충격은 주행 중 기록으로 남고 주차 후 요약에 들어간다`() {
        val m = ParkingMonitor()
        val r = assertIs<ReceiveResult.Recorded>(m.receive(scenario("02_right_rear_door"), 1_000, driving = true)).record
        assertTrue(r.whileDriving)
        assertEquals(ParkingMonitor.DRIVING_SESSION, r.sessionId)
        assertEquals(Region.RIGHT_REAR_DOOR, r.estimate.region)
        assertEquals(1, m.currentSummary()?.count)
        // 주차 세션이 없을 때 주행이 아니면 여전히 기록하지 않는다
        assertEquals(ReceiveResult.NoSession, ParkingMonitor().receive(scenario("02_right_rear_door"), 1_000))
    }

    @Test
    fun `주행 중에도 약한 신호와 중복은 기록하지 않는다`() {
        val m = ParkingMonitor()
        assertIs<ReceiveResult.NoEvent>(m.receive(scenario("03_weak"), 1_000, driving = true))
        assertIs<ReceiveResult.Recorded>(m.receive(scenario("01_left_front_door"), 2_000, driving = true))
        assertIs<ReceiveResult.Duplicate>(m.receive(scenario("01_left_front_door"), 3_000, driving = true))
        assertEquals(1, m.state.records.size)
    }

    @Test
    fun `주차 세션이 있으면 주행 표시 없이 주차 기록이다`() {
        val m = ParkingMonitor().also { it.startParking(1_000) }
        val r = assertIs<ReceiveResult.Recorded>(m.receive(scenario("01_left_front_door"), 2_000, driving = true)).record
        assertFalse(r.whileDriving)
    }

    @Test
    fun `P-T24 노면 충격이 몰려도 1분에 한 번만 말한다`() {
        val gate = DrivingAlertGate()
        assertTrue(gate.allow(0))
        assertFalse(gate.allow(10_000))
        assertFalse(gate.allow(59_999))
        assertTrue(gate.allow(60_000))
        gate.reset()
        assertTrue(gate.allow(60_001))
    }

    @Test
    fun `P-T25 주행 중 음성과 화면 문구`() {
        val zone = ZoneId.of("Asia/Seoul")
        val at = Instant.parse("2026-11-06T03:32:00Z")
        val r = ImpactRecord(
            id = "r", rawEventIds = listOf("e"), sessionId = ParkingMonitor.DRIVING_SESSION, source = Source.SIMULATED,
            occurredAt = at.toString(), receivedAtMs = at.toEpochMilli(),
            estimate = Estimate(Region.RIGHT_REAR_DOOR, Evidence.SUFFICIENT, "이유"), whileDriving = true,
        )
        assertEquals("오른쪽 뒷문 쪽 충격이 의심돼요. 주차 후 확인해 주세요.", Texts.drivingSpeech(r))
        assertEquals("오른쪽 뒷문에 주행 중 오후 12:32분 쯤 충격 의심이 감지되었습니다", Texts.impactNotice(r, at.toEpochMilli(), zone))
        // 후보가 여러 곳이면 다 읽지 않는다
        val vague = r.copy(estimate = Estimate(Region.UNKNOWN, Evidence.SUFFICIENT, "이유", candidates = listOf(Region.LEFT_FRONT_DOOR, Region.LEFT_REAR_DOOR)))
        assertEquals("차량에 충격이 의심돼요. 주차 후 확인해 주세요.", Texts.drivingSpeech(vague))
    }
}
