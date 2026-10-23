package com.ovengers.slotkey.reservation.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 예약 상태 전이 이력. 성공한 전이만 기록한다 — 조건부 UPDATE의 영향 행이 1일 때만
 * 같은 트랜잭션에서 INSERT한다(core-domain-decisions 3-1).
 */
@Entity
@Table(name = "reservation_status_history")
public class ReservationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "changed_by_member_id")
    private Long changedByMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ReservationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ReservationStatus toStatus;

    @Column(length = 255)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    public ReservationStatusHistory() {
    }

    public ReservationStatusHistory(
            Long id,
            Long reservationId,
            Long changedByMemberId,
            ReservationStatus fromStatus,
            ReservationStatus toStatus,
            String reason,
            LocalDateTime changedAt
    ) {
        this.id = id;
        this.reservationId = reservationId;
        this.changedByMemberId = changedByMemberId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedAt = changedAt;
    }

    // 기존 정적 팩토리 메서드 유지
    public static ReservationStatusHistory of(
            Long reservationId,
            Long changedByMemberId,
            ReservationStatus fromStatus,
            ReservationStatus toStatus,
            String reason,
            LocalDateTime changedAt
    ) {
        return ReservationStatusHistory.builder()
                .reservationId(reservationId)
                .changedByMemberId(changedByMemberId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .reason(reason)
                .changedAt(changedAt)
                .build();
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getChangedByMemberId() {
        return changedByMemberId;
    }

    public ReservationStatus getFromStatus() {
        return fromStatus;
    }

    public ReservationStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public static ReservationStatusHistoryBuilder builder() {
        return new ReservationStatusHistoryBuilder();
    }

    public static class ReservationStatusHistoryBuilder {

        private Long id;
        private Long reservationId;
        private Long changedByMemberId;
        private ReservationStatus fromStatus;
        private ReservationStatus toStatus;
        private String reason;
        private LocalDateTime changedAt;

        ReservationStatusHistoryBuilder() {
        }

        public ReservationStatusHistoryBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ReservationStatusHistoryBuilder reservationId(Long reservationId) {
            this.reservationId = reservationId;
            return this;
        }

        public ReservationStatusHistoryBuilder changedByMemberId(Long changedByMemberId) {
            this.changedByMemberId = changedByMemberId;
            return this;
        }

        public ReservationStatusHistoryBuilder fromStatus(ReservationStatus fromStatus) {
            this.fromStatus = fromStatus;
            return this;
        }

        public ReservationStatusHistoryBuilder toStatus(ReservationStatus toStatus) {
            this.toStatus = toStatus;
            return this;
        }

        public ReservationStatusHistoryBuilder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public ReservationStatusHistoryBuilder changedAt(LocalDateTime changedAt) {
            this.changedAt = changedAt;
            return this;
        }

        public ReservationStatusHistory build() {
            return new ReservationStatusHistory(
                    id,
                    reservationId,
                    changedByMemberId,
                    fromStatus,
                    toStatus,
                    reason,
                    changedAt
            );
        }

        @Override
        public String toString() {
            return "ReservationStatusHistory.ReservationStatusHistoryBuilder("
                    + "id=" + id
                    + ", reservationId=" + reservationId
                    + ", changedByMemberId=" + changedByMemberId
                    + ", fromStatus=" + fromStatus
                    + ", toStatus=" + toStatus
                    + ", reason=" + reason
                    + ", changedAt=" + changedAt
                    + ")";
        }
    }
}