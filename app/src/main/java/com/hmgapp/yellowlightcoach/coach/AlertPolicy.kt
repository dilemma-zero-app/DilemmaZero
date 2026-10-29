package com.hmgapp.yellowlightcoach.coach

/**
 * 언제 말할지 — "필요할 때만, 짧게, 한 번만"
 *
 *  - 같은 교차로에서 상태가 이전보다 위험해졌을 때만 말한다 (여유→주의→정지 준비)
 *  - 같은 단계는 다시 말하지 않는다
 *  - 다른 교차로로 넘어가면 처음부터
 *  - 여유·비활성은 말하지 않는다 (침묵)
 */
class AlertPolicy {
    private var intersectionId: String? = null
    private var spokenRank = 0

    /** 말할 문장, 말하지 않으면 null */
    fun next(intersectionId: String?, level: Level): String? {
        if (intersectionId != this.intersectionId) {
            this.intersectionId = intersectionId
            spokenRank = 0
        }
        if (intersectionId == null) return null
        if (level.rank <= spokenRank) return null
        val text = phrase(level) ?: return null
        spokenRank = level.rank
        return text
    }

    companion object {
        fun phrase(level: Level): String? = when (level) {
            Level.CAUTION -> "앞 신호, 속도 줄이세요"
            Level.PREPARE_STOP -> "정지 준비"
            else -> null
        }
    }
}