package com.jeffery.cellularmonitor.data

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 与 GCJ-02（火星坐标系）之间的转换。
 *
 * 中国大陆的所有国产地图（高德/百度/腾讯）都用 GCJ-02，而 GPS 原生输出的是 WGS-84。
 * 直接把 WGS-84 坐标标在高德地图上会偏移几十到几百米，看起来像"人在楼里，蓝点在马路上"。
 *
 * 转换算法本身是公开的（早期从测绘部门泄露出来的经验公式），业界公认，几乎所有地图 App
 * 都在用。这里手写而不是依赖高德 SDK 的转换类，理由：
 *   1. 少一层黑盒，出问题时能自己排查
 *   2. 工参坐标可能在导入时就要转，不想让 data 层依赖高德 SDK
 *
 * 中国大陆之外的坐标直接返回原值——GCJ-02 只对大陆做了偏移，境外仍是 WGS-84。
 */
object CoordinateConverter {

    // GCJ-02 使用的椭球参数（Krasovsky 1940 近似，也是公开常数）
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    private const val PI = Math.PI

    /**
     * GPS 原生 → 高德/腾讯地图。
     *
     * 中国大陆外返回原值。判定用矩形包络，粗但够用——GCJ-02 本来就只在大陆做偏移。
     */
    fun wgs84ToGcj02(lat: Double, lon: Double): DoubleArray {
        if (outOfChina(lat, lon)) return doubleArrayOf(lat, lon)
        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val correctedLat = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * PI)
        val correctedLon = dLon * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(lat + correctedLat, lon + correctedLon)
    }

    /**
     * 是否在中国大陆的矩形包络外。用于判断"要不要偏"。
     *
     * 边界略宽（包了台湾/南海诸岛），因为国产地图 API 在这些区域也按大陆规则处理，
     * 不偏反而会显示错。
     */
    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
