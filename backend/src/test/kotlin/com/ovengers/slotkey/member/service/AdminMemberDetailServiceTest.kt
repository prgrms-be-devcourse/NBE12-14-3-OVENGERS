@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.member.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.auth.service.VerificationGuardService
import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.credit.service.CreditGrantService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.dto.response.AdminMemberCreditTransactionResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberDetailResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberReservationResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberStatusHistoryResponse
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.outbox.NotificationEventRecorder
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
internal class AdminMemberDetailServiceTest {
    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var auditLogRepository: AuditLogRepository

    @Mock
    private lateinit var auditLogService: AuditLogService

    @Mock
    private lateinit var creditGrantService: CreditGrantService

    @Mock
    private lateinit var notificationEventRecorder: NotificationEventRecorder

    @Mock
    private lateinit var verificationGuardService: VerificationGuardService

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var authSessionService: AuthSessionService

    private lateinit var clock: Clock
    private lateinit var adminMemberService: AdminMemberService

    @BeforeEach fun setUp() {
        clock =
            Clock.fixed(
                Instant.parse("2026-09-18T04:00:00Z"),
                ZoneId.of("Asia/Seoul"),
            )

        adminMemberService =
            AdminMemberService(
                memberRepository,
                auditLogRepository,
                auditLogService,
                creditGrantService,
                clock,
                verificationGuardService,
                reservationRepository,
                creditTransactionRepository,
                spaceRepository,
                authSessionService,
                notificationEventRecorder,
            )
    }

    @Test
    @DisplayName("회원 상세 조회 시 회원 정보와 전체 예약 및 정지 기록 건수를 반환한다")
    fun getMemberDetail_returnsMemberAndSummaryCounts() {
        // given
        val memberId: Long = 1L
        val member: Member = mock()

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.of(member))

        whenever(member.id).thenReturn(memberId)
        whenever(member.email).thenReturn("user@test.com")
        whenever(member.nickname).thenReturn("테스트회원")
        whenever(member.role).thenReturn(MemberRole.USER)
        whenever(member.status).thenReturn(MemberStatus.ACTIVE)
        whenever(member.balance).thenReturn(30000)
        whenever(member.createdAt)
            .thenReturn(LocalDateTime.of(2026, 9, 18, 12, 0))

        whenever(
            auditLogRepository
                .findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
                    AuditTargetType.MEMBER,
                    memberId,
                    listOf(
                        AuditAction.SUSPEND_MEMBER,
                        AuditAction.REACTIVATE_MEMBER,
                        AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                    ),
                ),
        ).thenReturn(Optional.empty())

        whenever(reservationRepository.countByMemberId(memberId))
            .thenReturn(12L)

        whenever(
            auditLogRepository.countByTargetTypeAndTargetIdAndActionIn(
                AuditTargetType.MEMBER,
                memberId,
                listOf(
                    AuditAction.SUSPEND_MEMBER,
                    AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                ),
            ),
        ).thenReturn(1L)

        // when
        val response: AdminMemberDetailResponse =
            adminMemberService.getMemberDetail(memberId)

        // then
        assertThat(response.member.memberId).isEqualTo(memberId)
        assertThat(response.member.email).isEqualTo("user@test.com")
        assertThat(response.member.nickname).isEqualTo("테스트회원")
        assertThat(response.member.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(response.member.balance).isEqualTo(30000)

        assertThat(response.totalReservationCount).isEqualTo(12L)
        assertThat(response.suspensionCount).isEqualTo(1L)
    }

    @Test
    @DisplayName("존재하지 않는 회원을 상세 조회하면 MEMBER_NOT_FOUND 예외가 발생한다")
    fun getMemberDetail_memberNotFound() {
        // given
        val memberId: Long = 999L

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.empty())

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                { adminMemberService.getMemberDetail(memberId) },
            )

        // then
        assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.MEMBER_NOT_FOUND)

        verifyNoInteractions(reservationRepository, auditLogRepository)
    }

    @Test
    @DisplayName("회원 크레딧 거래 내역을 최신순으로 조회하고 페이지 정보와 함께 반환한다")
    fun getMemberCreditTransactions_returnsPageSortedNewestFirst() {
        // given
        val memberId: Long = 1L

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.of(mock()))

        val createdAt: LocalDateTime =
            LocalDateTime.of(2026, 9, 18, 12, 0)

        val transaction: CreditTransaction = mock()

        whenever(transaction.id).thenReturn(10L)
        whenever(transaction.type)
            .thenReturn(CreditTransactionType.TOP_UP)
        whenever(transaction.amount).thenReturn(50000)
        whenever(transaction.balanceAfter).thenReturn(80000)
        whenever(transaction.createdAt).thenReturn(createdAt)

        val requestedPage: Pageable = PageRequest.of(1, 5)

        val sortedPage: Pageable =
            PageRequest.of(
                1,
                5,
                Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"),
                ),
            )

        whenever(
            creditTransactionRepository.findByMember_Id(
                memberId,
                sortedPage,
            ),
        ).thenReturn(
            PageImpl(
                listOf(transaction),
                sortedPage,
                6L,
            ),
        )

        // when
        val response: Page<AdminMemberCreditTransactionResponse> =
            adminMemberService.getMemberCreditTransactions(
                memberId,
                requestedPage,
            )

        // then
        assertThat(response.content).hasSize(1)
        assertThat(response.totalElements).isEqualTo(6L)
        assertThat(response.totalPages).isEqualTo(2)
        assertThat(response.number).isEqualTo(1)
        assertThat(response.size).isEqualTo(5)

        val transactionResponse: AdminMemberCreditTransactionResponse =
            response.content[0]

        assertThat(transactionResponse.transactionId).isEqualTo(10L)
        assertThat(transactionResponse.type)
            .isEqualTo(CreditTransactionType.TOP_UP)
        assertThat(transactionResponse.amount).isEqualTo(50000)
        assertThat(transactionResponse.balanceAfter).isEqualTo(80000)
        assertThat(transactionResponse.createdAt).isEqualTo(createdAt)

        // Repository에 전달한 페이지 및 정렬 조건 확인
        val pageableCaptor = argumentCaptor<Pageable>()

        verify(creditTransactionRepository)
            .findByMember_Id(eq(memberId), pageableCaptor.capture())

        val usedPageable: Pageable = pageableCaptor.firstValue

        assertThat(usedPageable.pageNumber).isEqualTo(1)
        assertThat(usedPageable.pageSize).isEqualTo(5)

        assertThat(usedPageable.sort.getOrderFor("createdAt"))
            .isNotNull()
            .extracting(Sort.Order::getDirection)
            .isEqualTo(Sort.Direction.DESC)

        assertThat(usedPageable.sort.getOrderFor("id"))
            .isNotNull()
            .extracting(Sort.Order::getDirection)
            .isEqualTo(Sort.Direction.DESC)
    }

    @Test
    @DisplayName("존재하지 않는 회원의 크레딧 거래 내역을 조회하면 MEMBER_NOT_FOUND 예외가 발생한다")
    fun getMemberCreditTransactions_memberNotFound() {
        // given
        val memberId: Long = 999L

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.empty())

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.getMemberCreditTransactions(
                        memberId,
                        PageRequest.of(0, 20),
                    )
                },
            )

        // then
        assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.MEMBER_NOT_FOUND)

        verifyNoInteractions(creditTransactionRepository)
    }

    @Test
    @DisplayName("회원 예약 내역 조회 시 공간 이름과 예약 상태 및 페이지 정보를 반환한다")
    fun getMemberReservations_returnsPageSortedNewestFirst() {
        // given
        val memberId: Long = 1L

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.of(mock()))

        val createdAt: LocalDateTime =
            LocalDateTime.of(2026, 10, 18, 10, 0)

        val first: Reservation = mock()
        val second: Reservation = mock()
        val space: Space = mock()

        whenever(first.getId()).thenReturn(102L)
        whenever(first.getSpaceId()).thenReturn(10L)
        whenever(first.getStatus()).thenReturn(ReservationStatus.CONFIRMED)
        whenever(first.getTotalAmount()).thenReturn(5000)
        whenever(first.getStartTime()).thenReturn(createdAt.plusHours(1))
        whenever(first.getEndTime()).thenReturn(createdAt.plusHours(2))
        whenever(first.getCreatedAt()).thenReturn(createdAt)

        whenever(second.getId()).thenReturn(101L)
        whenever(second.getSpaceId()).thenReturn(10L)
        whenever(second.getStatus()).thenReturn(ReservationStatus.CANCELLED)
        whenever(second.getTotalAmount()).thenReturn(3000)
        whenever(second.getStartTime()).thenReturn(createdAt.plusDays(1).plusHours(1))
        whenever(second.getEndTime()).thenReturn(createdAt.plusDays(1).plusHours(2))
        whenever(second.getCreatedAt()).thenReturn(createdAt.minusDays(1))

        whenever(space.id).thenReturn(10L)
        whenever(space.name).thenReturn("회의실 A")

        val requestedPage: Pageable = PageRequest.of(1, 2)

        val sortedPage: Pageable =
            PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.DESC, "id"),
            )

        whenever(
            reservationRepository.findAllByMemberId(
                memberId,
                sortedPage,
            ),
        ).thenReturn(
            PageImpl(
                listOf(first, second),
                sortedPage,
                4L,
            ),
        )

        whenever(spaceRepository.findAllById(any<List<Long>>()))
            .thenReturn(listOf(space))

        // when
        val response: Page<AdminMemberReservationResponse> =
            adminMemberService.getMemberReservations(
                memberId,
                requestedPage,
            )

        // then
        assertThat(response.content).hasSize(2)
        assertThat(response.totalElements).isEqualTo(4L)
        assertThat(response.totalPages).isEqualTo(2)
        assertThat(response.number).isEqualTo(1)
        assertThat(response.size).isEqualTo(2)

        val firstResponse: AdminMemberReservationResponse =
            response.content[0]

        assertThat(firstResponse.reservationId).isEqualTo(102L)
        assertThat(firstResponse.spaceId).isEqualTo(10L)
        assertThat(firstResponse.spaceName).isEqualTo("회의실 A")
        assertThat(firstResponse.status)
            .isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(firstResponse.totalAmount).isEqualTo(5000)
        assertThat(firstResponse.createdAt).isEqualTo(createdAt)

        val secondResponse: AdminMemberReservationResponse =
            response.content[1]

        assertThat(secondResponse.reservationId).isEqualTo(101L)
        assertThat(secondResponse.status)
            .isEqualTo(ReservationStatus.CANCELLED)
        assertThat(secondResponse.totalAmount).isEqualTo(3000)

        // Repository에 전달된 페이지 및 정렬 조건 확인
        val pageableCaptor = argumentCaptor<Pageable>()

        verify(reservationRepository)
            .findAllByMemberId(eq(memberId), pageableCaptor.capture())

        val usedPageable: Pageable = pageableCaptor.firstValue

        assertThat(usedPageable.pageNumber).isEqualTo(1)
        assertThat(usedPageable.pageSize).isEqualTo(2)

        assertThat(usedPageable.sort.getOrderFor("id"))
            .isNotNull()
            .extracting(Sort.Order::getDirection)
            .isEqualTo(Sort.Direction.DESC)

        // 동일한 공간은 한 번에 조회
        verify(spaceRepository).findAllById(listOf(10L))
    }

    @Test
    @DisplayName("예약 내역이 없는 회원은 빈 페이지를 반환한다")
    fun getMemberReservations_emptyResult() {
        // given
        val memberId: Long = 1L
        val requestedPage: Pageable = PageRequest.of(0, 20)

        val sortedPage: Pageable =
            PageRequest.of(
                0,
                20,
                Sort.by(Sort.Direction.DESC, "id"),
            )

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.of(mock()))

        whenever(
            reservationRepository.findAllByMemberId(
                memberId,
                sortedPage,
            ),
        ).thenReturn(Page.empty(sortedPage))

        whenever(spaceRepository.findAllById(emptyList()))
            .thenReturn(emptyList())

        // when
        val response: Page<AdminMemberReservationResponse> =
            adminMemberService.getMemberReservations(
                memberId,
                requestedPage,
            )

        // then
        assertThat(response.content).isEmpty()
        assertThat(response.totalElements).isZero()
        assertThat(response.totalPages).isZero()

        verify(spaceRepository).findAllById(emptyList())
    }

    @Test
    @DisplayName("존재하지 않는 회원의 예약 내역 조회 시 MEMBER_NOT_FOUND 예외가 발생한다")
    fun getMemberReservations_memberNotFound() {
        // given
        val memberId: Long = 999L

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.empty())

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.getMemberReservations(
                        memberId,
                        PageRequest.of(0, 20),
                    )
                },
            )

        // then
        assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.MEMBER_NOT_FOUND)

        verifyNoInteractions(
            reservationRepository,
            spaceRepository,
        )
    }

    @Test
    @DisplayName("회원 상태 변경 이력을 최신순으로 조회하고 페이지 정보와 함께 반환한다")
    fun getMemberStatusHistory_returnsPageSortedNewestFirst() {
        // given
        val memberId: Long = 1L

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.of(mock()))

        val firstChangedAt: LocalDateTime =
            LocalDateTime.of(2026, 10, 18, 15, 0)

        val secondChangedAt: LocalDateTime =
            LocalDateTime.of(2026, 10, 17, 12, 0)

        val first: AuditLog = mock()
        val second: AuditLog = mock()

        whenever(first.id).thenReturn(12L)
        whenever(first.action)
            .thenReturn(AuditAction.REACTIVATE_MEMBER)
        whenever(first.actorMemberId).thenReturn(100L)
        whenever(first.reason).thenReturn("정지 사유 해소")
        whenever(first.beforeValue).thenReturn("SUSPENDED")
        whenever(first.afterValue).thenReturn("ACTIVE")
        whenever(first.createdAt).thenReturn(firstChangedAt)

        whenever(second.id).thenReturn(11L)
        whenever(second.action)
            .thenReturn(AuditAction.SUSPEND_MEMBER)
        whenever(second.actorMemberId).thenReturn(100L)
        whenever(second.reason).thenReturn("운영 정책 위반")
        whenever(second.beforeValue).thenReturn("ACTIVE")
        whenever(second.afterValue).thenReturn("SUSPENDED")
        whenever(second.createdAt).thenReturn(secondChangedAt)

        val requestedPage: Pageable = PageRequest.of(1, 2)

        val sortedPage: Pageable =
            PageRequest.of(
                1,
                2,
                Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"),
                ),
            )

        val actions: List<AuditAction> =
            listOf(
                AuditAction.SUSPEND_MEMBER,
                AuditAction.REACTIVATE_MEMBER,
                AuditAction.LOCK_MEMBER_BY_VERIFICATION,
            )

        whenever(
            auditLogRepository.findByTargetTypeAndTargetIdAndActionIn(
                AuditTargetType.MEMBER,
                memberId,
                actions,
                sortedPage,
            ),
        ).thenReturn(
            PageImpl(
                listOf(first, second),
                sortedPage,
                4L,
            ),
        )

        // when
        val response: Page<AdminMemberStatusHistoryResponse> =
            adminMemberService.getMemberStatusHistory(
                memberId,
                requestedPage,
            )

        // then
        assertThat(response.content).hasSize(2)
        assertThat(response.totalElements).isEqualTo(4L)
        assertThat(response.totalPages).isEqualTo(2)
        assertThat(response.number).isEqualTo(1)
        assertThat(response.size).isEqualTo(2)

        val firstResponse: AdminMemberStatusHistoryResponse =
            response.content[0]

        assertThat(firstResponse.historyId).isEqualTo(12L)
        assertThat(firstResponse.action)
            .isEqualTo(AuditAction.REACTIVATE_MEMBER)
        assertThat(firstResponse.actorMemberId).isEqualTo(100L)
        assertThat(firstResponse.reason).isEqualTo("정지 사유 해소")
        assertThat(firstResponse.beforeValue).isEqualTo("SUSPENDED")
        assertThat(firstResponse.afterValue).isEqualTo("ACTIVE")
        assertThat(firstResponse.changedAt).isEqualTo(firstChangedAt)

        val secondResponse: AdminMemberStatusHistoryResponse =
            response.content[1]

        assertThat(secondResponse.historyId).isEqualTo(11L)
        assertThat(secondResponse.action)
            .isEqualTo(AuditAction.SUSPEND_MEMBER)
        assertThat(secondResponse.reason).isEqualTo("운영 정책 위반")
        assertThat(secondResponse.changedAt).isEqualTo(secondChangedAt)

        // Repository에 전달한 페이지 및 정렬 조건 확인
        val pageableCaptor = argumentCaptor<Pageable>()

        verify(auditLogRepository)
            .findByTargetTypeAndTargetIdAndActionIn(
                eq(AuditTargetType.MEMBER),
                eq(memberId),
                eq(actions),
                pageableCaptor.capture(),
            )

        val usedPageable: Pageable = pageableCaptor.firstValue

        assertThat(usedPageable.pageNumber).isEqualTo(1)
        assertThat(usedPageable.pageSize).isEqualTo(2)

        assertThat(usedPageable.sort.getOrderFor("createdAt"))
            .isNotNull()
            .extracting(Sort.Order::getDirection)
            .isEqualTo(Sort.Direction.DESC)

        assertThat(usedPageable.sort.getOrderFor("id"))
            .isNotNull()
            .extracting(Sort.Order::getDirection)
            .isEqualTo(Sort.Direction.DESC)
    }

    @Test
    @DisplayName("상태 변경 이력이 없는 회원은 빈 페이지를 반환한다")
    fun getMemberStatusHistory_emptyResult() {
        // given
        val memberId: Long = 1L
        val requestedPage: Pageable = PageRequest.of(0, 20)

        val sortedPage: Pageable =
            PageRequest.of(
                0,
                20,
                Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"),
                ),
            )

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.of(mock()))

        whenever(
            auditLogRepository.findByTargetTypeAndTargetIdAndActionIn(
                eq(AuditTargetType.MEMBER),
                eq(memberId),
                any<Collection<AuditAction>>(),
                eq(sortedPage),
            ),
        ).thenReturn(Page.empty(sortedPage))

        // when
        val response: Page<AdminMemberStatusHistoryResponse> =
            adminMemberService.getMemberStatusHistory(
                memberId,
                requestedPage,
            )

        // then
        assertThat(response.content).isEmpty()
        assertThat(response.totalElements).isZero()
        assertThat(response.totalPages).isZero()
    }

    @Test
    @DisplayName("존재하지 않는 회원의 상태 변경 이력 조회 시 MEMBER_NOT_FOUND 예외가 발생한다")
    fun getMemberStatusHistory_memberNotFound() {
        // given
        val memberId: Long = 999L

        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.empty())

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.getMemberStatusHistory(
                        memberId,
                        PageRequest.of(0, 20),
                    )
                },
            )

        // then
        assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.MEMBER_NOT_FOUND)

        verifyNoInteractions(auditLogRepository)
    }
}
