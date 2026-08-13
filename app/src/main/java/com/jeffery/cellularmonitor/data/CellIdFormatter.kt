package com.jeffery.cellularmonitor.data

/**
 * 小区标识的两种展示格式：原始十进制串，以及按协议字段拆分后用 "-" 连接的串。
 */
object CellIdFormatter {

    /** ECI 是 28 位。 */
    private const val ECI_MAX = (1L shl 28) - 1

    /** LTE 低 8 位是 Cell ID，高 20 位是 eNB ID。见 3GPP TS 36.413。 */
    private const val LTE_CELL_ID_BITS = 8

    /** NCI 是 36 位。 */
    private const val NCI_MAX = (1L shl 36) - 1

    /** gNB ID 位长由运营商配置，协议允许 22..32，空口不下发。见 3GPP TS 38.413。 */
    val GNB_BITS_RANGE = 22..32
    const val DEFAULT_GNB_BITS = 24

    /**
     * LTE ECI 拆成 eNB ID 与 Cell ID，如 123456789 -> "482253-21"。
     * 超出 28 位或为负时返回 null。
     */
    fun splitLteEci(eci: Int?): String? {
        val value = eci?.toLong() ?: return null
        if (value < 0 || value > ECI_MAX) return null
        val enbId = value shr LTE_CELL_ID_BITS
        val cellId = value and ((1L shl LTE_CELL_ID_BITS) - 1)
        return "$enbId-$cellId"
    }

    /**
     * NR NCI 拆成 gNB ID 与 Cell ID，如 1234567890123 按 24 位 gNB -> "73709-2251"。
     *
     * [gnbBits] 不在 [GNB_BITS_RANGE] 内，或 NCI 超出 36 位时返回 null。
     */
    fun splitNrNci(nci: Long?, gnbBits: Int = DEFAULT_GNB_BITS): String? {
        val value = nci ?: return null
        if (value < 0 || value > NCI_MAX) return null
        if (gnbBits !in GNB_BITS_RANGE) return null
        val cellIdBits = 36 - gnbBits
        val gnbId = value shr cellIdBits
        val cellId = value and ((1L shl cellIdBits) - 1)
        return "$gnbId-$cellId"
    }

    /** 原始十进制串，null 时返回 null 由 UI 兜底。 */
    fun plain(value: Long?): String? = value?.takeIf { it >= 0 }?.toString()

    /** [plain] 的 Int 重载。 */
    fun plain(value: Int?): String? = plain(value?.toLong())
}
