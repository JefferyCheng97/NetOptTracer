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
     * 邻区反查索引：(PCI, 频点) → 候选小区号列表。
     * 4G 用 EARFCN，5G 用 SSB 频点（手机 API 返回的 NR-ARFCN 实际就是 SSB 频点）。
     *
     * 同一 (PCI, 频点) 可能对应多个小区（PCI 复用），全部列出来交给用户判断，
     * 而不是随便选一个（可能错）。
     */
    private val pciIndex: Map<GongcanParser.Carrier, Map<Pair<Int, Int>, List<Long>>> =
        byCarrier.mapValues { (carrier, cells) ->
            val index = HashMap<Pair<Int, Int>, MutableList<Long>>()
            var ssbCount = 0
            var arfcnCount = 0
            for ((cellId, record) in cells) {
                val pci = record.pci ?: continue
                // 4G 用 EARFCN，5G 用 SSB 频点：优先取 SSB 频点，取不到再用 EARFCN
                val freq = record.ssbArfcn ?: record.arfcn ?: continue
                if (record.ssbArfcn != null) ssbCount++ else arfcnCount++
                index.getOrPut(Pair(pci, freq)) { mutableListOf() }.add(cellId)
            }
            android.util.Log.d("CellTypeClassifier", "PCI索引: $carrier → ${index.size} 个key，${index.values.sumOf { it.size }} 条候选（SSB=$ssbCount, EARFCN=$arfcnCount）")
            index
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
     * 邻区反查：用 (PCI, 频点) 查所有匹配的候选小区。
     *
     * 4G 频点是 EARFCN，5G 频点是 SSB 频点（手机 API 返回的 NR-ARFCN 实际就是 SSB 频点）。
     * 同一 (PCI, 频点) 可能对应多个小区（PCI 复用），全部返回给 UI 让用户判断。
     *
     * **跨运营商查询**：邻区可能是别家运营商的（比如联通卡读到移动邻区），
     * 先查 PLMN 对应运营商，查不到就全局查所有工参表。
     */
    fun lookupNeighbors(pci: Int, arfcn: Int, plmn: String?): List<NeighborCandidate> {
        val key = Pair(pci, arfcn)
        android.util.Log.d("CellTypeClassifier", "查询邻区: key=$key PLMN=$plmn")

        // 先查当前运营商（PLMN 对应的那家）
        val carrier = plmnToCarrier(plmn)
        if (carrier != null) {
            val cellIds = pciIndex[carrier]?.get(key)
            android.util.Log.d("CellTypeClassifier", "  查 $carrier 工参: ${cellIds?.size ?: 0} 条")
            if (!cellIds.isNullOrEmpty()) {
                val cells = byCarrier[carrier]!!
                return cellIds.mapNotNull { cellId ->
                    val record = cells[cellId] ?: return@mapNotNull null
                    NeighborCandidate(
                        cellId = cellId,
                        cellName = record.detail.cellName,
                        siteName = record.detail.siteName,
                    )
                }
            }
        } else {
            android.util.Log.d("CellTypeClassifier", "  PLMN=$plmn 无法识别运营商")
        }

        // 查不到，全局查所有运营商的工参（联通卡读移动邻区这种情况）
        android.util.Log.d("CellTypeClassifier", "  全局查所有工参...")
        val allCandidates = mutableListOf<NeighborCandidate>()
        for ((otherCarrier, index) in pciIndex) {
            val cellIds = index[key]
            android.util.Log.d("CellTypeClassifier", "    $otherCarrier: ${cellIds?.size ?: 0} 条")
            if (cellIds == null) continue
            val cells = byCarrier[otherCarrier] ?: continue
            for (cellId in cellIds) {
                val record = cells[cellId] ?: continue
                allCandidates.add(
                    NeighborCandidate(
                        cellId = cellId,
                        cellName = record.detail.cellName,
                        siteName = record.detail.siteName,
                    )
                )
            }
        }
        android.util.Log.d("CellTypeClassifier", "  全局查结果: ${allCandidates.size} 候选")
        return allCandidates
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
     * 按 (运营商, 基站名) 聚合的基站分组。地图 Marker 用这个——同一基站的多个小区
     * 只打一个点，避免重叠，点击时一次显示所有小区。
     *
     * 坐标已从 WGS-84 转成 GCJ-02，跟 [sitesWithLocation] 一致。分组内不同小区如果
     * 有微小的坐标偏差（同基站不同扇区录入时轻微不同），取第一个小区的坐标。
     */
    fun groupedSitesWithLocation(): List<SiteGroup> {
        val groups = HashMap<Pair<GongcanParser.Carrier, String>, MutableList<Pair<Long, GongcanParser.CellRecord>>>()
        for ((carrier, cells) in byCarrier) {
            for ((cellId, record) in cells) {
                if (record.detail.latitude == null || record.detail.longitude == null) continue
                val key = Pair(carrier, record.detail.siteName)
                groups.getOrPut(key) { mutableListOf() }.add(cellId to record)
            }
        }
        return groups.map { (key, cellPairs) ->
            val (carrier, siteName) = key
            val (primaryCellId, primaryRecord) = cellPairs.first()
            val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(
                primaryRecord.detail.latitude!!,
                primaryRecord.detail.longitude!!,
            )
            SiteGroup(
                carrier = carrier,
                siteName = siteName,
                type = primaryRecord.type,
                latitude = gcjLat,
                longitude = gcjLon,
                primaryCellId = primaryCellId,
                cells = cellPairs.map { (cellId, record) ->
                    GroupedCell(
                        cellId = cellId,
                        cellName = record.detail.cellName,
                        type = record.type,
                        cgi = record.cgi,
                        azimuths = record.azimuths,
                    )
                },
            )
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

/**
 * 基站分组：同一 (运营商, 基站名) 下的所有小区聚合成一组。
 * 地图上按基站打 Marker，一个基站一个点；点击弹窗一次显示这个基站下的所有小区。
 */
data class SiteGroup(
    val carrier: GongcanParser.Carrier,
    val siteName: String,
    /** Marker 图标用的覆盖类型，取组内第一个小区的类型。 */
    val type: String,
    val latitude: Double,
    val longitude: Double,
    /** 组内第一个小区的 cellId，作为 Marker 的 key。 */
    val primaryCellId: Long,
    val cells: List<GroupedCell>,
)

/** 基站分组里的一个小区。 */
data class GroupedCell(
    val cellId: Long,
    val cellName: String,
    val type: String,
    val cgi: String,
    val azimuths: List<Int>,
)
