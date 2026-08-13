package com.jeffery.cellularmonitor.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import javax.net.ssl.HttpsURLConnection

/**
 * 网速测试。用中科大/南大等国内测速节点，不绕远。
 *
 * 下载：向镜像站的大文件（Arch Linux ISO）发 Range 请求，只取前 N MB，边读边算瞬时速率。
 * 上传：向中科大 LibreSpeed 后端 POST 随机字节——发出去的内容与本机数据无关，不涉及隐私。
 *        后端返回 500 但完整接收了数据，计时有效（LibreSpeed 的响应码问题）。
 *
 * 用 HttpURLConnection 而不是 OkHttp：不想为一个功能加依赖。
 * 单连接测出来的值会略低于多连接并发的真实带宽，够看趋势，不当作权威结果。
 */
object SpeedTester {

    private const val TAG = "SpeedTester"

    /** 下载测速用的大文件（Arch Linux ISO，各镜像站都有）。用 Range 只取前 25 MB。 */
    private val DOWNLOAD_URLS = listOf(
        "https://mirrors.ustc.edu.cn/archlinux/iso/latest/archlinux-x86_64.iso",
        "https://mirrors.nju.edu.cn/archlinux/iso/latest/archlinux-x86_64.iso",
        "https://mirrors.tuna.tsinghua.edu.cn/archlinux/iso/latest/archlinux-x86_64.iso",
    )

    /** 上传测速用中科大 LibreSpeed 后端。虽返回 500 但完整接收数据，计时有效。 */
    private const val UPLOAD_URL = "https://test.ustc.edu.cn/backend/empty.php"

    /** 单次下载的字节数。100 MB 在 5G 下约十几秒，4G 下约一分钟。 */
    private const val DOWNLOAD_BYTES = 100L * 1024 * 1024

    /** 单次上传的字节数。上行通常远慢于下行，但也加大到 30MB。 */
    private const val UPLOAD_BYTES = 30L * 1024 * 1024

    /** 超时上限，网络不通时不能一直挂着。 */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 整轮测试的硬上限，慢网下超时就用已传输的量算结果。 */
    private const val PHASE_BUDGET_MS = 20_000L

    private const val BUFFER_SIZE = 256 * 1024  // 256KB，高带宽下减少系统调用开销

    /** 前若干字节算建连和 TCP 慢启动，不计入速率。 */
    private const val WARMUP_BYTES = 256L * 1024

    /** 测几轮。2 轮下 sorted[size/2] 会取到较快的一次，抵消慢启动/瞬时抖动。 */
    private const val TEST_ROUNDS = 2

    /**
     * 跑一次完整测试：下载和上传各测 3 轮，取中位数。
     *
     * [onProgress] 在每个阶段推进时回调，用来让界面显示实时速率。
     * 回调在 IO 线程，界面侧要自己切回主线程。
     */
    suspend fun run(onProgress: (SpeedTestProgress) -> Unit): SpeedTestResult =
        withContext(Dispatchers.IO) {
            try {
                // 下载测 3 轮
                val downloadSamples = mutableListOf<Double>()
                repeat(TEST_ROUNDS) { round ->
                    val mbps = measureDownload(DOWNLOAD_URLS.first()) { mbps ->
                        onProgress(
                            SpeedTestProgress(
                                phase = SpeedTestPhase.DOWNLOAD,
                                downloadMbps = mbps,
                            )
                        )
                    }
                    mbps?.let { downloadSamples.add(it) }
                    if (round < TEST_ROUNDS - 1 && currentCoroutineContext().isActive) {
                        // 轮次间歇 1 秒，避免服务器限流
                        kotlinx.coroutines.delay(1000)
                    }
                }

                val download = downloadSamples.sorted().getOrNull(downloadSamples.size / 2)

                // 上传测 3 轮
                val uploadSamples = mutableListOf<Double>()
                repeat(TEST_ROUNDS) { round ->
                    val mbps = measureUpload { mbps ->
                        onProgress(
                            SpeedTestProgress(
                                phase = SpeedTestPhase.UPLOAD,
                                downloadMbps = download,
                                uploadMbps = mbps,
                            )
                        )
                    }
                    mbps?.let { uploadSamples.add(it) }
                    if (round < TEST_ROUNDS - 1 && currentCoroutineContext().isActive) {
                        kotlinx.coroutines.delay(1000)
                    }
                }

                val upload = uploadSamples.sorted().getOrNull(uploadSamples.size / 2)

                SpeedTestResult.Success(
                    downloadMbps = download,
                    uploadMbps = upload,
                    finishedAt = System.currentTimeMillis(),
                )
            } catch (e: IOException) {
                Log.w(TAG, "测速失败", e)
                SpeedTestResult.Failure(e.message ?: "网络错误")
            }
        }

    /** 下载：边读边算，读满 [DOWNLOAD_BYTES] 或超预算就停。用 Range 请求只取前 N MB。 */
    private suspend fun measureDownload(url: String, onTick: (Double) -> Unit): Double? {
        val conn = openConnection(url).apply {
            // Range 请求：只取前 DOWNLOAD_BYTES，不下载整个 1.5GB ISO
            setRequestProperty("Range", "bytes=0-${DOWNLOAD_BYTES - 1}")
        }
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            var counted = 0L
            var startNanos = 0L
            val deadline = System.currentTimeMillis() + PHASE_BUDGET_MS

            conn.inputStream.use { input ->
                while (currentCoroutineContext().isActive) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read

                    // 慢启动阶段的字节不计入速率，从预热结束那一刻重新起表
                    if (total >= WARMUP_BYTES) {
                        if (startNanos == 0L) {
                            startNanos = System.nanoTime()
                        } else {
                            counted += read
                            onTick(toMbps(counted, System.nanoTime() - startNanos))
                        }
                    }

                    if (System.currentTimeMillis() > deadline) break
                }
            }

            if (startNanos == 0L || counted == 0L) return null
            return toMbps(counted, System.nanoTime() - startNanos)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 上传：POST 随机字节到中科大 LibreSpeed 后端。
     *
     * 后端返回 500 但完整接收了数据——判定成功看"发完了多少字节"，不看响应码。
     * 用 setFixedLengthStreamingMode 而不是默认缓冲，否则整个 body 会先在内存里攒齐，
     * 8 MB 虽不至于 OOM，但也就没有边传边算的实时速率了。
     */
    private suspend fun measureUpload(onTick: (Double) -> Unit): Double? {
        val conn = openConnection(UPLOAD_URL).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(UPLOAD_BYTES)
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            // 每个 chunk 都重新随机没必要，填一次反复发即可
            val chunk = ByteArray(BUFFER_SIZE).also { Random().nextBytes(it) }
            var sent = 0L
            var counted = 0L
            var startNanos = 0L
            val deadline = System.currentTimeMillis() + PHASE_BUDGET_MS

            conn.outputStream.use { out ->
                while (sent < UPLOAD_BYTES && currentCoroutineContext().isActive) {
                    val n = minOf(chunk.size.toLong(), UPLOAD_BYTES - sent).toInt()
                    out.write(chunk, 0, n)
                    sent += n

                    if (sent >= WARMUP_BYTES) {
                        if (startNanos == 0L) {
                            startNanos = System.nanoTime()
                        } else {
                            counted += n
                            onTick(toMbps(counted, System.nanoTime() - startNanos))
                        }
                    }

                    if (System.currentTimeMillis() > deadline) break
                }
                out.flush()
            }

            // 读一次响应码让连接走完，但不判成功——中科大后端返回 500
            runCatching { conn.responseCode }

            if (startNanos == 0L || counted == 0L) return null
            return toMbps(counted, System.nanoTime() - startNanos)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        // 压缩会让"传输字节数"和"链路字节数"对不上，测速必须关掉
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.useCaches = false
        return conn
    }

    /** 字节 + 纳秒 → Mbps（1 Mbps = 10^6 bit/s，运营商口径）。 */
    private fun toMbps(bytes: Long, nanos: Long): Double {
        if (nanos <= 0) return 0.0
        return bytes * 8.0 / (nanos / 1_000_000_000.0) / 1_000_000.0
    }
}
