package com.ovengers.slotkey.reservation.service
import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessLog
import com.ovengers.slotkey.access.service.DoorAccessLogService
import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.outbox.SlotReleasedRecorder
import com.ovengers.slotkey.reservation.dto.request.AdminReservationSearchCondition
import com.ovengers.slotkey.reservation.dto.response.AdminReservationDetailResponse
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.captureNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AdminReservationServiceTest {
    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val RESERVATION_ID: Long = 10L
        private val MEMBER_ID: Long = 1L
        private val SPACE_ID: Long = 5L
        private val ADMIN_MEMBER_ID: Long = 99L
        private val REASON: String = "고객 요청에 의한 강제 취소"
    }

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Mock
    private lateinit var statusHistoryRepository: ReservationStatusHistoryRepository

    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var auditLogService: AuditLogService

    @Mock
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    @Mock
    private lateinit var doorAccessLogService: DoorAccessLogService

    @Mock
    private lateinit var creditService: CreditService

    @Mock
    private lateinit var slotReleasedRecorder: SlotReleasedRecorder
    private lateinit var adminReservationService: AdminReservationService
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 17, 12, 0)
    private lateinit var startTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        val clock: Clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE)
        adminReservationService =
            AdminReservationService(
                reservationRepository,
                reservationSlotRepository,
                statusHistoryRepository,
                memberRepository,
                spaceRepository,
                auditLogService,
                doorAccessTokenService,
                doorAccessLogService,
                creditService,
                clock,
                slotReleasedRecorder,
            )
        startTime = LocalDateTime.of(2026, 9, 18, 14, 0)
    }

    private fun confirmedReservation(): Reservation = reservationWithStatus(ReservationStatus.CONFIRMED, 10000)

    private fun reservationWithStatus(
        status: ReservationStatus,
        totalAmount: Int,
    ): Reservation =
        Reservation
            .builder()
            .id(RESERVATION_ID)
            .memberId(MEMBER_ID)
            .spaceId(SPACE_ID)
            .startTime(startTime)
            .endTime(startTime.plusHours(1))
            .status(status)
            .pricePerSlotSnapshot(5000)
            .totalAmount(totalAmount)
            .createdAt(startTime.minusDays(1))
            .build()

    /** 응답 조립에 쓰이는 회원 이메일·공간 이름 조회를 준비한다. */

    private fun stubMemberAndSpace() {
        given(memberRepository.findById(MEMBER_ID))
            .willReturn(Optional.of(Member("user@slotkey.test", "회원1")))
        given(spaceRepository.findById(SPACE_ID)).willReturn(
            Optional.of(
                Space.builder().name("공간1").build(),
            ),
        )
    }

    // ---------- getReservationDetail ----------

    @Test
    @DisplayName("존재하지 않는 예약을 상세 조회하면 RESERVATION_NOT_FOUND 예외가 발생한다")
    fun getReservationDetail_reservationNotFound_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty())

        assertThatThrownBy({ adminReservationService.getReservationDetail(RESERVATION_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND)
    }

    @Test
    @DisplayName("예약 상세 조회 시 소유자 검사 없이 출입 로그를 조회해 상태 이력과 함께 응답에 담는다")
    fun getReservationDetail_success_mapsStatusHistoryAndAccessLogs() {
        val reservation: Reservation = confirmedReservation()
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation))
        stubMemberAndSpace()

        val history: ReservationStatusHistory =
            ReservationStatusHistory.of(
                RESERVATION_ID,
                MEMBER_ID,
                null,
                ReservationStatus.CONFIRMED,
                null,
                startTime.minusHours(1),
            )
        given(statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(RESERVATION_ID))
            .willReturn(listOf(history))

        val allowLog: DoorAccessLog =
            DoorAccessLog(
                null,
                null,
                null,
                AccessResult.ALLOW,
                null,
                startTime.plusMinutes(1),
            )
        val denyLog: DoorAccessLog =
            DoorAccessLog(
                null,
                null,
                null,
                AccessResult.DENY,
                AccessDenyReason.TOKEN_REVOKED,
                startTime.plusMinutes(2),
            )
        given(doorAccessLogService.findAllByReservationId(RESERVATION_ID))
            .willReturn(listOf(allowLog, denyLog))

        val response: AdminReservationDetailResponse = adminReservationService.getReservationDetail(RESERVATION_ID)

        // 관리자는 예약 소유자가 아니어도 조회할 수 있어야 하므로, owner 검사가 있는
        // findResponsesByReservationId가 아니라 findAllByReservationId가 호출되어야 한다.
        verify(doorAccessLogService).findAllByReservationId(RESERVATION_ID)
        verify(doorAccessLogService, never()).findResponsesByReservationId(anyNonNull(), anyNonNull())

        assertThat(response.statusHistory).hasSize(1)
        assertThat(response.accessLogs).hasSize(2)
        assertThat(response.accessLogs.get(0).result).isEqualTo(AccessResult.ALLOW)
        assertThat(response.accessLogs.get(1).result).isEqualTo(AccessResult.DENY)
        assertThat(response.accessLogs.get(1).reasonCode).isEqualTo(AccessDenyReason.TOKEN_REVOKED)
    }

    // ---------- forceCancel ----------

    @Test
    @DisplayName("존재하지 않는 예약을 강제 취소하면 RESERVATION_NOT_FOUND 예외가 발생한다")
    fun forceCancel_reservationNotFound_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty())

        assertThatThrownBy({ adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND)
    }

    @Test
    @DisplayName("이미 종료 상태(COMPLETED 등)인 예약은 강제 취소할 수 없고 환불을 포함한 후속 처리도 일어나지 않는다")
    fun forceCancel_terminalState_throwsExceptionWithoutSideEffects() {
        val completed: Reservation = reservationWithStatus(ReservationStatus.COMPLETED, 10000)
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(completed))

        assertThatThrownBy({ adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        verify(reservationRepository, never()).forceCancelIfStatusIs(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())
        verify(reservationSlotRepository, never()).deleteByReservationId(anyNonNull())
        verify(doorAccessTokenService, never()).revokeByReservation(anyNonNull(), anyNonNull(), anyNonNull())
        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
        verify(statusHistoryRepository, never()).save(anyNonNull())
        verify(
            auditLogService,
            never(),
        ).log(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())
        verify(slotReleasedRecorder, never()).record(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyNonNull(),
        )
    }

    @Test
    @DisplayName("CONFIRMED 예약을 강제 취소하면 CANCELLED로 전이하고 슬롯 반환·토큰 revoke·이력·전액 환불·감사 로그를 모두 수행한다")
    fun forceCancel_confirmed_cancelsAndRefundsFullAmount() {
        val confirmed: Reservation = confirmedReservation()
        val cancelled: Reservation = reservationWithStatus(ReservationStatus.CANCELLED, 10000)
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(confirmed), Optional.of(cancelled))
        given(
            reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        stubMemberAndSpace()

        val response: AdminReservationResponse = adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID)

        assertThat(response.status).isEqualTo(ReservationStatus.CANCELLED)

        // 결제된 예약 취소 시 환불 전 회원 배타 잠금 선점 검증 및 Space 배타 잠금 미선점 검증
        verify(spaceRepository, never()).findByIdForUpdate(anyNonNull())
        verify(memberRepository).findByIdForUpdate(MEMBER_ID)
        verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID)
        verify(doorAccessTokenService).revokeByReservation(eqNonNull(RESERVATION_ID), eqNonNull(now), eqNonNull("ADMIN_FORCE_CANCEL"))

        // 관리자 사유의 취소이므로 위약금 없이 결제액 전액을 돌려준다.
        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, 10000)
        verify(creditService, never()).penalize(anyNonNull(), anyNonNull(), anyInt())

        verify(statusHistoryRepository).save(
            argThat({ history ->
                history.reservationId.equals(RESERVATION_ID) &&
                    history.changedByMemberId!!.equals(ADMIN_MEMBER_ID) &&
                    history.fromStatus == ReservationStatus.CONFIRMED &&
                    history.toStatus == ReservationStatus.CANCELLED &&
                    history.reason.equals(REASON)
            }),
        )

        verify(auditLogService).log(
            eqNonNull(ADMIN_MEMBER_ID),
            eqNonNull(AuditAction.FORCE_CANCEL_RESERVATION),
            eqNonNull(AuditTargetType.RESERVATION),
            eqNonNull(RESERVATION_ID),
            eqNonNull(REASON),
            eqNonNull(ReservationStatus.CONFIRMED),
            eqNonNull(ReservationStatus.CANCELLED),
        )
        verify(slotReleasedRecorder).record(RESERVATION_ID, SPACE_ID, SlotReleasedRecorder.Reason.FORCE_CANCELLED)
    }

    @Test
    @DisplayName("연장으로 늘어난 총액을 가진 예약을 강제 취소하면 연장분까지 포함한 total_amount 전액을 환불한다")
    fun forceCancel_extendedReservation_refundsTotalAmountIncludingExtension() {
        val extended: Reservation = reservationWithStatus(ReservationStatus.CONFIRMED, 25000)
        val cancelled: Reservation = reservationWithStatus(ReservationStatus.CANCELLED, 25000)
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(extended), Optional.of(cancelled))
        given(
            reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        stubMemberAndSpace()

        adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID)

        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, 25000)
    }

    @Test
    @DisplayName("이미 이용 중(IN_USE)인 예약을 강제 취소해도 전액 환불한다")
    fun forceCancel_inUse_refundsFullAmount() {
        val inUse: Reservation = reservationWithStatus(ReservationStatus.IN_USE, 10000)
        val cancelled: Reservation = reservationWithStatus(ReservationStatus.CANCELLED, 10000)
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(inUse), Optional.of(cancelled))
        given(
            reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID,
                now,
                ReservationStatus.IN_USE,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        stubMemberAndSpace()

        adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID)

        verify(spaceRepository, never()).findByIdForUpdate(anyNonNull())
        verify(memberRepository).findByIdForUpdate(MEMBER_ID)
        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, 10000)
        verify(statusHistoryRepository).save(
            argThat({ history ->
                history.fromStatus == ReservationStatus.IN_USE &&
                    history.toStatus == ReservationStatus.CANCELLED
            }),
        )
        verify(slotReleasedRecorder).record(RESERVATION_ID, SPACE_ID, SlotReleasedRecorder.Reason.FORCE_CANCELLED)
    }

    @Test
    @DisplayName("결제 전(HELD) 예약을 강제 취소하면 Space 배타 잠금을 선점하고 슬롯만 반환하며 환불 및 회원 잠금은 하지 않는다")
    fun forceCancel_held_doesNotRefund() {
        val held: Reservation = reservationWithStatus(ReservationStatus.HELD, 10000)
        val cancelled: Reservation = reservationWithStatus(ReservationStatus.CANCELLED, 10000)
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(held), Optional.of(cancelled))
        given(spaceRepository.findByIdForUpdate(SPACE_ID))
            .willReturn(
                Optional.of(
                    Space
                        .builder()
                        .id(SPACE_ID)
                        .name("공간1")
                        .build(),
                ),
            )
        given(
            reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID,
                now,
                ReservationStatus.HELD,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        stubMemberAndSpace()

        adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID)

        verify(spaceRepository).findByIdForUpdate(SPACE_ID)
        verify(memberRepository, never()).findByIdForUpdate(anyLong())
        verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID)
        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
        verify(creditService, never()).penalize(anyNonNull(), anyNonNull(), anyInt())
        verify(slotReleasedRecorder).record(RESERVATION_ID, SPACE_ID, SlotReleasedRecorder.Reason.FORCE_CANCELLED)
    }

    @Test
    @DisplayName("조회 뒤 다른 요청이 먼저 상태를 바꿔 조건부 UPDATE가 0행이면 RESERVATION_STATE_CONFLICT가 발생하고 환불을 포함한 후속 처리가 일어나지 않는다")
    fun forceCancel_statusChangedConcurrently_throwsConflictWithoutSideEffects() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmedReservation()))
        given(
            reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(0)

        assertThatThrownBy({ adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
        verify(reservationSlotRepository, never()).deleteByReservationId(anyNonNull())
        verify(doorAccessTokenService, never()).revokeByReservation(anyNonNull(), anyNonNull(), anyNonNull())
        verify(statusHistoryRepository, never()).save(anyNonNull())
        verify(
            auditLogService,
            never(),
        ).log(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())
        verify(slotReleasedRecorder, never()).record(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyNonNull(),
        )
    }

    @Test
    @DisplayName("searchReservations: 조건이 비어있으면 null 파라미터와 기본 id DESC 정렬로 리포지토리를 호출한다")
    fun searchReservations_defaultCondition_appliesDefaultSortAndMapsResponses() {
        // given
        val reservation: Reservation = confirmedReservation()
        val page: Page<Reservation> = PageImpl(listOf(reservation), PageRequest.of(0, 20), 1)

        given(
            reservationRepository.searchReservations(
                eqNonNull(null),
                eqNonNull(null),
                eqNonNull(null),
                eqNonNull(null),
                anyNonNull(Pageable::class.java),
            ),
        ).willReturn(page)
        stubMemberAndSpace()

        // when
        val result: Page<AdminReservationResponse> =
            adminReservationService.searchReservations(
                AdminReservationSearchCondition(null, null, null),
                PageRequest.of(0, 20),
            )

        // then
        assertThat(result.content).hasSize(1)
        val item: AdminReservationResponse = result.content.get(0)
        assertThat(item.reservationId).isEqualTo(RESERVATION_ID)
        assertThat(item.memberEmail).isEqualTo("user@slotkey.test")
        assertThat(item.spaceName).isEqualTo("공간1")

        val pageableCaptor: org.mockito.ArgumentCaptor<Pageable> = org.mockito.ArgumentCaptor.forClass(Pageable::class.java)
        verify(
            reservationRepository,
        ).searchReservations(eqNonNull(null), eqNonNull(null), eqNonNull(null), eqNonNull(null), captureNonNull(pageableCaptor))
        val capturedPageable: Pageable = pageableCaptor.value
        assertThat(capturedPageable.sort.getOrderFor("id")).isNotNull()
        assertThat(capturedPageable.sort.getOrderFor("id")!!.direction).isEqualTo(Sort.Direction.DESC)
    }

    @Test
    @DisplayName("searchReservations: date, spaceId, status가 주어지면 [date 00:00, date+1 00:00) 범위와 파라미터를 정확히 전달한다")
    fun searchReservations_withAllFilters_passesCorrectArguments() {
        // given
        val searchDate: LocalDate = LocalDate.of(2026, 9, 30)
        val expectedInclusive: LocalDateTime = LocalDateTime.of(2026, 9, 30, 0, 0, 0)
        val expectedExclusive: LocalDateTime = LocalDateTime.of(2026, 10, 1, 0, 0, 0)

        val emptyPage: Page<Reservation> = PageImpl(listOf(), PageRequest.of(0, 10), 0)
        given(
            reservationRepository.searchReservations(
                eqNonNull(SPACE_ID),
                eqNonNull(ReservationStatus.CONFIRMED),
                eqNonNull(expectedInclusive),
                eqNonNull(expectedExclusive),
                anyNonNull(Pageable::class.java),
            ),
        ).willReturn(emptyPage)

        // when
        val condition: AdminReservationSearchCondition =
            AdminReservationSearchCondition(
                searchDate,
                SPACE_ID,
                ReservationStatus.CONFIRMED,
            )
        val result: Page<AdminReservationResponse> = adminReservationService.searchReservations(condition, PageRequest.of(0, 10))

        // then
        assertThat(result.content).isEmpty()
        verify(reservationRepository).searchReservations(
            eqNonNull(SPACE_ID),
            eqNonNull(ReservationStatus.CONFIRMED),
            eqNonNull(expectedInclusive),
            eqNonNull(expectedExclusive),
            anyNonNull(Pageable::class.java),
        )
    }

    @Test
    @DisplayName("searchReservations: 클라이언트가 지정한 정렬이 있으면 기본 정렬로 덮어쓰지 않고 유지한다")
    fun searchReservations_withClientSort_preservesSort() {
        // given
        val emptyPage: Page<Reservation> = PageImpl(listOf(), PageRequest.of(0, 10), 0)
        val pageableCaptor: org.mockito.ArgumentCaptor<Pageable> = org.mockito.ArgumentCaptor.forClass(Pageable::class.java)
        given(
            reservationRepository.searchReservations(
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                captureNonNull(pageableCaptor),
            ),
        ).willReturn(emptyPage)

        val clientPageable: Pageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.ASC, "startTime"))

        // when
        adminReservationService.searchReservations(AdminReservationSearchCondition(null, null, null), clientPageable)

        // then
        val captured: Pageable = pageableCaptor.value
        assertThat(captured.pageNumber).isEqualTo(1)
        assertThat(captured.pageSize).isEqualTo(10)
        assertThat(captured.sort.getOrderFor("startTime")).isNotNull()
        assertThat(captured.sort.getOrderFor("startTime")!!.direction).isEqualTo(Sort.Direction.ASC)
    }

    @Test
    @DisplayName("findAllReservations: searchReservations를 빈 조건으로 위임 호출한다")
    fun findAllReservations_delegatesToSearchReservations() {
        // given
        val reservation: Reservation = confirmedReservation()
        val page: Page<Reservation> = PageImpl(listOf(reservation), PageRequest.of(0, 20), 1)
        given(
            reservationRepository.searchReservations(
                eqNonNull(null),
                eqNonNull(null),
                eqNonNull(null),
                eqNonNull(null),
                anyNonNull(Pageable::class.java),
            ),
        ).willReturn(page)
        stubMemberAndSpace()

        // when
        val result: Page<AdminReservationResponse> = adminReservationService.findAllReservations(PageRequest.of(0, 20))

        // then
        assertThat(result.content).hasSize(1)
        verify(
            reservationRepository,
        ).searchReservations(eqNonNull(null), eqNonNull(null), eqNonNull(null), eqNonNull(null), anyNonNull(Pageable::class.java))
    }
}
