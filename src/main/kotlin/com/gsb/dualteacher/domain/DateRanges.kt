package com.gsb.dualteacher.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 闭区间日期段 [start, end]，含首尾两天。 */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!start.isAfter(end)) { "invalid range: $start > $end" }
    }

    /** 区间天数（首尾都算）。 */
    fun days(): Long = ChronoUnit.DAYS.between(start, end) + 1

    fun overlapsOrTouches(other: DateRange): Boolean =
        !other.end.isBefore(start) && !other.start.isAfter(end.plusDays(1))
}

/**
 * 合并重叠/相邻的日期区间：重叠的企业实践只算一次。
 * 相邻区间合并与否不影响总天数（相邻段不共享任何一天）。
 */
fun mergeRanges(ranges: List<DateRange>): List<DateRange> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.start }
    val merged = mutableListOf<DateRange>()
    for (range in sorted) {
        val last = merged.lastOrNull()
        if (last != null && range.start <= last.end.plusDays(1)) {
            if (range.end.isAfter(last.end)) {
                merged[merged.lastIndex] = DateRange(last.start, range.end)
            }
        } else {
            merged.add(range)
        }
    }
    return merged
}

/** 只保留 asOf 当日（含）之前已经发生的部分：未开始的区间丢弃，跨 asOf 的区间截断。 */
fun clipToAsOf(ranges: List<DateRange>, asOf: LocalDate): List<DateRange> =
    ranges.mapNotNull { range ->
        when {
            range.start.isAfter(asOf) -> null
            range.end.isAfter(asOf) -> DateRange(range.start, asOf)
            else -> range
        }
    }
