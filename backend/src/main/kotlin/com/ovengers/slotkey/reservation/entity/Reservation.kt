package com.ovengers.slotkey.reservation.entity

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 예약. 상태 전이는 이 엔티티의 메서드로만 수행한다(docs/core-domain-decisions.md §3).
 * 동시성 하의 최종 판정은 서비스 레이어의 조건부 UPDATE(레포지토리)가 담당하며,
 * 이 클래스의 검증은 "문지기를 통과한 뒤에도 상태 기계가 스스로를 지킨다"는 방어선이다.
 */
@Entity
@Table(name = "reservation")
class Reservation {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getIdValue")
    final var id: Long = 0
        protected set

    @get:JvmName("getMemberIdValue")
    @field:Column(name = "member_id", nullable = false)
    final var memberId: Long = 0
        protected set

    @get:JvmName("getSpaceIdValue")
    @field:Column(name = "space_id", nullable = false)
    final var spaceId: Long = 0
        protected set

    @get:JvmName("getStartTimeValue")
    @field:Column(name = "start_time", nullable = false)
    final lateinit var startTime: LocalDateTime
        protected set

    @get:JvmName("getEndTimeValue")
    @field:Column(name = "end_time", nullable = false)
    final lateinit var endTime: LocalDateTime
        protected set

    @get:JvmName("getStatusValue")
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    final lateinit var status: ReservationStatus
        protected set

    @get:JvmName("getPricePerSlotSnapshotValue")
    @field:Column(name = "price_per_slot_snapshot", nullable = false)
    final var pricePerSlotSnapshot: Int = 0
        protected set

    @get:JvmName("getTotalAmountValue")
    @field:Column(name = "total_amount", nullable = false)
    final var totalAmount: Int = 0
        protected set

    @field:Column(name = "hold_expires_at")
    var holdExpiresAt: LocalDateTime? = null
        protected set

    @field:Column(name = "checked_in_at")
    var checkedInAt: LocalDateTime? = null
        protected set

    @field:Column(name = "checked_out_at")
    var checkedOutAt: LocalDateTime? = null
        protected set

    @field:Column(name = "cancelled_at")
    var cancelledAt: LocalDateTime? = null
        protected set

    @get:JvmName("getCreatedAtValue")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    final lateinit var createdAt: LocalDateTime
        protected set

    constructor()

    constructor(
        id: Long?,
        memberId: Long?,
        spaceId: Long?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        status: ReservationStatus?,
        pricePerSlotSnapshot: Int?,
        totalAmount: Int?,
        holdExpiresAt: LocalDateTime?,
        checkedInAt: LocalDateTime?,
        checkedOutAt: LocalDateTime?,
        cancelledAt: LocalDateTime?,
        createdAt: LocalDateTime?,
    ) {
        id?.let { this.id = it }
        memberId?.let { this.memberId = it }
        spaceId?.let { this.spaceId = it }
        startTime?.let { this.startTime = it }
        endTime?.let { this.endTime = it }
        status?.let { this.status = it }
        pricePerSlotSnapshot?.let { this.pricePerSlotSnapshot = it }
        totalAmount?.let { this.totalAmount = it }
        this.holdExpiresAt = holdExpiresAt
        this.checkedInAt = checkedInAt
        this.checkedOutAt = checkedOutAt
        this.cancelledAt = cancelledAt
        createdAt?.let { this.createdAt = it }
    }

    /** HELD -> CONFIRMED. 결제(크레딧 차감) 성공 시. now가 holdExpiresAt 이후면 만료로 취급한다. */
    fun confirm(now: LocalDateTime) {
        requireStatus(ReservationStatus.HELD)
        if (holdExpiresAt?.let(now::isBefore) != true) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "HOLD가 만료되었습니다.")
        }
        status = ReservationStatus.CONFIRMED
    }

    /** HELD -> EXPIRED. 결제 확인 없이 hold_expires_at을 지난 경우. */
    fun expire() {
        requireStatus(ReservationStatus.HELD)
        status = ReservationStatus.EXPIRED
    }

    /** CONFIRMED -> CANCELLED. 시작 전에만 가능하다. */
    fun cancel(now: LocalDateTime) {
        requireStatus(ReservationStatus.CONFIRMED)
        if (!now.isBefore(startTime)) {
            throw BusinessException(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                "시작 이후에는 취소할 수 없습니다. 체크아웃을 이용하세요.",
            )
        }
        status = ReservationStatus.CANCELLED
        cancelledAt = now
    }

    /** CONFIRMED -> IN_USE. 최초 체크인 성공(출입 검증의 부수 효과). */
    fun checkIn(now: LocalDateTime) {
        requireStatus(ReservationStatus.CONFIRMED)
        status = ReservationStatus.IN_USE
        checkedInAt = now
    }

    /**
     * IN_USE -> COMPLETED. 명시적 체크아웃 또는 종료 시각 경과(자동 퇴실)에서 호출한다.
     * 자동 퇴실 시 checkedOutAt에는 배치 실행 시각이 아니라 end_time을 넣는다(core-domain-decisions 3-4).
     */
    fun checkOut(checkedOutAt: LocalDateTime) {
        requireStatus(ReservationStatus.IN_USE)
        status = ReservationStatus.COMPLETED
        this.checkedOutAt = checkedOutAt
    }

    /** CONFIRMED -> NO_SHOW. 체크인 창(시작 + 15분)을 놓친 경우(배치). */
    fun markNoShow() {
        requireStatus(ReservationStatus.CONFIRMED)
        status = ReservationStatus.NO_SHOW
    }

    /** CONFIRMED/IN_USE 상태에서 종료 시각을 늘린다. 기존 슬롯은 건드리지 않는다(§7). */
    fun extend(
        expectedCurrentEndTime: LocalDateTime,
        newEndTime: LocalDateTime,
        additionalAmount: Int,
    ) {
        if (status != ReservationStatus.CONFIRMED && status != ReservationStatus.IN_USE) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "연장할 수 없는 상태입니다.")
        }
        if (!endTime.isEqual(expectedCurrentEndTime)) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "예약 정보가 변경되었습니다. 다시 시도해주세요.")
        }
        if (!newEndTime.isAfter(endTime)) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "연장 시각은 기존 종료 시각보다 늦어야 합니다.")
        }
        endTime = newEndTime
        totalAmount += additionalAmount
    }

    private fun requireStatus(expected: ReservationStatus) {
        if (status != expected) {
            throw BusinessException(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                "허용되지 않는 상태 전이입니다. (현재: $status, 필요: $expected)",
            )
        }
    }

    /** 종료된 상태인지 확인 (COMPLETED/EXPIRED/NO_SHOW/CANCELLED). */
    fun isTerminalState(): Boolean =
        when (status) {
            ReservationStatus.COMPLETED,
            ReservationStatus.EXPIRED,
            ReservationStatus.NO_SHOW,
            ReservationStatus.CANCELLED,
            -> true
            else -> false
        }

    fun getId(): Long = id

    fun getMemberId(): Long? = memberId

    fun getSpaceId(): Long = spaceId

    fun getStartTime(): LocalDateTime = startTime

    fun getEndTime(): LocalDateTime = endTime

    fun getStatus(): ReservationStatus = status

    fun getPricePerSlotSnapshot(): Int = pricePerSlotSnapshot

    fun getTotalAmount(): Int = totalAmount

    fun getCreatedAt(): LocalDateTime = createdAt

    /** Java 호출부와 테스트가 사용하는 기존 빌더 API를 유지한다. */
    class ReservationBuilder {
        private var id: Long? = null
        private var memberId: Long? = null
        private var spaceId: Long? = null
        private var startTime: LocalDateTime? = null
        private var endTime: LocalDateTime? = null
        private var status: ReservationStatus? = null
        private var pricePerSlotSnapshot: Int? = null
        private var totalAmount: Int? = null
        private var holdExpiresAt: LocalDateTime? = null
        private var checkedInAt: LocalDateTime? = null
        private var checkedOutAt: LocalDateTime? = null
        private var cancelledAt: LocalDateTime? = null
        private var createdAt: LocalDateTime? = null

        fun id(id: Long?) = apply { this.id = id }

        fun memberId(memberId: Long?) = apply { this.memberId = memberId }

        fun spaceId(spaceId: Long?) = apply { this.spaceId = spaceId }

        fun startTime(startTime: LocalDateTime?) = apply { this.startTime = startTime }

        fun endTime(endTime: LocalDateTime?) = apply { this.endTime = endTime }

        fun status(status: ReservationStatus?) = apply { this.status = status }

        fun pricePerSlotSnapshot(pricePerSlotSnapshot: Int?) = apply { this.pricePerSlotSnapshot = pricePerSlotSnapshot }

        fun totalAmount(totalAmount: Int?) = apply { this.totalAmount = totalAmount }

        fun holdExpiresAt(holdExpiresAt: LocalDateTime?) = apply { this.holdExpiresAt = holdExpiresAt }

        fun checkedInAt(checkedInAt: LocalDateTime?) = apply { this.checkedInAt = checkedInAt }

        fun checkedOutAt(checkedOutAt: LocalDateTime?) = apply { this.checkedOutAt = checkedOutAt }

        fun cancelledAt(cancelledAt: LocalDateTime?) = apply { this.cancelledAt = cancelledAt }

        fun createdAt(createdAt: LocalDateTime?) = apply { this.createdAt = createdAt }

        fun build() =
            Reservation(
                id,
                memberId,
                spaceId,
                startTime,
                endTime,
                status,
                pricePerSlotSnapshot,
                totalAmount,
                holdExpiresAt,
                checkedInAt,
                checkedOutAt,
                cancelledAt,
                createdAt,
            )

        override fun toString(): String =
            "Reservation.ReservationBuilder(id=$id, memberId=$memberId, spaceId=$spaceId, startTime=$startTime, " +
                "endTime=$endTime, status=$status, pricePerSlotSnapshot=$pricePerSlotSnapshot, totalAmount=$totalAmount, " +
                "holdExpiresAt=$holdExpiresAt, checkedInAt=$checkedInAt, checkedOutAt=$checkedOutAt, " +
                "cancelledAt=$cancelledAt, createdAt=$createdAt)"
    }

    companion object {
        /** HOLD 생성. 결제는 이 시점에 일어나지 않는다(core-domain-decisions 2-1). */
        @JvmStatic
        fun createHeld(
            memberId: Long?,
            spaceId: Long?,
            startTime: LocalDateTime,
            endTime: LocalDateTime,
            pricePerSlotSnapshot: Int,
            totalAmount: Int,
            now: LocalDateTime,
            holdExpiresAt: LocalDateTime,
        ): Reservation =
            Reservation(
                id = null,
                memberId = memberId,
                spaceId = spaceId,
                startTime = startTime,
                endTime = endTime,
                status = ReservationStatus.HELD,
                pricePerSlotSnapshot = pricePerSlotSnapshot,
                totalAmount = totalAmount,
                holdExpiresAt = holdExpiresAt,
                checkedInAt = null,
                checkedOutAt = null,
                cancelledAt = null,
                createdAt = now,
            )

        @JvmStatic
        fun builder() = ReservationBuilder()
    }
}
