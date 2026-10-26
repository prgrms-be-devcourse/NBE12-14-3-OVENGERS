
package com.ovengers.slotkey.member.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.auth.service.VerificationGuardService
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.credit.service.CreditGrantService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.dto.response.AdminMemberCreditTransactionResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberDetailResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberReservationResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberStatusHistoryResponse
import com.ovengers.slotkey.member.dto.response.MemberStatusChangeResponse
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class AdminMemberService(
    private val memberRepository: MemberRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditLogService: AuditLogService,
    private val creditGrantService: CreditGrantService,
    private val clock: Clock,
    private val verificationGuardService: VerificationGuardService,
    private val reservationRepository: ReservationRepository,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val spaceRepository: SpaceRepository,
    private val authSessionService: AuthSessionService,
) {
    @Transactional(readOnly = true)
    fun getMembers(
        status: MemberStatus?,
        keyword: String?,
        pageable: Pageable,
    ): Page<AdminMemberResponse> =
        memberRepository
            .searchMembers(keyword, status, pageable)
            .map(::toAdminMemberResponse)

    @Transactional(readOnly = true)
    fun getMemberDetail(memberId: Long): AdminMemberDetailResponse {
        val member = findMember(memberId)
        val totalReservationCount = reservationRepository.countByMemberId(memberId)

        val suspensionCount =
            auditLogRepository.countByTargetTypeAndTargetIdAndActionIn(
                AuditTargetType.MEMBER,
                memberId,
                listOf(
                    AuditAction.SUSPEND_MEMBER,
                    AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                ),
            )

        return AdminMemberDetailResponse(
            toAdminMemberResponse(member),
            totalReservationCount,
            suspensionCount,
        )
    }

    @Transactional(readOnly = true)
    fun getMemberCreditTransactions(
        memberId: Long,
        pageable: Pageable,
    ): Page<AdminMemberCreditTransactionResponse> {
        findMember(memberId)

        val sortedPageable =
            PageRequest.of(
                pageable.pageNumber,
                pageable.pageSize,
                Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"),
                ),
            )

        return creditTransactionRepository
            .findByMember_Id(memberId, sortedPageable)
            .map(AdminMemberCreditTransactionResponse::from)
    }

    @Transactional(readOnly = true)
    fun getMemberReservations(
        memberId: Long,
        pageable: Pageable,
    ): Page<AdminMemberReservationResponse> {
        findMember(memberId)

        val sortedPageable =
            PageRequest.of(
                pageable.pageNumber,
                pageable.pageSize,
                Sort.by(Sort.Direction.DESC, "id"),
            )

        val reservations =
            reservationRepository.findAllByMemberId(
                memberId,
                sortedPageable,
            )

        val spaceIds =
            reservations.content
                .map { it.getSpaceId() }
                .distinct()

        val spaceNames =
            spaceRepository
                .findAllById(spaceIds)
                .associate { space ->
                    space.id to space.name
                }

        return reservations.map { reservation ->
            AdminMemberReservationResponse.from(
                reservation,
                spaceNames[reservation.getSpaceId()] ?: "삭제된 공간",
            )
        }
    }

    @Transactional(readOnly = true)
    fun getMemberStatusHistory(
        memberId: Long,
        pageable: Pageable,
    ): Page<AdminMemberStatusHistoryResponse> {
        findMember(memberId)

        val sortedPageable =
            PageRequest.of(
                pageable.pageNumber,
                pageable.pageSize,
                Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"),
                ),
            )

        return auditLogRepository
            .findByTargetTypeAndTargetIdAndActionIn(
                AuditTargetType.MEMBER,
                memberId,
                listOf(
                    AuditAction.SUSPEND_MEMBER,
                    AuditAction.REACTIVATE_MEMBER,
                    AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                ),
                sortedPageable,
            ).map(AdminMemberStatusHistoryResponse::from)
    }

    @Transactional
    fun suspend(
        memberId: Long,
        reason: String,
        adminMemberId: Long,
    ): MemberStatusChangeResponse =
        changeStatus(
            memberId,
            MemberStatus.SUSPENDED,
            reason,
            adminMemberId,
            AuditAction.SUSPEND_MEMBER,
        )

    @Transactional
    fun restore(
        memberId: Long,
        reason: String,
        adminMemberId: Long,
    ): MemberStatusChangeResponse {
        val member = findMember(memberId)

        if (member.status == MemberStatus.WITHDRAWN) {
            throw BusinessException(ErrorCode.MEMBER_WITHDRAWN)
        }

        if (member.role == MemberRole.ADMIN) {
            throw BusinessException(ErrorCode.TARGET_IS_ADMIN)
        }

        // 기존 잠금 순서 유지: Guard 잠금 후 회원 상태 변경
        verificationGuardService.unlockAllForEmail(member.email)

        return changeStatus(
            memberId,
            MemberStatus.ACTIVE,
            reason,
            adminMemberId,
            AuditAction.REACTIVATE_MEMBER,
        )
    }

    @Transactional
    fun grantCredit(
        memberId: Long,
        amount: Int,
        reason: String,
        adminMemberId: Long,
    ): AdminMemberResponse {
        if (adminMemberId == memberId) {
            throw BusinessException(ErrorCode.SELF_GRANT_NOT_ALLOWED)
        }

        val member = findMemberForUpdate(memberId)

        if (member.status == MemberStatus.WITHDRAWN) {
            throw BusinessException(ErrorCode.MEMBER_WITHDRAWN)
        }

        val beforeBalance = member.balance

        creditGrantService.grantAdminCredit(memberId, amount, reason)

        val updatedMember = findMember(memberId)

        auditLogService.log(
            adminMemberId,
            AuditAction.GRANT_CREDIT,
            AuditTargetType.MEMBER,
            memberId,
            reason,
            beforeBalance,
            updatedMember.balance,
        )

        return toAdminMemberResponse(updatedMember)
    }

    private fun changeStatus(
        memberId: Long,
        targetStatus: MemberStatus,
        reason: String,
        adminMemberId: Long,
        action: AuditAction,
    ): MemberStatusChangeResponse {
        val member = findMemberForUpdate(memberId)

        if (member.status == MemberStatus.WITHDRAWN) {
            throw BusinessException(ErrorCode.MEMBER_WITHDRAWN)
        }

        if (member.role == MemberRole.ADMIN) {
            throw BusinessException(ErrorCode.TARGET_IS_ADMIN)
        }

        val beforeStatus = member.status

        member.updateStatus(targetStatus)

        val changedAt = LocalDateTime.now(clock)

        if (targetStatus == MemberStatus.SUSPENDED) {
            authSessionService.revokeAllForMember(memberId, changedAt)
        }

        auditLogService.log(
            adminMemberId,
            action,
            AuditTargetType.MEMBER,
            memberId,
            reason,
            beforeStatus,
            targetStatus,
        )

        return MemberStatusChangeResponse.of(member, reason, changedAt)
    }

    private fun toAdminMemberResponse(member: Member): AdminMemberResponse {
        val memberId = requireNotNull(member.id)

        val lastLog =
            auditLogRepository
                .findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
                    AuditTargetType.MEMBER,
                    memberId,
                    listOf(
                        AuditAction.SUSPEND_MEMBER,
                        AuditAction.REACTIVATE_MEMBER,
                        AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                    ),
                ).orElse(null)

        return AdminMemberResponse.of(
            member,
            lastLog?.createdAt,
            lastLog?.reason,
        )
    }

    private fun findMember(memberId: Long): Member =
        memberRepository
            .findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

    private fun findMemberForUpdate(memberId: Long): Member =
        memberRepository
            .findByIdForUpdate(memberId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
}
