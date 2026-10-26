package com.ovengers.slotkey.reservation.repository

import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReservationStatusHistoryRepository : JpaRepository<ReservationStatusHistory, Long> {
    fun findAllByReservationIdOrderByChangedAtAsc(reservationId: Long): List<ReservationStatusHistory>
}
