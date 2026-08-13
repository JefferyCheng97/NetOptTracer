package com.jeffery.cellularmonitor.data

/**
 * 频段号解析。
 *
 * `CellIdentityLte.getBands()` / `CellIdentityNr.getBands()` 是 API 30 才有的，
 * 而本项目 minSdk 是 29；且即便在 API 30+ 上，不少设备也返回空数组。
 * 所以采集层优先取 `getBands()`，取不到时回退到这里按频点号反查。
 */
object BandLookup {

    private data class ArfcnRange(val band: String, val range: LongRange)

    /** 频点号落在哪个区间就返回对应频段，找不到返回 null。 */
    private fun lookup(arfcn: Int?, table: List<ArfcnRange>): String? {
        val value = arfcn?.toLong() ?: return null
        if (value < 0) return null
        return table.firstOrNull { value in it.range }?.band
    }

    /** 由 EARFCN 反查 LTE 频段，如 1650 -> "B3"。 */
    fun lteBandFromEarfcn(earfcn: Int?): String? = lookup(earfcn, LTE_TABLE)

    /** 由 NR-ARFCN 反查 NR 频段，如 504990 -> "n41"。 */
    fun nrBandFromArfcn(nrarfcn: Int?): String? = lookup(nrarfcn, NR_TABLE)

    /** 把 framework 给的频段号数组格式化成 "B3" / "B3+B41"。空数组返回 null。 */
    fun formatLteBands(bands: IntArray?): String? = formatBands(bands, "B")

    /** 把 framework 给的频段号数组格式化成 "n41" / "n41+n78"。空数组返回 null。 */
    fun formatNrBands(bands: IntArray?): String? = formatBands(bands, "n")

    private fun formatBands(bands: IntArray?, prefix: String): String? {
        if (bands == null || bands.isEmpty()) return null
        return bands.filter { it > 0 }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("+") { "$prefix$it" }
    }

    // 下面两张表的区间来自 3GPP TS 36.101 表 5.7.3-1 与 TS 38.104 表 5.4.2.3-1。
    // 只覆盖国内在用及主要全球频段，顺序按频点号递增以便阅读。

    private val LTE_TABLE = listOf(
        // FDD
        ArfcnRange("B1", 0L..599L),
        ArfcnRange("B2", 600L..1199L),
        ArfcnRange("B3", 1200L..1949L),
        ArfcnRange("B4", 1950L..2399L),
        ArfcnRange("B5", 2400L..2649L),
        ArfcnRange("B7", 2750L..3449L),
        ArfcnRange("B8", 3450L..3799L),
        ArfcnRange("B12", 5010L..5179L),
        ArfcnRange("B13", 5180L..5279L),
        ArfcnRange("B14", 5280L..5379L),
        ArfcnRange("B17", 5730L..5849L),
        ArfcnRange("B18", 5850L..5999L),
        ArfcnRange("B19", 6000L..6149L),
        ArfcnRange("B20", 6150L..6449L),
        ArfcnRange("B21", 6450L..6599L),
        ArfcnRange("B25", 8040L..8689L),
        ArfcnRange("B26", 8690L..9039L),
        ArfcnRange("B28", 9210L..9659L),
        ArfcnRange("B32", 9920L..10359L),
        // TDD
        ArfcnRange("B33", 36000L..36199L),
        ArfcnRange("B34", 36200L..36349L),
        ArfcnRange("B35", 36350L..36949L),
        ArfcnRange("B36", 36950L..37549L),
        ArfcnRange("B37", 37550L..37749L),
        ArfcnRange("B38", 37750L..38249L),
        ArfcnRange("B39", 38250L..38649L),
        ArfcnRange("B40", 38650L..39649L),
        ArfcnRange("B41", 39650L..41589L),
        ArfcnRange("B42", 41590L..43589L),
        ArfcnRange("B43", 43590L..45589L),
        ArfcnRange("B46", 46790L..54539L),
        ArfcnRange("B48", 55240L..56739L),
        ArfcnRange("B66", 66436L..67335L),
        ArfcnRange("B71", 68586L..68935L),
    )

    // NR 有几组频段的频点区间天然重叠（n78 整个落在 n77 内，n66 与 n3 相交，
    // n261 落在 n257 内），单靠频点号无法区分。表按"国内实际在用优先"排序，
    // 窄的、常见的放前面，宽的作兜底。
    private val NR_TABLE = listOf(
        // 低频 FR1 FDD
        ArfcnRange("n28", 151600L..160600L),
        ArfcnRange("n20", 158200L..164200L),
        ArfcnRange("n5", 173800L..178800L),
        ArfcnRange("n8", 185000L..191800L),
        ArfcnRange("n3", 342000L..357000L),
        ArfcnRange("n66", 342000L..356000L),
        ArfcnRange("n2", 370000L..382000L),
        ArfcnRange("n1", 384000L..396000L),
        // 中频 FR1 TDD
        ArfcnRange("n40", 460000L..480000L),
        ArfcnRange("n41", 499200L..537999L),
        ArfcnRange("n78", 620000L..653333L),
        ArfcnRange("n77", 653334L..680000L),
        ArfcnRange("n79", 693334L..733333L),
        // 毫米波 FR2，国内未商用，仅作兜底
        ArfcnRange("n258", 2016667L..2054165L),
        ArfcnRange("n261", 2070833L..2084999L),
        ArfcnRange("n257", 2054166L..2104165L),
        ArfcnRange("n260", 2229166L..2279165L),
    )
}
