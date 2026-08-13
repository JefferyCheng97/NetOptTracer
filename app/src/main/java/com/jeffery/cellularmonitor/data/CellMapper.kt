package com.jeffery.cellularmonitor.data

import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.TelephonyManager

/**
 * framework 的 CellInfo / SignalStrength 到本项目模型的映射。
 *
 * framework 用 [Int.MAX_VALUE]（偶尔是 [Int.MIN_VALUE] 或 -1）表示"该字段不可用"，
 * 这里统一归一成 null，UI 只需判空。
 */
internal object CellMapper {

    /** framework 表示"无效"的哨兵值。 */
    private fun Int.orNull(): Int? =
        if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this

    private fun Long.orNull(): Long? =
        if (this == Long.MAX_VALUE || this == Long.MIN_VALUE) null else this

    /** 频点号、PCI、TAC 这类字段用 -1 或 MAX_VALUE 表示无效。 */
    private fun Int.orNullIfNegative(): Int? = orNull()?.takeIf { it >= 0 }

    fun mapLte(info: CellInfoLte): LteMetrics {
        val id: CellIdentityLte = info.cellIdentity
        val ss: CellSignalStrengthLte = info.cellSignalStrength
        val earfcn = id.earfcn.orNullIfNegative()
        return LteMetrics(
            rsrp = ss.rsrp.orNull(),
            sinr = ss.rssnr.orNull()?.toDouble(),
            rsrq = ss.rsrq.orNull(),
            rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ss.rssi.orNull() else null,
            cqi = ss.cqi.orNull(),
            level = ss.level,
            eci = id.ci.orNullIfNegative(),
            pci = id.pci.orNullIfNegative(),
            tac = id.tac.orNullIfNegative(),
            earfcn = earfcn,
            band = lteBand(id, earfcn),
            fromCellInfo = true,
        )
    }

    fun mapNr(info: CellInfoNr): NrMetrics {
        val id = info.cellIdentity as? CellIdentityNr
        val ss = info.cellSignalStrength as? CellSignalStrengthNr
        val nrarfcn = id?.nrarfcn?.orNullIfNegative()
        return NrMetrics(
            ssRsrp = ss?.ssRsrp?.orNull(),
            ssSinr = ss?.ssSinr?.orNull()?.toDouble(),
            ssRsrq = ss?.ssRsrq?.orNull(),
            level = ss?.level,
            nci = id?.nci?.orNull()?.takeIf { it >= 0 },
            pci = id?.pci?.orNullIfNegative(),
            tac = id?.tac?.orNullIfNegative(),
            nrarfcn = nrarfcn,
            band = nrBand(id, nrarfcn),
            fromCellInfo = true,
        )
    }

    /**
     * NSA 下服务小区是 LTE，NR 辅载波的小区标识空口不下发，
     * 只能从 [SignalStrength] 里捞到 SS-RSRP / SS-SINR。
     */
    fun mapNrFromSignalStrength(signal: SignalStrength?): NrMetrics? {
        val ss = signal
            ?.getCellSignalStrengths(CellSignalStrengthNr::class.java)
            ?.firstOrNull()
            ?: return null
        val metrics = NrMetrics(
            ssRsrp = ss.ssRsrp.orNull(),
            ssSinr = ss.ssSinr.orNull()?.toDouble(),
            ssRsrq = ss.ssRsrq.orNull(),
            level = ss.level,
        )
        // 三项全空说明其实没有 NR 辅载波，别在界面上凭空造一个 5G 分区。
        val hasAny = metrics.ssRsrp != null || metrics.ssSinr != null || metrics.ssRsrq != null
        return if (hasAny) metrics else null
    }

    /** 从 [SignalStrength] 补 LTE 数值，用于 getAllCellInfo 拿不到服务小区的场景。 */
    fun mapLteFromSignalStrength(signal: SignalStrength?): LteMetrics? {
        val ss = signal
            ?.getCellSignalStrengths(CellSignalStrengthLte::class.java)
            ?.firstOrNull()
            ?: return null
        val metrics = LteMetrics(
            rsrp = ss.rsrp.orNull(),
            sinr = ss.rssnr.orNull()?.toDouble(),
            rsrq = ss.rsrq.orNull(),
            rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ss.rssi.orNull() else null,
            cqi = ss.cqi.orNull(),
            level = ss.level,
        )
        return if (metrics.rsrp != null || metrics.sinr != null) metrics else null
    }

    fun mapNeighbor(info: CellInfo): NeighborCell? = when (info) {
        is CellInfoLte -> {
            val earfcn = info.cellIdentity.earfcn.orNullIfNegative()
            NeighborCell(
                type = CellType.LTE,
                pci = info.cellIdentity.pci.orNullIfNegative(),
                rsrp = info.cellSignalStrength.rsrp.orNull(),
                arfcn = earfcn,
                band = lteBand(info.cellIdentity, earfcn),
            )
        }

        is CellInfoNr -> {
            val id = info.cellIdentity as? CellIdentityNr
            val nrarfcn = id?.nrarfcn?.orNullIfNegative()
            NeighborCell(
                type = CellType.NR,
                pci = id?.pci?.orNullIfNegative(),
                rsrp = (info.cellSignalStrength as? CellSignalStrengthNr)?.ssRsrp?.orNull(),
                arfcn = nrarfcn,
                band = nrBand(id, nrarfcn),
            )
        }

        else -> null
    }

    /** getBands() 是 API 30+ 且常返回空数组，回退到按频点反查。 */
    private fun lteBand(id: CellIdentityLte, earfcn: Int?): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BandLookup.formatLteBands(id.bands)?.let { return it }
        }
        return BandLookup.lteBandFromEarfcn(earfcn)
    }

    private fun nrBand(id: CellIdentityNr?, nrarfcn: Int?): String? {
        if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BandLookup.formatNrBands(id.bands)?.let { return it }
        }
        return BandLookup.nrBandFromArfcn(nrarfcn)
    }

    /** 由注册网络类型与 5G 覆盖标识判定制式。 */
    fun resolveMode(networkType: Int, isNsaOverride: Boolean): NetworkMode = when {
        networkType == TelephonyManager.NETWORK_TYPE_NR -> NetworkMode.NR_SA
        isNsaOverride -> NetworkMode.NR_NSA
        networkType == TelephonyManager.NETWORK_TYPE_LTE -> NetworkMode.LTE
        networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN -> NetworkMode.UNKNOWN
        else -> NetworkMode.OTHER
    }
}
