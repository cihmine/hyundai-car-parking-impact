package com.cihmine.parkingimpact.data

import android.util.AtomicFile
import com.cihmine.parkingimpact.core.MonitorCodec
import com.cihmine.parkingimpact.core.MonitorState
import java.io.File

/**
 * 앱 전용 폴더의 JSON 파일. 쓰다가 꺼져도 이전 내용이 남도록 AtomicFile 로 쓴다.
 * 이벤트 수신·구역 결과·미확인 상태를 한 파일에 함께 저장해 서로 어긋나지 않게 한다.
 */
class FileMonitorStore(dir: File) {
    private val file = AtomicFile(File(dir, "parking_impact.json"))

    fun load(): MonitorState? = runCatching {
        MonitorCodec.decode(file.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

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
