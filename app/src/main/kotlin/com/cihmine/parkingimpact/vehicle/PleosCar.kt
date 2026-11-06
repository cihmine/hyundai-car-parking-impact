package com.cihmine.parkingimpact.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener

/**
 * Pleos Vehicle SDK 연결: 실제 기어 상태 읽기와 트렁크 열기.
 *
 * 기어가 P 일 때만 정차로 본다. 트렁크는 차량이 뒷문 위치 제어를 지원한다고 답할 때만 [trunkSupported] 가 true 가 되고,
 * 아니면 앱은 모의 동작으로 돌아간다. SDK 콜백은 다른 스레드에서 올 수 있어 메인 스레드로 넘긴다.
 * Pleos 서비스가 없는 기기에서는 값이 오지 않을 뿐 앱은 계속 동작한다.
 */
class PleosCar(
    context: Context,
    private val onGear: (VehicleState, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = attempt("Vehicle 준비") { Vehicle(context.applicationContext).also { it.initialize() } }
    private val trunk = DoorArea(DoorValue.REAR)

    private var gearConnected = false
    private var trunkChecked = false
    private var trunkOpenPosition: Int? = null
    private var tries = 0

    val trunkSupported: Boolean get() = trunkOpenPosition != null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = publish(gear ?: VehicleGear.GEAR_UNKNOWN)
        override fun onFailed(exception: Exception) {
            Log.w(TAG, "기어 상태 오류", exception)
            // 구독이 끊기면 마지막 값을 믿지 않는다(P 로 남아 있으면 주행 중에도 정차로 보게 된다).
            publish(VehicleGear.GEAR_UNKNOWN)
        }
    }

    init {
        connect()
    }

    /** 서비스 연결이 끝나기 전에는 호출이 실패하므로 값이 올 때까지 몇 번 다시 시도한다. */
    private fun connect() {
        val v = vehicle ?: return
        if (!gearConnected) {
            attempt("기어 읽기") {
                v.getGear().getCurrentGearSelectionState({ gear ->
                    main.post {
                        if (!gearConnected) {
                            gearConnected = true
                            attempt("기어 구독") { v.getGear().registerGearSelection(gearListener) }
                        }
                    }
                    publish(gear ?: VehicleGear.GEAR_UNKNOWN)
                }, { Log.w(TAG, "기어 읽기 실패: $it") })
            }
        }
        if (!trunkChecked) {
            attempt("트렁크 확인") {
                v.getDoor().checkDoorPositionCapabilities({ bounds: Map<DoorArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>> ->
                    main.post {
                        trunkChecked = true
                        trunkOpenPosition = bounds[trunk]?.maxValue
                        Log.i(TAG, "뒷문 제어 지원: ${bounds.keys.map { it.values.toList() }} → $trunkOpenPosition")
                    }
                }, { Log.w(TAG, "트렁크 확인 실패: $it") })
            }
        }
        if (gearConnected && trunkChecked) return
        if (++tries < MAX_TRIES) main.postDelayed(::connect, RETRY_MS)
        else if (!gearConnected) publish(VehicleGear.GEAR_UNKNOWN)
    }

    private fun publish(gear: VehicleGear) {
        val state = when (gear) {
            VehicleGear.GEAR_PARK -> VehicleState.PARKED
            VehicleGear.GEAR_UNKNOWN -> VehicleState.UNKNOWN
            else -> VehicleState.MOVING
        }
        val label = when (gear) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE -> "D"
            VehicleGear.GEAR_UNKNOWN -> "알 수 없음"
            else -> gear.name.removePrefix("GEAR_")
        }
        main.post { onGear(state, label) }
    }

    /**
     * 트렁크 열기를 요청한다. 차량 서비스가 요청을 받았다는 응답은 문이 열렸다는 뜻이 아니다(에뮬레이터는 성공을 답해도 위치가 0 이다).
     * 그래서 [onSent] 뒤에 실제 위치를 몇 번 읽어, 위치가 올라가면 [onConfirmed] 에 true, 끝까지 그대로면 false 를 준다.
     * 콜백은 메인 스레드에서 불린다. 답이 없으면 [onSent] 가 불리지 않는다(시간 초과는 부르는 쪽에서 처리).
     */
    fun openTrunk(onSent: (Boolean) -> Unit, onConfirmed: (Boolean) -> Unit) {
        val position = trunkOpenPosition
        val v = vehicle
        if (position == null || v == null) {
            main.post { onSent(false) }
            return
        }
        val sent = attempt("트렁크 열기") {
            v.getDoor().setDoorPosition(trunk, position, { _, _ ->
                main.post {
                    onSent(true)
                    confirmTrunk(v, CONFIRM_TRIES, onConfirmed)
                }
            }, {
                Log.w(TAG, "트렁크 열기 실패: $it")
                main.post { onSent(false) }
            })
        }
        if (sent == null) main.post { onSent(false) }
    }

    private fun confirmTrunk(v: Vehicle, triesLeft: Int, onConfirmed: (Boolean) -> Unit) {
        if (triesLeft == 0) return onConfirmed(false)
        main.postDelayed({
            val asked = attempt("트렁크 위치 확인") {
                v.getDoor().getCurrentDoorPosition(trunk, { value ->
                    main.post { if ((value ?: 0) > 0) onConfirmed(true) else confirmTrunk(v, triesLeft - 1, onConfirmed) }
                }, {
                    Log.w(TAG, "트렁크 위치 읽기 실패: $it")
                    main.post { confirmTrunk(v, triesLeft - 1, onConfirmed) }
                })
            }
            if (asked == null) confirmTrunk(v, triesLeft - 1, onConfirmed)
        }, RETRY_MS)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        attempt("Vehicle 해제") {
            if (gearConnected) vehicle?.getGear()?.unregisterGearSelection(gearListener)
            vehicle?.release()
        }
    }

    private fun <T> attempt(what: String, action: () -> T): T? =
        runCatching(action).onFailure { Log.w(TAG, "$what 실패", it) }.getOrNull()

    private companion object {
        const val TAG = "PleosCar"
        const val RETRY_MS = 1_000L
        const val MAX_TRIES = 10
        const val CONFIRM_TRIES = 8
    }
}
