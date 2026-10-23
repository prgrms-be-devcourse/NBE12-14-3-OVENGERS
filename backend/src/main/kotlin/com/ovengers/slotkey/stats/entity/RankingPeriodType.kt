package com.ovengers.slotkey.stats.entity

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 랭킹 기간 종류. 날짜는 모두 KST 달력 날짜이고, 기간은 [startOf, endOf) 반열린 구간이다.
 * 주는 월요일에 시작한다. 근거: docs/decisions/space-ranking.md §2-4.
 */
enum class RankingPeriodType {
    DAILY {
        override fun startOf(date: LocalDate?): LocalDate? = date

        override fun endOf(date: LocalDate?): LocalDate = date?.plusDays(1) ?: throw NullPointerException()
    },
    WEEKLY {
        override fun startOf(date: LocalDate?): LocalDate =
            date?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                ?: throw NullPointerException()

        override fun endOf(date: LocalDate?): LocalDate = date?.let { startOf(it).plusWeeks(1) } ?: throw NullPointerException()
    },
    MONTHLY {
        override fun startOf(date: LocalDate?): LocalDate = date?.withDayOfMonth(1) ?: throw NullPointerException()

        override fun endOf(date: LocalDate?): LocalDate = date?.let { startOf(it).plusMonths(1) } ?: throw NullPointerException()
    },
    ;

    /** date가 속한 기간의 첫 날(포함). */
    abstract fun startOf(date: LocalDate?): LocalDate?

    /** date가 속한 기간의 끝(제외) = 다음 기간의 첫 날. */
    abstract fun endOf(date: LocalDate?): LocalDate
}
