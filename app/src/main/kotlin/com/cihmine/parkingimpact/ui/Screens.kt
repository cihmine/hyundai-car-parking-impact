package com.cihmine.parkingimpact.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.parkingimpact.ParkingController
import com.cihmine.parkingimpact.ParkingUi
import com.cihmine.parkingimpact.Screen
import com.cihmine.parkingimpact.core.AccidentState
import com.cihmine.parkingimpact.core.ControlStatus
import com.cihmine.parkingimpact.core.Hotline
import com.cihmine.parkingimpact.core.Hotlines
import com.cihmine.parkingimpact.core.ImpactRecord
import com.cihmine.parkingimpact.core.RecordStatus
import com.cihmine.parkingimpact.core.Region
import com.cihmine.parkingimpact.core.Texts
import com.cihmine.parkingimpact.core.occurredMs
import com.cihmine.parkingimpact.core.ParkingMonitor
import com.cihmine.parkingimpact.vehicle.InputState
import com.cihmine.parkingimpact.vehicle.MockCarReply
import com.cihmine.parkingimpact.vehicle.TrunkOutcome
import com.cihmine.parkingimpact.vehicle.VehicleState

private val Banner = Color(0xFF3A2F00)
private val BannerText = Color(0xFFFFD54F)
private val Panel = Color(0xFF1E1E26)
private val Danger = Color(0xFFC62828)
private val Ok = Color(0xFF81C784)
private val EmergencyButtonHeight = 64.dp

/** 고정된 비상 버튼에 내용이 가리지 않게 비워 두는 높이 */
private val EmergencyButtonClearance = 80.dp

@Composable
fun ParkingApp(c: ParkingController) {
    val ui by c.ui.collectAsState()
    Surface(Modifier.fillMaxSize(), color = Color(0xFF121218), contentColor = Color.White) { Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Banner).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("차량 연동 시뮬레이션 · 충격 입력: 가상 센서 시나리오 · 차량 상태: ${ui.gearLabel?.let { "실제 기어 $it" } ?: "모의 ${ui.vehicle.label}"}", color = BannerText, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("음성 안내: ${ui.ttsEngine}${if (ui.ttsReady) "" else "(준비 안 됨)"}", color = BannerText, fontSize = 15.sp)
            Spacer(Modifier.width(24.dp))
            Text(
                if (ui.demoPanel) "시연 패널 닫기 ▸" else "◂ 시연 패널",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = c::toggleDemoPanel),
            )
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                val accident = ui.accident
                if (accident != null) AccidentScreen(ui, accident, c)
                else if (!ui.detailAllowed) Driving(ui)
                else when (ui.screen) {
                    Screen.Home -> Home(ui, c)
                    Screen.Records -> Records(ui, c)
                }
                // 어느 화면에서든 같은 자리에서 바로 누를 수 있게 고정한다.
                if (accident == null) {
                    Button(
                        onClick = c::startAccident,
                        // 주행 중에는 같은 자리에서 크기만 키워 찾기 쉽게 한다.
                        modifier = Modifier.align(Alignment.BottomEnd).height(if (ui.detailAllowed) EmergencyButtonHeight else 130.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
                    ) { Text("비상 버튼", fontSize = if (ui.detailAllowed) 24.sp else 38.sp, fontWeight = FontWeight.Bold) }
                } else {
                    // 비상 대응을 끝내는 버튼도 비상 버튼과 같은 자리·크기에(누른 자리에서 바로 끝낼 수 있게)
                    OutlinedButton(
                        onClick = c::finishAccident,
                        modifier = Modifier.align(Alignment.BottomEnd).height(EmergencyButtonHeight),
                    ) { Text("비상 대응 끝내기", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                }
            }
            if (ui.demoPanel) DemoPanel(ui, c)
        }
    } }
}

/**
 * 기본 화면. 평소에는 확인할 기록이 없다고 보여 주고, 충격 의심이 있으면 같은 화면에서 문구를 보고 확인 완료까지 한다.
 * 확인 완료하면 다음 미확인 기록이나 평소 상태로 돌아온다.
 */
@Composable
private fun Home(ui: ParkingUi, c: ParkingController) {
    val m = ui.monitor
    val session = m.session
    val pending = ParkingMonitor(m).currentSummary()
    val estimate = pending?.latest?.estimate
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
    CarDiagram(
        highlight = estimate?.region?.takeIf { it != Region.UNKNOWN },
        candidates = estimate?.candidates.orEmpty(),
        counts = m.unreviewed.groupingBy { it.estimate.region }.eachCount(),
        width = 360.dp,
    )
    Column(Modifier.weight(1f).padding(bottom = EmergencyButtonClearance), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("충격비상 어플", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("주차 후, 확인할 충격과 구역을 다음 탑승 때 알려드려요", fontSize = 20.sp, color = Color.LightGray)
        val status = when {
            session == null -> "주차 기록 중 아님"
            ui.input == InputState.DISCONNECTED -> "입력 연결 안 됨 — 지금은 감시하지 않아요"
            else -> "데모 감시 중(모의 입력) · ${c.time(java.time.Instant.ofEpochMilli(session.startedAtMs).toString())}부터"
        }
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pending != null) ImpactNotice(c, pending.latest, pending.count, headline = 21.sp)
            else Text("확인할 충격 기록이 없어요", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ok)
            Text(status, fontSize = 17.sp, color = if (session != null && ui.input == InputState.DISCONNECTED) Highlight else Color.LightGray)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pending != null) {
                Button(onClick = { c.acknowledge(pending.latest.id) }, modifier = Modifier.height(56.dp)) { Text("기록 확인했어요", fontSize = 18.sp) }
            }
            if (m.records.isNotEmpty()) OutlinedButton(onClick = { c.open(Screen.Records) }, Modifier.height(56.dp)) { Text("기록 보기", fontSize = 18.sp) }
        }
        if (pending != null) Text(Texts.ACK_MEANING, fontSize = 14.sp, color = Color.Gray)
        // 심사자가 바로 찾게: 시연 패널(① 주차 → ② 충격 → ③ 재탑승)을 여는 큰 버튼
        if (!ui.demoPanel) {
            OutlinedButton(onClick = c::toggleDemoPanel, Modifier.height(64.dp)) { Text("▶ 주차 충격 체험 (시제품 시연)", fontSize = 20.sp) }
        }
    }
    }
}

/** 가장 최근 충격 의심과, 구역이 추정이니 차 전체를 확인하라는 안내. 사용자가 확인 완료할 때까지 보여 준다. */
@Composable
private fun ImpactNotice(c: ParkingController, latest: ImpactRecord, count: Int, headline: TextUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(c.impactNotice(latest), fontSize = headline, fontWeight = FontWeight.Bold, color = Highlight)
        Text(Texts.IMPACT_CHECK_NOTE, fontSize = 15.sp, color = Color.LightGray)
        if (count > 1) Text("다른 기록 ${count - 1}건 더 있어요", fontSize = 15.sp, color = Color.LightGray)
    }
}

/** 주행 화면. 운전 중에 읽을 내용을 주지 않고, 할 수 있는 일은 비상 버튼뿐이다. */
@Composable
private fun Driving(ui: ParkingUi) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("안전 운전하세요", fontSize = 44.sp, fontWeight = FontWeight.Bold)
        // 주행 중 충격은 음성으로만 알리고 화면은 그대로 둔다 — 그래서 건수에도 넣지 않는다(정차 후 기본 화면에서 보임)
        val count = ui.monitor.unreviewed.count { !it.whileDriving }
        if (count > 0) {
            Spacer(Modifier.height(16.dp))
            Text("확인할 기록 ${count}건은 주차 후 보여 드려요", fontSize = 22.sp, color = Highlight)
        }
    }
}

/** 비상 버튼을 누른 뒤의 화면. 대피 안내를 가장 크게 보여 주고, 차가 멈추면 도움을 요청할 번호를 띄운다. */
@Composable
private fun AccidentScreen(ui: ParkingUi, a: AccidentState, c: ParkingController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        CarDiagram(
            highlight = null,
            width = 280.dp,
            hazardLit = a.hazard == ControlStatus.DONE && (ui.accidentNowMs / 500) % 2 == 0L,
            trunkOpen = a.trunk == ControlStatus.DONE,
            trunkLabel = when (ui.trunkOutcome) {
                TrunkOutcome.MOCK -> "트렁크 열림(모의)"
                TrunkOutcome.OPENED -> "트렁크 열림"
                else -> "트렁크 열림 미확인"
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("비상 대응 중", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Highlight)
            Text(
                if (a.stopConfirmed) "주변이 안전하면 안전한 곳으로 대피해 주세요 (고속도로라면 가드레일 밖)" else "안전한 곳에 차를 세워 주세요",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // 열렸다고 아는 경우에만 경고한다. 명령만 보내고 열림을 확인하지 못했으면 아래 트렁크 줄이 그 사실을 알려 준다.
            if (a.trunkOpenWhileMoving && ui.trunkOutcome in listOf(TrunkOutcome.MOCK, TrunkOutcome.OPENED)) {
                Text("트렁크가 열려 있어요 — 차를 세워 주세요", fontSize = 19.sp, color = Highlight, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlRow("비상등", a.hazard)
                if (a.trunk == ControlStatus.DONE && ui.trunkOutcome != TrunkOutcome.MOCK) {
                    when (ui.trunkOutcome) {
                        TrunkOutcome.OPENED -> StatusRow("트렁크", "열림 확인", Ok)
                        TrunkOutcome.CHECKING -> StatusRow("트렁크", "명령 전송 — 열림 확인 중…", Color.LightGray)
                        else -> StatusRow("트렁크", "명령 전송 — 열림 미확인, 직접 확인해 주세요", Highlight)
                    }
                } else ControlRow("트렁크", a.trunk)
            }
            if (a.showContacts) Contacts(ui) else {
                Button(onClick = c::requestContacts, Modifier.height(60.dp)) { Text("차를 세웠어요 · 연락처 보기", fontSize = 19.sp) }
            }
            Text(Texts.ACCIDENT_NOT_REAL, fontSize = 14.sp, color = Color.Gray)
            // 오른쪽 아래 '비상 대응 끝내기' 버튼에 마지막 줄이 가리지 않게
            Spacer(Modifier.height(EmergencyButtonClearance))
        }
    }
}

/** 도움을 요청할 번호. 차 밖에서도 읽히게 번호를 크게 쓴다. */
@Composable
private fun Contacts(ui: ParkingUi) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 가장 급한 번호를 맨 위에(앱이 대신 걸지 않음)
        Text("다친 사람·화재 119 · 사고 처리 112", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Highlight)
        HorizontalDivider()
        // 전화로 불러 줘야 하는 값이라 번호만큼 크게
        Text("현재 위치 · 전화할 때 알려 주세요", fontSize = 19.sp, color = Color.LightGray)
        Text(Texts.locationHeadline(ui.shownAddress, ui.position, ui.locationFailed), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (ui.shownAddress != null) ui.position?.let { Text(Texts.coordinates(it), fontSize = 19.sp, color = Color.LightGray) }
        HorizontalDivider()
        Text(Hotlines.ROAD.name, fontSize = 16.sp, color = Color.LightGray)
        Text(Hotlines.ROAD.number, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = BannerText)
        HorizontalDivider()
        Text("보험사 긴급출동", fontSize = 16.sp, color = Color.LightGray)
        Hotlines.INSURERS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { InsurerCell(it, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(Texts.INSURER_NOTE, fontSize = 13.sp, color = Color.Gray)
    }
}

@Composable
private fun InsurerCell(line: Hotline, modifier: Modifier) {
    Column(modifier) {
        Text(line.name, fontSize = 14.sp, color = Color.LightGray)
        Text(line.number, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ControlRow(name: String, status: ControlStatus) = StatusRow(
    name,
    status.label,
    when (status) {
        ControlStatus.DONE -> Ok
        ControlStatus.FAILED -> Highlight
        else -> Color.LightGray
    },
)

@Composable
private fun StatusRow(name: String, value: String, color: Color) {
    Row {
        Text(name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(88.dp))
        Text(value, fontSize = 19.sp, color = color)
    }
}

/** 기록 화면. 목록에서 바로 근거를 보고 확인 완료·삭제까지 한다. */
@Composable
private fun Records(ui: ParkingUi, c: ParkingController) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("충격 의심 기록", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { c.open(Screen.Home) }) { Text("기본 화면") }
        }
        val list = ui.monitor.records.sortedByDescending { occurredMs(it) }
        if (list.isEmpty()) Text("기록이 없어요.", fontSize = 18.sp)
        list.forEach { r -> RecordRow(r, c, enabled = ui.detailAllowed) }
        if (list.isNotEmpty()) Text(Texts.EVIDENCE_MEANING, fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.height(EmergencyButtonClearance))
    }
}

@Composable
private fun RecordRow(r: ImpactRecord, c: ParkingController, enabled: Boolean) {
    val reviewed = r.status == RecordStatus.REVIEWED
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (reviewed) "확인 완료" else "미확인", color = if (reviewed) Color.Gray else Highlight, fontSize = 17.sp, modifier = Modifier.width(96.dp))
            Text(if (r.whileDriving) "주행 중" else "주차 중", fontSize = 16.sp, color = if (r.whileDriving) BannerText else Color.LightGray, modifier = Modifier.width(80.dp))
            Text(c.time(r.occurredAt), fontSize = 18.sp, modifier = Modifier.width(240.dp))
            Text(Texts.regionPhrase(r.estimate), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(r.source.label, fontSize = 14.sp, color = Color.Gray)
        }
        if (enabled) {
            Text("${r.estimate.evidence.label} — ${r.estimate.reason}", fontSize = 15.sp, color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!reviewed) Button(onClick = { c.acknowledge(r.id) }) { Text("확인 완료") }
                OutlinedButton(onClick = { c.delete(r.id) }) { Text("삭제") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(ui: ParkingUi, c: ParkingController) {
    Column(
        Modifier.width(360.dp).fillMaxHeight().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("시연 패널", color = BannerText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("실제 사용자 화면이 아닌 시연·검증용 입력", fontSize = 12.sp, color = Color.Gray)
        HorizontalDivider()
        // 핵심 체험 3단계를 맨 위에, 결과도 바로 아래에 보여 준다
        Text("핵심 체험 3단계", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Button(onClick = c::startParking, enabled = ui.monitor.session == null, modifier = Modifier.fillMaxWidth()) { Text("① 시동 끔 · 주차 시작") }
        Text("② 충격 넣기(가상 센서)", fontSize = 15.sp)
        val (main, extra) = ui.scenarios.partition { s -> listOf("weak", "missing", "malformed").none { it in s.file } }
        main.forEach { s ->
            OutlinedButton(onClick = { c.inject(s) }, Modifier.fillMaxWidth()) { Text(s.title, fontSize = 13.sp) }
        }
        Button(onClick = c::returnToCar, modifier = Modifier.fillMaxWidth()) { Text("③ 시동 켬 · 재탑승") }
        ui.lastResult?.let { Text("결과: $it", fontSize = 14.sp, color = BannerText) }
        OutlinedButton(onClick = c::resetDemo, Modifier.fillMaxWidth()) { Text("시연 처음부터(기록·모의 설정 초기화)") }
        Text("주행 중 충격: 아래 차량 상태를 '주행'으로 바꾼 뒤 ② 충격 넣기 → 음성만 나와요(1분에 한 번)", fontSize = 12.sp, color = Color.Gray)
        HorizontalDivider()
        Text("추가 검증", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        extra.forEach { s ->
            OutlinedButton(onClick = { c.inject(s) }, Modifier.fillMaxWidth()) { Text(s.title, fontSize = 13.sp) }
        }
        Text("모의 차량 상태", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleState.entries.forEach { v -> FilterChip(ui.vehicle == v, { c.setVehicle(v) }, { Text(v.label) }) }
        }
        Text("실제 기어 값이 바뀌면 그 값이 우선해요.", fontSize = 12.sp, color = Color.Gray)
        Text("모의 차량 제어 응답(비상 버튼)", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MockCarReply.entries.forEach { r -> FilterChip(ui.carReply == r, { c.setCarReply(r) }, { Text(r.label) }) }
        }
        Text("정상 응답이면 차량이 지원할 때 트렁크 명령을 실제로 보내요.", fontSize = 12.sp, color = Color.Gray)
        Text("충격 입력 공급", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputState.entries.forEach { s -> FilterChip(ui.input == s, { c.setInput(s) }, { Text(s.label) }) }
        }
        Text("앱 재시작 확인: 기록 후 앱을 강제 종료하고 다시 열면 미확인 기록이 남아 있어요.", fontSize = 12.sp, color = Color.Gray)
    }
}
