package com.ovengers.slotkey.access.repository

import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessLog
import org.springframework.data.jpa.repository.JpaRepository

interface DoorAccessLogRepository : JpaRepository<DoorAccessLog, Long> {
    // 기존 Java 호출부와 null 인자 허용 계약을 유지한다.

    // 특정 예약의 출입 로그 최신순으로 조회
    fun findAllByReservationIdOrderByAttemptedAtDesc(reservationId: Long?): List<DoorAccessLog>

    // 특정 예약의 출입 결과 존재 여부 확인
    fun existsByReservationIdAndResult(
        reservationId: Long?,
        result: AccessResult?,
    ): Boolean
}
