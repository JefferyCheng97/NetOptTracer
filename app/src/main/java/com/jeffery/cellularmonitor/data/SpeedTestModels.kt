package com.jeffery.cellularmonitor.data

/** 测速进行到哪一步。 */
enum class SpeedTestPhase { DOWNLOAD, UPLOAD }

/**
 * 测速中的实时进度。
 *
 * 已经测完的阶段保留数值，界面上下载测完后切到上传时，下载结果不会闪掉。
 */
data class SpeedTestProgress(
    val phase: SpeedTestPhase,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
)

/** 测速结果。 */
sealed interface SpeedTestResult {
    data class Success(
        val downloadMbps: Double?,
        val uploadMbps: Double?,
        val finishedAt: Long,
    ) : SpeedTestResult

    data class Failure(val message: String) : SpeedTestResult
}
