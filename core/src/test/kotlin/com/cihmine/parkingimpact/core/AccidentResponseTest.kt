package com.cihmine.parkingimpact.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/PLAN.md 9절 비상 대응 검증 사례(P-T11~P-T18) */
class AccidentResponseTest {
    private val t0 = 1_000L
    private fun AccidentResponse.s() = assertNotNull(state)

    @Test
    fun `P-T11 정차 중이면 비상등과 트렁크를 함께 요청하고 연락처를 보여 준다`() {
        val a = AccidentResponse()
        assertEquals(listOf(CarCommand.HAZARD_ON, CarCommand.TRUNK_OPEN), a.start(t0, stopConfirmed = true))
        assertEquals(ControlStatus.REQUESTED, a.s().hazard)
        assertEquals(ControlStatus.REQUESTED, a.s().trunk)
        assertTrue(a.s().showContacts)
    }

    @Test
    fun `P-T12 주행 중이거나 상태를 모르면 비상등만 켜고 트렁크와 연락처는 보류한다`() {
        val a = AccidentResponse()
        assertEquals(listOf(CarCommand.HAZARD_ON), a.start(t0, stopConfirmed = false))
        assertEquals(ControlStatus.HELD, a.s().trunk)
        assertFalse(a.s().showContacts)
        a.tick(t0 + 60_000)
        assertEquals(ControlStatus.HELD, a.s().trunk, "보류는 시간이 지나도 실패가 아니다")
    }

    @Test
    fun `P-T13 차가 멈추면 보류한 트렁크를 한 번만 요청하고 연락처를 보여 준다`() {
        val a = AccidentResponse()
        a.start(t0, stopConfirmed = false)
        assertEquals(emptyList(), a.vehicleChanged(false, t0 + 100))
        assertEquals(listOf(CarCommand.TRUNK_OPEN), a.vehicleChanged(true, t0 + 200))
        assertEquals(emptyList(), a.vehicleChanged(true, t0 + 300))
        assertEquals(ControlStatus.REQUESTED, a.s().trunk)
        assertTrue(a.s().showContacts)
    }

    @Test
    fun `P-T14 차가 다시 움직이면 연락처를 숨기고 트렁크 경고를 낸다`() {
        val a = AccidentResponse()
        a.start(t0, true)
        a.carResult(CarCommand.TRUNK_OPEN, true)
        assertFalse(a.s().trunkOpenWhileMoving)
        a.vehicleChanged(false, t0 + 100)
        assertTrue(a.s().trunkOpenWhileMoving)
        assertFalse(a.s().showContacts)
        a.vehicleChanged(true, t0 + 200)
        assertFalse(a.s().trunkOpenWhileMoving)
        assertTrue(a.s().showContacts)
    }

    @Test
    fun `P-T21 정차를 확인하지 못해도 사용자가 요청하면 번호를 보여 주고 트렁크는 계속 보류한다`() {
        val a = AccidentResponse()
        a.start(t0, stopConfirmed = false)
        assertFalse(a.s().showContacts)
        a.requestContacts()
        assertTrue(a.s().showContacts)
        assertEquals(ControlStatus.HELD, a.s().trunk)
        assertEquals(emptyList(), a.vehicleChanged(false, t0 + 100))
        assertTrue(a.s().showContacts, "기어 값이 다시 와도 요청한 번호는 사라지지 않는다")
        assertEquals(listOf(CarCommand.TRUNK_OPEN), a.vehicleChanged(true, t0 + 200))
    }

    @Test
    fun `P-T26 '차를 세웠어요' 뒤 다시 주행하면 번호를 숨긴다`() {
        val a = AccidentResponse()
        a.start(t0, stopConfirmed = false)
        a.requestContacts()
        assertTrue(a.s().showContacts)
        a.vehicleChanged(false, t0 + 100) // 상태 불명: 기어 없이 멈춘 것일 수 있어 유지
        assertTrue(a.s().showContacts)
        a.vehicleChanged(false, t0 + 200, moving = true)
        assertFalse(a.s().showContacts, "확실한 주행이면 주행 중 번호를 보여 주지 않는다")
        assertEquals(ControlStatus.HELD, a.s().trunk)
    }

    @Test
    fun `P-T15 연락처에 도로공사와 보험사 번호가 형식에 맞게 들어 있다`() {
        assertEquals("1588-2504", Hotlines.ROAD.number)
        assertTrue(Hotlines.INSURERS.isNotEmpty())
        val format = Regex("""\d{4}-\d{4}""")
        (Hotlines.INSURERS + Hotlines.ROAD).forEach { assertTrue(format.matches(it.number), it.name) }
        assertEquals(Hotlines.INSURERS.size, Hotlines.INSURERS.map { it.number }.toSet().size)
    }

    @Test
    fun `P-T16 차량 명령이 실패해도 다른 동작과 연락처 표시는 계속한다`() {
        val a = AccidentResponse()
        a.start(t0, true)
        a.carResult(CarCommand.HAZARD_ON, success = false)
        a.carResult(CarCommand.TRUNK_OPEN, success = true)
        assertEquals(ControlStatus.FAILED, a.s().hazard)
        assertEquals(ControlStatus.DONE, a.s().trunk)
        assertTrue(a.s().showContacts)
    }

    @Test
    fun `P-T17 차량이 답하지 않으면 시간 초과로 실패 처리하고 늦은 응답은 무시한다`() {
        val a = AccidentResponse()
        a.start(t0, true)
        a.tick(t0 + 2_999)
        assertEquals(ControlStatus.REQUESTED, a.s().hazard)
        a.tick(t0 + 3_000)
        assertEquals(ControlStatus.FAILED, a.s().hazard)
        assertEquals(ControlStatus.FAILED, a.s().trunk)
        a.carResult(CarCommand.HAZARD_ON, success = true)
        assertEquals(ControlStatus.FAILED, a.s().hazard)
    }

    @Test
    fun `시간 초과로 실패 처리한 트렁크도 열림이 확인되면 완료로 바꾸고 보류 중이면 바꾸지 않는다`() {
        val a = AccidentResponse()
        a.start(t0, true)
        a.tick(t0 + 3_000)
        assertEquals(ControlStatus.FAILED, a.s().trunk)
        a.trunkConfirmedOpen()
        assertEquals(ControlStatus.DONE, a.s().trunk)
        val held = AccidentResponse()
        held.start(t0, false)
        held.trunkConfirmedOpen()
        assertEquals(ControlStatus.HELD, held.s().trunk)
    }

    @Test
    fun `보류했다가 요청한 트렁크의 시간 초과는 요청한 때부터 잰다`() {
        val a = AccidentResponse()
        a.start(t0, false)
        a.vehicleChanged(true, t0 + 10_000)
        a.tick(t0 + 12_999)
        assertEquals(ControlStatus.REQUESTED, a.s().trunk)
        a.tick(t0 + 13_000)
        assertEquals(ControlStatus.FAILED, a.s().trunk)
    }

    @Test
    fun `P-T19 위치 문구는 주소, 좌표, 찾는 중, 실패 순서로 고른다`() {
        val p = CarPosition(37.47, 127.04)
        assertEquals("서울 서초구", Texts.locationHeadline("서울 서초구", p, failed = true))
        assertEquals("위도 37.47000, 경도 127.04000", Texts.locationHeadline(" ", p, failed = true))
        assertEquals("위치를 찾는 중…", Texts.locationHeadline(null, null, failed = false))
        assertTrue("표지판" in Texts.locationHeadline(null, null, failed = true))
    }

    @Test
    fun `P-T20 주소가 현재 좌표와 어긋나면 주소를 쓰지 않는다`() {
        val gps = CarPosition(37.4685, 127.0396)
        val near = CarPosition(37.4690, 127.0400)
        val far = CarPosition(37.4127212, 127.094681)
        assertEquals("근처", matchedAddress("근처", near, gps))
        assertNull(matchedAddress("먼 곳", far, gps))
        assertEquals("좌표 없음", matchedAddress("좌표 없음", far, null))
        assertNull(matchedAddress(" ", near, gps))
        assertTrue(gps.distanceMeters(far) in 7_000.0..9_000.0)
    }

    @Test
    fun `못 잡은 좌표는 위치로 쓰지 않는다`() {
        assertTrue(CarPosition(37.47, 127.04).valid)
        assertFalse(CarPosition(0.0, 0.0).valid)
        assertFalse(CarPosition(91.0, 127.0).valid)
        assertFalse(CarPosition(37.0, 181.0).valid)
        assertFalse(CarPosition(Double.NaN, 127.0).valid)
    }

    @Test
    fun `P-T18 버튼을 두 번 눌러도 다시 시작하지 않고, 끝낸 뒤의 늦은 응답은 영향이 없다`() {
        val a = AccidentResponse()
        a.start(t0, true)
        val first = a.s()
        assertEquals(emptyList(), a.start(t0 + 2_000, true))
        assertEquals(first, a.s())
        a.finish()
        assertNull(a.state)
        a.carResult(CarCommand.HAZARD_ON, success = true)
        a.tick(t0 + 10_000)
        assertNull(a.state)
    }
}
