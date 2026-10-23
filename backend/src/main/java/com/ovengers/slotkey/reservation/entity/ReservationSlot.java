package com.ovengers.slotkey.reservation.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 30분 단위 슬롯 점유 행. 살아있는 점유일 때만 존재하며,
 * UNIQUE(space_id, slot_start)가 동시 예약 차단의 유일한 진실이다(core-domain-decisions 4-1, core-domain-decisions 4-2).
 * 취소/노쇼/만료 시 하드 삭제한다.
 */
@Entity
@Table(name = "reservation_slot")
public class ReservationSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "slot_start", nullable = false)
    private LocalDateTime slotStart;

    public static ReservationSlot of(Long reservationId, Long spaceId, LocalDateTime slotStart) {
        return ReservationSlot.builder()
                .reservationId(reservationId)
                .spaceId(spaceId)
                .slotStart(slotStart)
                .build();
    }

    // Lombok이 생성하던 공개 API를 같은 시그니처로 명시한다.
    public static class ReservationSlotBuilder {
        private Long id;
        private Long reservationId;
        private Long spaceId;
        private LocalDateTime slotStart;

        ReservationSlotBuilder() {
        }

        /**
         * @return {@code this}.
         */
        public ReservationSlot.ReservationSlotBuilder id(final Long id) {
            this.id = id;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public ReservationSlot.ReservationSlotBuilder reservationId(final Long reservationId) {
            this.reservationId = reservationId;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public ReservationSlot.ReservationSlotBuilder spaceId(final Long spaceId) {
            this.spaceId = spaceId;
            return this;
        }

        /**
         * @return {@code this}.
         */
        public ReservationSlot.ReservationSlotBuilder slotStart(final LocalDateTime slotStart) {
            this.slotStart = slotStart;
            return this;
        }

        public ReservationSlot build() {
            return new ReservationSlot(this.id, this.reservationId, this.spaceId, this.slotStart);
        }

        @Override
        public String toString() {
            return "ReservationSlot.ReservationSlotBuilder(id=" + this.id + ", reservationId=" + this.reservationId + ", spaceId=" + this.spaceId + ", slotStart=" + this.slotStart + ")";
        }
    }

    public static ReservationSlot.ReservationSlotBuilder builder() {
        return new ReservationSlot.ReservationSlotBuilder();
    }

    public Long getId() {
        return this.id;
    }

    public Long getReservationId() {
        return this.reservationId;
    }

    public Long getSpaceId() {
        return this.spaceId;
    }

    public LocalDateTime getSlotStart() {
        return this.slotStart;
    }

    public ReservationSlot() {
    }

    public ReservationSlot(final Long id, final Long reservationId, final Long spaceId, final LocalDateTime slotStart) {
        this.id = id;
        this.reservationId = reservationId;
        this.spaceId = spaceId;
        this.slotStart = slotStart;
    }
}
