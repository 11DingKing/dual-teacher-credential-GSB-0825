package com.dualteacher.unit

import com.dualteacher.domain.PracticeInterval
import com.dualteacher.service.IntervalMerger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class IntervalMergerTest {

    private fun interval(
        id: String,
        start: String,
        end: String,
        eventId: String = "evt-$id"
    ) = PracticeInterval(
        intervalId = id,
        teacherId = "T1",
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        company = "Company",
        role = null,
        eventId = eventId
    )

    @Test
    fun `single interval returns correct days`() {
        val intervals = listOf(interval("i1", "2025-01-01", "2025-01-31"))
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2025-12-31"))

        assertEquals(1, merged.size)
        assertEquals(31, merged[0].days)
        assertEquals(31, IntervalMerger.totalDays(merged))
    }

    @Test
    fun `overlapping intervals are merged and counted once`() {
        val intervals = listOf(
            interval("i1", "2025-01-01", "2025-03-01"),
            interval("i2", "2025-02-15", "2025-04-01")
        )
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2026-12-31"))

        assertEquals(1, merged.size)
        assertEquals(LocalDate.parse("2025-01-01"), merged[0].start)
        assertEquals(LocalDate.parse("2025-04-01"), merged[0].end)
        assertEquals(91, merged[0].days)
        assertEquals(91, IntervalMerger.totalDays(merged))
    }

    @Test
    fun `non-overlapping intervals are kept separate`() {
        val intervals = listOf(
            interval("i1", "2025-01-01", "2025-01-31"),
            interval("i2", "2025-03-01", "2025-03-31")
        )
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2025-12-31"))

        assertEquals(2, merged.size)
        assertEquals(62, IntervalMerger.totalDays(merged))
    }

    @Test
    fun `touching intervals are not merged`() {
        val intervals = listOf(
            interval("i1", "2025-01-01", "2025-01-31"),
            interval("i2", "2025-02-01", "2025-02-28")
        )
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2025-12-31"))

        assertEquals(2, merged.size)
        assertEquals(59, IntervalMerger.totalDays(merged))
    }

    @Test
    fun `interval ending on asOf is included`() {
        val intervals = listOf(interval("i1", "2025-01-01", "2025-03-01"))
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2025-03-01"))

        assertEquals(1, merged.size)
        assertEquals(LocalDate.parse("2025-03-01"), merged[0].end)
        assertEquals(60, merged[0].days)
    }

    @Test
    fun `interval starting after asOf is excluded`() {
        val intervals = listOf(interval("i1", "2025-06-01", "2025-07-01"))
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2025-05-31"))

        assertEquals(0, merged.size)
        assertEquals(0, IntervalMerger.totalDays(merged))
    }

    @Test
    fun `interval spanning asOf is clamped`() {
        val intervals = listOf(interval("i1", "2025-01-01", "2025-12-31"))
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2025-06-15"))

        assertEquals(1, merged.size)
        assertEquals(LocalDate.parse("2025-06-15"), merged[0].end)
        assertEquals(166, merged[0].days)
    }

    @Test
    fun `multiple overlapping intervals merge into one`() {
        val intervals = listOf(
            interval("i1", "2025-01-01", "2025-02-01"),
            interval("i2", "2025-01-15", "2025-03-01"),
            interval("i3", "2025-02-15", "2025-04-01")
        )
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2026-01-01"))

        assertEquals(1, merged.size)
        assertEquals(LocalDate.parse("2025-01-01"), merged[0].start)
        assertEquals(LocalDate.parse("2025-04-01"), merged[0].end)
        assertEquals(91, merged[0].days)
    }

    @Test
    fun `same day interval counts as one day`() {
        val intervals = listOf(interval("i1", "2025-05-15", "2025-05-15"))
        val merged = IntervalMerger.merge(intervals, LocalDate.parse("2025-12-31"))

        assertEquals(1, merged.size)
        assertEquals(1, merged[0].days)
    }
}
