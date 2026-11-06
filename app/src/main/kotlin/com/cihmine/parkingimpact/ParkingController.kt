package com.cihmine.parkingimpact

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cihmine.parkingimpact.core.AccidentResponse
import com.cihmine.parkingimpact.core.AccidentState
import com.cihmine.parkingimpact.core.CarCommand
import com.cihmine.parkingimpact.core.CarPosition
import com.cihmine.parkingimpact.core.ControlStatus
import com.cihmine.parkingimpact.core.ImpactJson
import com.cihmine.parkingimpact.core.ImpactRecord
import com.cihmine.parkingimpact.core.matchedAddress
import com.cihmine.parkingimpact.core.MonitorState
import com.cihmine.parkingimpact.core.ParkingMonitor
import com.cihmine.parkingimpact.core.ParsedEvent
import com.cihmine.parkingimpact.core.ReceiveResult
import com.cihmine.parkingimpact.core.Texts
import com.cihmine.parkingimpact.data.FileMonitorStore
import com.cihmine.parkingimpact.speech.Voice
import com.cihmine.parkingimpact.vehicle.CarLocationSource
import com.cihmine.parkingimpact.vehicle.InputState
import com.cihmine.parkingimpact.vehicle.MockCarReply
import com.cihmine.parkingimpact.vehicle.PleosCar
import com.cihmine.parkingimpact.vehicle.TrunkOutcome
import com.cihmine.parkingimpact.vehicle.VehicleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

sealed interface Screen {
    data object Home : Screen
    data object Records : Screen
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
    /** 비상 대응 진행 상태. 있으면 다른 화면 대신 비상 대응 화면을 보여 준다. */
    val accident: AccidentState? = null,
    /** 비상등 깜빡임 계산용 단조 시계 값 */
    val accidentNowMs: Long = 0,
    val carReply: MockCarReply = MockCarReply.OK,
    /** 실제 기어 값을 쓰는 중이면 그 표시(P·D 등). null 이면 시연 패널의 모의 상태를 쓰는 중이다. */
    val gearLabel: String? = null,
    val trunkOutcome: TrunkOutcome = TrunkOutcome.MOCK,
    /** 비상 대응 중에만 채우는 현재 위치(저장하지 않음) */
    val position: CarPosition? = null,
    val address: String? = null,
    /** 주소를 낸 내비의 좌표 */
    val addressPosition: CarPosition? = null,
    val locationFailed: Boolean = false,
) {
    /** 실제 차량 명령은 실제 기어 P 를 받았을 때만 보낸다. 모의 상태나 값이 없는 상태로는 보내지 않는다. */
    val realGearParked: Boolean get() = gearLabel == "P"

    /** 현재 좌표와 맞는 주소만 쓴다 */
    val shownAddress: String? get() = matchedAddress(address, addressPosition, position)

    /** 상세 확인·삭제는 주차 중에만 */
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

    private val accident = AccidentResponse()
    private val accidentJobs = mutableListOf<Job>()

    /** 끝난 비상 대응의 늦은 트렁크 응답이 새 대응에 섞이지 않게 구분한다. */
    private var accidentRun = 0
    private val speechQueue = ArrayDeque<String>()
    private var speaking = false

    private val tts = Voice.output(app) { ok ->
        _ui.update { it.copy(ttsReady = ok) }
        if (ok) speakNext()
    }

    private val car = PleosCar(app) { state, label ->
        _ui.update { it.copy(gearLabel = label) }
        applyVehicle(state)
    }

    private val location = CarLocationSource(
        app,
        onPosition = { p -> _ui.update { it.copy(position = p) } },
        onAddress = { a, p -> _ui.update { it.copy(address = a, addressPosition = p) } },
    )

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
            show("주차를 시작했어요")
            if (_ui.value.gearLabel == null) setVehicle(VehicleState.PARKED)
        }
    }

    /** 모의 재탑승. 미확인 기록이 있을 때만(저장에 성공한 뒤) 요약 화면·음성 안내를 낸다. */
    fun returnToCar() {
        transact({ monitor.returnToCar(now()) }) { summary ->
            show(if (summary == null) "재탑승 — 새로 알릴 기록 없음" else "재탑승 — 미확인 ${summary.count}건") {
                copy(screen = Screen.Home)
            }
            if (summary != null && _ui.value.ttsReady && accident.state == null) tts.speak(Texts.summarySpeech(summary, now(), zone), 100) {}
        }
    }

    /** 시연을 처음부터 다시 한다. 같은 시나리오도 다시 기록된다. */
    fun resetDemo() {
        if (accident.state != null) finishAccident()
        tts.stop()
        transact({ monitor.reset() }) { show("처음 상태로 되돌렸어요") { copy(screen = Screen.Home) } }
    }

    fun toggleDemoPanel() = _ui.update { it.copy(demoPanel = !it.demoPanel) }

    /** 시연 패널의 모의 상태. 다음 실제 기어 값이 오면 그 값으로 바뀐다. */
    fun setVehicle(state: VehicleState) {
        _ui.update { it.copy(gearLabel = null) }
        applyVehicle(state)
    }

    private fun applyVehicle(state: VehicleState) {
        _ui.update { it.copy(vehicle = state) }
        val before = accident.state ?: return
        val commands = accident.vehicleChanged(state == VehicleState.PARKED, elapsed())
        if (CarCommand.TRUNK_OPEN in commands) say(Texts.ACCIDENT_EVACUATE)
        send(commands)
        publishAccident(before)
    }

    fun setCarReply(reply: MockCarReply) = _ui.update { it.copy(carReply = reply) }

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

    // ── 비상 대응(모의) ─────────────────────────────────────
    // 판단은 AccidentResponse 가 한다. 여기서는 모의 차량 응답, 시간 경과, 음성 순서만 다룬다.
    // 차량 명령 중 실제로 나가는 것은 실제 기어 P 일 때의 트렁크뿐이다. 전화 걸기·네트워크 전송은 없고 번호는 보여 주기만 한다.

    private fun elapsed() = SystemClock.elapsedRealtime()

    fun startAccident() {
        if (accident.state != null) return
        accidentRun++
        val stopped = _ui.value.vehicle == VehicleState.PARKED
        val commands = accident.start(elapsed(), stopped)
        _ui.update { it.copy(accident = accident.state, accidentNowMs = elapsed(), position = null, address = null, addressPosition = null, locationFailed = false, trunkOutcome = TrunkOutcome.MOCK) }
        location.start()
        accidentJobs += viewModelScope.launch {
            var tries = 0
            while (true) {
                delay(LOCATION_RETRY_MS)
                location.retry()
                if (++tries == LOCATION_FAIL_TRIES) _ui.update { it.copy(locationFailed = true) }
            }
        }
        tts.stop()
        speechQueue.clear()
        speaking = false
        say(if (stopped) Texts.ACCIDENT_EVACUATE else Texts.ACCIDENT_STOP_FIRST)
        send(commands)
        accidentJobs += viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val before = accident.state ?: break
                accident.tick(elapsed())
                publishAccident(before)
            }
        }
    }

    /** 사용자가 차를 세웠다고 알려 왔을 때 번호를 보여 준다. */
    fun requestContacts() {
        val before = accident.state ?: return
        accident.requestContacts()
        publishAccident(before)
    }

    /** 비상 대응 화면을 닫는다. */
    fun finishAccident() {
        accidentJobs.forEach { it.cancel() }
        accidentJobs.clear()
        accident.finish()
        location.stop()
        speechQueue.clear()
        speaking = false
        tts.stop()
        _ui.update { it.copy(accident = null, position = null, address = null, addressPosition = null, locationFailed = false, trunkOutcome = TrunkOutcome.MOCK, lastResult = "비상 대응을 끝냈어요") }
    }

    /**
     * 차량 명령 실행. 트렁크는 차량이 지원하면 실제 SDK 로 연다. 비상등은 공개 SDK 에 켜는 기능이 없어 모의다.
     * 모의 차량은 고른 응답을 조금 뒤에 돌려주고, '응답 없음'이면 AccidentResponse 의 시간 초과로 실패가 된다.
     * 시연 패널에서 실패·응답 없음을 고르면 실패 흐름을 보이기 위해 트렁크도 모의로 처리한다.
     */
    private fun send(commands: List<CarCommand>) {
        val reply = _ui.value.carReply
        commands.forEach { command ->
            if (reply == MockCarReply.NO_RESPONSE) return@forEach
            if (command == CarCommand.TRUNK_OPEN && reply == MockCarReply.OK && car.trunkSupported && _ui.value.realGearParked) {
                val run = accidentRun
                car.openTrunk(
                    onSent = { ok ->
                        val before = accident.state
                        if (before != null && run == accidentRun) {
                            accident.carResult(command, ok)
                            if (ok) _ui.update { it.copy(trunkOutcome = TrunkOutcome.CHECKING) }
                            publishAccident(before)
                        }
                    },
                    onConfirmed = { opened ->
                        val before = accident.state
                        if (before != null && run == accidentRun) {
                            if (opened) accident.trunkConfirmedOpen()
                            _ui.update { it.copy(trunkOutcome = if (opened) TrunkOutcome.OPENED else TrunkOutcome.UNCONFIRMED) }
                            publishAccident(before)
                        }
                    },
                )
                return@forEach
            }
            accidentJobs += viewModelScope.launch {
                delay(if (command == CarCommand.HAZARD_ON) HAZARD_REPLY_MS else TRUNK_REPLY_MS)
                val before = accident.state ?: return@launch
                accident.carResult(command, reply == MockCarReply.OK)
                publishAccident(before)
            }
        }
    }

    /** 화면을 갱신하고, 이번에 새로 실패한 동작은 직접 조작하도록 음성으로 알린다. */
    private fun publishAccident(before: AccidentState) {
        val after = accident.state
        if (after != null) {
            if (before.hazard != ControlStatus.FAILED && after.hazard == ControlStatus.FAILED) say(Texts.ACCIDENT_HAZARD_FAILED)
            if (before.trunk != ControlStatus.FAILED && after.trunk == ControlStatus.FAILED) say(Texts.ACCIDENT_TRUNK_FAILED)
        }
        _ui.update { it.copy(accident = after, accidentNowMs = elapsed()) }
    }

    /** 안내가 서로 끊기지 않게 차례대로 말한다. */
    private fun say(text: String) {
        speechQueue.addLast(text)
        speakNext()
    }

    private fun speakNext() {
        if (speaking || !_ui.value.ttsReady) return
        val next = speechQueue.removeFirstOrNull() ?: return
        speaking = true
        tts.speak(next, 100) {
            speaking = false
            speakNext()
        }
    }

    // ── 확인·삭제 ───────────────────────────────────────────

    fun open(screen: Screen) {
        if (screen is Screen.Home) tts.stop()
        _ui.update { it.copy(screen = screen) }
    }

    fun acknowledge(recordId: String) {
        if (!_ui.value.detailAllowed) return
        transact({ monitor.acknowledge(recordId, now()) }) { ok -> if (ok) show("확인 완료로 바꿨어요") }
    }

    fun delete(recordId: String) {
        if (!_ui.value.detailAllowed) return
        transact({ monitor.delete(recordId) }) { ok -> if (ok) show("기록을 삭제했어요") }
    }

    fun time(iso: String) = Texts.time(iso, zone)

    fun impactNotice(record: ImpactRecord) = Texts.impactNotice(record, now(), zone)

    override fun onCleared() {
        location.release()
        car.release()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "ParkingImpact"
        const val TICK_MS = 200L
        const val HAZARD_REPLY_MS = 600L
        const val TRUNK_REPLY_MS = 1_500L
        const val LOCATION_RETRY_MS = 5_000L

        /** 이 횟수만큼 찾아도 위치가 없으면 표지판을 알려 달라고 안내한다. 찾기는 계속한다. */
        const val LOCATION_FAIL_TRIES = 3
    }
}
