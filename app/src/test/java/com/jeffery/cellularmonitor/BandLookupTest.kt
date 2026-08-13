package com.jeffery.cellularmonitor

import com.jeffery.cellularmonitor.data.BandLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandLookupTest {

    @Test
    fun `LTE EARFCN to band - common Chinese bands`() {
        assertEquals("B1", BandLookup.lteBandFromEarfcn(0)) // B1 起点
        assertEquals("B1", BandLookup.lteBandFromEarfcn(599)) // B1 终点
        assertEquals("B3", BandLookup.lteBandFromEarfcn(1650)) // B3 中间
        assertEquals("B3", BandLookup.lteBandFromEarfcn(1200)) // B3 起点
        assertEquals("B3", BandLookup.lteBandFromEarfcn(1949)) // B3 终点
        assertEquals("B5", BandLookup.lteBandFromEarfcn(2400)) // B5 起点
        assertEquals("B8", BandLookup.lteBandFromEarfcn(3450)) // B8 起点
    }

    @Test
    fun `LTE EARFCN to band - TDD bands`() {
        assertEquals("B38", BandLookup.lteBandFromEarfcn(38000)) // B38 中间
        assertEquals("B39", BandLookup.lteBandFromEarfcn(38250)) // B39 起点
        assertEquals("B40", BandLookup.lteBandFromEarfcn(39000)) // B40 中间
        assertEquals("B41", BandLookup.lteBandFromEarfcn(40000)) // B41 中间
        assertEquals("B41", BandLookup.lteBandFromEarfcn(39650)) // B41 起点
        assertEquals("B41", BandLookup.lteBandFromEarfcn(41589)) // B41 终点
    }

    @Test
    fun `LTE EARFCN out of any range returns null`() {
        assertNull(BandLookup.lteBandFromEarfcn(99999))
        assertNull(BandLookup.lteBandFromEarfcn(-1))
    }

    @Test
    fun `LTE EARFCN null returns null`() {
        assertNull(BandLookup.lteBandFromEarfcn(null))
    }

    @Test
    fun `NR ARFCN to band - common Chinese bands`() {
        assertEquals("n1", BandLookup.nrBandFromArfcn(384000)) // n1 起点
        assertEquals("n1", BandLookup.nrBandFromArfcn(390000)) // n1 中间
        assertEquals("n3", BandLookup.nrBandFromArfcn(350000)) // n3 中间
        assertEquals("n8", BandLookup.nrBandFromArfcn(185000)) // n8 起点
        assertEquals("n28", BandLookup.nrBandFromArfcn(155000)) // n28 中间
    }

    @Test
    fun `NR ARFCN to band - TDD bands`() {
        assertEquals("n41", BandLookup.nrBandFromArfcn(504990)) // n41 中间
        assertEquals("n41", BandLookup.nrBandFromArfcn(499200)) // n41 起点
        assertEquals("n41", BandLookup.nrBandFromArfcn(537999)) // n41 终点
        assertEquals("n78", BandLookup.nrBandFromArfcn(620000)) // n78 起点，也是 n77 起点
        assertEquals("n78", BandLookup.nrBandFromArfcn(640000)) // n78 中间
        assertEquals("n77", BandLookup.nrBandFromArfcn(660000)) // n77 但不在 n78 内
        assertEquals("n79", BandLookup.nrBandFromArfcn(700000)) // n79 中间
    }

    @Test
    fun `NR ARFCN out of any range returns null`() {
        assertNull(BandLookup.nrBandFromArfcn(999999))
        assertNull(BandLookup.nrBandFromArfcn(-1))
    }

    @Test
    fun `NR ARFCN null returns null`() {
        assertNull(BandLookup.nrBandFromArfcn(null))
    }

    @Test
    fun `format LTE bands array`() {
        assertEquals("B3", BandLookup.formatLteBands(intArrayOf(3)))
        assertEquals("B3+B41", BandLookup.formatLteBands(intArrayOf(3, 41)))
        assertNull(BandLookup.formatLteBands(intArrayOf()))
        assertNull(BandLookup.formatLteBands(null))
    }

    @Test
    fun `format NR bands array`() {
        assertEquals("n41", BandLookup.formatNrBands(intArrayOf(41)))
        assertEquals("n41+n78", BandLookup.formatNrBands(intArrayOf(41, 78)))
        assertNull(BandLookup.formatNrBands(intArrayOf()))
        assertNull(BandLookup.formatNrBands(null))
    }

    @Test
    fun `format bands filters out non-positive values`() {
        assertEquals("B3", BandLookup.formatLteBands(intArrayOf(0, 3, -1)))
        assertNull(BandLookup.formatLteBands(intArrayOf(0, -1)))
    }
}
