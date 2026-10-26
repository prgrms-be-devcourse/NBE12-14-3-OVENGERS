package com.ovengers.slotkey.access.service;

import com.ovengers.slotkey.access.authorization.DoorAccessAuthorizationService;
import com.ovengers.slotkey.access.dto.response.DoorAccessLogResponse;
import com.ovengers.slotkey.access.entity.AccessDenyReason;
import com.ovengers.slotkey.access.entity.AccessResult;
import com.ovengers.slotkey.access.entity.DoorAccessLog;
import com.ovengers.slotkey.access.repository.DoorAccessLogRepository;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.space.entity.Space;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DoorAccessLogService {

    private final DoorAccessLogRepository doorAccessLogRepository;
    private final ReservationRepository reservationRepository;
    private final DoorAccessAuthorizationService doorAccessAuthorizationService;

    public DoorAccessLogService(
            DoorAccessLogRepository doorAccessLogRepository,
            ReservationRepository reservationRepository,
            DoorAccessAuthorizationService doorAccessAuthorizationService
    ) {
        this.doorAccessLogRepository = doorAccessLogRepository;
        this.reservationRepository = reservationRepository;
        this.doorAccessAuthorizationService = doorAccessAuthorizationService;
    }

    // 출입 시도 결과에 대한 로그 생성
    @Transactional
    public DoorAccessLog create(
            Member actorMember,
            Reservation reservation,
            Space requestedSpace,
            AccessResult result,
            AccessDenyReason reasonCode,
            LocalDateTime attemptedAt
    ) {
        DoorAccessLog accessLog = new DoorAccessLog(
                actorMember,
                reservation,
                requestedSpace,
                result,
                reasonCode,
                attemptedAt
        );

        return doorAccessLogRepository.save(accessLog);
    }

    // 출입 허용 로그 생성
    @Transactional
    public DoorAccessLog createAllowLog(
            Member actorMember,
            Reservation reservation,
            Space requestedSpace,
            LocalDateTime attemptedAt
    ) {
        return create(
                actorMember,
                reservation,
                requestedSpace,
                AccessResult.ALLOW,
                null,
                attemptedAt
        );
    }

    // 출입 거절 로그 생성
    @Transactional
    public DoorAccessLog createDenyLog(
            Member actorMember,
            Reservation reservation,
            Space requestedSpace,
            AccessDenyReason reasonCode,
            LocalDateTime attemptedAt
    ) {
        if (reasonCode == null) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENY_REASON_REQUIRED
            );
        }

        return create(
                actorMember,
                reservation,
                requestedSpace,
                AccessResult.DENY,
                reasonCode,
                attemptedAt
        );
    }

    // 출입 로그 단건 조회
    public DoorAccessLog findById(Long accessLogId) {
        return doorAccessLogRepository.findById(accessLogId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCESS_LOG_NOT_FOUND
                ));
    }

    // 출입 로그 전체 조회
    public List<DoorAccessLog> findAll() {
        return doorAccessLogRepository.findAll();
    }

    // 특정 예약의 출입 로그를 최신순으로 조회
    public List<DoorAccessLog> findAllByReservationId(Long reservationId) {
        return doorAccessLogRepository.findAllByReservationIdOrderByAttemptedAtDesc(reservationId);
    }

    // 특정 예약의 출입 로그 응답 목록 조회
    public List<DoorAccessLogResponse> findResponsesByReservationId(
            Long loginMemberId,
            Long reservationId
    ) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESERVATION_NOT_FOUND
                ));

        doorAccessAuthorizationService.validateOwner(
                loginMemberId,
                reservation.getMemberId()
        );

        return findAllByReservationId(reservationId)
                .stream()
                .map(DoorAccessLogResponse::from)
                .toList();
    }

    // 해당 예약의 출입 성공 기록 존재 여부
    public boolean hasSuccessfulAccess(Long reservationId) {
        return doorAccessLogRepository.existsByReservationIdAndResult(
                reservationId,
                AccessResult.ALLOW
        );
    }
}
