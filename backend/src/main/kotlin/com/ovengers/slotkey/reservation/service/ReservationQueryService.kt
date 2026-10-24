package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.dto.request.ReservationSearchCondition
import com.ovengers.slotkey.reservation.dto.response.ReservationDetailResponse
import com.ovengers.slotkey.reservation.dto.response.ReservationListResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val MAX_PAGE_SIZE = 100
private val DEFAULT_SORT: Sort =
    Sort
        .by(Sort.Direction.DESC, "startTime")
        .and(Sort.by(Sort.Direction.DESC, "id"))

/** 본인 예약 목록과 상세를 조회한다. */
@Service
@Transactional(readOnly = true)
class ReservationQueryService(
    private val spaceRepository: SpaceRepository,
    private val reservationRepository: ReservationRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
) {
    fun getMyReservations(
        memberId: Long?,
        condition: ReservationSearchCondition?,
        pageable: Pageable?,
    ): Page<ReservationListResponse> {
        val requiredMemberId = requireNotNull(memberId)
        val requiredPageable = requireNotNull(pageable)
        val pageRequest =
            PageRequest.of(
                requiredPageable.pageNumber,
                minOf(requiredPageable.pageSize, MAX_PAGE_SIZE),
                DEFAULT_SORT,
            )
        val searchCondition = condition ?: ReservationSearchCondition(null, null)
        val normalizedKeyword = searchCondition.keyword?.takeIf { it.isNotBlank() }?.trim()
        val reservations =
            reservationRepository.searchMemberReservations(
                requiredMemberId,
                searchCondition.status,
                normalizedKeyword,
                pageRequest,
            )

        if (reservations.isEmpty) {
            return reservations.map { reservation -> ReservationListResponse.from(reservation, null) }
        }

        val spaceIds = reservations.content.map { it.spaceId }.distinct()
        val spacesById: Map<Long, Space> = spaceRepository.findAllById(spaceIds).associateBy { it.id }
        return reservations.map { reservation ->
            ReservationListResponse.from(reservation, spacesById[reservation.spaceId])
        }
    }

    fun getMyReservation(
        memberId: Long?,
        reservationId: Long?,
    ): ReservationDetailResponse {
        val requiredMemberId = requireNotNull(memberId)
        val requiredReservationId = requireNotNull(reservationId)
        val reservation =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        if (reservation.memberId != requiredMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER)
        }
        val histories: List<ReservationStatusHistory> =
            reservationStatusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(requiredReservationId)
        return ReservationDetailResponse.of(reservation, histories)
    }
}
