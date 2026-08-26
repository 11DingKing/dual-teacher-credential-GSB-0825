package com.example.dualteacher.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class IntervalMergerTest {

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    @Test
    fun `empty list yields zero days`() {
        assertEquals(0, IntervalMerger.totalDays(emptyList()))
    }

    @Test
    fun `single day interval counts as one day`() {
        assertEquals(1, IntervalMerger.totalDays(listOf(d("2025-03-01") to d("2025-03-01"))))
    }

    @Test
    fun `T1 overlapping intervals merge and count overlap only once`() {
        // 2025-01-01..2025-03-01 与 2025-02-15..2025-04-01 → 合并为 2025-01-01..2025-04-01
        val days = IntervalMerger.totalDays(
            listOf(
                d("2025-01-01") to d("2025-03-01"),
                d("2025-02-15") to d("2025-04-01"),
            )
        )
        assertEquals(91, days) // 31(1月) + 28(2月) + 31(3月) + 1(4月1日)
    }

    @Test
    fun `disjoint intervals are summed separately`() {
        val days = IntervalMerger.totalDays(
            listOf(
                d("2025-01-01") to d("2025-01-10"),
                d("2025-02-01") to d("2025-02-10"),
            )
        )
        assertEquals(20, days)
    }

    @Test
    fun `adjacent intervals merge seamlessly`() {
        val days = IntervalMerger.totalDays(
            listOf(
                d("2025-01-01") to d("2025-01-31"),
                d("2025-02-01") to d("2025-02-28"),
            )
        )
        assertEquals(59, days)
    }

    @Test
    fun `contained interval does not extend total`() {
        val days = IntervalMerger.totalDays(
            listOf(
                d("2025-01-01") to d("2025-12-31"),
                d("2025-03-01") to d("2025-04-01"),
            )
        )
        assertEquals(365, days)
    }

    @Test
    fun `unsorted input is handled`() {
        val days = IntervalMerger.totalDays(
            listOf(
                d("2025-02-15") to d("2025-04-01"),
                d("2025-01-01") to d("2025-03-01"),
            )
        )
        assertEquals(91, days)
    }
}
