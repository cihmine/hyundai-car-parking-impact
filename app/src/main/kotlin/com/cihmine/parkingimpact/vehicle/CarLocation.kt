package com.cihmine.parkingimpact.vehicle

import android.content.Context
import android.util.Log
import kotlin.math.abs
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.cihmine.parkingimpact.core.CarPosition

/**
 * 비상 화면에 보여 줄 현재 위치. 좌표는 Pleos FusedLocation, 주소는 Pleos NaviHelper 에서 받는다.
 *
 * 위치는 비상 대응 중([start]~[stop])에만 읽고 저장하지 않는다. 앱을 켤 때 SDK 연결을 미리 해 두어
 * 버튼을 누른 순간 바로 읽을 수 있게 한다. Pleos 서비스가 없는 기기에서는 값이 오지 않을 뿐 앱은 계속 동작한다.
 * 콜백은 SDK 스레드에서 올 수 있다.
 */
class CarLocationSource(
    context: Context,
    private val onPosition: (CarPosition) -> Unit,
    /** 주소와, 그 주소를 낸 내비의 좌표 */
    private val onAddress: (String, CarPosition?) -> Unit,
) {
    private val fused = attempt("FusedLocation 준비") { FusedLocation(context.applicationContext).also { it.initialize() } }
    private val navi = attempt("NaviHelper 준비") { NaviHelper(context.applicationContext) }

    @Volatile private var active = false
    private var naviReady = false
    private var subscribed = false
    @Volatile private var gotLive = false

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (!active || data == null) return
            val position = CarPosition(data.latitude, data.longitude)
            if (position.valid) {
                gotLive = true
                onPosition(position)
            }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "위치 오류", exception)
        }
    }

    /** 마지막으로 알려진 위치는 최근 값일 때만, 실시간 값이 오기 전까지만 쓴다(오래된 곳을 현재 위치로 말하지 않게). */
    private val lastKnownListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (!active || gotLive || data == null) return
            if (abs(System.currentTimeMillis() - data.timestampMillis) > MAX_LAST_KNOWN_AGE_MS) return
            val position = CarPosition(data.latitude, data.longitude)
            if (position.valid) onPosition(position)
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "마지막 위치 오류", exception)
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            val address = info.address.orEmpty().trim()
            if (active && address.isNotEmpty()) onAddress(address, CarPosition(info.latitude, info.longitude).takeIf { it.valid })
        }
    }

    init {
        attempt("NaviHelper 연결") {
            navi?.initialize({
                navi.addListener(naviListener)
                naviReady = true
                if (active) requestAddress()
            }, { Log.w(TAG, "NaviHelper 연결 실패", it) })
        }
    }

    fun start() {
        active = true
        gotLive = false
        attempt("마지막 위치 요청") { fused?.getLastKnownLocation(lastKnownListener) }
        retry()
    }

    /** 비상 대응 중 주기적으로 부른다. 실패한 구독을 다시 시도하고 주소를 새로 묻는다. */
    fun retry() {
        if (!subscribed) {
            subscribed = attempt("위치 구독") { fused?.registerFusedLocationCallback(locationListener) } != null
        }
        requestAddress()
    }

    /** 주소는 요청할 때마다 한 번 온다. */
    private fun requestAddress() {
        if (naviReady) attempt("주소 요청") { navi?.getCurrentLocationInfo() }
    }

    fun stop() {
        active = false
        if (subscribed) attempt("위치 해제") { fused?.unregisterFusedLocationCallback(locationListener) }
        subscribed = false
    }

    fun release() {
        stop()
        attempt("SDK 해제") {
            fused?.release()
            navi?.removeListener(naviListener)
            navi?.release()
        }
    }

    private fun <T> attempt(what: String, action: () -> T): T? =
        runCatching(action).onFailure { Log.w(TAG, "$what 실패", it) }.getOrNull()

    companion object {
        private const val TAG = "CarLocation"
        private const val MAX_LAST_KNOWN_AGE_MS = 5 * 60_000L

        /** 좌표·주소를 읽는 데 필요한 위험 권한. 사고 순간에 창이 뜨지 않게 앱을 켤 때 받는다. */
        val PERMISSIONS = arrayOf("pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.NAVI_ROUTE")
    }
}
