package com.cihmine.parkingimpact.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 비상 대응 중 차량에 보내는 명령 */
enum class CarCommand { HAZARD_ON, TRUNK_OPEN }

enum class ControlStatus(val label: String) {
    /** 정차를 확인하지 못해 아직 보내지 않은 명령 */
    HELD("보류 — 차가 멈추면 열어요"),
    REQUESTED("작동 중…"),
    DONE("작동 완료(모의)"),
    FAILED("작동 실패 — 직접 조작해 주세요"),
}

data class AccidentConfig(
    /** 이 시간 안에 차량이 답하지 않으면 실패로 본다 */
    val carTimeoutMs: Long = 3_000,
)

/** 시각은 모두 단조 시계 값이다. */
data class AccidentState(
    val stopConfirmed: Boolean,
    val hazard: ControlStatus,
    val trunk: ControlStatus,
    val hazardRequestedAtMs: Long?,
    val trunkRequestedAtMs: Long?,
    /** 정차를 확인하지 못해도 사용자가 차를 세웠다고 알려 온 경우 */
    val contactsRequested: Boolean = false,
) {
    /**
     * 연락처는 차가 멈췄을 때 보여 준다(주행 중에 번호를 읽지 않게). 기어가 P 가 아닌 채 멈췄거나 기어 값을 모를 수 있으므로
     * 사용자가 직접 요청해도 보여 준다.
     */
    val showContacts: Boolean get() = stopConfirmed || contactsRequested

    /** 트렁크를 연 뒤 차가 다시 움직이는 경우(화면에 경고) */
    val trunkOpenWhileMoving: Boolean get() = trunk == ControlStatus.DONE && !stopConfirmed
}

/**
 * 사고·고장 때 비상 버튼을 누른 뒤의 순서를 정하는 규칙. 2차 사고 예방 요령 '비트박스'(비상등·트렁크·밖으로 대피·스마트폰 신고)를 따른다.
 *
 * - 비상등은 차량 상태와 상관없이 바로 켠다.
 * - 트렁크는 정차가 확인될 때만 연다. 주행 중이거나 상태를 모르면 보류했다가 정차가 확인되면 연다.
 * - 정차가 확인되면 도움을 요청할 번호([Hotlines])를 보여 준다. 앱이 대신 전화하거나 신고하지 않는다.
 * - 차량 명령이 실패해도 다른 동작은 계속한다.
 *
 * 돌려준 [CarCommand] 를 실행하는 쪽은 앱이다. 트렁크는 차량이 지원하면 실제로 열고, 비상등은 공개 SDK 에 기능이 없어 모의다.
 */
class AccidentResponse(private val config: AccidentConfig = AccidentConfig()) {
    var state: AccidentState? = null
        private set

    /** 보낼 차량 명령을 돌려준다. 이미 진행 중이면(버튼 두 번 누름) 아무것도 다시 시작하지 않는다. */
    fun start(nowMs: Long, stopConfirmed: Boolean): List<CarCommand> {
        if (state != null) return emptyList()
        state = AccidentState(
            stopConfirmed = stopConfirmed,
            hazard = ControlStatus.REQUESTED,
            trunk = if (stopConfirmed) ControlStatus.REQUESTED else ControlStatus.HELD,
            hazardRequestedAtMs = nowMs,
            trunkRequestedAtMs = nowMs.takeIf { stopConfirmed },
        )
        return if (stopConfirmed) listOf(CarCommand.HAZARD_ON, CarCommand.TRUNK_OPEN) else listOf(CarCommand.HAZARD_ON)
    }

    /** 차량 상태가 바뀌었을 때. 보류한 트렁크는 정차가 확인되는 순간 한 번만 요청한다. */
    fun vehicleChanged(stopConfirmed: Boolean, nowMs: Long): List<CarCommand> {
        val s = state ?: return emptyList()
        if (stopConfirmed && s.trunk == ControlStatus.HELD) {
            state = s.copy(stopConfirmed = true, trunk = ControlStatus.REQUESTED, trunkRequestedAtMs = nowMs)
            return listOf(CarCommand.TRUNK_OPEN)
        }
        state = s.copy(stopConfirmed = stopConfirmed)
        return emptyList()
    }

    /** 차량 응답. 이미 시간 초과로 실패 처리한 명령의 늦은 응답은 무시한다. */
    fun carResult(command: CarCommand, success: Boolean) {
        val s = state ?: return
        val result = if (success) ControlStatus.DONE else ControlStatus.FAILED
        state = when (command) {
            CarCommand.HAZARD_ON -> if (s.hazard == ControlStatus.REQUESTED) s.copy(hazard = result) else s
            CarCommand.TRUNK_OPEN -> if (s.trunk == ControlStatus.REQUESTED) s.copy(trunk = result) else s
        }
    }

    /** 사용자가 차를 세웠다고 알려 왔을 때. 번호만 보여 주고, 트렁크는 정차가 확인될 때까지 계속 보류한다. */
    fun requestContacts() {
        state = state?.copy(contactsRequested = true)
    }

    /** 차량이 알려 준 위치로 트렁크가 열린 것을 확인했을 때. 응답이 늦어 실패로 처리했어도 실제 상태를 따른다. */
    fun trunkConfirmedOpen() {
        val s = state ?: return
        if (s.trunk != ControlStatus.HELD) state = s.copy(trunk = ControlStatus.DONE)
    }

    /** 시간 경과 처리: 답이 없는 차량 명령을 실패로 바꾼다. */
    fun tick(nowMs: Long) {
        var s = state ?: return
        if (s.hazard.timedOut(s.hazardRequestedAtMs, nowMs)) s = s.copy(hazard = ControlStatus.FAILED)
        if (s.trunk.timedOut(s.trunkRequestedAtMs, nowMs)) s = s.copy(trunk = ControlStatus.FAILED)
        state = s
    }

    /** 비상 대응 화면을 끝낸다. */
    fun finish() {
        state = null
    }

    private fun ControlStatus.timedOut(requestedAtMs: Long?, nowMs: Long) =
        this == ControlStatus.REQUESTED && requestedAtMs != null && nowMs - requestedAtMs >= config.carTimeoutMs
}

/** 차량 현재 좌표. 비상 화면에 보여 주기만 하고 저장하지 않는다. */
data class CarPosition(val latitude: Double, val longitude: Double) {
    /** 범위를 벗어나거나 (0, 0)이면 위치를 못 잡은 값으로 본다. */
    val valid: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)

    /** 두 좌표 사이 거리(미터, 구면 근사) */
    fun distanceMeters(other: CarPosition): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

/** 주소가 이 거리보다 먼 좌표의 것이면 지금 위치의 주소로 보지 않는다. */
const val ADDRESS_MATCH_METERS = 1_000.0

/**
 * 화면에 쓸 주소. 주소는 내비, 좌표는 위치 서비스에서 따로 오므로 둘이 어긋나면 주소를 버리고 좌표만 보여 준다
 * (비상 화면에 서로 다른 곳을 가리키는 값을 함께 내지 않는다).
 */
fun matchedAddress(address: String?, addressPosition: CarPosition?, position: CarPosition?): String? = when {
    address.isNullOrBlank() -> null
    position == null || addressPosition == null -> address
    position.distanceMeters(addressPosition) <= ADDRESS_MATCH_METERS -> address
    else -> null
}

data class Hotline(val name: String, val number: String)

/**
 * 비상 화면에 보여 주는 번호. 2026-11-06 웹 검색 기준이며, 제출 전 각 기관 공식 안내로 다시 확인한다.
 * 번호를 확인하지 못한 보험사는 넣지 않았다(화면에서 증권·보험사 앱을 보라고 안내한다).
 */
object Hotlines {
    val ROAD = Hotline("한국도로공사 · 고속도로 무료 긴급견인", "1588-2504")
    val INSURERS = listOf(
        Hotline("삼성화재", "1588-5114"),
        Hotline("현대해상", "1588-5656"),
        Hotline("DB손해보험", "1588-0100"),
        Hotline("KB손해보험", "1544-0114"),
        Hotline("롯데손해보험", "1588-3344"),
    )
}
