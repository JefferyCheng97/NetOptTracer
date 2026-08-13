package com.jeffery.cellularmonitor

import com.jeffery.cellularmonitor.data.CellTypeClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CellTypeClassifier] 的空表行为。
 *
 * 完整加载路径要 Context + assets，属于仪器化测试的范围；这里只覆盖
 * 没有工参时必须安静降级这一条——它决定了没导入工参的机器上界面不会出错。
 * 解析逻辑本身由 [GongcanParserTest] 覆盖。
 */
class CellTypeClassifierTest {

    @Test
    fun `空表时查询返回 null`() {
        val empty = CellTypeClassifier.EMPTY
        assertNull(empty.classifyLte(257570856L, "46000"))
        assertNull(empty.classifyNr(6387450359L, "46000"))
        assertNull(empty.detailLte(257570856L, "46000"))
        assertNull(empty.detailNr(6387450359L, "46000"))
    }

    @Test
    fun `空表 size 为 0`() {
        assertEquals(0, CellTypeClassifier.EMPTY.size)
    }

    @Test
    fun `非移动 PLMN 一律返回 null`() {
        val empty = CellTypeClassifier.EMPTY
        // 电信 46011、联通 46001，以及读不到 PLMN 的情况
        assertNull(empty.classifyLte(257570856L, "46011"))
        assertNull(empty.classifyLte(257570856L, "46001"))
        assertNull(empty.classifyLte(257570856L, null))
    }
}
