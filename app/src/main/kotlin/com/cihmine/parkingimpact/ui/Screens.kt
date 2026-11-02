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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.parkingimpact.ParkingController
import com.cihmine.parkingimpact.ParkingUi
import com.cihmine.parkingimpact.Screen
import com.cihmine.parkingimpact.core.ImpactRecord
import com.cihmine.parkingimpact.core.RecordStatus
import com.cihmine.parkingimpact.core.Region
import com.cihmine.parkingimpact.core.Texts
import com.cihmine.parkingimpact.vehicle.InputState
import com.cihmine.parkingimpact.vehicle.VehicleState

private val Banner = Color(0xFF3A2F00)
private val BannerText = Color(0xFFFFD54F)
private val Panel = Color(0xFF1E1E26)

@Composable
fun ParkingApp(c: ParkingController) {
    val ui by c.ui.collectAsState()
    Surface(Modifier.fillMaxSize(), color = Color(0xFF121218), contentColor = Color.White) { Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Banner).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("차량 연동 시뮬레이션 · 충격 입력: 가상 센서 시나리오 · 차량 상태: 모의 ${ui.vehicle.label}", color = BannerText, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("음성 안내: ${ui.ttsEngine}${if (ui.ttsReady) "" else "(준비 안 됨)"}", color = BannerText, fontSize = 15.sp)
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                when (val s = ui.screen) {
                    Screen.Home -> Home(ui, c)
                    Screen.Summary -> Summary(ui, c)
                    Screen.Records -> Records(ui, c)
                    is Screen.Detail -> Detail(ui, c, s.recordId)
                }
            }
            DemoPanel(ui, c)
        }
    } }
}

@Composable
private fun Home(ui: ParkingUi, c: ParkingController) {
    val m = ui.monitor
    val session = m.session
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("주차 충격 확인", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("주차 중 생긴 충격 의심 기록을 구역과 함께 남기고, 다시 탈 때 알려 드려요.", fontSize = 17.sp, color = Color.LightGray)
        val status = when {
            session == null -> "주차 기록 중 아님"
            ui.input == InputState.DISCONNECTED -> "입력 연결 안 됨 — 지금은 감시하지 않아요"
            else -> "데모 감시 중(모의 입력) · ${c.time(java.time.Instant.ofEpochMilli(session.startedAtMs).toString())}부터"
        }
        Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = if (m.session != null && ui.input == InputState.DISCONNECTED) Highlight else Color.White)
                Text("확인하지 않은 기록 ${m.unreviewed.size}건 · 전체 ${m.records.size}건", fontSize = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CarDiagram(highlight = null, counts = m.unreviewed.groupingBy { it.estimate.region }.eachCount(), width = 160.dp)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { c.open(Screen.Records) }, Modifier.height(56.dp)) { Text("기록 보기", fontSize = 18.sp) }
                if (m.notice != null && m.unreviewed.isNotEmpty()) {
                    OutlinedButton(onClick = { c.open(Screen.Summary) }, Modifier.height(56.dp)) { Text("재탑승 요약 다시 보기", fontSize = 18.sp) }
                }
            }
        }
        Text("이 시제품은 실제 차량에서 충격을 감지하지 않아요. 판정은 '충격 의심·구역 추정'이며 파손 여부나 책임을 판단하지 않아요.",
            fontSize = 14.sp, color = Color.Gray)
    }
}

@Composable
private fun Summary(ui: ParkingUi, c: ParkingController) {
    val s = ui.summary ?: ui.monitor.unreviewed.takeIf { it.isNotEmpty() }?.let { list ->
        com.cihmine.parkingimpact.core.ReturnSummary(list.size, list.maxBy { it.occurredAt }, list.map { it.id })
    }
    if (s == null) return Text("확인할 기록이 없어요.", fontSize = 22.sp)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("주차 중 충격 의심 기록 ${s.count}건이 있어요", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Highlight)
        Text("가장 최근: ${c.time(s.latest.occurredAt)} · ${Texts.regionPhrase(s.latest.estimate)}", fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CarDiagram(
                highlight = s.latest.estimate.region.takeIf { it != Region.UNKNOWN },
                candidates = s.latest.estimate.candidates,
                counts = ui.monitor.unreviewed.groupingBy { it.estimate.region }.eachCount(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!ui.detailAllowed) Text("주행 중에는 상세 확인을 할 수 없어요. 차를 세운 뒤 확인해 주세요.", color = Highlight, fontSize = 17.sp)
                Button(onClick = { c.open(Screen.Records) }, enabled = ui.detailAllowed, modifier = Modifier.height(56.dp)) { Text("기록 확인하기", fontSize = 18.sp) }
                OutlinedButton(onClick = c::dismissSummary, Modifier.height(56.dp)) { Text("닫기(기록은 남아요)", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun Records(ui: ParkingUi, c: ParkingController) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("충격 의심 기록", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { c.open(Screen.Home) }) { Text("처음 화면") }
        }
        if (!ui.detailAllowed) Text("주행 중에는 상세 확인을 할 수 없어요.", color = Highlight, fontSize = 17.sp)
        val list = ui.monitor.records.sortedByDescending { it.occurredAt }
        if (list.isEmpty()) Text("기록이 없어요.", fontSize = 18.sp)
        list.forEach { r -> RecordRow(r, c, enabled = ui.detailAllowed) }
    }
}

@Composable
private fun RecordRow(r: ImpactRecord, c: ParkingController, enabled: Boolean) {
    val reviewed = r.status == RecordStatus.REVIEWED
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { c.open(Screen.Detail(r.id)) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (reviewed) "확인 완료" else "미확인", color = if (reviewed) Color.Gray else Highlight, fontSize = 17.sp, modifier = Modifier.width(96.dp))
        Text(c.time(r.occurredAt), fontSize = 18.sp, modifier = Modifier.width(240.dp))
        Text(Texts.regionPhrase(r.estimate), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(r.source.label, fontSize = 14.sp, color = Color.Gray)
    }
}

@Composable
private fun Detail(ui: ParkingUi, c: ParkingController, recordId: String) {
    val r = ui.monitor.records.firstOrNull { it.id == recordId } ?: return Text("삭제된 기록이에요.", fontSize = 20.sp)
    var note by remember(recordId) { mutableStateOf(r.note) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("사건 상세", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { c.open(Screen.Records) }) { Text("목록") }
        }
        if (!ui.detailAllowed) return@Column Text("주행 중에는 상세 확인을 할 수 없어요. 차를 세운 뒤 확인해 주세요.", color = Highlight, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CarDiagram(r.estimate.region.takeIf { it != Region.UNKNOWN }, r.estimate.candidates)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("발생 시각: ${c.time(r.occurredAt)}", fontSize = 19.sp)
                Text("확인 권장 구역: ${Texts.regionPhrase(r.estimate)}", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("근거: ${r.estimate.evidence.label} — ${r.estimate.reason}", fontSize = 17.sp)
                Text(Texts.EVIDENCE_MEANING, fontSize = 14.sp, color = Color.Gray)
                Text("원천 데이터: ${r.source.label}(가상 센서 시나리오 ${r.rawEventIds.joinToString()})", fontSize = 14.sp, color = Color.Gray)
                Text("상태: ${if (r.status == RecordStatus.REVIEWED) "확인 완료" else "미확인"}", fontSize = 17.sp)
            }
        }
        OutlinedTextField(note, { note = it.take(200) }, label = { Text("메모(선택)") }, modifier = Modifier.fillMaxWidth(0.7f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { c.acknowledge(r.id, note) }, Modifier.height(56.dp)) {
                Text(if (r.status == RecordStatus.REVIEWED) "메모 저장" else "확인 완료", fontSize = 18.sp)
            }
            OutlinedButton(onClick = { c.delete(r.id) }, Modifier.height(56.dp)) { Text("삭제", fontSize = 18.sp) }
        }
        Text(Texts.ACK_MEANING, fontSize = 14.sp, color = Color.Gray)
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
        Text("주차·재탑승(모의)", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = c::startParking, enabled = ui.monitor.session == null) { Text("주차 시작") }
            Button(onClick = c::returnToCar) { Text("재탑승") }
        }
        Text("모의 차량 상태", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleState.entries.forEach { v -> FilterChip(ui.vehicle == v, { c.setVehicle(v) }, { Text(v.label) }) }
        }
        Text("충격 입력 공급", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputState.entries.forEach { s -> FilterChip(ui.input == s, { c.setInput(s) }, { Text(s.label) }) }
        }
        HorizontalDivider()
        Text("가상 센서 시나리오 주입", fontSize = 15.sp)
        ui.scenarios.forEach { s ->
            OutlinedButton(onClick = { c.inject(s) }, Modifier.fillMaxWidth()) { Text(s.title, fontSize = 13.sp) }
        }
        ui.lastResult?.let {
            HorizontalDivider()
            Text("결과: $it", fontSize = 14.sp, color = BannerText)
        }
        Text("앱 재시작 확인: 기록 후 앱을 강제 종료하고 다시 열면 미확인 기록이 남아 있어요.", fontSize = 12.sp, color = Color.Gray)
    }
}
