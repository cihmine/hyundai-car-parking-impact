package com.cihmine.parkingimpact

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.cihmine.parkingimpact.core.ImpactJson
import com.cihmine.parkingimpact.core.MonitorState
import com.cihmine.parkingimpact.core.ParkingMonitor
import com.cihmine.parkingimpact.core.ParsedEvent
import com.cihmine.parkingimpact.core.ReceiveResult
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
    /** 마지막 입력 처리 결과(시연 패널 표시) */
    val lastResult: String? = null,
    val ttsReady: Boolean = false,
    val ttsEngine: String = "",
    val scenarios: List<Scenario> = emptyList(),
    /** 시연 패널 열림. 기본은 닫힘 — 앱을 켜면 실제 사용자 화면이 나온다. */
    val demoPanel: Boolean = false,
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
        _ui.update { it.copy(ttsEngine = tts.engineName, lastResult = store.backedUp?.let { f -> "저장 파일을 읽지 못해 새로 시작했어요(백업: ${f.name})" }) }
    }

    private fun now() = System.currentTimeMillis()

    private fun loadScenarios(app: Application): List<Scenario> = runCatching {
        app.assets.list("scenarios").orEmpty().sorted().map { file ->
            val text = app.assets.open("scenarios/$file").bufferedReader().use { it.readText() }
            val title = (ImpactJson.parse(text) as? ParsedEvent.Ok)?.event?.description ?: file
            Scenario(file, title, text)
        }
    }.getOrElse { Log.w(TAG, "시나리오를 읽지 못함", it); emptyList() }

    /**
     * [action] 으로 상태를 바꾸고 저장한 뒤 화면에 반영한다. 저장이 실패하면 바꾸기 전 상태로 되돌리고
     * 화면·음성도 바꾸지 않는다(메모리·디스크·화면이 어긋나지 않게). 성공하면 true.
     */
    private fun <T> transact(action: () -> T, onSaved: (T) -> Unit): Boolean {
        val before = monitor.state
        val result = action()
        if (monitor.state == before) { onSaved(result); return true }
        runCatching { store.save(monitor.state) }.onFailure {
            Log.e(TAG, "저장 실패", it)
            monitor.rollback(before)
            _ui.update { u -> u.copy(monitor = monitor.state, lastResult = "저장 실패 — 바뀐 내용을 되돌렸어요. 다시 시도해 주세요") }
            return false
        }
        _ui.update { it.copy(monitor = monitor.state) }
        onSaved(result)
        return true
    }

    private fun show(message: String?, change: ParkingUi.() -> ParkingUi = { this }) =
        _ui.update { it.copy(lastResult = message ?: it.lastResult).change() }

    // ── 주차·재탑승 ─────────────────────────────────────────

    fun startParking() {
        transact({ monitor.startParking(now()) }) {
            show("주차를 시작했어요") { copy(vehicle = VehicleState.PARKED) }
        }
    }

    /** 모의 재탑승. 미확인 기록이 있을 때만(저장에 성공한 뒤) 요약 화면·음성 안내를 낸다. */
    fun returnToCar() {
        transact({ monitor.returnToCar(now()) }) { summary ->
            show(if (summary == null) "재탑승 — 새로 알릴 기록 없음" else "재탑승 — 미확인 ${summary.count}건") {
                copy(screen = if (summary != null) Screen.Summary else Screen.Home)
            }
            if (summary != null && _ui.value.ttsReady) tts.speak(Texts.summarySpeech(summary, zone), 100) {}
        }
    }

    fun toggleDemoPanel() = _ui.update { it.copy(demoPanel = !it.demoPanel) }

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
            is ParsedEvent.Invalid -> {
                transact({ monitor.recordInvalid(scenario.file, p.reason, now()) }) { show("저장 안 함: ${p.reason}") }
                return
            }
        }
        transact({ monitor.receive(event, now()) }) { r ->
            show(
                when (r) {
                    is ReceiveResult.Recorded -> "기록함: ${Texts.regionPhrase(r.record.estimate)} · ${r.record.estimate.evidence.label}"
                    is ReceiveResult.Duplicate -> "이미 받은 이벤트 — 새 기록을 만들지 않음"
                    is ReceiveResult.NoEvent -> "기록할 충격 없음: ${r.reason}"
                    is ReceiveResult.Rejected -> "저장 안 함: ${r.reason}"
                    ReceiveResult.NoSession -> "주차 중이 아니어서 기록하지 않음"
                },
            )
        }
    }

    // ── 확인·삭제 ───────────────────────────────────────────

    fun dismissSummary() {
        tts.stop()
        transact({ monitor.dismissNotice() }) { show(null) { copy(screen = Screen.Home) } }
    }

    fun open(screen: Screen) {
        if (screen is Screen.Summary || screen is Screen.Home) tts.stop()
        _ui.update { it.copy(screen = screen) }
    }

    fun acknowledge(recordId: String, note: String) {
        if (!_ui.value.detailAllowed) return
        transact({ monitor.acknowledge(recordId, note, now()) }) { ok -> if (ok) show("확인 완료로 바꿨어요") }
    }

    fun delete(recordId: String) {
        if (!_ui.value.detailAllowed) return
        transact({ monitor.delete(recordId) }) { ok -> if (ok) show("기록을 삭제했어요") { copy(screen = Screen.Records) } }
    }

    fun time(iso: String) = Texts.time(iso, zone)

    override fun onCleared() {
        tts.shutdown()
    }

    private companion object {
        const val TAG = "ParkingImpact"
    }
}
