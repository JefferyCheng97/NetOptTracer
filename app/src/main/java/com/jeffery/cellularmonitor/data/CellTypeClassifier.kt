package com.jeffery.cellularmonitor.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 按工参判断当前小区是宏站还是室分，并提供小区明细（基站名、小区名）。
 *
 * 数据来源：用户在 App 里导入的工参 TXT，解析后存到私有目录（[importedFile]）。
 * 如果没有导入工参，则所有查询返回 null，不显示覆盖类型。
 *
 * 键用小区号（4G 的 ECI / 5G 的 NCI 纯数字），不带前缀——手机侧读到的就是这个纯数字。
 * 实测 4G 与 5G 的小区号区间不重叠（4G 约 1.5e7~2.7e8，5G 约 6.37e9~6.39e9），
 * 4G/5G 合用一张表。
 *
 * **按运营商分表存**：移动、电信、联通、广电的 ECI 完全可能撞车，所以每家单独一张表；
 * 查表前先看 PLMN 属于哪家，再去对应的表里找。工参没覆盖到的运营商（比如没有电信工参
 * 的用户就是这种）直接返回 null——显示错的类型比不显示更糟。
 */
class CellTypeClassifier private constructor(
    private val byCarrier: Map<GongcanParser.Carrier, Map<Long, GongcanParser.CellRecord>>,
) {
    /**
     * 邻区反查索引：(PCI, 频点) → 小区号。
     * 按运营商分表——PCI 可能复用，但同一运营商的 (PCI, 频点) 组合理论上唯一。
     * 工参如果没 PCI/频点列，这个索引就是空的，邻区反查返回 null。
     */
    private val pciIndex: Map<GongcanParser.Carrier, Map<Pair<Int, Int>, Long>> =
        byCarrier.mapValues { (carrier, cells) ->
            buildMap {
                for ((cellId, record) in cells) {
                    val pci = record.pci ?: continue
                    val arfcn = record.arfcn ?: continue
                    // PCI 冲突时后来的覆盖前面的——实际工参应该不会冲突，冲突就是工参错了
                    put(Pair(pci, arfcn), cellId)
                }
            }.also { index ->
                android.util.Log.d("CellTypeClassifier", "PCI索引: $carrier → ${index.size} 条")
            }
        }

    /** 表里的小区总数（各家相加），0 表示没有可用工参。 */
    val size: Int get() = byCarrier.values.sumOf { it.size }

    /** 4G：按 ECI 查覆盖类型。[plmn] 归属家没工参、或查不到，都返回 null。 */
    fun classifyLte(eci: Long, plmn: String?): String? = record(eci, plmn)?.type

    /** 5G：按 NCI 查覆盖类型。[plmn] 归属家没工参、或查不到，都返回 null。 */
    fun classifyNr(nci: Long, plmn: String?): String? = record(nci, plmn)?.type

    /** 4G：查小区明细。 */
    fun detailLte(eci: Long, plmn: String?): CellDetail? = record(eci, plmn)?.detail

    /** 5G：查小区明细。 */
    fun detailNr(nci: Long, plmn: String?): CellDetail? = record(nci, plmn)?.detail

    /**
     * 邻区反查：4G，用 (PCI, EARFCN) 查小区号和小区名。
     * 查不到（工参没 PCI/频点列、或这个 (PCI, EARFCN) 组合不在工参里）返回 null。
     */
    fun lookupNeighborLte(pci: Int, earfcn: Int, plmn: String?): Pair<Long, String>? {
        val carrier = plmnToCarrier(plmn) ?: return null
        val cellId = pciIndex[carrier]?.get(Pair(pci, earfcn)) ?: return null
        val cellName = byCarrier[carrier]?.get(cellId)?.detail?.cellName ?: return null
        return Pair(cellId, cellName)
    }

    /**
     * 邻区反查：5G，用 (PCI, NR-ARFCN) 查小区号和小区名。
     */
    fun lookupNeighborNr(pci: Int, nrArfcn: Int, plmn: String?): Pair<Long, String>? {
        val carrier = plmnToCarrier(plmn) ?: return null
        val cellId = pciIndex[carrier]?.get(Pair(pci, nrArfcn)) ?: return null
        val cellName = byCarrier[carrier]?.get(cellId)?.detail?.cellName ?: return null
        return Pair(cellId, cellName)
    }

    private fun record(cellId: Long, plmn: String?): GongcanParser.CellRecord? {
        val carrier = plmnToCarrier(plmn) ?: return null
        return byCarrier[carrier]?.get(cellId)
    }

    /**
     * 所有有坐标的小区，供地图打点用。
     *
     * 每张记录的 [SiteMarker.cellId] 是小区号（4G ECI 或 5G NCI 的纯数字），
     * 主界面 LTE/NR 卡片里显示的 ECI/NCI 与这个能对上，方便"高亮当前小区"。
     *
     * 工参坐标通常是 WGS-84（GPS 原生），高德地图用 GCJ-02，所以这里转换一次。
     * 转换只对中国境内有效（境外直接返回原值），偏差约 300–600 米。
     *
     * 直接返回 sequence 是想避免一次性 toList 分配一个 4 万元素的临时数组——
     * 地图侧遍历一遍就够了，不需要落地。
     */
    fun sitesWithLocation(): Sequence<SiteMarker> = sequence {
        for ((carrier, cells) in byCarrier) {
            for ((cellId, record) in cells) {
                val lat = record.detail.latitude ?: continue
                val lon = record.detail.longitude ?: continue
                // WGS-84 → GCJ-02 转换：工参是 GPS 原生坐标，高德用火星坐标
                val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(lat, lon)
                yield(
                    SiteMarker(
                        carrier = carrier,
                        cellId = cellId,
                        type = record.type,
                        latitude = gcjLat,
                        longitude = gcjLon,
                        siteName = record.detail.siteName,
                        cellName = record.detail.cellName,
                        cgi = record.cgi,
                        azimuths = record.azimuths,
                    )
                )
            }
        }
    }

    /**
     * 所有有坐标的小区，不做坐标转换，直接用工参原值。
     *
     * 调试用：让用户在地图上对比"转换 vs 不转换"，看哪个准。如果工参本来就是
     * GCJ-02，转了反而会偏得更远。
     */
    fun sitesWithLocationRaw(): Sequence<SiteMarker> = sequence {
        for ((carrier, cells) in byCarrier) {
            for ((cellId, record) in cells) {
                val lat = record.detail.latitude ?: continue
                val lon = record.detail.longitude ?: continue
                yield(
                    SiteMarker(
                        carrier = carrier,
                        cellId = cellId,
                        type = record.type,
                        latitude = lat,
                        longitude = lon,
                        siteName = record.detail.siteName,
                        cellName = record.detail.cellName,
                        cgi = record.cgi,
                        azimuths = record.azimuths,
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "CellTypeClassifier"

        /** 导入后落盘的位置，放私有目录不需要任何存储权限。 */
        private const val IMPORTED_NAME = "gongcan.txt"

        /**
         * PLMN（mcc + mnc）到运营商的映射。
         *
         * 移动：00 主用（GSM/LTE/NR），02/04/07/08 是历史或专用网段。
         * 电信：11 主用，03 CDMA/LTE 历史号段，05 也在用。
         * 同一张卡在不同小区可能上报其中任一个，都算同一家。
         */
        private val PLMN_TO_CARRIER: Map<String, GongcanParser.Carrier> = mapOf(
            "46000" to GongcanParser.Carrier.CMCC,
            "46002" to GongcanParser.Carrier.CMCC,
            "46004" to GongcanParser.Carrier.CMCC,
            "46007" to GongcanParser.Carrier.CMCC,
            "46008" to GongcanParser.Carrier.CMCC,
            "46003" to GongcanParser.Carrier.CTCC,
            "46005" to GongcanParser.Carrier.CTCC,
            "46011" to GongcanParser.Carrier.CTCC,
        )

        private fun plmnToCarrier(plmn: String?): GongcanParser.Carrier? =
            plmn?.let { PLMN_TO_CARRIER[it] }

        /** 没有工参时的空实例，所有查询返回 null。 */
        val EMPTY = CellTypeClassifier(emptyMap())

        private fun importedFile(context: Context) = File(context.filesDir, IMPORTED_NAME)

        /** 是否已经导入过工参。 */
        fun hasImported(context: Context): Boolean = importedFile(context).exists()

        /**
         * 加载工参：只从用户导入的文件加载，没有则返回 [EMPTY]。
         *
         * 解析 2 万行约几百毫秒，必须在 IO 线程。任何失败都降级成 [EMPTY]，
         * 信号显示不受影响。
         */
        suspend fun load(context: Context): CellTypeClassifier = withContext(Dispatchers.IO) {
            val imported = importedFile(context)
            if (imported.exists()) {
                runCatching { fromResult(GongcanParser.parse(imported.inputStream())) }
                    .getOrElse { e ->
                        // 导入过的文件解析不了（手改坏了？），不显示覆盖类型
                        Log.w(TAG, "导入的工参解析失败，不显示覆盖类型", e)
                        EMPTY
                    }
            } else {
                Log.i(TAG, "无导入工参，不显示覆盖类型")
                EMPTY
            }
        }


        /**
         * 导入用户选的工参 TXT：先解析验证，通过了才落盘覆盖旧的。
         *
         * 顺序很重要——解析失败时旧工参必须还在，不能因为选错文件把已有数据弄丢了。
         *
         * @return 解析结果，用来给用户看导入了多少条
         */
        @Throws(GongcanParser.ParseException::class)
        suspend fun import(context: Context, uri: Uri): GongcanParser.Result =
            withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw GongcanParser.ParseException("读不到这个文件")

                // 先解析，确认是能用的工参，再覆盖已有文件
                val result = GongcanParser.parse(bytes.inputStream())
                importedFile(context).writeBytes(bytes)
                result
            }

        /** 删掉导入的工参，之后不再显示覆盖类型。 */
        suspend fun clearImported(context: Context): Unit = withContext(Dispatchers.IO) {
            importedFile(context).delete()
        }

        private fun fromResult(result: GongcanParser.Result) =
            CellTypeClassifier(result.cells)
    }
}

/**
 * 小区明细：基站名、小区名、经纬度。工参里字段是 "-" 时字符串原样保留；
 * 经纬度缺失或格式坏时置 null，地图上不打点，但主界面明细卡不受影响。
 *
 * 坐标系是 GCJ-02（网管工参就用这个，跟高德地图对应），显示到高德地图上不用转换。
 */
data class CellDetail(
    val siteName: String,
    val cellName: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** 地图上的基站标记，供 MapActivity 遍历打点。 */
data class SiteMarker(
    val carrier: GongcanParser.Carrier,
    val cellId: Long,
    /** 覆盖类型，如"宏站"/"室分"——地图上按类型上色。 */
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val siteName: String,
    val cellName: String,
    /** 原始 CGI，搜索时匹配用。 */
    val cgi: String,
    /**
     * 方位角列表。0° = 正北，顺时针增加。地图上按每个方位角画一个扇形。
     * 空列表表示工参没这个字段或没值，地图上不画扇区。
     */
    val azimuths: List<Int> = emptyList(),
)
