package com.jeffery.cellularmonitor.data

import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * 单张卡的监听器。持有一个 `createForSubscriptionId` 得到的 [TelephonyManager]，
 * 把回调数据攒成一份 [SlotSnapshot]。
 *
 * API 31+ 用 [TelephonyCallback]，API 29/30 回退到已废弃的 [PhoneStateListener]
 * ——minSdk 是 29，两条路径都得留。
 */
internal class SlotMonitor(
    sim: SimSlotInfo,
    private val telephonyManager: TelephonyManager,
    private val executor: Executor,
    private val onUpdate: () -> Unit,
    /**
     * 工参分类器的**取值器**——用 lambda 而不是直接传实例，
     * 这样 CellularRepository 重新加载工参后 SlotMonitor 能拿到最新的。
     */
    private val classifierProvider: () -> CellTypeClassifier,
) {

    private val state = AtomicReference(SlotSnapshot(sim = sim))

    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    /** NSA 判定结果，来自 DisplayInfo 回调。 */
    @Volatile
    private var isNsa = false

    /** 最近一次 SignalStrength，NSA 下 NR 数值只能从这里取。 */
    @Volatile
    private var lastSignalStrength: SignalStrength? = null

    /**
     * LTE / NR 各自最后一次拿到真实读数的时刻（[elapsed]）。
     *
     * 服务小区从 CellInfo 里消失时，为了抗抖动会短暂保留旧值；但没有上限的话
     * 就会永久冻住——纯 SA 下 LTE 卡、掉回纯 4G 后 NR 卡都会挂着几分钟前的数
     * 不走，小区明细还会跟着显示一个已经离开的小区。用这两个时间戳给保留期封顶。
     */
    @Volatile
    private var lteSeenAt = 0L

    @Volatile
    private var nrSeenAt = 0L

    fun snapshot(): SlotSnapshot = state.get()

    /** 拔插卡或运营商名变化时更新卡信息，不影响已采集的数值。 */
    fun updateSim(sim: SimSlotInfo) {
        state.updateAndGet { it.copy(sim = sim) }
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerModernCallback()
        } else {
            registerLegacyListener()
        }
        // 先读一次当前值，别让界面空等第一次回调。
        requestRefresh()
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                runCatching { telephonyManager.unregisterTelephonyCallback(it) }
            }
            telephonyCallback = null
        } else {
            phoneStateListener?.let {
                @Suppress("DEPRECATION")
                runCatching { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
            phoneStateListener = null
        }
    }

    /**
     * 主动拉一次数据。[TelephonyManager.requestCellInfoUpdate] 会触发一次真实的
     * 空口测量，比缓存的 getAllCellInfo 更新更及时。
     */
    fun requestRefresh() {
        runCatching {
            telephonyManager.requestCellInfoUpdate(
                executor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        applyCellInfo(cellInfo)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        // 拿不到实时测量就退回缓存值，不打断界面刷新。
                        applyCellInfo(runCatching { telephonyManager.allCellInfo }.getOrNull())
                    }
                },
            )
        }.onFailure {
            applyCellInfo(runCatching { telephonyManager.allCellInfo }.getOrNull())
        }
        runCatching { telephonyManager.signalStrength }
            .getOrNull()
            ?.let { applySignalStrength(it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            applyNetworkTypeFallback()
        }
        // 上面各 apply 变化时已各自通知过，这里不再重复触发一次重组。
    }

    // ---- 注册 ----

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernCallback() {
        val callback = object :
            TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener {

            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                applyCellInfo(cellInfo)
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                applySignalStrength(signalStrength)
            }

            override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                applyDisplayInfo(displayInfo.networkType, displayInfo.overrideNetworkType)
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                // 服务状态变化（如失去信号）时刷新一次，避免界面留着旧值。
                requestRefresh()
            }
        }
        telephonyCallback = callback
        runCatching { telephonyManager.registerTelephonyCallback(executor, callback) }
    }

    /**
     * API 29/30 的回退路径。
     *
     * [TelephonyDisplayInfo] 是 API 30 才有的类，如果在 API 29 上加载一个签名里
     * 引用了它的方法会有 NoClassDefFoundError 的风险，所以按版本分成两个监听器：
     * API 30 的带 DisplayInfo 回调，API 29 的完全不碰这个类，靠
     * [applyNetworkTypeFallback] 从 dataNetworkType 推断制式。
     */
    @Suppress("DEPRECATION")
    private fun registerLegacyListener() {
        val listener: PhoneStateListener
        val events: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listener = LegacyListenerWithDisplayInfo()
            events = PhoneStateListener.LISTEN_CELL_INFO or
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_SERVICE_STATE or
                PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
        } else {
            listener = LegacyListener()
            events = PhoneStateListener.LISTEN_CELL_INFO or
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_SERVICE_STATE
        }
        phoneStateListener = listener
        runCatching { telephonyManager.listen(listener, events) }
    }

    @Suppress("DEPRECATION")
    private open inner class LegacyListener : PhoneStateListener() {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
            applyCellInfo(cellInfo)
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            signalStrength?.let { applySignalStrength(it) }
        }

        override fun onServiceStateChanged(serviceState: ServiceState?) {
            requestRefresh()
        }
    }

    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private inner class LegacyListenerWithDisplayInfo : LegacyListener() {
        override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
            applyDisplayInfo(displayInfo.networkType, displayInfo.overrideNetworkType)
        }
    }

    // ---- 数据归集 ----

    /** 从小区列表里挑出服务小区与邻区。 */
    private fun applyCellInfo(cellInfo: List<CellInfo>?) {
        if (cellInfo == null) return

        val registered = cellInfo.filter { it.isRegistered }
        val servingLte = registered.filterIsInstance<CellInfoLte>().firstOrNull()
        val servingNr = registered.filterIsInstance<CellInfoNr>().firstOrNull()

        // 当前 PLMN，邻区反查工参时要用（同一 PCI 在不同运营商可能对应不同小区）
        val plmn = state.get().sim.plmn

        val neighbors = cellInfo
            .filterNot { it.isRegistered }
            .mapNotNull { CellMapper.mapNeighbor(it) }
            // 反查工参：用 (PCI, 频点) 找所有候选小区
            .map { neighbor ->
                val pci = neighbor.pci
                val arfcn = neighbor.arfcn
                val candidates = if (pci != null && arfcn != null) {
                    classifierProvider().lookupNeighbors(pci, arfcn, plmn)
                } else emptyList()
                android.util.Log.d("SlotMonitor", "邻区反查: ${neighbor.type} PCI=$pci 频点=$arfcn PLMN=$plmn → ${candidates.size} 候选")
                neighbor.copy(candidates = candidates)
            }
            // 邻区可能很多，按 RSRP 由强到弱排，读不到强度的排最后。
            .sortedByDescending { it.rsrp ?: Int.MIN_VALUE }
            .take(MAX_NEIGHBORS)

        val now = elapsed()
        if (servingLte != null) lteSeenAt = now
        if (servingNr != null) nrSeenAt = now

        val stateBefore = state.get()
        val updated = state.updateAndGet { current ->
            // NSA 下 CellInfo 里读到的 NR 信号值可能缺失，用 SignalStrength 的补上。
            val lte = servingLte
                ?.let { mergeLte(CellMapper.mapLte(it), lteFromLastSignal()) }
                // 服务小区暂时读空：短时间内保留旧值抗抖动，超过 STALE_AFTER_MS 就清掉，
                // 否则纯 SA 下 LTE 卡会永久冻住，小区明细还会挑到这个已离开的小区。
                ?: retainLte(current.lte, now)
            // NSA 下服务小区是 LTE，NR 数值来自 SignalStrength，别被空的 NR 小区覆盖掉。
            val nr = servingNr
                ?.let { mergeNr(CellMapper.mapNr(it), nrFromLastSignal()) }
                // 掉回纯 4G 后 NR 小区会消失，同样限期保留后清掉。
                ?: retainNr(current.nr, now)
            if (lte == current.lte && nr == current.nr && neighbors == current.neighbors) {
                current
            } else {
                current.copy(lte = lte, nr = nr, neighbors = neighbors, updatedAt = now)
            }
        }
        if (updated !== stateBefore) onUpdate()
    }

    /**
     * 服务小区从 CellInfo 里读空时，决定 LTE 旧值的去留：
     * 距上次真实读到未超过 [STALE_AFTER_MS] 就保留（把主导权还给 SignalStrength），
     * 否则清成 null，让卡片和小区明细都消失。
     */
    private fun retainLte(current: LteMetrics?, now: Long): LteMetrics? {
        if (current == null) return null
        if (now - lteSeenAt > STALE_AFTER_MS) return null
        return current.takeIf { !it.fromCellInfo } ?: current.copy(fromCellInfo = false)
    }

    private fun retainNr(current: NrMetrics?, now: Long): NrMetrics? {
        if (current == null) return null
        if (now - nrSeenAt > STALE_AFTER_MS) return null
        return current.takeIf { !it.fromCellInfo } ?: current.copy(fromCellInfo = false)
    }

    private fun lteFromLastSignal(): LteMetrics? =
        CellMapper.mapLteFromSignalStrength(lastSignalStrength)

    private fun nrFromLastSignal(): NrMetrics? =
        CellMapper.mapNrFromSignalStrength(lastSignalStrength)

    private fun applySignalStrength(signalStrength: SignalStrength) {
        lastSignalStrength = signalStrength
        val nrFromSignal = CellMapper.mapNrFromSignalStrength(signalStrength)
        val lteFromSignal = CellMapper.mapLteFromSignalStrength(signalStrength)
        // SignalStrength 也是真实读数：NSA 下 NR 只走这条通道，不刷新时间戳
        // 会让 applyCellInfo 的保留期误判成掉网，6 秒后把正在更新的卡清掉。
        val now = elapsed()
        if (lteFromSignal != null) lteSeenAt = now
        if (nrFromSignal != null) nrSeenAt = now
        val stateBefore = state.get()

        val updated = state.updateAndGet { current ->
            val nr = mergeNr(current.nr, nrFromSignal)
            val lte = mergeLte(current.lte, lteFromSignal)
            if (nr == current.nr && lte == current.lte) {
                current
            } else {
                current.copy(lte = lte, nr = nr, updatedAt = elapsed())
            }
        }
        // 数值没变就不通知，省掉一次无意义的重组。
        if (updated !== stateBefore) onUpdate()
    }

    /**
     * NR 的合并规则：[current] 已经来自 CellInfo（SA 场景）时，信号字段以 CellInfo
     * 为准，只用 SignalStrength 补它缺的项；否则（NSA，CellInfo 里没有 NR 小区）
     * 完全采用 SignalStrength。
     */
    private fun mergeNr(current: NrMetrics?, fromSignal: NrMetrics?): NrMetrics? = when {
        fromSignal == null -> current
        current == null -> fromSignal
        current.fromCellInfo -> current.copy(
            ssRsrp = current.ssRsrp ?: fromSignal.ssRsrp,
            ssSinr = current.ssSinr ?: fromSignal.ssSinr,
            ssRsrq = current.ssRsrq ?: fromSignal.ssRsrq,
            level = current.level ?: fromSignal.level,
        )
        // 标识字段只有 CellInfo 有，得保留下来。
        else -> fromSignal.copy(
            nci = current.nci,
            pci = current.pci,
            tac = current.tac,
            nrarfcn = current.nrarfcn,
            band = current.band,
        )
    }

    /** LTE 的合并规则，同 [mergeNr]。 */
    private fun mergeLte(current: LteMetrics?, fromSignal: LteMetrics?): LteMetrics? = when {
        fromSignal == null -> current
        current == null -> fromSignal
        current.fromCellInfo -> current.copy(
            rsrp = current.rsrp ?: fromSignal.rsrp,
            sinr = current.sinr ?: fromSignal.sinr,
            rsrq = current.rsrq ?: fromSignal.rsrq,
            rssi = current.rssi ?: fromSignal.rssi,
            cqi = current.cqi ?: fromSignal.cqi,
            level = current.level ?: fromSignal.level,
        )
        else -> fromSignal.copy(
            eci = current.eci,
            pci = current.pci,
            tac = current.tac,
            earfcn = current.earfcn,
            band = current.band,
        )
    }

    /**
     * API 29 上没有 DisplayInfo 回调，只能从 dataNetworkType 推断。
     * 此时无法区分 NSA——若 NR 有信号值但服务小区是 LTE，就按 NSA 记。
     */
    private fun applyNetworkTypeFallback() {
        val networkType = runCatching { telephonyManager.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val current = state.get()
        val nsaGuess = networkType != TelephonyManager.NETWORK_TYPE_NR &&
            current.nr?.ssRsrp != null
        val mode = CellMapper.resolveMode(networkType, nsaGuess)
        state.updateAndGet {
            if (it.networkMode == mode) it else it.copy(networkMode = mode, updatedAt = elapsed())
        }
    }

    private fun applyDisplayInfo(networkType: Int, overrideNetworkType: Int) {
        isNsa = overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
        val mode = CellMapper.resolveMode(networkType, isNsa)
        val stateBefore = state.get()
        val updated = state.updateAndGet {
            if (it.networkMode == mode) it else it.copy(networkMode = mode, updatedAt = elapsed())
        }
        if (updated !== stateBefore) onUpdate()
    }

    /** 用开机时长做时间戳，不受用户改系统时间影响。 */
    private fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()

    private companion object {
        /** 邻区最多展示这么多个，防止列表过长。 */
        const val MAX_NEIGHBORS = 8

        /**
         * 服务小区从数据源消失后，旧值最多保留这么久（毫秒）。
         *
         * 轮询周期 2 秒，给 3 个周期的冗余：偶发一两次读空不会让卡片闪烁，
         * 但真正掉网/切制式后 6 秒内一定清干净，不会留下幽灵小区。
         */
        const val STALE_AFTER_MS = 6_000L
    }
}
