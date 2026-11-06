package com.cihmine.parkingimpact.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.parkingimpact.core.Region

val Highlight = Color(0xFFFFB74D)
val Possible = Color(0xFFFFE0B2)
private val HazardLight = Color(0xFFFF9800)
private val TrunkOpen = Color(0xFF80CBC4)
private val Idle = Color(0xFF3A3A44)
private val Roof = Color(0xFF24242C)
private val Tire = Color(0xFF6A6A74)

/** 차체 안에서 구역이 차지하는 자리(0~1 비율). 위쪽이 차량 앞이고, 화면 왼쪽이 운전석에 앉았을 때의 왼쪽이다. */
private const val HOOD_END = 0.24f
private const val DOOR_SPLIT = 0.52f
private const val TRUNK_START = 0.80f
private const val DOOR_WIDTH = 0.30f

private fun zoneOf(r: Region): Rect = when (r) {
    Region.FRONT -> Rect(0f, 0f, 1f, HOOD_END)
    Region.LEFT_FRONT_DOOR -> Rect(0f, HOOD_END, DOOR_WIDTH, DOOR_SPLIT)
    Region.LEFT_REAR_DOOR -> Rect(0f, DOOR_SPLIT, DOOR_WIDTH, TRUNK_START)
    Region.RIGHT_FRONT_DOOR -> Rect(1 - DOOR_WIDTH, HOOD_END, 1f, DOOR_SPLIT)
    Region.RIGHT_REAR_DOOR -> Rect(1 - DOOR_WIDTH, DOOR_SPLIT, 1f, TRUNK_START)
    Region.REAR -> Rect(0f, TRUNK_START, 1f, 1f)
    Region.UNKNOWN -> Rect.Zero
}

private val ZONES = Region.entries.filter { it != Region.UNKNOWN }

/** 도식 안에 쓰는 이름. 문 칸은 좁아서 두 줄로 쓴다. */
private fun zoneLabel(r: Region): String = r.label.replace(" ", "\n")

/**
 * 위에서 본 차량 도식 — 앞쪽, 왼쪽 문 2개, 오른쪽 문 2개, 뒤쪽. 강조는 "확인 권장 구역"이며 확정 파손 부위가 아니다.
 * 색만으로 구분하지 않도록 칸 안에 구역 이름과 미확인 건수를 글자로 쓴다.
 */
@Composable
fun CarDiagram(
    highlight: Region?,
    candidates: List<Region> = emptyList(),
    counts: Map<Region, Int> = emptyMap(),
    width: Dp = 220.dp,
    /** 비상등 불빛(깜빡임은 부르는 쪽에서 켜고 끈다) */
    hazardLit: Boolean = false,
    trunkOpen: Boolean = false,
    trunkLabel: String = "트렁크 열림",
) {
    val measurer = rememberTextMeasurer()
    val labelSize = (width.value * 0.05f).sp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("▲ 차량 앞", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Canvas(Modifier.width(width).aspectRatio(0.55f)) {
            val body = Size(size.width * 0.8f, size.height * 0.96f)
            val origin = Offset((size.width - body.width) / 2, (size.height - body.height) / 2)
            fun at(x: Float, y: Float) = origin + Offset(body.width * x, body.height * y)
            val corner = CornerRadius(body.width * 0.2f)

            drawWheels(origin, body)
            val outline = Path().apply { addRoundRect(RoundRect(Rect(origin, body), corner)) }
            clipPath(outline) {
                drawRect(Roof, origin, body)
                for (r in ZONES) {
                    val z = zoneOf(r)
                    val color = when {
                        r == Region.REAR && trunkOpen -> TrunkOpen
                        r == highlight -> Highlight
                        r in candidates || (counts[r] ?: 0) > 0 -> Possible
                        else -> Idle
                    }
                    drawRect(color, at(z.left, z.top), Size(body.width * z.width, body.height * z.height))
                }
                // 구역 경계
                val edge = Color.White.copy(alpha = 0.55f)
                drawLine(edge, at(0f, HOOD_END), at(1f, HOOD_END), 3f)
                drawLine(edge, at(0f, TRUNK_START), at(1f, TRUNK_START), 3f)
                drawLine(edge, at(0f, DOOR_SPLIT), at(DOOR_WIDTH, DOOR_SPLIT), 3f)
                drawLine(edge, at(1 - DOOR_WIDTH, DOOR_SPLIT), at(1f, DOOR_SPLIT), 3f)
                drawLine(edge, at(DOOR_WIDTH, HOOD_END), at(DOOR_WIDTH, TRUNK_START), 3f)
                drawLine(edge, at(1 - DOOR_WIDTH, HOOD_END), at(1 - DOOR_WIDTH, TRUNK_START), 3f)
            }
            drawRoundRect(Color.White, origin, body, corner, style = Stroke(width = 4f))
            // 앞·뒤 유리: 어느 쪽이 앞인지 한눈에 보이게
            val glass = Color.White.copy(alpha = 0.6f)
            drawPath(trapezoid(at(0.34f, 0.26f), at(0.66f, 0.26f), at(0.62f, 0.34f), at(0.38f, 0.34f)), glass, style = Stroke(width = 3f))
            drawPath(trapezoid(at(0.38f, 0.72f), at(0.62f, 0.72f), at(0.66f, 0.78f), at(0.34f, 0.78f)), glass, style = Stroke(width = 3f))

            if (hazardLit) {
                for (x in listOf(0.13f, 0.87f)) for (y in listOf(0.035f, 0.965f)) {
                    drawCircle(HazardLight, body.width * 0.07f, at(x, y))
                }
            }

            for (r in ZONES) {
                val z = zoneOf(r)
                val lit = r == highlight || r in candidates || (counts[r] ?: 0) > 0
                val count = counts[r]?.takeIf { it > 0 }?.let { "\n${it}건" }.orEmpty()
                val name = if (r == Region.REAR && trunkOpen) trunkLabel else zoneLabel(r).replace("\n", if (r == Region.FRONT || r == Region.REAR) " " else "\n")
                val text = measurer.measure(
                    name + count,
                    TextStyle(
                        color = if (lit || (r == Region.REAR && trunkOpen)) Color(0xFF201A10) else Color.White,
                        fontSize = labelSize,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
                val center = at(z.center.x, z.center.y)
                drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
            }
        }
        Text("왼쪽·오른쪽은 운전석에 앉아 앞을 볼 때 기준이에요", fontSize = 13.sp, color = Color.Gray)
    }
}

private fun DrawScope.drawWheels(origin: Offset, body: Size) {
    val wheel = Size(body.width * 0.09f, body.height * 0.13f)
    val r = CornerRadius(wheel.width * 0.4f)
    for (y in listOf(0.13f, 0.74f)) {
        drawRoundRect(Tire, origin + Offset(-wheel.width * 0.55f, body.height * y), wheel, r)
        drawRoundRect(Tire, origin + Offset(body.width - wheel.width * 0.45f, body.height * y), wheel, r)
    }
}

private fun trapezoid(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}
