package com.ovengers.slotkey.reservation.entity;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 예약. 상태 전이는 이 엔티티의 메서드로만 수행한다(docs/core-domain-decisions.md §3).
 * 동시성 하의 최종 판정은 서비스 레이어의 조건부 UPDATE(레포지토리)가 담당하며,
 * 이 클래스의 검증은 "문지기를 통과한 뒤에도 상태 기계가 스스로를 지킨다"는 방어선이다.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "price_per_slot_snapshot", nullable = false)
    private Integer pricePerSlotSnapshot;

    @Column(name = "total_amount", nullable = false)
    private Integer totalAmount;

    @Column(name = "hold_expires_at")
    private LocalDateTime holdExpiresAt;

    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    @Column(name = "checked_out_at")
    private LocalDateTime checkedOutAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** HOLD 생성. 결제는 이 시점에 일어나지 않는다(core-domain-decisions 2-1). */
    public static Reservation createHeld(
            Long memberId,
            Long spaceId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int pricePerSlotSnapshot,
            int totalAmount,
            LocalDateTime now,
            LocalDateTime holdExpiresAt
    ) {
        return Reservation.builder()
                .memberId(memberId)
                .spaceId(spaceId)
                .startTime(startTime)
                .endTime(endTime)
                .status(ReservationStatus.HELD)
                .pricePerSlotSnapshot(pricePerSlotSnapshot)
                .totalAmount(totalAmount)
                .holdExpiresAt(holdExpiresAt)
                .createdAt(now)
                .build();
    }

    /** HELD -> CONFIRMED. 결제(크레딧 차감) 성공 시. now가 holdExpiresAt 이후면 만료로 취급한다. */
    public void confirm(LocalDateTime now) {
        requireStatus(ReservationStatus.HELD);
        if (holdExpiresAt == null || !now.isBefore(holdExpiresAt)) {
            throw new BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "HOLD가 만료되었습니다.");
        }
        this.status = ReservationStatus.CONFIRMED;
    }

    /** HELD -> EXPIRED. 결제 확인 없이 hold_expires_at을 지난 경우. */
    public void expire() {
        requireStatus(ReservationStatus.HELD);
        this.status = ReservationStatus.EXPIRED;
    }

    /** CONFIRMED -> CANCELLED. 시작 전에만 가능하다. */
    public void cancel(LocalDateTime now) {
        requireStatus(ReservationStatus.CONFIRMED);
        if (!now.isBefore(startTime)) {
            throw new BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "시작 이후에는 취소할 수 없습니다. 체크아웃을 이용하세요.");
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
    }

    /** CONFIRMED -> IN_USE. 최초 체크인 성공(출입 검증의 부수 효과). */
    public void checkIn(LocalDateTime now) {
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.IN_USE;
        this.checkedInAt = now;
    }

    /** IN_USE -> COMPLETED. 명시적 체크아웃 또는 종료 시각 경과(자동 퇴실)에서 호출한다.
     *  자동 퇴실 시 checkedOutAt에는 배치 실행 시각이 아니라 end_time을 넣는다(core-domain-decisions 3-4). */
    public void checkOut(LocalDateTime checkedOutAt) {
        requireStatus(ReservationStatus.IN_USE);
        this.status = ReservationStatus.COMPLETED;
        this.checkedOutAt = checkedOutAt;
    }

    /** CONFIRMED -> NO_SHOW. 체크인 창(시작 + 15분)을 놓친 경우(배치). */
    public void markNoShow() {
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.NO_SHOW;
    }

    /** CONFIRMED/IN_USE 상태에서 종료 시각을 늘린다. 기존 슬롯은 건드리지 않는다(§7). */
    public void extend(LocalDateTime expectedCurrentEndTime, LocalDateTime newEndTime, int additionalAmount) {
        if (status != ReservationStatus.CONFIRMED && status != ReservationStatus.IN_USE) {
            throw new BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "연장할 수 없는 상태입니다.");
        }
        if (!this.endTime.isEqual(expectedCurrentEndTime)) {
            throw new BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "예약 정보가 변경되었습니다. 다시 시도해주세요.");
        }
        if (!newEndTime.isAfter(this.endTime)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "연장 시각은 기존 종료 시각보다 늦어야 합니다.");
        }
        this.endTime = newEndTime;
        this.totalAmount += additionalAmount;
    }

    private void requireStatus(ReservationStatus expected) {
        if (this.status != expected) {
            throw new BusinessException(
                    ErrorCode.RESERVATION_STATE_CONFLICT,
                    "허용되지 않는 상태 전이입니다. (현재: " + this.status + ", 필요: " + expected + ")"
            );
        }
    }

    /**
     * 종료된 상태인지 확인 (COMPLETED/EXPIRED/NO_SHOW/CANCELLED).
     */
    public boolean isTerminalState() {
        return this.status == ReservationStatus.COMPLETED
                || this.status == ReservationStatus.EXPIRED
                || this.status == ReservationStatus.NO_SHOW
                || this.status == ReservationStatus.CANCELLED;
    }

    // Lombok이 생성하던 공개 API를 같은 시그니처로 명시한다.
    public static class ReservationBuilder {
        private Long id;
        private Long memberId;
        private Long spaceId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private ReservationStatus status;
        private Integer pricePerSlotSnapshot;
        private Integer totalAmount;
        private LocalDateTime holdExpiresAt;
        private LocalDateTime checkedInAt;
        private LocalDateTime checkedOutAt;
        private LocalDateTime cancelledAt;
        private LocalDateTime createdAt;

        ReservationBuilder() {
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder id(final Long id) {
            this.id = id;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder memberId(final Long memberId) {
            this.memberId = memberId;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder spaceId(final Long spaceId) {
            this.spaceId = spaceId;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder startTime(final LocalDateTime startTime) {
            this.startTime = startTime;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder endTime(final LocalDateTime endTime) {
            this.endTime = endTime;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder status(final ReservationStatus status) {
            this.status = status;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder pricePerSlotSnapshot(final Integer pricePerSlotSnapshot) {
            this.pricePerSlotSnapshot = pricePerSlotSnapshot;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder totalAmount(final Integer totalAmount) {
            this.totalAmount = totalAmount;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder holdExpiresAt(final LocalDateTime holdExpiresAt) {
            this.holdExpiresAt = holdExpiresAt;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder checkedInAt(final LocalDateTime checkedInAt) {
            this.checkedInAt = checkedInAt;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder checkedOutAt(final LocalDateTime checkedOutAt) {
            this.checkedOutAt = checkedOutAt;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder cancelledAt(final LocalDateTime cancelledAt) {
            this.cancelledAt = cancelledAt;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public Reservation.ReservationBuilder createdAt(final LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Reservation build() {
            return new Reservation(this.id, this.memberId, this.spaceId, this.startTime, this.endTime, this.status, this.pricePerSlotSnapshot, this.totalAmount, this.holdExpiresAt, this.checkedInAt, this.checkedOutAt, this.cancelledAt, this.createdAt);
        }

        @Override
        public String toString() {
            return "Reservation.ReservationBuilder(id=" + this.id + ", memberId=" + this.memberId + ", spaceId=" + this.spaceId + ", startTime=" + this.startTime + ", endTime=" + this.endTime + ", status=" + this.status + ", pricePerSlotSnapshot=" + this.pricePerSlotSnapshot + ", totalAmount=" + this.totalAmount + ", holdExpiresAt=" + this.holdExpiresAt + ", checkedInAt=" + this.checkedInAt + ", checkedOutAt=" + this.checkedOutAt + ", cancelledAt=" + this.cancelledAt + ", createdAt=" + this.createdAt + ")";
        }
    }

    public static Reservation.ReservationBuilder builder() {
        return new Reservation.ReservationBuilder();
    }

    public Long getId() {
        return this.id;
    }

    public Long getMemberId() {
        return this.memberId;
    }

    public Long getSpaceId() {
        return this.spaceId;
    }

    public LocalDateTime getStartTime() {
        return this.startTime;
    }

    public LocalDateTime getEndTime() {
        return this.endTime;
    }

    public ReservationStatus getStatus() {
        return this.status;
    }

    public Integer getPricePerSlotSnapshot() {
        return this.pricePerSlotSnapshot;
    }

    public Integer getTotalAmount() {
        return this.totalAmount;
    }

    public LocalDateTime getHoldExpiresAt() {
        return this.holdExpiresAt;
    }

    public LocalDateTime getCheckedInAt() {
        return this.checkedInAt;
    }

    public LocalDateTime getCheckedOutAt() {
        return this.checkedOutAt;
    }

    public LocalDateTime getCancelledAt() {
        return this.cancelledAt;
    }

    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }

    public Reservation() {
    }

    public Reservation(final Long id, final Long memberId, final Long spaceId, final LocalDateTime startTime, final LocalDateTime endTime, final ReservationStatus status, final Integer pricePerSlotSnapshot, final Integer totalAmount, final LocalDateTime holdExpiresAt, final LocalDateTime checkedInAt, final LocalDateTime checkedOutAt, final LocalDateTime cancelledAt, final LocalDateTime createdAt) {
        this.id = id;
        this.memberId = memberId;
        this.spaceId = spaceId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.pricePerSlotSnapshot = pricePerSlotSnapshot;
        this.totalAmount = totalAmount;
        this.holdExpiresAt = holdExpiresAt;
        this.checkedInAt = checkedInAt;
        this.checkedOutAt = checkedOutAt;
        this.cancelledAt = cancelledAt;
        this.createdAt = createdAt;
    }
}
