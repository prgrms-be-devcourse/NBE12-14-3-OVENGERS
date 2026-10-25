package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.policy.ReservationTimePolicy
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

/** 기존 슬롯을 유지한 채 뒤쪽 슬롯을 추가하는 예약 연장 서비스. */
@Service
class ReservationExtendService(
    private val reservationRepository: ReservationRepository,
    private val spaceRepository: SpaceRepository,
    private val memberRepository: MemberRepository,
    private val reservationSlotService: ReservationSlotService,
    private val pricingService: PricingService,
    private val creditService: CreditService,
    private val clock: Clock,
) {
    @Transactional
    fun extend(
        memberId: Long?,
        reservationId: Long?,
        expectedEndTime: LocalDateTime,
        newEndTime: LocalDateTime,
    ): ReservationResponse {
        val requiredMemberId = requireNotNull(memberId)
        val requiredReservationId = requireNotNull(reservationId)
        val spaceId =
            reservationRepository.findSpaceIdById(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        val space =
            spaceRepository.findByIdForShare(spaceId).orElseThrow {
                BusinessException(ErrorCode.SPACE_NOT_FOUND)
            }
        val reservation =
            reservationRepository.findByIdForUpdate(requiredReservationId).orElseThrow {
                BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
            }

        if (reservation.memberId != requiredMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER)
        }
        if (reservation.status != ReservationStatus.CONFIRMED && reservation.status != ReservationStatus.IN_USE) {
            throw BusinessException(ErrorCode.RESERVATION_EXTEND_NOT_ALLOWED, "연장할 수 없는 상태입니다.")
        }
        val now = LocalDateTime.now(clock)
        if (!now.isBefore(reservation.endTime)) {
            throw BusinessException(ErrorCode.RESERVATION_EXTEND_NOT_ALLOWED, "이미 종료된 예약은 연장할 수 없습니다.")
        }
        if (!reservation.endTime.isEqual(expectedEndTime)) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "예약 정보가 변경되었습니다. 다시 시도해주세요.")
        }

        ReservationTimePolicy.validateExtension(
            reservation.endTime,
            newEndTime,
            space.closingTime ?: LocalTime.MAX,
        )

        val member =
            memberRepository.findByIdForUpdate(requiredMemberId).orElseThrow {
                BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
            }
        if (member.status == MemberStatus.WITHDRAWN) {
            throw BusinessException(ErrorCode.ACCOUNT_WITHDRAWN)
        }

        val additionalSlotStarts = reservationSlotService.buildSlotStarts(reservation.endTime, newEndTime)
        reservationSlotService.secureSlots(requiredReservationId, reservation.spaceId, additionalSlotStarts)

        val additionalAmount =
            pricingService.calculateTotalAmount(reservation.pricePerSlotSnapshot, additionalSlotStarts.size)
        val newTotalAmount = reservation.totalAmount + additionalAmount
        creditService.charge(requiredMemberId, requiredReservationId, additionalAmount)

        val updated =
            reservationRepository.extendIfEndTimeMatches(
                requiredReservationId,
                expectedEndTime,
                newEndTime,
                newTotalAmount,
                ReservationStatus.CONFIRMED,
                ReservationStatus.IN_USE,
            )
        if (updated == 0) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "연장에 실패했습니다. 다시 시도해주세요.")
        }

        val extended =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        return ReservationResponse.from(extended)
    }
}
