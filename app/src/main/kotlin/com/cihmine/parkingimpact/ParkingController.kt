package com.cihmine.parkingimpact

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.cihmine.parkingimpact.core.ImpactJson
import com.cihmine.parkingimpact.core.MonitorState
import com.cihmine.parkingimpact.core.ParkingMonitor
import com.cihmine.parkingimpact.core.ParsedEvent
import com.cihmine.parkingimpact.core.ReceiveResult
import com.cihmine.parkingimpact.core.ReturnSummary
import com.cihmine.parkingimpact.core.Texts
import com.cihmine.parkingimpact.data.FileMonitorStore
import com.cihmine.parkingimpact.speech.Voice
import com.cihmine.parkingimpact.vehicle.InputState
import com.cihmine.parkingimpact.vehicle.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.ZoneId

sealed interface Screen {
    data object Home : Screen
    data object Summary : Screen
    data object Records : Screen
    data class Detail(val recordId: String) : Screen
}

/** 시연 패널에서 주입할 가상 센서 시나리오(앱에 넣은 demo/scenarios 의 JSON) */
data class Scenario(val file: String, val title: String, val text: String)

data class ParkingUi(
    val monitor: MonitorState = MonitorState(),
    val screen: Screen = Screen.Home,
    val vehicle: VehicleState = VehicleState.PARKED,
    val input: InputState = InputState.SIMULATED_CONNECTED,
    val summary: ReturnSummary? = null,
    /** 마지막 입력 처리 결과(시연 패널 표시) */
    val lastResult: String? = null,
    val ttsReady: Boolean = false,
    val ttsEngine: String = "",
    val scenarios: List<Scenario> = emptyList(),
) {
    /** 상세 확인·메모·삭제는 주차 중에만 */
    val detailAllowed: Boolean get() = vehicle == VehicleState.PARKED
}

/**
 * 주차 충격 확인 화면 흐름. 판정·기록·중복 차단은 [ParkingMonitor] 가 하고, 여기서는
 * 입력 주입 → 저장 → 화면/음성 갱신 순서만 다룬다. 상태가 바뀔 때마다 저장을 끝낸 뒤 화면을 갱신한다.
 */
class ParkingController(app: Application) : AndroidViewModel(app) {
    private val store = FileMonitorStore(app.filesDir)
    private val monitor = ParkingMonitor(store.load() ?: MonitorState())
    private val zone: ZoneId = ZoneId.systemDefault()

    private val _ui = MutableStateFlow(ParkingUi(monitor = monitor.state, scenarios = loadScenarios(app)))
    val ui: StateFlow<ParkingUi> = _ui

    private val tts = Voice.output(app) { ok -> _ui.update { it.copy(ttsReady = ok) } }

    init {
        _ui.update { it.copy(ttsEngine = tts.engineName) }
    }

    private fun now() = System.currentTimeMillis()

    private fun loadScenarios(app: Application): List<Scenario> = runCatching {
        app.assets.list("scenarios").orEmpty().sorted().map { file ->
            val text = app.assets.open("scenarios/$file").bufferedReader().use { it.readText() }
            val title = (ImpactJson.parse(text) as? ParsedEvent.Ok)?.event?.description ?: file
            Scenario(file, title, text)
        }
    }.getOrElse { Log.w(TAG, "시나리오를 읽지 못함", it); emptyList() }

    /** 상태를 저장하고 화면에 반영한다. 저장이 실패하면 화면도 바꾸지 않고 알린다. */
    private fun commit(message: String? = null, change: ParkingUi.() -> ParkingUi = { this }) {
        runCatching { store.save(monitor.state) }.onFailure {
            Log.e(TAG, "저장 실패", it)
            _ui.update { u -> u.copy(lastResult = "저장 실패 — 기록이 남지 않았을 수 있어요") }
            return
        }
        _ui.update { it.copy(monitor = monitor.state, lastResult = message ?: it.lastResult).change() }
    }

    // ── 주차·재탑승 ─────────────────────────────────────────

    fun startParking() {
        monitor.startParking(now())
        _ui.update { it.copy(vehicle = VehicleState.PARKED) }
        commit("주차를 시작했어요")
    }

    /** 모의 재탑승. 미확인 기록이 있을 때만 요약 화면·음성 안내를 낸다. */
    fun returnToCar() {
        val summary = monitor.returnToCar(now())
        commit(if (summary == null) "재탑승 — 확인할 기록 없음" else "재탑승 — 미확인 ${summary.count}건") {
            copy(summary = summary, screen = if (summary != null) Screen.Summary else Screen.Home)
        }
        if (summary != null && _ui.value.ttsReady) tts.speak(Texts.summarySpeech(summary, zone), 100) {}
    }

    fun setVehicle(state: VehicleState) {
        _ui.update { it.copy(vehicle = state) }
    }

    fun setInput(state: InputState) {
        _ui.update { it.copy(input = state, lastResult = state.label) }
    }

    // ── 가상 센서 입력 ──────────────────────────────────────

    fun inject(scenario: Scenario) {
        if (_ui.value.input == InputState.DISCONNECTED) {
            _ui.update { it.copy(lastResult = "입력 연결 안 됨 — 이벤트를 받지 못했어요") }
            return
        }
        val event = when (val p = ImpactJson.parse(scenario.text)) {
            // 시연에서는 지금 일어난 충격처럼 발생 시각을 주입 시각으로 바꾼다(eventId 는 그대로라 중복 차단은 같다).
            is ParsedEvent.Ok -> p.event.copy(occurredAt = java.time.Instant.ofEpochMilli(now()).toString())
            is ParsedEvent.Invalid -> return _ui.update { it.copy(lastResult = "저장 안 함: ${p.reason}") }
        }
        val message = when (val r = monitor.receive(event, now())) {
            is ReceiveResult.Recorded -> "기록함: ${Texts.regionPhrase(r.record.estimate)} · ${r.record.estimate.evidence.label}"
            is ReceiveResult.Duplicate -> "이미 받은 이벤트 — 새 기록을 만들지 않음"
            is ReceiveResult.NoEvent -> "기록할 충격 없음: ${r.reason}"
            is ReceiveResult.Rejected -> "저장 안 함: ${r.reason}"
            ReceiveResult.NoSession -> "주차 중이 아니어서 기록하지 않음"
        }
        commit(message)
    }

    // ── 확인·삭제 ───────────────────────────────────────────

    fun dismissSummary() {
        tts.stop()
        monitor.dismissNotice()
        commit { copy(screen = Screen.Home, summary = null) }
    }

    fun open(screen: Screen) {
        if (screen is Screen.Summary || screen is Screen.Home) tts.stop()
        _ui.update { it.copy(screen = screen) }
    }

    fun acknowledge(recordId: String, note: String) {
        if (!_ui.value.detailAllowed) return
        if (monitor.acknowledge(recordId, note, now())) commit("확인 완료로 바꿨어요")
    }

    fun delete(recordId: String) {
        if (!_ui.value.detailAllowed) return
        if (monitor.delete(recordId)) commit("기록을 삭제했어요") { copy(screen = Screen.Records) }
    }

    fun time(iso: String) = Texts.time(iso, zone)

    override fun onCleared() {
        tts.shutdown()
    }

    private companion object {
        const val TAG = "ParkingImpact"
    }
}
