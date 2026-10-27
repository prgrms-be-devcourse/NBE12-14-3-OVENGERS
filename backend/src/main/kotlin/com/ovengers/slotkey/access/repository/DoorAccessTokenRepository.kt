package com.ovengers.slotkey.access.repository

import com.ovengers.slotkey.access.entity.DoorAccessToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.Optional

interface DoorAccessTokenRepository : JpaRepository<DoorAccessToken, Long> {
    // 기존 Java 호출부와 null 인자 허용 계약을 유지한다.
    // TODO: Java 호출부·테스트 전환 완료 후 호출부와 함께 Optional 반환을 nullable 타입으로 변경한다.

    // 토큰 해시로 출입 토큰 조회
    fun findByTokenHash(tokenHash: String?): Optional<DoorAccessToken>

    // 특정 예약의 활성 토큰 조회
    fun findByReservationIdAndRevokedAtIsNull(reservationId: Long?): Optional<DoorAccessToken>

    // REPEATABLE READ Read View를 우회하는 활성 토큰 상태 Locking Read
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DoorAccessToken t WHERE t.reservation.id = :reservationId AND t.revokedAt IS NULL")
    fun findByReservationIdAndRevokedAtIsNullForUpdate(
        @Param("reservationId") reservationId: Long?,
    ): Optional<DoorAccessToken>

    // REPEATABLE READ Read View를 우회하는 최신 토큰 상태 Locking Read
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DoorAccessToken t WHERE t.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long?,
    ): Optional<DoorAccessToken>

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE DoorAccessToken t
        SET t.revokedAt = :now,
            t.revokeReason = :reason
        WHERE t.revokedAt IS NULL
          AND t.reservation.id IN (
              SELECT r.id
              FROM Reservation r
              WHERE r.memberId = :memberId
          )
        """,
    )
    fun revokeAllByMemberId(
        @Param("memberId") memberId: Long?,
        @Param("now") now: LocalDateTime?,
        @Param("reason") reason: String?,
    ): Int
}
