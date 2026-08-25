package com.dualteacher.domain

import java.time.LocalDate

/** A closed, inclusive date interval [start, end]. */
data class DateInterval(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "interval end ($end) must not precede start ($start)" }
    }

    /** Inclusive day count, e.g. a single-day interval is 1 day. */
    fun days(): Long = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
}

/**
 * Merges overlapping or adjacent inclusive intervals so shared days are counted once.
 *
 * Two intervals are combined when the next one starts on or before the running end, or on
 * the very next day (adjacency), producing the union of covered days.
 */
object IntervalMerger {
    fun merge(intervals: List<DateInterval>): List<DateInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedWith(compareBy({ it.start }, { it.end }))
        val merged = ArrayList<DateInterval>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            // overlap or touch (next starts within, or exactly one day after, current.end)
            if (!next.start.isAfter(current.end.plusDays(1))) {
                val newEnd = if (next.end.isAfter(current.end)) next.end else current.end
                current = DateInterval(current.start, newEnd)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /** Total distinct days across the merged intervals. */
    fun totalDays(intervals: List<DateInterval>): Long = merge(intervals).sumOf { it.days() }
}
