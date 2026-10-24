package com.ovengers.slotkey.waitlist.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.policy.ReservationTimePolicy
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse
import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import com.ovengers.slotkey.waitlist.repository.WaitlistEntryRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 대기 신청·취소·내 목록. 활성 대기는 `status = WAITING AND startTime > now`로 판정한다.
 * 점유 검사와 INSERT 사이 경합은 수용하고 알림 시점에 같은 조건을 다시 검사한다.
 */
@Service
@Transactional(readOnly = true)
class WaitlistService(
    private val waitlistEntryRepository: WaitlistEntryRepository,
    private val spaceRepository: SpaceRepository,
    private val memberRepository: MemberRepository,
    private val reservationOccupancyProvider: ReservationOccupancyProvider,
    private val clock: Clock,
) {
    @Transactional
    fun apply(
        memberId: Long?,
        spaceId: Long?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
    ): WaitlistEntryResponse {
        val requiredMemberId = requireNotNull(memberId)
        val requiredSpaceId = requireNotNull(spaceId)
        val requiredStartTime = requireNotNull(startTime)
        val requiredEndTime = requireNotNull(endTime)
        val now = LocalDateTime.now(clock)
        val space =
            spaceRepository.findById(requiredSpaceId).orElseThrow {
                BusinessException(ErrorCode.SPACE_NOT_FOUND)
            }
        if (space.status != SpaceStatus.ACTIVE) {
            throw BusinessException(ErrorCode.SPACE_INACTIVE)
        }

        ReservationTimePolicy.validate(
            requiredStartTime,
            requiredEndTime,
            space.openingTime,
            space.closingTime,
            now,
        )
        validateMemberCanApply(requiredMemberId)

        if (!reservationOccupancyProvider.hasOccupiedSlot(requiredSpaceId, requiredStartTime, requiredEndTime, now)) {
            throw BusinessException(ErrorCode.WAITLIST_NOT_NEEDED)
        }
        if (
            reservationOccupancyProvider.hasOwnOccupyingReservationOverlapping(
                requiredMemberId,
                requiredSpaceId,
                requiredStartTime,
                requiredEndTime,
                now,
            )
        ) {
            throw BusinessException(ErrorCode.WAITLIST_ALREADY_HOLDING)
        }
        if (
            waitlistEntryRepository.countByMemberIdAndStatusAndStartTimeAfter(
                requiredMemberId,
                WaitlistStatus.WAITING,
                now,
            ) >= MAX_ACTIVE_ENTRIES
        ) {
            throw BusinessException(ErrorCode.WAITLIST_LIMIT_EXCEEDED)
        }

        val entry = WaitlistEntry.waiting(requiredMemberId, requiredSpaceId, requiredStartTime, requiredEndTime, now)
        val saved =
            try {
                waitlistEntryRepository.saveAndFlush(entry)
            } catch (exception: DataIntegrityViolationException) {
                if (!isActiveUniqueViolation(exception)) throw exception
                throw BusinessException(ErrorCode.WAITLIST_DUPLICATE)
            }
        return WaitlistEntryResponse.of(saved, positionOf(saved, now))
    }

    /** 이미 취소된 대기에 대한 재요청은 그대로 성공으로 둔다. */
    @Transactional
    fun cancel(
        memberId: Long?,
        entryId: Long?,
    ) {
        val requiredMemberId = requireNotNull(memberId)
        val entry =
            waitlistEntryRepository.findById(requireNotNull(entryId)).orElseThrow {
                BusinessException(ErrorCode.WAITLIST_NOT_FOUND)
            }
        if (entry.memberId != requiredMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER)
        }
        entry.cancel()
    }

    /** 활성 대기만 신청 순서대로 반환한다. */
    fun getMyEntries(memberId: Long?): List<WaitlistEntryResponse> {
        val now = LocalDateTime.now(clock)
        return waitlistEntryRepository
            .findAllByMemberIdAndStatusAndStartTimeAfterOrderByIdAsc(
                requireNotNull(memberId),
                WaitlistStatus.WAITING,
                now,
            ).map { entry -> WaitlistEntryResponse.of(entry, positionOf(entry, now)) }
    }

    private fun validateMemberCanApply(memberId: Long) {
        val member =
            memberRepository.findById(memberId).orElseThrow {
                BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
            }
        when (member.status) {
            MemberStatus.WITHDRAWN -> throw BusinessException(ErrorCode.ACCOUNT_WITHDRAWN)
            MemberStatus.ACTIVE -> Unit
            else -> throw BusinessException(ErrorCode.ACCOUNT_INACTIVE)
        }
    }

    private fun positionOf(
        entry: WaitlistEntry,
        now: LocalDateTime,
    ): Long =
        waitlistEntryRepository.countOverlappingAhead(
            entry.spaceId,
            entry.startTime,
            entry.endTime,
            requireNotNull(entry.id),
            WaitlistStatus.WAITING,
            now,
        ) + 1

    companion object {
        private const val MAX_ACTIVE_ENTRIES = 3
        private const val ACTIVE_UNIQUE_CONSTRAINT = "uk_waitlist_entry_member_space_range_active"

        @JvmStatic
        fun isActiveUniqueViolation(exception: DataIntegrityViolationException): Boolean =
            generateSequence<Throwable>(exception) { it.cause }
                .mapNotNull { cause -> cause.message }
                .any { message -> message.contains(ACTIVE_UNIQUE_CONSTRAINT) }
    }
}
