package com.ovengers.slotkey.reservation.service;


import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.dto.request.ReservationSearchCondition;
import com.ovengers.slotkey.reservation.dto.response.ReservationDetailResponse;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ovengers.slotkey.reservation.dto.response.ReservationListResponse;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;

/**
 * 본인 예약 조회(api-spec 5-3). 본인 예약만 조회할 수 있다 — 관리자도 이 경로로는
 * 타인 예약을 볼 수 없다(관리자 조회는 AdminReservationService).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "startTime")
            .and(Sort.by(Sort.Direction.DESC, "id"));
    private final SpaceRepository spaceRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationStatusHistoryRepository reservationStatusHistoryRepository;

    /** 본인 예약 목록. 공간 정보는 현재 페이지의 공간 ID로 일괄 조회한다. */
    public Page<ReservationListResponse> getMyReservations(
            Long memberId,
            ReservationSearchCondition condition,
            Pageable pageable
    ) {
        Pageable pageRequest = PageRequest.of(
                pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                DEFAULT_SORT
        );

        ReservationSearchCondition searchCondition = condition != null
                ? condition
                : new ReservationSearchCondition(null, null);

        String keyword = searchCondition.keyword();
        String normalizedKeyword = keyword != null && !keyword.isBlank()
                ? keyword.trim()
                : null;

        Page<Reservation> reservations = reservationRepository.searchMemberReservations(
                memberId,
                searchCondition.status(),
                normalizedKeyword,
                pageRequest
        );

        // 비어 있는 페이지는 공간 조회 없이 반환한다.
        // map을 사용하므로 기존 페이지의 전체 건수도 유지된다.
        if (reservations.isEmpty()) {
            return reservations.map(
                    reservation -> ReservationListResponse.from(reservation, null)
            );
        }

        List<Long> spaceIds = reservations.getContent().stream()
                .map(Reservation::getSpaceId)
                .distinct()
                .toList();

        Map<Long, Space> spacesById = spaceRepository.findAllById(spaceIds)
                .stream()
                .collect(Collectors.toMap(
                        Space::getId,
                        Function.identity()
                ));

        return reservations.map(reservation ->
                ReservationListResponse.from(
                        reservation,
                        spacesById.get(reservation.getSpaceId())
                )
        );
    }

    /** 본인 예약 상세 + 상태 전이 이력. 존재하지 않으면 404, 남의 예약이면 403. */
    public ReservationDetailResponse getMyReservation(Long memberId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!reservation.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER);
        }

        List<ReservationStatusHistory> histories =
                reservationStatusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);
        return ReservationDetailResponse.of(reservation, histories);
    }
}
