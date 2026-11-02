package com.cihmine.parkingimpact.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/PLAN.md 9절 필수 검증 사례(P-T01~P-T10) 중 순수 로직으로 확인할 수 있는 것 */
class ParkingMonitorTest {
    private fun scenario(name: String): RawImpactEvent =
        assertIs<ParsedEvent.Ok>(ImpactJson.parse(File("../demo/scenarios/$name.json").readText())).event

    private fun parked(): ParkingMonitor = ParkingMonitor().also { it.startParking(1_000) }

    private fun recorded(m: ParkingMonitor, name: String): ImpactRecord =
        assertIs<ReceiveResult.Recorded>(m.receive(scenario(name), 2_000)).record

    @Test
    fun `P-T01 좌앞·우뒤 우세 시나리오는 해당 구역, 모의 입력으로 표시`() {
        val m = parked()
        val fl = recorded(m, "01_front_left")
        assertEquals(Region.FRONT_LEFT, fl.estimate.region)
        assertEquals(Evidence.SUFFICIENT, fl.estimate.evidence)
        assertEquals(Source.SIMULATED, fl.source)
        assertEquals(Region.REAR_RIGHT, recorded(m, "02_rear_right").estimate.region)
    }

    @Test
    fun `P-T02 비슷한 점수·채널 누락은 확정 구역 대신 판단 어려움`() {
        val m = parked()
        val amb = recorded(m, "04_ambiguous").estimate
        assertEquals(Region.UNKNOWN, amb.region)
        assertEquals(listOf(Region.FRONT_LEFT, Region.FRONT_RIGHT), amb.candidates)
        val missing = recorded(m, "05_missing_channel").estimate
        assertEquals(Region.UNKNOWN, missing.region)
        assertTrue(missing.reason.startsWith("정보 부족"), missing.reason)
    }

    @Test
    fun `P-T03 약한 신호·형식 오류는 저장하지 않고 이유를 남긴다`() {
        val m = parked()
        assertIs<ReceiveResult.NoEvent>(m.receive(scenario("03_weak"), 2_000))
        assertIs<ReceiveResult.NoEvent>(m.receive(scenario("06_door_like"), 2_000))
        assertIs<ReceiveResult.Rejected>(m.receive(scenario("07_malformed"), 2_000))
        assertIs<ParsedEvent.Invalid>(ImpactJson.parse("""{"schemaVersion":1,"eventId":"x"}"""))
        assertTrue(m.state.records.isEmpty())
        assertEquals(3, m.state.ignored.size)
        assertTrue(m.state.ignored.last().reason.contains("값 범위 오류"))
    }

    @Test
    fun `형식 오류 입력 — 버전·시각·채널·값·시간 범위`() {
        val base = scenario("01_front_left")
        val est = RegionEstimator()
        fun reject(e: RawImpactEvent) = assertIs<EstimationResult.Rejected>(est.estimate(e)).reason
        assertTrue(reject(base.copy(schemaVersion = 2)).contains("버전"))
        assertTrue(reject(base.copy(occurredAt = "어제")).contains("시각"))
        assertTrue(reject(base.copy(samples = base.samples + ImpactSample("roof_virtual", 0, 0.5))).contains("알 수 없는 채널"))
        assertTrue(reject(base.copy(samples = base.samples + base.samples[0])).contains("두 번"))
        assertTrue(reject(base.copy(samples = listOf(base.samples[0].copy(normalizedPeak = -0.1)) + base.samples.drop(1))).contains("값 범위"))
        assertTrue(reject(base.copy(samples = listOf(base.samples[0].copy(normalizedPeak = Double.NaN)) + base.samples.drop(1))).contains("값 범위"))
        assertTrue(reject(base.copy(samples = listOf(base.samples[0].copy(relativeTimeMs = 9_999)) + base.samples.drop(1))).contains("시간 범위"))
    }

    @Test
    fun `P-T04 같은 eventId 를 두 번 받아도 기록·알림은 한 번`() {
        val m = parked()
        recorded(m, "01_front_left")
        assertIs<ReceiveResult.Duplicate>(m.receive(scenario("01_front_left"), 3_000))
        assertEquals(1, m.state.records.size)
        assertEquals(1, m.returnToCar(4_000)?.count)
    }

    @Test
    fun `P-T05 독립 사건 두 건은 따로 남고 요약 개수가 맞다`() {
        val m = parked()
        recorded(m, "01_front_left")
        recorded(m, "02_rear_right")
        val summary = assertNotNull(m.returnToCar(5_000))
        assertEquals(2, summary.count)
        assertEquals("demo-impact-002", summary.latest.rawEventIds.single(), "가장 최근 사건을 먼저 알린다")
    }

    @Test
    fun `P-T06 기록 후 앱이 꺼졌다 켜져도 미확인 사건이 남는다`() {
        val m = parked()
        recorded(m, "01_front_left")
        val restored = ParkingMonitor(MonitorCodec.decode(MonitorCodec.encode(m.state)))
        assertTrue(restored.state.isParking)
        assertIs<ReceiveResult.Duplicate>(restored.receive(scenario("01_front_left"), 6_000), "재시작 뒤에도 중복 차단")
        assertEquals(1, restored.returnToCar(7_000)?.count)
    }

    @Test
    fun `P-T07 알림 닫기는 기록을 남기고, 확인 완료만 상태를 바꾼다`() {
        val m = parked()
        val r = recorded(m, "01_front_left")
        m.returnToCar(3_000)
        m.dismissNotice()
        assertEquals(1, m.state.unreviewed.size)
        assertTrue(m.state.notice!!.dismissed)
        assertTrue(m.acknowledge(r.id, "  앞범퍼 확인함  ", 4_000))
        assertEquals(RecordStatus.REVIEWED, m.state.records.single().status)
        assertEquals("앞범퍼 확인함", m.state.records.single().note)
        assertNull(m.state.notice, "모두 확인하면 알림 상태를 지운다")
    }

    @Test
    fun `P-T09 사건 없는 재탑승은 경고하지 않는다(약한 신호만 있었어도)`() {
        val m = parked()
        m.receive(scenario("03_weak"), 2_000)
        assertNull(m.returnToCar(3_000))
        assertFalse(m.state.isParking)
    }

    @Test
    fun `P-T10 삭제하면 목록·미확인 개수가 일치하고 같은 이벤트로 되살아나지 않는다`() {
        val m = parked()
        val a = recorded(m, "01_front_left")
        recorded(m, "02_rear_right")
        m.returnToCar(3_000)
        assertTrue(m.delete(a.id))
        assertEquals(1, m.state.records.size)
        assertEquals(1, m.state.unreviewed.size)
        assertEquals(1, m.state.notice!!.recordIds.size)
        m.startParking(4_000)
        assertIs<ReceiveResult.Duplicate>(m.receive(scenario("01_front_left"), 5_000))
    }

    @Test
    fun `주차 중이 아니면 기록하지 않는다`() {
        val m = ParkingMonitor()
        assertEquals(ReceiveResult.NoSession, m.receive(scenario("01_front_left"), 1_000))
        assertTrue(m.state.records.isEmpty())
    }

    @Test
    fun `이전 주차의 미확인 기록도 다음 재탑승 요약에 포함된다`() {
        val m = parked()
        recorded(m, "01_front_left")
        m.returnToCar(3_000)
        m.dismissNotice()
        m.startParking(4_000)
        recorded(m, "02_rear_right")
        assertEquals(2, m.returnToCar(5_000)?.count)
    }

    @Test
    fun `입력 채널 값을 바꾸면 규칙에 따라 다른 구역이 나온다(화면 정답만 바꾸는 목업이 아님)`() {
        val base = scenario("01_front_left")
        val est = RegionEstimator()
        val peaks = mapOf("front_left_virtual" to 0.1, "front_right_virtual" to 0.9, "rear_left_virtual" to 0.1, "rear_right_virtual" to 0.3)
        val moved = base.copy(samples = base.samples.map { it.copy(normalizedPeak = peaks.getValue(it.channelId)) })
        assertEquals(Region.FRONT_RIGHT, assertIs<EstimationResult.Detected>(est.estimate(moved)).estimate.region)
    }
}
