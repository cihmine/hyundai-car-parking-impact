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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.parkingimpact.core.Region

val Highlight = Color(0xFFFFB74D)
val Possible = Color(0xFFFFE0B2)

/**
 * 위에서 본 차량 도식. 위쪽이 차량 앞(화살표). 강조는 "확인 권장 구역"이며 확정 파손 부위가 아니다.
 * 색만으로 구분하지 않도록 아래에 구역 이름을 글자로도 쓴다.
 */
@Composable
fun CarDiagram(
    highlight: Region?,
    candidates: List<Region> = emptyList(),
    counts: Map<Region, Int> = emptyMap(),
    width: Dp = 220.dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("▲ 차량 앞", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Canvas(Modifier.width(width).aspectRatio(0.55f)) {
            val w = size.width
            val h = size.height
            val body = Size(w * 0.8f, h * 0.92f)
            val origin = Offset((w - body.width) / 2, (h - body.height) / 2)
            val half = Size(body.width / 2, body.height / 2)
            fun quad(r: Region): Offset = when (r) {
                Region.FRONT_LEFT -> origin
                Region.FRONT_RIGHT -> origin + Offset(half.width, 0f)
                Region.REAR_LEFT -> origin + Offset(0f, half.height)
                else -> origin + Offset(half.width, half.height)
            }
            for (r in listOf(Region.FRONT_LEFT, Region.FRONT_RIGHT, Region.REAR_LEFT, Region.REAR_RIGHT)) {
                val color = when {
                    r == highlight -> Highlight
                    r in candidates -> Possible
                    (counts[r] ?: 0) > 0 -> Possible
                    else -> Color(0xFF3A3A44)
                }
                drawRect(color, topLeft = quad(r), size = half)
            }
            // 차체 윤곽과 구역 경계
            drawRoundRect(Color.White, origin, body, CornerRadius(w * 0.12f), style = Stroke(width = 4f))
            drawLine(Color.White.copy(alpha = 0.5f), origin + Offset(half.width, 0f), origin + Offset(half.width, body.height), 2f)
            drawLine(Color.White.copy(alpha = 0.5f), origin + Offset(0f, half.height), origin + Offset(body.width, half.height), 2f)
            // 앞 유리(앞쪽 표시)
            val glass = Path().apply {
                moveTo(origin.x + body.width * 0.2f, origin.y + body.height * 0.22f)
                lineTo(origin.x + body.width * 0.8f, origin.y + body.height * 0.22f)
                lineTo(origin.x + body.width * 0.7f, origin.y + body.height * 0.32f)
                lineTo(origin.x + body.width * 0.3f, origin.y + body.height * 0.32f)
                close()
            }
            drawPath(glass, Color.White.copy(alpha = 0.6f), style = Stroke(width = 3f))
        }
        Text("왼쪽 = 운전석 기준 좌측", fontSize = 13.sp, color = Color.Gray)
    }
}
