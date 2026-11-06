package com.cihmine.parkingimpact.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 화면·음성 문구. 파손·책임을 단정하지 않고 "충격 의심", "확인 권장 구역"으로 말한다. */
object Texts {
    private val timeFormat = DateTimeFormatter.ofPattern("M월 d일 a h시 m분", Locale.KOREAN)
    private val dayFormat = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
    private val clockFormat = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
    private val spokenFormat = DateTimeFormatter.ofPattern("a h시 m분", Locale.KOREAN)

    fun time(iso: String, zone: ZoneId): String =
        runCatching { Instant.parse(iso).atZone(zone).format(timeFormat) }.getOrDefault(iso)

    fun regionPhrase(e: Estimate): String = when (e.region) {
        Region.UNKNOWN -> if (e.candidates.isEmpty()) "구역 판단 어려움" else "${e.candidates.joinToString(" 또는 ") { it.label }} 쪽(판단 어려움)"
        else -> "${e.region.label} 구역"
    }

    /** 충격 의심이 있었던 곳. 조사가 붙는 자리에 쓴다. */
    private fun impactPlace(e: Estimate): String = when {
        e.region != Region.UNKNOWN -> e.region.label
        e.candidates.isEmpty() -> "차량"
        else -> "${e.candidates.joinToString(" 또는 ") { it.label }} 쪽"
    }

    /** 사용자에게 보여 주는 충격 의심 문구. 오늘이 아닌 기록은 날짜도 쓴다. */
    fun impactNotice(r: ImpactRecord, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(occurredMs(r)).atZone(zone)
        val today = at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = (if (today) "" else at.format(dayFormat) + " ") + at.format(clockFormat)
        return "${impactPlace(r.estimate)}에 ${clock}분 쯤 충격 의심이 감지되었습니다"
    }

    /** 재탑승 음성 요약. 화면 문구와 같은 내용을 말로 읽기 좋게 말한다. */
    fun summarySpeech(s: ReturnSummary, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(occurredMs(s.latest)).atZone(zone)
        val today = at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day = if (today) "" else at.format(dayFormat) + " "
        val more = if (s.count > 1) " 다른 기록도 ${s.count - 1}건 더 있어요." else ""
        return "${impactPlace(s.latest.estimate)}에 $day${at.format(spokenFormat)}쯤 충격 의심이 감지되었습니다.$more $IMPACT_CHECK_NOTE"
    }

    /** 구역은 추정이므로 사용자가 차 전체를 직접 확인하도록 권한다. */
    const val IMPACT_CHECK_NOTE = "정확하지 않을 수 있으니 전체적으로 확인해 보세요."

    fun coordinates(p: CarPosition): String = String.format(Locale.US, "위도 %.5f, 경도 %.5f", p.latitude, p.longitude)

    /** 비상 화면의 위치 문구. 전화로 읽어 주기 쉬운 주소를 먼저 쓴다. 위치를 모르면 찾는 중인지 못 찾았는지 구분한다. */
    fun locationHeadline(address: String?, position: CarPosition?, failed: Boolean): String = when {
        !address.isNullOrBlank() -> address
        position != null -> coordinates(position)
        failed -> "위치를 확인하지 못했어요. 주변 표지판이나 이정표를 알려 주세요."
        else -> "위치를 찾는 중…"
    }

    /** 차가 멈춘 상태의 안내. 대피를 가장 앞세운다. */
    const val ACCIDENT_EVACUATE =
        "비상등과 트렁크를 작동합니다. 주변이 안전하면 차에서 내려 가드레일 밖으로 대피해 주세요. 도움이 필요하면 화면의 번호로 연락해 주세요."
    const val ACCIDENT_STOP_FIRST = "비상등을 켭니다. 안전한 곳에 차를 세워 주세요. 차가 멈추면 트렁크를 열고 연락처를 보여 드릴게요."
    const val ACCIDENT_HAZARD_FAILED = "비상등이 켜지지 않았어요. 비상등 스위치를 직접 눌러 주세요."
    const val ACCIDENT_TRUNK_FAILED = "트렁크가 열리지 않았어요. 열지 못해도 대피를 먼저 해 주세요."
    const val ACCIDENT_NOT_REAL = "이 시제품의 비상등은 모의 동작이고, 앱이 대신 전화하지 않아요. 다친 사람이 있거나 불이 나면 119, 사고 처리는 112에 직접 전화해 주세요."
    const val INSURER_NOTE = "목록에 없는 보험사는 보험 증권이나 보험사 앱의 번호로 연락해 주세요."

    const val ACK_MEANING = "확인 완료는 기록을 읽었다는 뜻이며, 차량에 파손이 없다는 뜻은 아니에요."
    const val EVIDENCE_MEANING = "근거 수준은 규칙상 정보량이며 실제 정확도를 측정한 값이 아니에요."
}
