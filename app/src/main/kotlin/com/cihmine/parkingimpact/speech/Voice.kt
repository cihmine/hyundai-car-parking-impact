package com.cihmine.parkingimpact.speech

import android.content.Context
import android.content.Intent

/** 짧은 음성 안내(재탑승 요약). 화면에 어떤 엔진인지 보여 주고, 끝날 때 정리한다. */
interface VoiceOutput {
    val engineName: String

    /** 말하기 속도 조절 지원 여부. Pleos TTS 는 속도 API 가 없다. */
    val supportsRate: Boolean

    /** [onDone] 은 메인 스레드에서 한 번만 불린다. [stop] 으로 끊으면 불리지 않는다. */
    fun speak(text: String, ratePercent: Int, onDone: () -> Unit)
    fun stop()
    fun shutdown()
}

object Voice {
    private const val PLEOS_SPEECH_SERVICE = "ai.pleos.playground.caas.intent.action.SPEECH_START"

    /** Pleos Connect 음성 서비스가 있는 기기(차량·Pleos 에뮬레이터)인지 */
    fun hasPleosSpeech(context: Context): Boolean =
        context.packageManager.queryIntentServices(Intent(PLEOS_SPEECH_SERVICE), 0).isNotEmpty()

    fun output(context: Context, onReady: (Boolean) -> Unit): VoiceOutput =
        if (hasPleosSpeech(context)) PleosSpeechOutput(context, onReady) else AndroidSpeechOutput(context, onReady)
}
