package app.noveltts.engine

import app.noveltts.engine.api.SynthesisError

/**
 * 合成失败后的重试/降级策略。
 *
 * [maxAttemptsPerVoice] 是**同一个音色**的总尝试次数（含第一次），默认 1 表示不重试；
 * 只有 [isTransient] 判定为瞬时错误、且 [retryTransientErrors] 打开时才会重试。
 * 同一音色放弃后是否换备用音色由 [allowStandbyVoice] 与 [isFatal] 决定。
 */
data class RetryPolicy(
    val maxAttemptsPerVoice: Int = 1,
    val retryTransientErrors: Boolean = true,
    val allowStandbyVoice: Boolean = true,
) {
    /** 归一化后的尝试次数，非法配置（0 或负数）按 1 次处理。 */
    val attemptsPerVoice: Int get() = maxAttemptsPerVoice.coerceAtLeast(1)

    /**
     * 已经尝试了 [completedAttempts] 次之后，是否还应对同一个音色再试一次。
     *
     * [completedAttempts] 从 1 开始计（第一次尝试失败后传 1）。
     */
    fun shouldRetry(code: Int, completedAttempts: Int): Boolean =
        retryTransientErrors && isTransient(code) && completedAttempts < attemptsPerVoice

    /** 当前错误码下是否允许换备用音色（致命错误和用户取消都不允许）。 */
    fun allowsStandby(code: Int): Boolean = allowStandbyVoice && !isFatal(code)
}

/**
 * 瞬时错误：重试同一个音色或换备用音色都可能成功。
 *
 * 包括网络失败、引擎内部合成失败、初始化失败，以及 HTTP 5xx / 408 / 429 这类
 * 服务端临时错误（桥接引擎可能直接透传 HTTP 码）。
 */
fun isTransient(code: Int): Boolean = when (code) {
    SynthesisError.NETWORK_FAILED,
    SynthesisError.SYNTHESIS_FAILED,
    SynthesisError.INIT_FAILED,
    -> true
    in 500..599, 408, 429 -> true
    else -> false
}

/**
 * 致命错误：重试和换音色都没有意义，必须立刻把错误抛给调用方。
 *
 * 注意 [SynthesisError.UNSUPPORTED_AUDIO] 故意不算致命：它只说明当前音色返回的
 * 音频格式不可用，同音色不重试（[isTransient] 为 false），但仍可尝试备用音色。
 */
fun isFatal(code: Int): Boolean = when (code) {
    SynthesisError.LICENSE_REQUIRED,
    SynthesisError.BAD_REQUEST,
    SynthesisError.CANCELLED,
    -> true
    else -> false
}
