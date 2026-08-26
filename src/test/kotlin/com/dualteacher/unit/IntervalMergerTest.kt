package com.dualteacher.unit

import com.dualteacher.domain.DateInterval
import com.dualteacher.domain.IntervalMerger
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class IntervalMergerTest {

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `single interval counts inclusive days`() {
        val i = DateInterval(d("2025-01-01"), d("2025-01-01"))
        assertEquals(1, i.days())
        assertEquals(1, IntervalMerger.totalDays(listOf(i)))
    }

    @Test
    fun `overlapping intervals are counted once`() {
        // T1's two overlapping practice windows.
        val a = DateInterval(d("2025-01-01"), d("2025-03-01"))
        val b = DateInterval(d("2025-02-15"), d("2025-04-01"))
        val merged = IntervalMerger.merge(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(DateInterval(d("2025-01-01"), d("2025-04-01")), merged.first())
        // 2025-01-01..2025-04-01 inclusive = 91 days
        assertEquals(91, IntervalMerger.totalDays(listOf(a, b)))
    }

    @Test
    fun `adjacent intervals merge without double counting the boundary`() {
        val a = DateInterval(d("2025-01-01"), d("2025-01-10"))
        val b = DateInterval(d("2025-01-11"), d("2025-01-20"))
        assertEquals(1, IntervalMerger.merge(listOf(a, b)).size)
        assertEquals(20, IntervalMerger.totalDays(listOf(a, b)))
    }

    @Test
    fun `disjoint intervals stay separate and days add up`() {
        val a = DateInterval(d("2025-01-01"), d("2025-01-10")) // 10
        val b = DateInterval(d("2025-03-01"), d("2025-03-05")) // 5
        assertEquals(2, IntervalMerger.merge(listOf(a, b)).size)
        assertEquals(15, IntervalMerger.totalDays(listOf(a, b)))
    }

    @Test
    fun `fully contained interval adds no days`() {
        val a = DateInterval(d("2025-01-01"), d("2025-12-31"))
        val b = DateInterval(d("2025-06-01"), d("2025-06-30"))
        assertEquals(365, IntervalMerger.totalDays(listOf(a, b)))
    }

    @Test
    fun `merge is order independent`() {
        val a = DateInterval(d("2025-02-15"), d("2025-04-01"))
        val b = DateInterval(d("2025-01-01"), d("2025-03-01"))
        assertEquals(IntervalMerger.merge(listOf(a, b)), IntervalMerger.merge(listOf(b, a)))
    }
}
