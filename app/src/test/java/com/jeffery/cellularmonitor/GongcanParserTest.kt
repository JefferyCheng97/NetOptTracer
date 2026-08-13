package com.jeffery.cellularmonitor

import com.jeffery.cellularmonitor.data.GongcanParser
import com.jeffery.cellularmonitor.data.GongcanParser.Carrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GongcanParser] 对真实工参格式的解析。样例取自实际网管导出文件。 */
class GongcanParserTest {

    // 便利读取器：按 CGI 前缀路由到对应运营商的表
    private fun GongcanParser.Result.cmcc(cellId: Long) = cells[Carrier.CMCC]?.get(cellId)
    private fun GongcanParser.Result.ctcc(cellId: Long) = cells[Carrier.CTCC]?.get(cellId)

    private val header =
        "tac\t站点号\t小区号\t基站中文名\t小区名\t网管IP\tPCI\tPRACH\t经度\t纬度\t" +
            "覆盖类型\t频段\t下行频点\t方向角\t制式\tCGI\t天线挂高"

    private fun row(
        cell: String,
        type: String,
        cgi: String,
        site: String = "MAS-测试基站-RHL",
        cellName: String = "MAS-测试小区-01",
    ) = "21858\t1006136\t$cell\t$site\t$cellName\t-\t463\t432\t118.5\t31.7\t" +
        "$type\tFDD1800\t1350\t90\t4G\t$cgi\t30"

    private fun parse(vararg lines: String) =
        GongcanParser.parse((listOf(header) + lines).joinToString("\r\n").toByteArray().inputStream())

    @Test
    fun `解析 4G 与 5G 行`() {
        val r = parse(
            row("257570856", "宏站", "460-00-1006136-40"),
            row("6387450359", "室分", "460-00-1559436-503"),
        )
        assertEquals(2, r.cellCount)
        assertEquals("宏站", r.cmcc(257570856L)?.type)
        assertEquals("室分", r.cmcc(6387450359L)?.type)
    }

    @Test
    fun `保留基站名与小区名`() {
        val r = parse(row("257570856", "宏站", "460-00-1006136-40", "站点甲", "小区乙"))
        val detail = r.cmcc(257570856L)?.detail
        assertNotNull(detail)
        assertEquals("站点甲", detail?.siteName)
        assertEquals("小区乙", detail?.cellName)
    }

    @Test
    fun `空的名称字段保留短横线`() {
        val r = parse(row("257570856", "宏站", "460-00-1006136-40", "-", "-"))
        assertEquals("-", r.cmcc(257570856L)?.detail?.siteName)
        assertEquals("-", r.cmcc(257570856L)?.detail?.cellName)
    }

    @Test
    fun `电信行进电信表，联通行跳过`() {
        val r = parse(
            row("257570856", "宏站", "460-00-1006136-40"),
            // 电信同样进表，只是放在电信的桶里；联通没有工参数据，跳过
            row("111111111", "室分", "460-11-100-1"),
            row("222222222", "室分", "460-01-100-1"),
        )
        assertEquals(2, r.cellCount)
        assertEquals(1, r.skippedOtherPlmn)
        assertEquals("宏站", r.cmcc(257570856L)?.type)
        assertEquals("室分", r.ctcc(111111111L)?.type)
        assertNull(r.cmcc(111111111L))  // 电信小区不能污染移动表
    }

    @Test
    fun `移动电信同小区号不冲突`() {
        // 假设两家 ECI 撞车（现实中并不罕见），必须各归各表
        val r = parse(
            row("100200300", "宏站", "460-00-1006136-40", site = "移动站"),
            row("100200300", "室分", "460-11-2222-0", site = "电信站"),
        )
        assertEquals("移动站", r.cmcc(100200300L)?.detail?.siteName)
        assertEquals("电信站", r.ctcc(100200300L)?.detail?.siteName)
    }

    @Test
    fun `同一小区多个方向角只取首次`() {
        val r = parse(
            row("257570856", "宏站", "460-00-1006136-40", "首次站名"),
            row("257570856", "宏站", "460-00-1006136-40", "重复站名"),
        )
        assertEquals(1, r.cellCount)
        assertEquals("首次站名", r.cmcc(257570856L)?.detail?.siteName)
    }

    @Test
    fun `统计各覆盖类型数量`() {
        val r = parse(
            row("257570856", "宏站", "460-00-1006136-40"),
            row("257570857", "宏站", "460-00-1006136-41"),
            row("257570858", "室分", "460-00-1006136-42"),
        )
        assertEquals(2, r.typeCounts["宏站"])
        assertEquals(1, r.typeCounts["室分"])
    }

    @Test
    fun `GBK 编码的文件也能解析`() {
        val text = listOf(header, row("257570856", "宏站", "460-00-1006136-40")).joinToString("\r\n")
        val r = GongcanParser.parse(text.toByteArray(charset("GBK")).inputStream())
        assertEquals("宏站", r.cmcc(257570856L)?.type)
    }

    @Test
    fun `带 BOM 的 UTF8 也能解析`() {
        val text = "﻿" +
            listOf(header, row("257570856", "宏站", "460-00-1006136-40")).joinToString("\r\n")
        val r = GongcanParser.parse(text.toByteArray().inputStream())
        assertEquals("宏站", r.cmcc(257570856L)?.type)
    }

    @Test
    fun `缺列时报出缺的是哪一列`() {
        val bad = "tac\t小区号\tCGI\n21858\t257570856\t460-00-1006136-40"
        val e = assertThrows(GongcanParser.ParseException::class.java) {
            GongcanParser.parse(bad.toByteArray().inputStream())
        }
        assertTrue(e.message!!.contains("覆盖类型"))
    }

    @Test
    fun `空文件报错`() {
        assertThrows(GongcanParser.ParseException::class.java) {
            GongcanParser.parse(ByteArray(0).inputStream())
        }
    }

    @Test
    fun `全是未支持运营商时报错并说明原因`() {
        val e = assertThrows(GongcanParser.ParseException::class.java) {
            // 联通不在识别范围内
            parse(row("111111111", "宏站", "460-01-100-1"))
        }
        assertTrue(e.message!!.contains("移动") || e.message!!.contains("电信"))
    }

    @Test
    fun `小区号不是数字的行跳过`() {
        val r = parse(
            row("257570856", "宏站", "460-00-1006136-40"),
            row("N/A", "宏站", "460-00-1006136-41"),
        )
        assertEquals(1, r.cellCount)
        assertEquals(1, r.skippedInvalid)
    }
}
