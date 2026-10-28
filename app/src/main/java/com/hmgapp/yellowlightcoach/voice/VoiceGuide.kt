package com.hmgapp.yellowlightcoach.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.SystemClock
import java.util.Locale

/**
 * 음성 안내 (Pleos TextToSpeech SDK 3.3.1)
 *
 * 모드
 *  - ON_DEVICE : 차량 안 엔진. 인터넷·앱 등록 불필요 → 10/28 첫 테스트
 *  - HYBRID    : 서버 우선, 실패하면 차량 엔진 (앱 등록 필요, 나중에)
 *
 * 순서: 생성 → initialize() → 리스너 등록 → setLocale(한국어) → speak()
 */
class VoiceGuide(
    context: Context,
    mode: Mode = Mode.ON_DEVICE,
    private val log: (String) -> Unit
) {
    private val tts = TextToSpeech(context.applicationContext, mode)

    @Volatile var ready = false
        private set

    /** 지연 측정: speak()를 부른 시각 기준으로 각 이벤트까지 몇 ms 걸렸는지 */
    @Volatile private var requestedAt = 0L
    private fun since() = if (requestedAt == 0L) "" else " (+${SystemClock.elapsedRealtime() - requestedAt}ms)"

    private val listener = object : EventListener {
        override fun onReady() = log("TTS 준비됨${since()}")
        override fun onStart() = log("🔊 재생 시작${since()}")
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = log("🔊 재생 끝${since()}")
        override fun onStop() = log("🔊 재생 중지${since()}")
        override fun onError(errorType: ErrorType) = log("❌ TTS 오류: ${errorType.name}${since()}")
    }

    fun init() {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(listener)
                tts.setLocale(Locale.KOREA)
                ready = true
                log("TTS 초기화 성공")
                // 예열: 엔진은 첫 요청만 ~12초 늦음(10/28 측정, 이후 요청은 0.02~0.04초 만에 시작)
                // → 앱 시작 인사로 미리 한 번 말해 두면 실제 안내는 처음부터 빠르게 나간다
                speak(WARM_UP_TEXT)
            }

            override fun onFailure(errorType: ErrorType) {
                log("❌ TTS 초기화 실패: ${errorType.name}")
            }
        })
    }

    fun speak(text: String) {
        if (!ready) {
            log("TTS 아직 준비 안 됨")
            return
        }
        requestedAt = SystemClock.elapsedRealtime()
        log("▶ 요청: $text")
        tts.speak(text)
    }

    fun stop() {
        if (ready) tts.stop()
    }

    companion object {
        const val WARM_UP_TEXT = "노란불 코치를 시작합니다"
    }

    fun release() {
        if (ready) tts.removeEventListener(listener)
        tts.release()
        ready = false
    }
}