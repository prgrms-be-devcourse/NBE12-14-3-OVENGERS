package com.ovengers.slotkey.access.service;

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest;
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse;
import com.ovengers.slotkey.access.entity.AccessDenyReason;
import com.ovengers.slotkey.access.entity.DoorAccessToken;
import com.ovengers.slotkey.access.policy.DoorAccessTimePolicy;
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class DoorAccessVerificationService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DoorAccessVerificationService.class);

    private final DoorAccessTokenService doorAccessTokenService;
    private final DoorAccessTokenRepository doorAccessTokenRepository;
    private final DoorAccessLogService doorAccessLogService;
    private final DoorAccessTimePolicy doorAccessTimePolicy;
    private final MemberRepository memberRepository;
    private final SpaceRepository spaceRepository;
    private final Clock clock;
    private final ReservationRepository reservationRepository;
    private final ReservationStatusHistoryRepository reservationStatusHistoryRepository;
    private final jakarta.persistence.EntityManager entityManager;

    public DoorAccessVerificationService(
            DoorAccessTokenService doorAccessTokenService,
            DoorAccessTokenRepository doorAccessTokenRepository,
            DoorAccessLogService doorAccessLogService,
            DoorAccessTimePolicy doorAccessTimePolicy,
            MemberRepository memberRepository,
            SpaceRepository spaceRepository,
            Clock clock,
            ReservationRepository reservationRepository,
            ReservationStatusHistoryRepository reservationStatusHistoryRepository,
            jakarta.persistence.EntityManager entityManager
    ) {
        this.doorAccessTokenService = doorAccessTokenService;
        this.doorAccessTokenRepository = doorAccessTokenRepository;
        this.doorAccessLogService = doorAccessLogService;
        this.doorAccessTimePolicy = doorAccessTimePolicy;
        this.memberRepository = memberRepository;
        this.spaceRepository = spaceRepository;
        this.clock = clock;
        this.reservationRepository = reservationRepository;
        this.reservationStatusHistoryRepository = reservationStatusHistoryRepository;
        this.entityManager = entityManager;
    }

    // 제출된 출입 토큰 검증
    @Transactional
    public DoorAccessVerifyResponse verify(
            Long loginMemberId,
            DoorAccessVerifyRequest request
    ) {
        LocalDateTime attemptedAt =
                LocalDateTime.now(clock);

        // 실제 출입을 시도한 로그인 회원 조회
        Member actorMember = memberRepository.findById(loginMemberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTHENTICATION_REQUIRED
                ));

        Space requestedSpace = spaceRepository.findById(request.getSpaceId())
                .orElse(null);

        DoorAccessToken accessToken =
                doorAccessTokenService.findByRawTokenOrNull(
                        request.getToken()
                );

        if (accessToken == null) {
            return deny(
                    actorMember,
                    null,
                    requestedSpace,
                    AccessDenyReason.TOKEN_NOT_FOUND,
                    attemptedAt
            );
        }

        Reservation reservation =
                accessToken.getReservation();

        // 로그인 회원과 출입 토큰의 예약자가 같은지 확인
        if (!Objects.equals(
                loginMemberId,
                reservation.getMemberId()
        )) {
            return deny(
                    actorMember,
                    reservation,
                    requestedSpace,
                    AccessDenyReason.MEMBER_MISMATCH,
                    attemptedAt
            );
        }

        // 폐기된 토큰이면 출입 거절
        if (accessToken.isRevoked()) {
            return deny(
                    actorMember,
                    reservation,
                    requestedSpace,
                    AccessDenyReason.TOKEN_REVOKED,
                    attemptedAt
            );
        }

        // 출입 가능한 예약 상태인지 확인
        if (!isActiveReservation(reservation)) {
            return deny(
                    actorMember,
                    reservation,
                    requestedSpace,
                    AccessDenyReason.RESERVATION_NOT_ACTIVE,
                    attemptedAt
            );
        }

        // 요청한 공간과 예약 공간이 같은지 확인
        if (!Objects.equals(
                reservation.getSpaceId(),
                request.getSpaceId()
        )) {
            return deny(
                    actorMember,
                    reservation,
                    requestedSpace,
                    AccessDenyReason.SPACE_MISMATCH,
                    attemptedAt
            );
        }

        // 출입 가능한 시간인지 확인
        AccessDenyReason timeDenyReason =
                doorAccessTimePolicy.findDenyReason(
                        attemptedAt,
                        reservation.getStartTime(),
                        reservation.getEndTime(),
                        reservation.getCheckedInAt()
                );

        if (timeDenyReason != null) {
            return deny(
                    actorMember,
                    reservation,
                    requestedSpace,
                    timeDenyReason,
                    attemptedAt
            );
        }

        // =========================================================================
        // 최종 허가 직전 current/locking read 직렬화 지점 (모든 ALLOW 경로에 적용)
        // 잠금 순서 불변식 준수: Reservation -> DoorAccessToken
        // 1차 캐시를 detach하여 REPEATABLE READ 및 영속성 컨텍스트 스냅샷을 우회하고 DB 최신 상태를 읽도록 보장
        // =========================================================================
        entityManager.detach(reservation);
        entityManager.detach(accessToken);

        // 1. 최신 예약 상태 Locking Read
        Reservation latestReservation = reservationRepository.findByIdForUpdate(reservation.getId())
                .orElse(null);
        if (latestReservation == null) {
            log.warn("최종 허가 전 예약 잠금 조회 실패(존재하지 않음) - reservationId: {}", reservation.getId());
            return deny(
                    actorMember,
                    null,
                    requestedSpace,
                    AccessDenyReason.RESERVATION_NOT_ACTIVE,
                    attemptedAt
            );
        }

        if (!isActiveReservation(latestReservation)) {
            return deny(
                    actorMember,
                    latestReservation,
                    requestedSpace,
                    AccessDenyReason.RESERVATION_NOT_ACTIVE,
                    attemptedAt
            );
        }

        // 2. 최신 출입 토큰 상태 Locking Read
        DoorAccessToken latestToken = doorAccessTokenRepository.findByIdForUpdate(accessToken.getId())
                .orElse(null);
        if (latestToken == null || latestToken.isRevoked()) {
            return deny(
                    actorMember,
                    latestReservation,
                    requestedSpace,
                    AccessDenyReason.TOKEN_REVOKED,
                    attemptedAt
            );
        }

        // 3. 최신 예약 정보로 공간 및 시간 재검증
        if (!Objects.equals(
                latestReservation.getSpaceId(),
                request.getSpaceId()
        )) {
            return deny(
                    actorMember,
                    latestReservation,
                    requestedSpace,
                    AccessDenyReason.SPACE_MISMATCH,
                    attemptedAt
            );
        }

        AccessDenyReason latestTimeDenyReason =
                doorAccessTimePolicy.findDenyReason(
                        attemptedAt,
                        latestReservation.getStartTime(),
                        latestReservation.getEndTime(),
                        latestReservation.getCheckedInAt()
                );
        if (latestTimeDenyReason != null) {
            return deny(
                    actorMember,
                    latestReservation,
                    requestedSpace,
                    latestTimeDenyReason,
                    attemptedAt
            );
        }

        // 4. 최초 체크인 여부 판정 및 조건부 전이
        boolean firstCheckIn = false;
        if (latestReservation.getStatus() == ReservationStatus.CONFIRMED) {
            int updatedRows = reservationRepository.checkInIfConfirmed(
                    latestReservation.getId(),
                    attemptedAt,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.IN_USE
            );

            if (updatedRows == 1) {
                firstCheckIn = true;
                reservationStatusHistoryRepository.save(
                        ReservationStatusHistory.of(
                                latestReservation.getId(),
                                loginMemberId,
                                ReservationStatus.CONFIRMED,
                                ReservationStatus.IN_USE,
                                "FIRST_CHECK_IN",
                                attemptedAt
                        )
                );
                latestReservation = reservationRepository.findById(latestReservation.getId())
                        .orElse(latestReservation);
            } else {
                Reservation reloaded = reservationRepository.findByIdForUpdate(latestReservation.getId())
                        .orElse(null);
                if (reloaded == null || !isActiveReservation(reloaded)) {
                    return deny(
                            actorMember,
                            reloaded,
                            requestedSpace,
                            AccessDenyReason.RESERVATION_NOT_ACTIVE,
                            attemptedAt
                    );
                }
                latestReservation = reloaded;
            }
        }

        doorAccessLogService.createAllowLog(
                actorMember,
                latestReservation,
                requestedSpace,
                attemptedAt
        );

        return DoorAccessVerifyResponse.allow(
                requestedSpace.getName(),
                firstCheckIn,
                attemptedAt
        );
    }

    // 출입 가능한 예약 상태 확인
    private boolean isActiveReservation(
            Reservation reservation
    ) {
        return reservation.getStatus()
                == ReservationStatus.CONFIRMED
                || reservation.getStatus()
                == ReservationStatus.IN_USE;
    }

    // 출입 거절 로그 저장 및 응답 생성
    private DoorAccessVerifyResponse deny(
            Member actorMember,
            Reservation reservation,
            Space requestedSpace,
            AccessDenyReason reasonCode,
            LocalDateTime attemptedAt
    ) {
        doorAccessLogService.createDenyLog(
                actorMember,
                reservation,
                requestedSpace,
                reasonCode,
                attemptedAt
        );

        return DoorAccessVerifyResponse.deny(
                reasonCode,
                attemptedAt
        );
    }
}
