package com.cihmine.parkingimpact.vehicle

enum class VehicleState(val label: String) {
    PARKED("주차"),
    MOVING("주행"),

    /** 값 없음·오래된 값. 주행으로 보고 상세 조작을 제한한다. */
    UNKNOWN("상태 불명"),
}

/**
 * 충격 입력 공급 상태. 공급이 없으면 "감시 중"이라고 표시하지 않는다.
 * 이번 시제품의 입력은 모두 가상 센서 시나리오다(실제 차량 충격 센서 접근은 미확인).
 */
enum class InputState(val label: String) {
    SIMULATED_CONNECTED("모의 입력 연결됨"),
    DISCONNECTED("입력 연결 안 됨"),
}
