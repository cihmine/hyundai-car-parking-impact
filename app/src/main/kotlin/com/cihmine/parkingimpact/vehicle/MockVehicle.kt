package com.cihmine.parkingimpact.vehicle

enum class VehicleState(val label: String) {
    PARKED("주차"),
    MOVING("주행"),

    /** 값 없음·오래된 값. 주행으로 보고 상세 조작을 제한한다. */
    UNKNOWN("상태 불명"),
}

/** 모의 차량이 비상등·트렁크 명령에 답하는 방식(시연 패널에서 고른다). 정상 응답이고 실제 기어가 P 이면 트렁크는 실제 명령을 보낸다. */
enum class MockCarReply(val label: String) {
    OK("정상 응답"),
    FAIL("실패 응답"),
    NO_RESPONSE("응답 없음"),
}

/** 트렁크를 어떻게 처리했는지. 화면에 사실대로 쓰기 위해 구분한다. */
enum class TrunkOutcome {
    /** 차량이 지원하지 않거나 시연 응답을 골라 모의로 처리함 */
    MOCK,

    /** 차량에 명령을 보냈고 위치가 올라가는지 보는 중 */
    CHECKING,

    /** 차량이 명령을 받았지만 위치가 그대로라 열렸는지 모름 */
    UNCONFIRMED,

    /** 차량이 알려 준 위치로 열린 것을 확인함 */
    OPENED,
}

/**
 * 충격 입력 공급 상태. 공급이 없으면 "감시 중"이라고 표시하지 않는다.
 * 이번 시제품의 입력은 모두 가상 센서 시나리오다(실제 차량 충격 센서 접근은 미확인).
 */
enum class InputState(val label: String) {
    SIMULATED_CONNECTED("모의 입력 연결됨"),
    DISCONNECTED("입력 연결 안 됨"),
}
