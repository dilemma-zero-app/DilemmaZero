package com.hmgapp.yellowlightcoach.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 *
 * 오디오 포커스 (10/29)
 *  - Pleos TTS는 스스로 포커스를 요청하지 않음 (Logcat requestAudioFocus 0건)
 *  - 그래서 말하기 직전에 "잠깐 소리 줄여 주세요"(GAIN_TRANSIENT_MAY_DUCK)를 직접 요청하고
 *    다 말하면(끝·오류·멈춤) 돌려준다 → 음악·라디오는 끊기지 않고 작아졌다가 돌아온다
 *  - 말하는 중 새 안내로 끊기면(onStop) 포커스를 유지한다 → 다음 문장도 계속 줄인 상태
 */
class VoiceGuide(
    context: Context,
    mode: Mode = Mode.ON_DEVICE,
    private val log: (String) -> Unit
) {
    private val tts = TextToSpeech(context.applicationContext, mode)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { }   // 짧은 안내라 포커스 변화에 따로 대응하지 않음
        .build()
    @Volatile private var hasFocus = false

    private fun takeFocus() {
        if (hasFocus) return
        val r = audio.requestAudioFocus(focusRequest)
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log(if (hasFocus) "🔉 포커스 받음 (다른 소리 줄임)" else "⚠ 포커스 거절됨 (결과 $r)")
    }

    private fun releaseFocus() {
        if (!hasFocus) return
        audio.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        log("🔉 포커스 돌려줌")
    }

    @Volatile var ready = false
        private set

    /** 지연 측정: speak()를 부른 시각 기준으로 각 이벤트까지 몇 ms 걸렸는지 */
    @Volatile private var requestedAt = 0L
    private fun since() = if (requestedAt == 0L) "" else " (+${SystemClock.elapsedRealtime() - requestedAt}ms)"

    private val listener = object : EventListener {
        override fun onReady() = log("TTS 준비됨${since()}")
        override fun onStart() = log("🔊 재생 시작${since()}")
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { log("🔊 재생 끝${since()}"); releaseFocus() }
        // 새 문장에 끊긴 경우가 대부분 → 포커스 유지 (직접 멈춤은 stop()에서 돌려줌)
        override fun onStop() = log("🔊 재생 중지${since()}")
        override fun onError(errorType: ErrorType) { log("❌ TTS 오류: ${errorType.name}${since()}"); releaseFocus() }
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
        takeFocus()
        tts.speak(text)
    }

    fun stop() {
        if (ready) tts.stop()
        releaseFocus()
    }

    companion object {
        const val WARM_UP_TEXT = "노란불 코치를 시작합니다"
    }

    fun release() {
        releaseFocus()
        if (ready) tts.removeEventListener(listener)
        tts.release()
        ready = false
    }
}