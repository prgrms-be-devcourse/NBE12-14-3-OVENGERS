package com.ovengers.slotkey.waitlist.service;

import java.time.LocalDateTime;

/**
 * 슬롯 해제 이벤트의 reservationId로 해제된 공간·구간을 묻는 포트. payload에는 구간이 없으므로(outbox-kafka.md)
 * 컨슈머가 예약 행에서 읽는다. 취소·만료 뒤에도 예약 행은 남는다. 구현은 reservation 도메인의 어댑터가 제공한다.
 */
public interface ReservationRangeProvider {

    /** 예약 행이 없으면 null. */
    ReservationRange findRange(Long reservationId);

    record ReservationRange(Long spaceId, LocalDateTime startTime, LocalDateTime endTime) {
    }
}
