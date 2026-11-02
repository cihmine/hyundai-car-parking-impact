package com.cihmine.parkingimpact.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 화면·음성 문구. 파손·책임을 단정하지 않고 "충격 의심", "확인 권장 구역"으로 말한다. */
object Texts {
    private val timeFormat = DateTimeFormatter.ofPattern("M월 d일 a h시 m분", Locale.KOREAN)

    fun time(iso: String, zone: ZoneId): String =
        runCatching { Instant.parse(iso).atZone(zone).format(timeFormat) }.getOrDefault(iso)

    fun regionPhrase(e: Estimate): String = when (e.region) {
        Region.UNKNOWN -> if (e.candidates.isEmpty()) "구역 판단 어려움" else "${e.candidates.joinToString(" 또는 ") { it.label }} 쪽(판단 어려움)"
        else -> "${e.region.label} 구역"
    }

    /** 재탑승 음성 요약. 개수와 가장 최근 사건부터 짧게. */
    fun summarySpeech(s: ReturnSummary, zone: ZoneId): String {
        val latest = s.latest
        val head = if (s.count == 1) "주차 중 충격 의심 기록이 1건 있어요." else "주차 중 충격 의심 기록이 ${s.count}건 있어요."
        val recent = "${if (s.count == 1) "" else "가장 최근은 "}${time(latest.occurredAt, zone)}, ${regionPhrase(latest.estimate)}이에요."
        return "$head $recent 차를 세운 상태에서 화면으로 확인해 주세요."
    }

    const val ACK_MEANING = "확인 완료는 기록을 읽었다는 뜻이며, 차량에 파손이 없다는 뜻은 아니에요."
    const val EVIDENCE_MEANING = "근거 수준은 규칙상 정보량이며 실제 정확도를 측정한 값이 아니에요."
}
