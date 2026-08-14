package com.jeffery.cellularmonitor.data

/**
 * 一次采样中，某个卡槽的全部可展示数据。
 *
 * 所有数值字段统一用可空类型表达"读不到"，framework 返回的
 * [Int.MAX_VALUE] / [Int.MIN_VALUE] 等哨兵值在采集层就被归一成 null，
 * UI 层只需判空后渲染成 "--"。
 */
data class SlotSnapshot(
    val sim: SimSlotInfo,
    val networkMode: NetworkMode = NetworkMode.UNKNOWN,
    val lte: LteMetrics? = null,
    val nr: NrMetrics? = null,
    val neighbors: List<NeighborCell> = emptyList(),
    val updatedAt: Long = 0L,
    val location: LocationInfo? = null,
)

/** 卡槽与其归属运营商。 */
data class SimSlotInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    /** 运营商名，如"中国移动"。 */
    val carrierName: String,
    /** 用户给这张卡起的显示名，可能与运营商名相同。 */
    val displayName: String,
    val mcc: String? = null,
    val mnc: String? = null,
) {
    /** 拼成 46000 这种形式，取不到时为 null。 */
    val plmn: String?
        get() = if (mcc != null && mnc != null) mcc + mnc else null
}

/** 当前注册的网络制式。NSA 与 SA 分开，因为两者能读到的字段差别很大。 */
enum class NetworkMode {
    UNKNOWN,

    /** 纯 4G，没有 5G 辅载波。 */
    LTE,

    /** 5G 非独立组网：锚点是 LTE 小区，NR 侧只有信号强度，没有小区标识。 */
    NR_NSA,

    /** 5G 独立组网：服务小区就是 NR 小区，字段完整。 */
    NR_SA,

    /** 既不是 LTE 也不是 NR，例如 2G/3G 或无服务。 */
    OTHER,
    ;

    val isNr: Boolean
        get() = this == NR_NSA || this == NR_SA
}

/** LTE 服务小区的信号与标识。 */
data class LteMetrics(
    /** RSRP，dBm。 */
    val rsrp: Int? = null,
    /** RS-SNR，dB，范围约 -20..30。 */
    val sinr: Double? = null,
    /** RSRQ，dB。 */
    val rsrq: Int? = null,
    /** RSSI，dBm。 */
    val rssi: Int? = null,
    /** CQI，0..15。 */
    val cqi: Int? = null,
    /** 信号格数 0..4。 */
    val level: Int? = null,
    /** E-UTRAN Cell Identifier，28 位。 */
    val eci: Int? = null,
    /** 物理小区 ID，0..503。 */
    val pci: Int? = null,
    /** 跟踪区码。 */
    val tac: Int? = null,
    val earfcn: Int? = null,
    /** 形如 "B3"，查不到时为 null。 */
    val band: String? = null,
    /**
     * 信号字段是否来自 CellInfo 的服务小区。
     *
     * CellInfo 与 SignalStrength 是 modem 里两条独立的上报通道，同一时刻的
     * RSRP 常差几个 dB。两边都往同一字段写会让界面在两个值之间反复跳，
     * 所以以 CellInfo 为准，SignalStrength 只补它没有的字段。
     */
    val fromCellInfo: Boolean = false,
    /**
     * 小区覆盖类型,如 "宏站"、"室分"。
     * 来自工参数据,未匹配时为 null。
     */
    val cellType: String? = null,
    /**
     * 小区详情(基站名、小区名),来自工参。
     * 未匹配时为 null。
     */
    val cellDetail: CellDetail? = null,
)

/**
 * NR 侧的信号与标识。
 *
 * NSA 下只有 [ssRsrp] / [ssSinr] / [ssRsrq] 有值——辅载波的小区标识空口不下发，
 * [nci] 等字段必然为 null，这不是缺陷。
 */
data class NrMetrics(
    /** SS-RSRP，dBm。 */
    val ssRsrp: Int? = null,
    /** SS-SINR，dB。 */
    val ssSinr: Double? = null,
    /** SS-RSRQ，dB。 */
    val ssRsrq: Int? = null,
    val level: Int? = null,
    /** NR Cell Identity，36 位，仅 SA 下可得。 */
    val nci: Long? = null,
    val pci: Int? = null,
    val tac: Int? = null,
    val nrarfcn: Int? = null,
    /** 形如 "n41"，查不到时为 null。 */
    val band: String? = null,
    /** 同 [LteMetrics.fromCellInfo]。 */
    val fromCellInfo: Boolean = false,
    /**
     * 小区覆盖类型,如 "宏站"、"室分"。
     * 来自工参数据,未匹配时为 null。
     */
    val cellType: String? = null,
    /**
     * 小区详情(基站名、小区名),来自工参。
     * 未匹配时为 null。
     */
    val cellDetail: CellDetail? = null,
)

/** 邻区（非注册小区）。字段比服务小区少，空口只广播这几项。 */
data class NeighborCell(
    val type: CellType,
    val pci: Int? = null,
    /** LTE 是 RSRP，NR 是 SS-RSRP，单位都是 dBm。 */
    val rsrp: Int? = null,
    /** LTE 是 EARFCN，NR 是 NR-ARFCN（实际读到的是 SSB 频点）。 */
    val arfcn: Int? = null,
    val band: String? = null,
    /**
     * 从工参反查到的候选小区列表。
     * 光靠 (PCI, 频点) 可能匹配多个小区（PCI 复用），全部列出来让用户判断。
     * 空列表表示工参里没匹配到，或者工参没导入。
     */
    val candidates: List<NeighborCandidate> = emptyList(),
)

/** 邻区候选：一个 (PCI, 频点) 组合可能匹配多个工参小区。 */
data class NeighborCandidate(
    val cellId: Long,
    val cellName: String,
    val siteName: String,
)

enum class CellType { LTE, NR }

/** 位置信息：经纬度 + 精度 + 时间戳。 */
data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val timestamp: Long = 0L,
)

