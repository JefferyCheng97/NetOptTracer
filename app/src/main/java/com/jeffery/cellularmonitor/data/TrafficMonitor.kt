package com.jeffery.cellularmonitor.data

import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 被动监测移动网络实时速率。
 *
 * 用 [TrafficStats.getMobileRxBytes] / [getMobileTxBytes] 每秒采样一次差值，
 * 不发任何流量，读的是系统计数器——你刷视频、下载文件时能实时看到速率变化。
 *
 * 注意：
 * - 返回的是**设备级全局移动数据速率**，包含所有 App 的流量（系统限制，单 App 读不到别家的）
 * - Wi-Fi 流量不计入（只统计移动数据）
 * - 某些手机厂商 ROM 可能禁用了这个 API，会返回 [TrafficStats.UNSUPPORTED]
 */
object TrafficMonitor {

    /** 采样间隔。太短抖动大，太长反应慢，1 秒是个合理的折衷。 */
    private const val SAMPLE_INTERVAL_MS = 1000L

    /**
     * 实时速率流，每秒推一次 [TrafficSpeed]。
     *
     * collect 这个 Flow 时监测自动开始，cancel 时自动停止，不需要额外的 start/stop。
     */
    fun observe(): Flow<TrafficSpeed> = flow {
        var lastRx = TrafficStats.getMobileRxBytes()
        var lastTx = TrafficStats.getMobileTxBytes()
        var lastTime = System.currentTimeMillis()

        // 首次采样，速率为 0
        if (lastRx != TrafficStats.UNSUPPORTED.toLong() && lastTx != TrafficStats.UNSUPPORTED.toLong()) {
            emit(TrafficSpeed(downloadBytesPerSec = 0.0, uploadBytesPerSec = 0.0))
        } else {
            emit(TrafficSpeed.UNSUPPORTED)
            return@flow
        }

        while (true) {
            delay(SAMPLE_INTERVAL_MS)

            val nowRx = TrafficStats.getMobileRxBytes()
            val nowTx = TrafficStats.getMobileTxBytes()
            val nowTime = System.currentTimeMillis()

            if (nowRx == TrafficStats.UNSUPPORTED.toLong() || nowTx == TrafficStats.UNSUPPORTED.toLong()) {
                emit(TrafficSpeed.UNSUPPORTED)
                break
            }

            val deltaRx = (nowRx - lastRx).coerceAtLeast(0)
            val deltaTx = (nowTx - lastTx).coerceAtLeast(0)
            val deltaTime = (nowTime - lastTime).toDouble() / 1000.0

            if (deltaTime > 0) {
                emit(
                    TrafficSpeed(
                        downloadBytesPerSec = deltaRx / deltaTime,
                        uploadBytesPerSec = deltaTx / deltaTime,
                    )
                )
            }

            lastRx = nowRx
            lastTx = nowTx
            lastTime = nowTime
        }
    }
}

/**
 * 实时流量速率（字节/秒）。
 *
 * 用 [toMbps] 转成 Mbps 显示。0.0 表示当前无流量（没在下载/上传）。
 */
data class TrafficSpeed(
    val downloadBytesPerSec: Double,
    val uploadBytesPerSec: Double,
) {
    /** 字节/秒 → Mbps（运营商口径，1 Mbps = 10^6 bit/s）。 */
    fun toMbps(bytesPerSec: Double): Double = bytesPerSec * 8.0 / 1_000_000.0

    val downloadMbps: Double get() = toMbps(downloadBytesPerSec)
    val uploadMbps: Double get() = toMbps(uploadBytesPerSec)

    companion object {
        /** 系统不支持 TrafficStats（少数厂商 ROM 禁用了）。 */
        val UNSUPPORTED = TrafficSpeed(-1.0, -1.0)
    }
}
