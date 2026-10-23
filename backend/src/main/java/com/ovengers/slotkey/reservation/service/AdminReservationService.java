package com.ovengers.slotkey.reservation.service;


import com.ovengers.slotkey.access.entity.DoorAccessLog;
import com.ovengers.slotkey.access.service.DoorAccessLogService;
import com.ovengers.slotkey.access.service.DoorAccessTokenService;
import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.service.AuditLogService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.outbox.SlotReleasedRecorder;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.dto.response.AdminReservationDetailResponse;
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse;
import com.ovengers.slotkey.reservation.dto.response.DoorAccessLogResponse;
import com.ovengers.slotkey.reservation.dto.response.ReservationStatusHistoryResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.reservation.dto.request.AdminReservationSearchCondition;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ovengers.slotkey.credit.service.CreditService;   // AuditLogService import 아래
import java.time.Clock;                                       // java.time.LocalDateTime import 위

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 예약 조회 및 강제 취소 서비스 (core-domain-decisions 3-4).
 *
 * 강제 취소는 "조건부 UPDATE 영향 행이 1일 때만 후속 처리"(core-domain-decisions 3-1) 패턴을 따르며,
 * 예약자 본인의 취소와 달리 관리자는 상태·시간에 상관없이 취소할 수 있다 (사유 기록).
 */
@Service
@RequiredArgsConstructor
public class AdminReservationService {
    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationStatusHistoryRepository statusHistoryRepository;
    private final MemberRepository memberRepository;
    private final SpaceRepository spaceRepository;
    private final AuditLogService auditLogService;
    private final DoorAccessTokenService doorAccessTokenService;
    private final DoorAccessLogService doorAccessLogService;
    private final CreditService creditService;
    private final Clock clock;
    private final SlotReleasedRecorder slotReleasedRecorder;

    /**
     * 관리자 예약 목록 검색 (조건 필터 및 페이징).
     */
    @Transactional(readOnly = true)
    public Page<AdminReservationResponse> searchReservations(
            AdminReservationSearchCondition condition,
            Pageable pageable
    ) {
        Pageable effectivePageable = (pageable != null && pageable.getSort().isSorted())
                ? pageable
                : PageRequest.of(
                        pageable != null ? pageable.getPageNumber() : 0,
                        pageable != null ? pageable.getPageSize() : 20,
                        Sort.by(Sort.Direction.DESC, "id")
                );

        AdminReservationSearchCondition cond = condition != null
                ? condition
                : new AdminReservationSearchCondition(null, null, null);

        LocalDate date = cond.date();
        LocalDateTime startTimeInclusive = date != null ? date.atStartOfDay() : null;
        LocalDateTime startTimeExclusive = date != null ? date.plusDays(1).atStartOfDay() : null;

        return reservationRepository.searchReservations(
                cond.spaceId(),
                cond.status(),
                startTimeInclusive,
                startTimeExclusive,
                effectivePageable
        ).map(reservation -> AdminReservationResponse.from(
                reservation,
                findMemberEmail(reservation.getMemberId()),
                findSpaceName(reservation.getSpaceId())
        ));
    }

    /**
     * 전체 예약 조회 (페이지네이션).
     */
    @Transactional(readOnly = true)
    public Page<AdminReservationResponse> findAllReservations(Pageable pageable) {
        return searchReservations(new AdminReservationSearchCondition(null, null, null), pageable);
    }

    /**
     * 예약 상세 조회 (상태 이력 + 출입 로그 포함).
     * 관리자는 다른 회원의 예약도 조회할 수 있다.
     */
    @Transactional(readOnly = true)
    public AdminReservationDetailResponse getReservationDetail(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        List<ReservationStatusHistory> statusHistories =
                statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);
        List<ReservationStatusHistoryResponse> historyResponses = statusHistories.stream()
                .map(ReservationStatusHistoryResponse::from)
                .toList();

        // 관리자 조회이므로 소유자 검사 없이(findResponsesByReservationId는 owner 검증 포함)
        // 예약 id로 출입 로그 엔티티를 직접 조회해 reservation 쪽 응답 DTO로 변환한다.
        List<DoorAccessLog> accessLogEntities = doorAccessLogService.findAllByReservationId(reservationId);
        List<DoorAccessLogResponse> accessLogs = accessLogEntities.stream()
                .map(DoorAccessLogResponse::from)
                .toList();

        return AdminReservationDetailResponse.from(
                reservation,
                findMemberEmail(reservation.getMemberId()),
                findSpaceName(reservation.getSpaceId()),
                historyResponses,
                accessLogs
        );
    }

    /**
     * 예약 강제 취소 (관리자만, 사유 기록).
     * 상태·시간에 상관없이 CANCELLED로 전이할 수 있다.
     * 이미 COMPLETED/EXPIRED/NO_SHOW/CANCELLED인 경우는 상태 변경 불가(409).
     *
     * 조건부 UPDATE(조회한 상태가 그대로일 때만 전이)가 문지기이며, 영향 행이 1일 때만 같은 트랜잭션에서
     * 후속 처리를 한다(core-domain-decisions 6-4). 그래서 본인 취소·체크인·배치와 겹쳐도 환불은 최대 1회다.
     * 결제한 예약(CONFIRMED/IN_USE)은 위약금 없이 total_amount 전액을 환불하고, 결제 전(HELD)은 환불하지 않는다.
     */
    @Transactional
    public AdminReservationResponse forceCancel(Long reservationId, String reason, Long adminMemberId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // 이미 종료된 상태는 취소할 수 없음
        if (reservation.isTerminalState()) {
            throw new BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT);
        }

        ReservationStatus previousStatus = reservation.getStatus();
        Long memberId = reservation.getMemberId();
        int totalAmount = reservation.getTotalAmount();
        LocalDateTime now = LocalDateTime.now(clock);

        // HELD 상태의 예약은 환불이 없어 명시적 예약자 Member X를 선점하지 않으나,
        // 이력/감사 로그 INSERT 시 adminMemberId에 대한 FK 공유 잠금(S)이 발생한다.
        // adminMemberId == memberId인 경우 대체 HOLD(Member X 보유)의 만료 정리(Reservation X 요청)와
        // Reservation X ↔ Member S 간 순환 데드락이 발생할 수 있다.
        // 따라서 상위 리소스인 Space의 배타 잠금(X)을 Reservation UPDATE 전에 선점하여,
        // 동일 공간의 신규 HOLD 및 연장(Space S 잠금)과 상호 배제(직렬화)함으로써 데드락을 원천 차단한다.
        if (previousStatus == ReservationStatus.HELD) {
            spaceRepository.findByIdForUpdate(reservation.getSpaceId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
        }

        // 문지기: 조회 시점의 상태가 그대로일 때만 CANCELLED로 전이한다. 0행이면 그 사이 다른 요청이 먼저 상태를 바꾼 것이다.
        int updated = reservationRepository.forceCancelIfStatusIs(
                reservationId, now, previousStatus, ReservationStatus.CANCELLED);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "다른 요청이 먼저 예약 상태를 변경했습니다.");
        }

        // 상태 이력 저장
        ReservationStatusHistory history = ReservationStatusHistory.of(
                reservationId,
                adminMemberId,
                previousStatus,
                ReservationStatus.CANCELLED,
                reason,
                now
        );
        statusHistoryRepository.save(history);

        // 환불이 발생하는 결제된 예약(CONFIRMED, IN_USE)인 경우, 슬롯 삭제 전에 회원 배타 잠금을 선점하여
        // Slot DELETE ↔ Member UPDATE와 연장/HOLD의 Member X ↔ Slot INSERT 간 교차 대기 데드락을 방지한다.
        // HELD 상태는 결제/환불이 발생하지 않으므로 회원 잠금을 잡지 않아,
        // 만료 정리(expireHold)의 Reservation UPDATE와 Member X 간 순환 데드락을 방지한다.
        if (previousStatus != ReservationStatus.HELD) {
            memberRepository.findByIdForUpdate(memberId);
        }

        // 슬롯 삭제 (기존 취소와 동일)
        reservationSlotRepository.deleteByReservationId(reservationId);

        // 활성 출입 토큰 revoke. 관리자의 강제 취소이므로 소유자 검사가 없는
        // revokeByReservation(예약 상태 변경에 따른 시스템 경로)을 사용한다.
        doorAccessTokenService.revokeByReservation(reservationId, now, "ADMIN_FORCE_CANCEL");

        // 결제한 예약은 위약금 없이 전액 환불한다. HELD는 결제한 적이 없으므로 환불하지 않는다.
        if (previousStatus != ReservationStatus.HELD) {
            creditService.refund(memberId, reservationId, totalAmount);
        }

        // 감사 로그 기록
        auditLogService.log(
                adminMemberId,
                AuditAction.FORCE_CANCEL_RESERVATION,
                AuditTargetType.RESERVATION,
                reservationId,
                reason,
                previousStatus,
                ReservationStatus.CANCELLED
        );

        slotReleasedRecorder.record(reservationId, reservation.getSpaceId(), SlotReleasedRecorder.Reason.FORCE_CANCELLED);

        // 조건부 UPDATE가 영속성 컨텍스트를 비웠으므로 취소된 최신 상태를 다시 읽어 응답한다.
        Reservation cancelled = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        return AdminReservationResponse.from(
                cancelled,
                findMemberEmail(cancelled.getMemberId()),
                findSpaceName(cancelled.getSpaceId())
        );
    }

    private String findMemberEmail(Long memberId) {
        return memberRepository.findById(memberId)
                .map(Member::getEmail)
                .orElse(null);
    }

    private String findSpaceName(Long spaceId) {
        return spaceRepository.findById(spaceId)
                .map(Space::getName)
                .orElse(null);
    }
}
