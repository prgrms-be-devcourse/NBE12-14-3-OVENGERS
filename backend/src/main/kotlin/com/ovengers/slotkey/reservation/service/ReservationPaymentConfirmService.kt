package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyService
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

private const val REQUEST_PATH_TEMPLATE = "/reservations/%d/pay"

/** 예약 2단계 결제 확인. 실제 금액 이동 지점에 Idempotency-Key를 적용한다. */
@Service
class ReservationPaymentConfirmService(
    private val reservationRepository: ReservationRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
    private val spaceRepository: SpaceRepository,
    private val creditService: CreditService,
    private val idempotencyService: IdempotencyService,
    private val clock: Clock,
) {
    @Transactional
    fun confirm(
        memberId: Long?,
        reservationId: Long?,
        expectedSpaceVersion: Int,
        idempotencyKey: String?,
    ): ReservationResponse {
        val requiredMemberId = requireNotNull(memberId)
        val requiredReservationId = requireNotNull(reservationId)
        if (idempotencyKey.isNullOrBlank()) {
            throw BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED)
        }
        val requestPath = REQUEST_PATH_TEMPLATE.format(requiredReservationId)

        idempotencyService
            .find(
                idempotencyKey,
                requiredMemberId,
                requestPath,
                ReservationResponse::class.java,
            )?.let { return it }

        val targetInfo =
            reservationRepository.findTargetInfoById(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        if (targetInfo.memberId != requiredMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER)
        }

        val space =
            spaceRepository.findByIdForShare(targetInfo.spaceId).orElseThrow {
                BusinessException(ErrorCode.SPACE_NOT_FOUND)
            }
        if (space.version != expectedSpaceVersion) {
            throw BusinessException(ErrorCode.SPACE_VERSION_MISMATCH)
        }

        val reservation =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        creditService.charge(requiredMemberId, requiredReservationId, reservation.totalAmount)

        val now = LocalDateTime.now(clock)
        val updated =
            reservationRepository.confirmIfHeldAndNotExpired(
                requiredReservationId,
                now,
                ReservationStatus.HELD,
                ReservationStatus.CONFIRMED,
            )
        if (updated == 0) {
            throw BusinessException(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                "HOLD가 만료되었거나 이미 처리된 예약입니다.",
            )
        }

        val confirmed =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        reservationStatusHistoryRepository.save(
            ReservationStatusHistory.of(
                requiredReservationId,
                requiredMemberId,
                ReservationStatus.HELD,
                ReservationStatus.CONFIRMED,
                null,
                now,
            ),
        )

        return ReservationResponse.from(confirmed).also { response ->
            idempotencyService.save(idempotencyKey, requiredMemberId, requestPath, 200, response)
        }
    }
}
