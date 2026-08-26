package com.gsb.dualteacher.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateRangesTest {

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `T1 两段重叠实践合并为一段并只计一次天数`() {
        val merged = mergeRanges(
            listOf(
                DateRange(d("2025-01-01"), d("2025-03-01")),
                DateRange(d("2025-02-15"), d("2025-04-01")),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(DateRange(d("2025-01-01"), d("2025-04-01")), merged.single())
        // 1 月 31 + 2 月 28 + 3 月 31 + 4 月 1 = 91 天（首尾都算）
        assertEquals(91L, merged.sumOf { it.days() })
    }

    @Test
    fun `不相交区间保持两段且天数相加`() {
        val merged = mergeRanges(
            listOf(
                DateRange(d("2025-01-01"), d("2025-01-10")),
                DateRange(d("2025-06-01"), d("2025-06-05")),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(10L + 5L, merged.sumOf { it.days() })
    }

    @Test
    fun `被包含的区间不产生重复天数`() {
        val merged = mergeRanges(
            listOf(
                DateRange(d("2025-01-01"), d("2025-03-01")),
                DateRange(d("2025-02-01"), d("2025-02-10")),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(DateRange(d("2025-01-01"), d("2025-03-01")), merged.single())
    }

    @Test
    fun `相邻区间合并且总天数不变`() {
        val merged = mergeRanges(
            listOf(
                DateRange(d("2025-01-01"), d("2025-01-31")),
                DateRange(d("2025-02-01"), d("2025-02-28")),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(59L, merged.single().days())
    }

    @Test
    fun `单日区间天数为 1`() {
        assertEquals(1L, DateRange(d("2025-05-01"), d("2025-05-01")).days())
    }

    @Test
    fun `asOf 之后才开始的区间被丢弃`() {
        val clipped = clipToAsOf(
            listOf(DateRange(d("2026-05-01"), d("2026-08-01"))),
            d("2026-04-30"),
        )
        assertEquals(0, clipped.size)
    }

    @Test
    fun `跨越 asOf 的区间截断到基准日（含当天）`() {
        val clipped = clipToAsOf(
            listOf(DateRange(d("2026-04-01"), d("2026-06-01"))),
            d("2026-04-30"),
        )
        assertEquals(DateRange(d("2026-04-01"), d("2026-04-30")), clipped.single())
        assertEquals(30L, clipped.single().days())
    }

    @Test
    fun `空区间列表合并为空`() {
        assertEquals(0, mergeRanges(emptyList()).size)
    }
}
