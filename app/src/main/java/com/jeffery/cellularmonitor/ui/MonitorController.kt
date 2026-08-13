package com.jeffery.cellularmonitor.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jeffery.cellularmonitor.data.CardKind
import com.jeffery.cellularmonitor.data.CardOrderStore
import com.jeffery.cellularmonitor.data.CellIdFormatter
import com.jeffery.cellularmonitor.data.CellTypeClassifier
import com.jeffery.cellularmonitor.data.CellularRepository
import com.jeffery.cellularmonitor.data.CollectorStatus
import com.jeffery.cellularmonitor.data.SlotSnapshot
import com.jeffery.cellularmonitor.data.SpeedTestProgress
import com.jeffery.cellularmonitor.data.SpeedTestResult
import com.jeffery.cellularmonitor.data.SpeedTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 状态持有者，持有 [CellularRepository] 并暴露 UI 状态。
 *
 * 不继承 ViewModel 避免引入 lifecycle-viewmodel-compose 依赖，
 * 改用 [remember] + [DisposableEffect] 管理生命周期。
 */
class MonitorController(context: Context) {
    private val appContext = context.applicationContext
    private val repository = CellularRepository(appContext)

    /** 导入工参用的协程作用域，跟随 [start] / [stop]。 */
    private var scope: CoroutineScope? = null

    val snapshots: List<SlotSnapshot>
        @Composable get() = repository.snapshots.collectAsState().value

    val status: CollectorStatus
        @Composable get() = repository.status.collectAsState().value

    var selectedSlotIndex by mutableIntStateOf(0)
        private set

    var gnbBits by mutableIntStateOf(CellIdFormatter.DEFAULT_GNB_BITS)

    /** 工参导入的进行中/结果状态，null 表示没有需要提示的内容。 */
    var importState by mutableStateOf<ImportState?>(null)
        private set

    /** 测速的实时进度，null 表示当前没在测。 */
    var speedProgress by mutableStateOf<SpeedTestProgress?>(null)
        private set

    /** 上一次测速结果，跨阶段保留，下次开测时清掉。 */
    var speedResult by mutableStateOf<SpeedTestResult?>(null)
        private set

    /** 卡片顺序。构造时从 SharedPreferences 读，改动立即落盘。 */
    var cardOrder by mutableStateOf(CardOrderStore.load(appContext))
        private set

    /** 把 [from] 位置的卡片移到 [to] 位置，其余顺次挪。 */
    fun moveCard(from: Int, to: Int) {
        val current = cardOrder
        if (from !in current.indices || to !in current.indices || from == to) return
        cardOrder = current.toMutableList().apply { add(to, removeAt(from)) }
        CardOrderStore.save(appContext, cardOrder)
    }

    /** 恢复默认顺序。 */
    fun resetCardOrder() {
        CardOrderStore.reset(appContext)
        cardOrder = CardKind.DEFAULT
    }

    private var speedJob: Job? = null

    val isSpeedTesting: Boolean get() = speedProgress != null

    fun start() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        repository.start()
    }

    fun stop() {
        speedJob?.cancel()
        speedJob = null
        speedProgress = null
        scope?.cancel()
        scope = null
        repository.stop()
    }

    fun retry() = repository.retry()

    fun selectSlot(index: Int) {
        selectedSlotIndex = index
    }

    /**
     * 导入用户选中的工参 TXT。解析成功才会覆盖已有工参，失败时旧数据保持不变。
     */
    fun importGongcan(uri: Uri) {
        val target = scope ?: return
        importState = ImportState.Loading
        target.launch {
            importState = runCatching { CellTypeClassifier.import(appContext, uri) }
                .fold(
                    onSuccess = { result ->
                        repository.reloadCellTypes()
                        ImportState.Success(
                            cellCount = result.cellCount,
                            carrierCounts = result.carrierCounts,
                            typeCounts = result.typeCounts,
                            skippedOtherPlmn = result.skippedOtherPlmn,
                        )
                    },
                    onFailure = { e -> ImportState.Failure(e.message ?: "导入失败") },
                )
        }
    }

    /** 清掉导入的工参，不再显示覆盖类型。 */
    fun clearImportedGongcan() {
        val target = scope ?: return
        target.launch {
            CellTypeClassifier.clearImported(appContext)
            repository.reloadCellTypes()
            importState = ImportState.Cleared(repository.cellTypeCount())
        }
    }

    /** 消掉提示。 */
    fun dismissImportState() {
        importState = null
    }

    /**
     * 开始测速。会消耗约 30 MB 移动数据，界面侧要先跟用户确认过。
     *
     * 重复点击不会叠加：正在测的时候直接忽略。
     */
    fun startSpeedTest() {
        val target = scope ?: return
        if (speedJob?.isActive == true) return

        speedResult = null
        speedProgress = SpeedTestProgress(com.jeffery.cellularmonitor.data.SpeedTestPhase.DOWNLOAD)
        speedJob = target.launch {
            // SpeedTester 的回调在 IO 线程，状态写入必须切回主线程
            val result = SpeedTester.run { progress ->
                target.launch { speedProgress = progress }
            }
            withContext(Dispatchers.Main.immediate) {
                speedResult = result
                speedProgress = null
            }
        }
    }

    /** 中途取消测速，已传输的量不作结果。 */
    fun cancelSpeedTest() {
        speedJob?.cancel()
        speedJob = null
        speedProgress = null
    }

    /** 消掉测速结果卡片上的数值。 */
    fun clearSpeedResult() {
        speedResult = null
    }
}

/** 工参导入的状态，用来驱动提示对话框。 */
sealed interface ImportState {
    data object Loading : ImportState

    data class Success(
        val cellCount: Int,
        /** 每家运营商识别到的小区数，用于告诉用户哪些卡能查到覆盖类型。 */
        val carrierCounts: Map<String, Int>,
        val typeCounts: Map<String, Int>,
        val skippedOtherPlmn: Int,
    ) : ImportState

    data class Failure(val message: String) : ImportState

    data class Cleared(val remainingCount: Int) : ImportState
}

@Composable
fun rememberMonitorController(context: Context): MonitorController {
    val controller = remember { MonitorController(context) }
    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }
    return controller
}
