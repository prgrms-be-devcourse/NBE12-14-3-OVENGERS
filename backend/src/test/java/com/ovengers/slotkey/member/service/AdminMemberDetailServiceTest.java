
package com.ovengers.slotkey.member.service;

import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditLog;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.repository.AuditLogRepository;
import com.ovengers.slotkey.audit.service.AuditLogService;
import com.ovengers.slotkey.auth.service.VerificationGuardService;
import com.ovengers.slotkey.auth.service.AuthSessionService;
import com.ovengers.slotkey.credit.entity.CreditTransaction;
import com.ovengers.slotkey.credit.entity.CreditTransactionType;
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository;
import com.ovengers.slotkey.credit.service.CreditGrantService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.dto.response.AdminMemberCreditTransactionResponse;
import com.ovengers.slotkey.member.dto.response.AdminMemberDetailResponse;
import com.ovengers.slotkey.member.dto.response.AdminMemberReservationResponse;
import com.ovengers.slotkey.member.dto.response.AdminMemberStatusHistoryResponse;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.entity.MemberRole;
import com.ovengers.slotkey.member.entity.MemberStatus;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminMemberDetailServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CreditGrantService creditGrantService;

    @Mock
    private VerificationGuardService verificationGuardService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CreditTransactionRepository creditTransactionRepository;

    @Mock
    private SpaceRepository spaceRepository;

    @Mock
    private AuthSessionService authSessionService;

    private Clock clock;
    private AdminMemberService adminMemberService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(
                Instant.parse("2026-09-18T04:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );

        adminMemberService = new AdminMemberService(
                memberRepository,
                auditLogRepository,
                auditLogService,
                creditGrantService,
                clock,
                verificationGuardService,
                reservationRepository,
                creditTransactionRepository,
                spaceRepository,
                authSessionService
        );
    }

    @Test
    @DisplayName("회원 상세 조회 시 회원 정보와 전체 예약 및 정지 기록 건수를 반환한다")
    void getMemberDetail_returnsMemberAndSummaryCounts() {
        // given
        Long memberId = 1L;
        Member member = mock(Member.class);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(member));

        given(member.getId()).willReturn(memberId);
        given(member.getEmail()).willReturn("user@test.com");
        given(member.getNickname()).willReturn("테스트회원");
        given(member.getRole()).willReturn(MemberRole.USER);
        given(member.getStatus()).willReturn(MemberStatus.ACTIVE);
        given(member.getBalance()).willReturn(30000);
        given(member.getCreatedAt())
                .willReturn(LocalDateTime.of(2026, 9, 18, 12, 0));

        given(auditLogRepository
                .findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
                        AuditTargetType.MEMBER,
                        memberId,
                        List.of(
                                AuditAction.SUSPEND_MEMBER,
                                AuditAction.REACTIVATE_MEMBER,
                                AuditAction.LOCK_MEMBER_BY_VERIFICATION
                        )
                )).willReturn(Optional.empty());

        given(reservationRepository.countByMemberId(memberId))
                .willReturn(12L);

        given(auditLogRepository.countByTargetTypeAndTargetIdAndActionIn(
                AuditTargetType.MEMBER,
                memberId,
                List.of(
                        AuditAction.SUSPEND_MEMBER,
                        AuditAction.LOCK_MEMBER_BY_VERIFICATION
                )
        )).willReturn(1L);

        // when
        AdminMemberDetailResponse response =
                adminMemberService.getMemberDetail(memberId);

        // then
        assertThat(response.member().memberId()).isEqualTo(memberId);
        assertThat(response.member().email()).isEqualTo("user@test.com");
        assertThat(response.member().nickname()).isEqualTo("테스트회원");
        assertThat(response.member().status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(response.member().balance()).isEqualTo(30000);

        assertThat(response.totalReservationCount()).isEqualTo(12L);
        assertThat(response.suspensionCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("존재하지 않는 회원을 상세 조회하면 MEMBER_NOT_FOUND 예외가 발생한다")
    void getMemberDetail_memberNotFound() {
        // given
        Long memberId = 999L;

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminMemberService.getMemberDetail(memberId)
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(reservationRepository, auditLogRepository);
    }

    @Test
    @DisplayName("회원 크레딧 거래 내역을 최신순으로 조회하고 페이지 정보와 함께 반환한다")
    void getMemberCreditTransactions_returnsPageSortedNewestFirst() {
        // given
        Long memberId = 1L;

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(mock(Member.class)));

        LocalDateTime createdAt =
                LocalDateTime.of(2026, 9, 18, 12, 0);

        CreditTransaction transaction = mock(CreditTransaction.class);

        given(transaction.getId()).willReturn(10L);
        given(transaction.getType())
                .willReturn(CreditTransactionType.TOP_UP);
        given(transaction.getAmount()).willReturn(50000);
        given(transaction.getBalanceAfter()).willReturn(80000);
        given(transaction.getCreatedAt()).willReturn(createdAt);

        Pageable requestedPage = PageRequest.of(1, 5);

        Pageable sortedPage = PageRequest.of(
                1,
                5,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        given(creditTransactionRepository.findByMember_Id(
                memberId,
                sortedPage
        )).willReturn(
                new PageImpl<>(
                        List.of(transaction),
                        sortedPage,
                        6L
                )
        );

        // when
        Page<AdminMemberCreditTransactionResponse> response =
                adminMemberService.getMemberCreditTransactions(
                        memberId,
                        requestedPage
                );

        // then
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(6L);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.getNumber()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(5);

        AdminMemberCreditTransactionResponse transactionResponse =
                response.getContent().get(0);

        assertThat(transactionResponse.transactionId()).isEqualTo(10L);
        assertThat(transactionResponse.type())
                .isEqualTo(CreditTransactionType.TOP_UP);
        assertThat(transactionResponse.amount()).isEqualTo(50000);
        assertThat(transactionResponse.balanceAfter()).isEqualTo(80000);
        assertThat(transactionResponse.createdAt()).isEqualTo(createdAt);

        // Repository에 전달한 페이지 및 정렬 조건 확인
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(creditTransactionRepository)
                .findByMember_Id(eq(memberId), pageableCaptor.capture());

        Pageable usedPageable = pageableCaptor.getValue();

        assertThat(usedPageable.getPageNumber()).isEqualTo(1);
        assertThat(usedPageable.getPageSize()).isEqualTo(5);

        assertThat(usedPageable.getSort().getOrderFor("createdAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);

        assertThat(usedPageable.getSort().getOrderFor("id"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("존재하지 않는 회원의 크레딧 거래 내역을 조회하면 MEMBER_NOT_FOUND 예외가 발생한다")
    void getMemberCreditTransactions_memberNotFound() {
        // given
        Long memberId = 999L;

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminMemberService.getMemberCreditTransactions(
                        memberId,
                        PageRequest.of(0, 20)
                )
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(creditTransactionRepository);
    }

    @Test
    @DisplayName("회원 예약 내역 조회 시 공간 이름과 예약 상태 및 페이지 정보를 반환한다")
    void getMemberReservations_returnsPageSortedNewestFirst() {
        // given
        Long memberId = 1L;

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(mock(Member.class)));

        LocalDateTime createdAt =
                LocalDateTime.of(2026, 10, 18, 10, 0);

        Reservation first = mock(Reservation.class);
        Reservation second = mock(Reservation.class);
        Space space = mock(Space.class);

        given(first.getId()).willReturn(102L);
        given(first.getSpaceId()).willReturn(10L);
        given(first.getStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(first.getTotalAmount()).willReturn(5000);
        given(first.getStartTime()).willReturn(createdAt.plusHours(1));
        given(first.getEndTime()).willReturn(createdAt.plusHours(2));
        given(first.getCreatedAt()).willReturn(createdAt);

        given(second.getId()).willReturn(101L);
        given(second.getSpaceId()).willReturn(10L);
        given(second.getStatus()).willReturn(ReservationStatus.CANCELLED);
        given(second.getTotalAmount()).willReturn(3000);
        given(second.getStartTime()).willReturn(createdAt.plusDays(1).plusHours(1));
        given(second.getEndTime()).willReturn(createdAt.plusDays(1).plusHours(2));
        given(second.getCreatedAt()).willReturn(createdAt.minusDays(1));

        given(space.getId()).willReturn(10L);
        given(space.getName()).willReturn("회의실 A");

        Pageable requestedPage = PageRequest.of(1, 2);

        Pageable sortedPage = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.DESC, "id")
        );

        given(reservationRepository.findAllByMemberId(
                memberId,
                sortedPage
        )).willReturn(
                new PageImpl<>(
                        List.of(first, second),
                        sortedPage,
                        4L
                )
        );

        given(spaceRepository.findAllById(List.of(10L)))
                .willReturn(List.of(space));

        // when
        Page<AdminMemberReservationResponse> response =
                adminMemberService.getMemberReservations(
                        memberId,
                        requestedPage
                );

        // then
        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(4L);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.getNumber()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(2);

        AdminMemberReservationResponse firstResponse =
                response.getContent().get(0);

        assertThat(firstResponse.reservationId()).isEqualTo(102L);
        assertThat(firstResponse.spaceId()).isEqualTo(10L);
        assertThat(firstResponse.spaceName()).isEqualTo("회의실 A");
        assertThat(firstResponse.status())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(firstResponse.totalAmount()).isEqualTo(5000);
        assertThat(firstResponse.createdAt()).isEqualTo(createdAt);

        AdminMemberReservationResponse secondResponse =
                response.getContent().get(1);

        assertThat(secondResponse.reservationId()).isEqualTo(101L);
        assertThat(secondResponse.status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(secondResponse.totalAmount()).isEqualTo(3000);

        // Repository에 전달된 페이지 및 정렬 조건 확인
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(reservationRepository)
                .findAllByMemberId(eq(memberId), pageableCaptor.capture());

        Pageable usedPageable = pageableCaptor.getValue();

        assertThat(usedPageable.getPageNumber()).isEqualTo(1);
        assertThat(usedPageable.getPageSize()).isEqualTo(2);

        assertThat(usedPageable.getSort().getOrderFor("id"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);

        // 동일한 공간은 한 번에 조회
        verify(spaceRepository).findAllById(List.of(10L));
    }

    @Test
    @DisplayName("예약 내역이 없는 회원은 빈 페이지를 반환한다")
    void getMemberReservations_emptyResult() {
        // given
        Long memberId = 1L;
        Pageable requestedPage = PageRequest.of(0, 20);

        Pageable sortedPage = PageRequest.of(
                0,
                20,
                Sort.by(Sort.Direction.DESC, "id")
        );

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(mock(Member.class)));

        given(reservationRepository.findAllByMemberId(
                memberId,
                sortedPage
        )).willReturn(Page.empty(sortedPage));

        given(spaceRepository.findAllById(List.of()))
                .willReturn(List.of());

        // when
        Page<AdminMemberReservationResponse> response =
                adminMemberService.getMemberReservations(
                        memberId,
                        requestedPage
                );

        // then
        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();

        verify(spaceRepository).findAllById(List.of());
    }

    @Test
    @DisplayName("존재하지 않는 회원의 예약 내역 조회 시 MEMBER_NOT_FOUND 예외가 발생한다")
    void getMemberReservations_memberNotFound() {
        // given
        Long memberId = 999L;

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminMemberService.getMemberReservations(
                        memberId,
                        PageRequest.of(0, 20)
                )
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(
                reservationRepository,
                spaceRepository
        );
    }

    @Test
    @DisplayName("회원 상태 변경 이력을 최신순으로 조회하고 페이지 정보와 함께 반환한다")
    void getMemberStatusHistory_returnsPageSortedNewestFirst() {
        // given
        Long memberId = 1L;

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(mock(Member.class)));

        LocalDateTime firstChangedAt =
                LocalDateTime.of(2026, 10, 18, 15, 0);

        LocalDateTime secondChangedAt =
                LocalDateTime.of(2026, 10, 17, 12, 0);

        AuditLog first = mock(AuditLog.class);
        AuditLog second = mock(AuditLog.class);

        given(first.getId()).willReturn(12L);
        given(first.getAction())
                .willReturn(AuditAction.REACTIVATE_MEMBER);
        given(first.getActorMemberId()).willReturn(100L);
        given(first.getReason()).willReturn("정지 사유 해소");
        given(first.getBeforeValue()).willReturn("SUSPENDED");
        given(first.getAfterValue()).willReturn("ACTIVE");
        given(first.getCreatedAt()).willReturn(firstChangedAt);

        given(second.getId()).willReturn(11L);
        given(second.getAction())
                .willReturn(AuditAction.SUSPEND_MEMBER);
        given(second.getActorMemberId()).willReturn(100L);
        given(second.getReason()).willReturn("운영 정책 위반");
        given(second.getBeforeValue()).willReturn("ACTIVE");
        given(second.getAfterValue()).willReturn("SUSPENDED");
        given(second.getCreatedAt()).willReturn(secondChangedAt);

        Pageable requestedPage = PageRequest.of(1, 2);

        Pageable sortedPage = PageRequest.of(
                1,
                2,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        List<AuditAction> actions = List.of(
                AuditAction.SUSPEND_MEMBER,
                AuditAction.REACTIVATE_MEMBER,
                AuditAction.LOCK_MEMBER_BY_VERIFICATION
        );

        given(auditLogRepository.findByTargetTypeAndTargetIdAndActionIn(
                AuditTargetType.MEMBER,
                memberId,
                actions,
                sortedPage
        )).willReturn(
                new PageImpl<>(
                        List.of(first, second),
                        sortedPage,
                        4L
                )
        );

        // when
        Page<AdminMemberStatusHistoryResponse> response =
                adminMemberService.getMemberStatusHistory(
                        memberId,
                        requestedPage
                );

        // then
        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(4L);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.getNumber()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(2);

        AdminMemberStatusHistoryResponse firstResponse =
                response.getContent().get(0);

        assertThat(firstResponse.historyId()).isEqualTo(12L);
        assertThat(firstResponse.action())
                .isEqualTo(AuditAction.REACTIVATE_MEMBER);
        assertThat(firstResponse.actorMemberId()).isEqualTo(100L);
        assertThat(firstResponse.reason()).isEqualTo("정지 사유 해소");
        assertThat(firstResponse.beforeValue()).isEqualTo("SUSPENDED");
        assertThat(firstResponse.afterValue()).isEqualTo("ACTIVE");
        assertThat(firstResponse.changedAt()).isEqualTo(firstChangedAt);

        AdminMemberStatusHistoryResponse secondResponse =
                response.getContent().get(1);

        assertThat(secondResponse.historyId()).isEqualTo(11L);
        assertThat(secondResponse.action())
                .isEqualTo(AuditAction.SUSPEND_MEMBER);
        assertThat(secondResponse.reason()).isEqualTo("운영 정책 위반");
        assertThat(secondResponse.changedAt()).isEqualTo(secondChangedAt);

        // Repository에 전달한 페이지 및 정렬 조건 확인
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(auditLogRepository)
                .findByTargetTypeAndTargetIdAndActionIn(
                        eq(AuditTargetType.MEMBER),
                        eq(memberId),
                        eq(actions),
                        pageableCaptor.capture()
                );

        Pageable usedPageable = pageableCaptor.getValue();

        assertThat(usedPageable.getPageNumber()).isEqualTo(1);
        assertThat(usedPageable.getPageSize()).isEqualTo(2);

        assertThat(usedPageable.getSort().getOrderFor("createdAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);

        assertThat(usedPageable.getSort().getOrderFor("id"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("상태 변경 이력이 없는 회원은 빈 페이지를 반환한다")
    void getMemberStatusHistory_emptyResult() {
        // given
        Long memberId = 1L;
        Pageable requestedPage = PageRequest.of(0, 20);

        Pageable sortedPage = PageRequest.of(
                0,
                20,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(mock(Member.class)));

        given(auditLogRepository.findByTargetTypeAndTargetIdAndActionIn(
                eq(AuditTargetType.MEMBER),
                eq(memberId),
                anyCollection(),
                eq(sortedPage)
        )).willReturn(Page.empty(sortedPage));

        // when
        Page<AdminMemberStatusHistoryResponse> response =
                adminMemberService.getMemberStatusHistory(
                        memberId,
                        requestedPage
                );

        // then
        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 회원의 상태 변경 이력 조회 시 MEMBER_NOT_FOUND 예외가 발생한다")
    void getMemberStatusHistory_memberNotFound() {
        // given
        Long memberId = 999L;

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminMemberService.getMemberStatusHistory(
                        memberId,
                        PageRequest.of(0, 20)
                )
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(auditLogRepository);
    }
}
