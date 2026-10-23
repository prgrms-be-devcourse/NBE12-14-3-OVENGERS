package com.ovengers.slotkey.reservation.adapter;

import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository;
import com.ovengers.slotkey.waitlist.service.ReservationOccupancyProvider;
import com.ovengers.slotkey.waitlist.service.ReservationRangeProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * waitlist 도메인의 ReservationOccupancyProvider 포트 구현체.
 *
 * 두 검사 모두 점유의 기준이 같다 — CONFIRMED/IN_USE(+슬롯 조회는 COMPLETED 포함)이거나
 * 아직 만료되지 않은 HELD만 점유이고, 만료된 HELD와 EXPIRED/CANCELLED/NO_SHOW는 점유가 아니다
 * (core-domain-decisions 2-3, 4-2). 신청 시점의 본인 점유 검사는 스냅샷이며,
 * 4단계 알림 시점에 같은 질의로 다시 검사하는 것이 최종 방어선이다
 * (docs/plans/waitlist-outbox/01-waitlist-rules.md §1).
 *
 * 신규 코드이므로 Lombok을 쓰지 않는다(AGENTS.md).
 */
@Component
public class WaitlistOccupancyAdapter implements ReservationOccupancyProvider, ReservationRangeProvider {

    /** 슬롯 점유 조회용. 이미 끝난 예약(COMPLETED)도 그 슬롯은 남의 것이므로 점유로 본다. */
    private static final List<ReservationStatus> SLOT_OCCUPIED_STATUSES = List.of(
            ReservationStatus.CONFIRMED,
            ReservationStatus.IN_USE,
            ReservationStatus.COMPLETED);

    /**
     * 본인 점유 검사용. 결제 완료(CONFIRMED)·이용 중(IN_USE)과, 아래 질의에서 따로 다루는
     * 만료 전 HELD(결제 대기)가 대상이다. 끝난 예약(COMPLETED)은 "자기 자리를 기다리는" 경우가
     * 아니므로 제외한다.
     */
    private static final List<ReservationStatus> OWN_OCCUPYING_STATUSES = List.of(
            ReservationStatus.CONFIRMED,
            ReservationStatus.IN_USE);

    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationRepository reservationRepository;

    public WaitlistOccupancyAdapter(ReservationSlotRepository reservationSlotRepository,
                                    ReservationRepository reservationRepository) {
        this.reservationSlotRepository = reservationSlotRepository;
        this.reservationRepository = reservationRepository;
    }

    @Override
    public boolean hasOccupiedSlot(Long spaceId, LocalDateTime startInclusive, LocalDateTime endExclusive,
                                   LocalDateTime now) {
        return !reservationSlotRepository.findOccupiedSlotStarts(
                spaceId,
                startInclusive,
                endExclusive,
                now,
                ReservationStatus.HELD,
                SLOT_OCCUPIED_STATUSES).isEmpty();
    }

    @Override
    public boolean hasOwnOccupyingReservationOverlapping(Long memberId, Long spaceId,
                                                         LocalDateTime startTime, LocalDateTime endTime,
                                                         LocalDateTime now) {
        return reservationRepository.existsOccupyingByMemberAndSpace(
                memberId, spaceId, startTime, endTime, now,
                ReservationStatus.HELD, OWN_OCCUPYING_STATUSES);
    }

    @Override
    public ReservationRange findRange(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .map(r -> new ReservationRange(r.getSpaceId(), r.getStartTime(), r.getEndTime()))
                .orElse(null);
    }
}
