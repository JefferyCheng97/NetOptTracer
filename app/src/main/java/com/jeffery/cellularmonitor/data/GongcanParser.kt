package com.jeffery.cellularmonitor.data

import java.io.InputStream

/**
 * 工参 TXT 解析器。
 *
 * 输入是网管导出的 Tab 分隔文本，按表头名取列，所以列顺序变了也不影响。
 * 需要这几列：小区号、覆盖类型、CGI、基站中文名、小区名。
 *
 * 编码上 UTF-8 和 GBK 都能吃：先按 UTF-8 严格解码，失败再按 GBK 来一遍。
 * 网管导出的文件这两种都常见，让用户自己去转码不现实。
 *
 * 支持中国移动（CGI 前缀 460-00）和中国电信（460-11）。其余家（联通 460-01、
 * 广电 460-15 等）目前跳过；工参手上没有，混进来只会显示错值。各家小区号
 * 自成体系可能撞车，所以内部按运营商分表存。
 */
object GongcanParser {

    private const val COL_CELL = "小区号"
    private const val COL_TYPE = "覆盖类型"
    private const val COL_CGI = "CGI"
    private const val COL_SITE = "基站中文名"
    private const val COL_CELL_NAME = "小区名"

    // 邻区反查用：PCI + 频点 → 小区号；可选（没这两列就不能反查邻区）
    // 4G 用下行频点（EARFCN），5G 用 SSB 频点（跟手机 API 返回的一致）
    private const val COL_PCI = "PCI"
    private const val COL_ARFCN = "下行频点"
    private const val COL_SSB = "SSB频点"

    // 经纬度是可选列——地图上打点会用到，但对判断覆盖类型不是必要的，
    // 缺列时不报错，只是不能在地图上显示位置
    private const val COL_LON = "经度"
    private const val COL_LAT = "纬度"
    // 方向角：地图画扇区用；缺列时不报错，只是不画扇区
    private const val COL_AZIMUTH = "方向角"

    /** 单元格里的空值占位，工参里就是这么写的，原样保留。 */
    private const val PLACEHOLDER = "-"

    /**
     * 工参识别的运营商。[cgiPrefix] 是 CGI 里 "MCC-MNC" 那一段，用来定位归属。
     * [displayName] 给导入结果对话框用。
     */
    enum class Carrier(val cgiPrefix: String, val displayName: String) {
        CMCC("460-00", "移动"),
        CTCC("460-11", "电信"),
        ;

        companion object {
            fun fromCgi(cgi: String): Carrier? =
                entries.firstOrNull { cgi.startsWith("${it.cgiPrefix}-") }
        }
    }

    /**
     * 解析结果。[cells] 按运营商分表——不同家的小区号可能重合，混一张表会互相覆盖。
     *
     * [totalRows] / [skippedOtherPlmn] / [skippedInvalid] 用来在导入后给用户一个交代，
     * 让他能判断这份文件是不是自己要的——只解析成功却不报数，出问题时无从查起。
     */
    data class Result(
        val cells: Map<Carrier, Map<Long, CellRecord>>,
        val totalRows: Int,
        val skippedOtherPlmn: Int,
        val skippedInvalid: Int,
        val typeCounts: Map<String, Int>,
    ) {
        /** 所有运营商加起来的小区总数。 */
        val cellCount: Int get() = cells.values.sumOf { it.size }

        /** 每家运营商的小区数，按显示名索引，给导入提示用。 */
        val carrierCounts: Map<String, Int>
            get() = cells.entries.associate { (c, m) -> c.displayName to m.size }
    }

    data class CellRecord(
        val type: String,
        val detail: CellDetail,
        /** 原始 CGI，形如 460-00-1006136-40。搜索时要用，所以原样留着。 */
        val cgi: String,
        /**
         * 方位角列表。同一小区在工参里因多个扇区可能出现多次，这里把所有方向合并起来。
         * 0° = 正北，顺时针增加。空列表表示没方位角信息，地图上不画扇区。
         */
        val azimuths: List<Int> = emptyList(),
        /** PCI，邻区反查用。工参没这列时为 null。 */
        val pci: Int? = null,
        /** 下行频点（4G EARFCN），4G 邻区反查用。工参没这列时为 null。 */
        val arfcn: Int? = null,
        /** SSB 频点（5G 用），5G 邻区反查用。4G 行这里是 null。 */
        val ssbArfcn: Int? = null,
    )

    /** 表头缺列、文件为空这类问题，用异常带出可读的原因。 */
    class ParseException(message: String) : Exception(message)

    /**
     * 从流里解析工参。
     *
     * 2 万行一次读进内存约 4 MB，能接受；按行流式处理反而要处理编码回退的重读问题。
     */
    @Throws(ParseException::class)
    fun parse(input: InputStream): Result {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) throw ParseException("文件是空的")

        val text = decode(bytes)
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext()) throw ParseException("文件是空的")

        val header = lines.next().split('\t').map { it.trim() }
        val iCell = header.requireCol(COL_CELL)
        val iType = header.requireCol(COL_TYPE)
        val iCgi = header.requireCol(COL_CGI)
        val iSite = header.requireCol(COL_SITE)
        val iCellName = header.requireCol(COL_CELL_NAME)
        // 经纬度、方向角、PCI、频点都是可选列：拿不到就是 -1，后面用它做取列时的下标兜底
        val iLon = header.indexOf(COL_LON)
        val iLat = header.indexOf(COL_LAT)
        val iAzimuth = header.indexOf(COL_AZIMUTH)
        val iPci = header.indexOf(COL_PCI)
        val iArfcn = header.indexOf(COL_ARFCN)
        val iSsb = header.indexOf(COL_SSB)
        val maxIndex = maxOf(iCell, iType, iCgi, iSite, iCellName, iLon, iLat, iAzimuth, iPci, iArfcn, iSsb)

        val cellsByCarrier = HashMap<Carrier, HashMap<Long, CellRecord>>()
        val typeCounts = HashMap<String, Int>()
        var total = 0
        var otherPlmn = 0
        var invalid = 0

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            total++

            val f = line.split('\t')
            if (f.size <= maxIndex) {
                invalid++
                continue
            }

            // CGI 形如 460-00-1006136-40，取前两段判归属
            val cgi = f[iCgi].trim()
            val carrier = Carrier.fromCgi(cgi)
            if (carrier == null) {
                otherPlmn++
                continue
            }

            val cell = f[iCell].trim().toLongOrNull()
            val type = f[iType].trim()
            if (cell == null || cell <= 0 || type.isEmpty()) {
                invalid++
                continue
            }

            // 各家的小区号自成体系，可能撞车，所以按运营商分桶
            val bucket = cellsByCarrier.getOrPut(carrier) { HashMap(1 shl 14) }

            // 方位角：0° 是正北顺时针，360° 折成 0°。同小区号多行时把每行的方位角合并
            val azimuth = if (iAzimuth >= 0) f.getOrNull(iAzimuth)?.trim()?.toIntOrNull()
                ?.let { if (it == 360) 0 else it }
                ?.takeIf { it in 0..359 }
            else null

            // 同一小区多次出现：只合并方位角，其他字段沿用首次的
            val existing = bucket[cell]
            if (existing != null) {
                if (azimuth != null && azimuth !in existing.azimuths) {
                    bucket[cell] = existing.copy(azimuths = existing.azimuths + azimuth)
                }
                continue
            }

            // 经纬度：解析不出来（空、非数字、超出中国矩形包络）就置 null
            val lon = if (iLon >= 0) f.getOrNull(iLon)?.trim()?.toDoubleOrNull()?.takeIf { it in 72.0..138.0 } else null
            val lat = if (iLat >= 0) f.getOrNull(iLat)?.trim()?.toDoubleOrNull()?.takeIf { it in 0.5..56.0 } else null

            // PCI 和频点：邻区反查用；解析不出来就置 null
            // 注意 PCI 上限：LTE 是 503，NR 是 1007，这里取 NR 上限，两者共用
            val pci = if (iPci >= 0) f.getOrNull(iPci)?.trim()?.toIntOrNull()?.takeIf { it in 0..1007 } else null
            val arfcn = if (iArfcn >= 0) f.getOrNull(iArfcn)?.trim()?.toIntOrNull()?.takeIf { it > 0 } else null
            // SSB 频点：只有 5G 有，4G 行是 "-" 占位符，toIntOrNull 会返回 null
            val ssbArfcn = if (iSsb >= 0) f.getOrNull(iSsb)?.trim()?.toIntOrNull()?.takeIf { it > 0 } else null

            bucket[cell] = CellRecord(
                type = type,
                detail = CellDetail(
                    siteName = f[iSite].trim().ifEmpty { PLACEHOLDER },
                    cellName = f[iCellName].trim().ifEmpty { PLACEHOLDER },
                    latitude = lat,
                    longitude = lon,
                ),
                cgi = cgi,
                azimuths = if (azimuth != null) listOf(azimuth) else emptyList(),
                pci = pci,
                arfcn = arfcn,
                ssbArfcn = ssbArfcn,
            )
            typeCounts[type] = (typeCounts[type] ?: 0) + 1
        }

        if (cellsByCarrier.isEmpty()) {
            throw ParseException(
                if (otherPlmn > 0) "文件里没有移动（460-00）或电信（460-11）的小区"
                else "没解析出任何小区，请确认是工参文件"
            )
        }

        return Result(
            cells = cellsByCarrier,
            totalRows = total,
            skippedOtherPlmn = otherPlmn,
            skippedInvalid = invalid,
            typeCounts = typeCounts,
        )
    }

    private fun List<String>.requireCol(name: String): Int {
        val i = indexOf(name)
        if (i < 0) throw ParseException("表头里没有「$name」列")
        return i
    }

    /**
     * UTF-8 优先，失败退 GBK。
     *
     * 用 CharsetDecoder 而不是 String(bytes, UTF_8)：后者遇到非法字节会静默替换成
     * U+FFFD，GBK 文件会解成一片乱码而不报错，那就没法判断该不该回退了。
     */
    private fun decode(bytes: ByteArray): String {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8.removePrefix("﻿")

        return runCatching {
            String(bytes, charset("GBK"))
        }.getOrElse {
            throw ParseException("文件编码不是 UTF-8 也不是 GBK")
        }
    }
}
