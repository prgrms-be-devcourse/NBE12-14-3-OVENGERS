package com.ovengers.slotkey.space.policy

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Component
class SpaceOperatingHoursPolicy {
    companion object {
        const val SLOT_DURATION_MINUTES: Int = 30
    }

    @JvmRecord
    data class SlotWindow(
        val start: LocalDateTime,
        val end: LocalDateTime,
    )

    /**
     * 운영시간(openingTime ~ closingTime)을 30분 단위 슬롯 구간 목록으로 분할 생성합니다.
     */
    fun generateSlots(
        date: LocalDate?,
        openingTime: LocalTime?,
        closingTime: LocalTime?,
    ): List<SlotWindow> {
        val validOpeningTime = openingTime ?: return emptyList()
        val validClosingTime = closingTime ?: return emptyList()
        if (!validOpeningTime.isBefore(validClosingTime) ||
            !isMultipleOf30Minutes(validOpeningTime) ||
            !isMultipleOf30Minutes(validClosingTime)
        ) {
            return emptyList()
        }

        // 기존 Java와 같이 운영시간 검증 뒤에 날짜를 사용한다. 유효한 시간에 date가 null이면 NPE다.
        val slotDate = date ?: throw NullPointerException()

        return buildList {
            var currentStart = validOpeningTime
            while (true) {
                val currentEnd = currentStart.plusMinutes(SLOT_DURATION_MINUTES.toLong())
                // 종료 시각을 넘어서면 중단 (또는 closingTime에 정확히 도달하거나 넘어가는 경우)
                if (currentEnd.isAfter(validClosingTime) || currentEnd == currentStart) {
                    break
                }

                add(SlotWindow(slotDate.atTime(currentStart), slotDate.atTime(currentEnd)))

                if (currentEnd == validClosingTime) {
                    break
                }
                currentStart = currentEnd
            }
        }
    }

    /**
     * 요청된 시간 구간이 공간의 운영시간 내에 온전히 포함되는지 검증합니다.
     */
    fun isWithinOperatingHours(
        openingTime: LocalTime?,
        closingTime: LocalTime?,
        requestedStart: LocalTime?,
        requestedEnd: LocalTime?,
    ): Boolean {
        val validOpeningTime = openingTime ?: return false
        val validClosingTime = closingTime ?: return false
        val validRequestedStart = requestedStart ?: return false
        val validRequestedEnd = requestedEnd ?: return false
        return with(validRequestedStart) {
            isBefore(validRequestedEnd) &&
                !isBefore(validOpeningTime) &&
                !validRequestedEnd.isAfter(validClosingTime)
        }
    }

    /**
     * 시간이 30분 단위 배수인지 검증합니다. (0분 또는 30분, 초/나노초는 0)
     */
    fun isMultipleOf30Minutes(time: LocalTime?): Boolean =
        time?.let {
            (it.minute == 0 || it.minute == 30) &&
                it.second == 0 &&
                it.nano == 0
        } ?: false
}
