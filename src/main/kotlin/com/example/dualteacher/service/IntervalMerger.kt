package com.example.dualteacher.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 区间合并工具：把重叠或首尾相接的日期区间合并后累计天数（起止日均计入），
 * 重叠部分只计算一次。
 */
object IntervalMerger {

    fun totalDays(intervals: List<Pair<LocalDate, LocalDate>>): Long {
        if (intervals.isEmpty()) return 0
        val sorted = intervals.sortedBy { it.first }
        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for ((start, end) in sorted.drop(1)) {
            if (start <= curEnd.plusDays(1)) {
                if (end > curEnd) curEnd = end
            } else {
                total += ChronoUnit.DAYS.between(curStart, curEnd) + 1
                curStart = start
                curEnd = end
            }
        }
        return total + ChronoUnit.DAYS.between(curStart, curEnd) + 1
    }
}
