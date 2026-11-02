package com.cihmine.parkingimpact.data

import android.util.AtomicFile
import android.util.Log
import com.cihmine.parkingimpact.core.MonitorCodec
import com.cihmine.parkingimpact.core.MonitorState
import java.io.File

/**
 * 앱 전용 폴더의 JSON 파일. 쓰다가 꺼져도 이전 내용이 남도록 AtomicFile 로 쓴다.
 * 이벤트 수신·구역 결과·미확인 상태를 한 파일에 함께 저장해 서로 어긋나지 않게 한다.
 * 읽지 못한 파일은 새 상태로 덮어쓰기 전에 옆에 백업해 둔다(기록 복구 기회를 남김).
 */
class FileMonitorStore(dir: File) {
    private val raw = File(dir, "parking_impact.json")
    private val file = AtomicFile(raw)

    /** 마지막 [load] 에서 파일이 손상돼 백업했으면 그 파일 */
    var backedUp: File? = null
        private set

    fun load(): MonitorState? {
        if (!raw.exists()) return null
        return runCatching { MonitorCodec.decode(file.readFully().toString(Charsets.UTF_8)) }.getOrElse {
            Log.e("ParkingImpact", "저장 파일을 읽지 못함 — 백업 후 새로 시작", it)
            val copy = File(raw.parentFile, "parking_impact.broken-${System.currentTimeMillis()}.json")
            if (runCatching { raw.copyTo(copy) }.isSuccess) backedUp = copy
            null
        }
    }

    fun save(state: MonitorState) {
        val out = file.startWrite()
        try {
            out.write(MonitorCodec.encode(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
