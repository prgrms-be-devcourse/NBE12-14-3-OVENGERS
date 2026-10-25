package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.policy.ReservationTimePolicy
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

private const val HOLD_MINUTES = 10L

/** 예약 1단계 HOLD 생성. 결제는 이 단계에서 수행하지 않는다. */
@Service
class ReservationHoldService(
    private val memberRepository: MemberRepository,
    private val spaceRepository: SpaceRepository,
    private val reservationRepository: ReservationRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
    private val pricingService: PricingService,
    private val reservationSlotService: ReservationSlotService,
    private val clock: Clock,
) {
    @Transactional
    fun createHold(
        memberId: Long?,
        spaceId: Long?,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
    ): ReservationResponse {
        val requiredMemberId = requireNotNull(memberId)
        val requiredSpaceId = requireNotNull(spaceId)
        val now = LocalDateTime.now(clock)

        val space =
            spaceRepository.findByIdForShare(requiredSpaceId).orElseThrow {
                BusinessException(ErrorCode.SPACE_NOT_FOUND)
            }
        if (space.status != SpaceStatus.ACTIVE) {
            throw BusinessException(ErrorCode.SPACE_INACTIVE)
        }

        ReservationTimePolicy.validate(
            startTime,
            endTime,
            space.openingTime ?: LocalTime.MIN,
            space.closingTime ?: LocalTime.MAX,
            now,
        )

        val slotCount = pricingService.calculateSlotCount(startTime, endTime)
        val pricePerSlotSnapshot = Math.toIntExact(space.pricePerSlot ?: 0L)
        val totalAmount = pricingService.calculateTotalAmount(pricePerSlotSnapshot, slotCount)

        val member =
            memberRepository.findByIdForUpdate(requiredMemberId).orElseThrow {
                BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
            }
        if (member.status == MemberStatus.WITHDRAWN) {
            throw BusinessException(ErrorCode.ACCOUNT_WITHDRAWN)
        }

        val reservation =
            reservationRepository.save(
                Reservation.createHeld(
                    requiredMemberId,
                    requiredSpaceId,
                    startTime,
                    endTime,
                    pricePerSlotSnapshot,
                    totalAmount,
                    now,
                    now.plusMinutes(HOLD_MINUTES),
                ),
            )

        val slotStarts = reservationSlotService.buildSlotStarts(startTime, endTime)
        reservationSlotService.secureSlots(reservation.id, requiredSpaceId, slotStarts)

        reservationStatusHistoryRepository.save(
            ReservationStatusHistory.of(
                reservation.id,
                requiredMemberId,
                null,
                ReservationStatus.HELD,
                null,
                now,
            ),
        )

        return ReservationResponse.from(reservation, space.version)
    }
}
