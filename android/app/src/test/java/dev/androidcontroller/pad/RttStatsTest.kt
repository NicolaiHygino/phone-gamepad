package dev.androidcontroller.pad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RttStatsTest {
    private fun ms(v: Int) = v * 1_000_000L

    @Test
    fun summarizesMedianP99AndMax() {
        val samples = LongArray(100) { ms(it + 1) } // 1..100 ms
        val s = PadClient.summarize(samples, 100)!!
        assertEquals(51f, s.median, 0.001f)
        assertEquals(100f, s.p99, 0.001f)
        assertEquals(100f, s.max, 0.001f)
        assertEquals(100, s.count)
    }

    @Test
    fun ignoresSamplesBeyondN() {
        val samples = longArrayOf(ms(3), ms(1), ms(2), ms(5000), ms(5000))
        val s = PadClient.summarize(samples, 3)!!
        assertEquals(2f, s.median, 0.001f)
        assertEquals(3f, s.max, 0.001f)
    }

    @Test
    fun orderDoesNotMatterAndInputIsNotMutated() {
        val samples = longArrayOf(ms(9), ms(1), ms(5))
        PadClient.summarize(samples, 3)
        assertEquals(listOf(ms(9), ms(1), ms(5)), samples.toList())
    }

    @Test
    fun noSamplesGivesNull() {
        assertNull(PadClient.summarize(LongArray(4), 0))
    }
}
