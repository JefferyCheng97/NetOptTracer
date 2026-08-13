package com.jeffery.cellularmonitor

import com.jeffery.cellularmonitor.data.CellIdFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellIdFormatterTest {

    @Test
    fun `LTE ECI split with valid 28-bit value`() {
        // 123456789 二进制是 28 位有效，高 20 位 482253，低 8 位 21
        val eci = 123456789
        assertEquals("482253-21", CellIdFormatter.splitLteEci(eci))
        assertEquals("123456789", CellIdFormatter.plain(eci))
    }

    @Test
    fun `LTE ECI split with zero`() {
        assertEquals("0-0", CellIdFormatter.splitLteEci(0))
    }

    @Test
    fun `LTE ECI split with max 28-bit value`() {
        val max28 = (1 shl 28) - 1 // 268435455
        assertEquals("1048575-255", CellIdFormatter.splitLteEci(max28))
    }

    @Test
    fun `LTE ECI split with overflow returns null`() {
        val overflow = (1 shl 28) // 超出 28 位
        assertNull(CellIdFormatter.splitLteEci(overflow))
    }

    @Test
    fun `LTE ECI split with negative returns null`() {
        assertNull(CellIdFormatter.splitLteEci(-1))
    }

    @Test
    fun `LTE ECI split with null returns null`() {
        assertNull(CellIdFormatter.splitLteEci(null))
    }

    @Test
    fun `NR NCI split with 24-bit gNB default`() {
        // 12345678901L 按 gNB 24 位拆：高 24 位 3014081，低 12 位 3125
        val nci = 12345678901L
        assertEquals("3014081-3125", CellIdFormatter.splitNrNci(nci, 24))
        assertEquals("3014081-3125", CellIdFormatter.splitNrNci(nci)) // 默认就是 24
        assertEquals("12345678901", CellIdFormatter.plain(nci))
    }

    @Test
    fun `NR NCI split with 22-bit gNB`() {
        // 同一个 NCI 换位长，拆分结果随之变化
        assertEquals("753520-7221", CellIdFormatter.splitNrNci(12345678901L, 22))
    }

    @Test
    fun `NR NCI split with 32-bit gNB`() {
        assertEquals("771604931-5", CellIdFormatter.splitNrNci(12345678901L, 32))
    }

    @Test
    fun `NR NCI split with zero`() {
        assertEquals("0-0", CellIdFormatter.splitNrNci(0L, 24))
    }

    @Test
    fun `NR NCI split with max 36-bit value`() {
        val max36 = (1L shl 36) - 1 // 68719476735
        // 按 24 位拆：高 24 位 16777215，低 12 位 4095
        assertEquals("16777215-4095", CellIdFormatter.splitNrNci(max36, 24))
    }

    @Test
    fun `NR NCI split with overflow returns null`() {
        val overflow = (1L shl 36)
        assertNull(CellIdFormatter.splitNrNci(overflow, 24))
        // 超出 36 位的值一律拒绝，不做截断
        assertNull(CellIdFormatter.splitNrNci(1234567890123L, 24))
    }

    @Test
    fun `NR NCI split with negative returns null`() {
        assertNull(CellIdFormatter.splitNrNci(-1L, 24))
    }

    @Test
    fun `NR NCI split with null returns null`() {
        assertNull(CellIdFormatter.splitNrNci(null, 24))
    }

    @Test
    fun `NR NCI split with gNB bits out of range returns null`() {
        val nci = 1234567890123L
        assertNull(CellIdFormatter.splitNrNci(nci, 21)) // < 22
        assertNull(CellIdFormatter.splitNrNci(nci, 33)) // > 32
    }

    @Test
    fun `plain with null returns null`() {
        assertNull(CellIdFormatter.plain(null as Int?))
        assertNull(CellIdFormatter.plain(null as Long?))
    }

    @Test
    fun `plain with negative returns null`() {
        assertNull(CellIdFormatter.plain(-1))
        assertNull(CellIdFormatter.plain(-1L))
    }
}
