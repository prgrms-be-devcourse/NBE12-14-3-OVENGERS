package com.ovengers.slotkey.access.service;

import com.ovengers.slotkey.access.authorization.DoorAccessAuthorizationService;
import com.ovengers.slotkey.access.dto.response.DoorAccessTokenResponse;
import com.ovengers.slotkey.access.entity.DoorAccessToken;
import com.ovengers.slotkey.access.policy.DoorAccessTimePolicy;
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository;
import com.ovengers.slotkey.access.support.AccessTokenGenerator;
import com.ovengers.slotkey.access.support.AccessTokenHasher;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DoorAccessTokenService {

    private final DoorAccessTokenRepository doorAccessTokenRepository;
    private final ReservationRepository reservationRepository;
    private final DoorAccessAuthorizationService doorAccessAuthorizationService;
    private final DoorAccessTimePolicy doorAccessTimePolicy;
    private final AccessTokenGenerator accessTokenGenerator;
    private final AccessTokenHasher accessTokenHasher;
    private final Clock clock;

    public DoorAccessTokenService(
            DoorAccessTokenRepository doorAccessTokenRepository,
            ReservationRepository reservationRepository,
            DoorAccessAuthorizationService doorAccessAuthorizationService,
            DoorAccessTimePolicy doorAccessTimePolicy,
            AccessTokenGenerator accessTokenGenerator,
            AccessTokenHasher accessTokenHasher,
            Clock clock
    ) {
        this.doorAccessTokenRepository = doorAccessTokenRepository;
        this.reservationRepository = reservationRepository;
        this.doorAccessAuthorizationService = doorAccessAuthorizationService;
        this.doorAccessTimePolicy = doorAccessTimePolicy;
        this.accessTokenGenerator = accessTokenGenerator;
        this.accessTokenHasher = accessTokenHasher;
        this.clock = clock;
    }

    // 새로운 출입 토큰 생성 (issue 내부 전용)
    private DoorAccessToken create(
            Reservation reservation,
            String tokenHash,
            LocalDateTime issuedAt
    ) {
        DoorAccessToken token = new DoorAccessToken(
                reservation,
                tokenHash,
                issuedAt
        );

        return doorAccessTokenRepository.save(token);
    }

    // 예약에 사용할 출입 토큰 발급
    @Transactional
    public DoorAccessTokenResponse issue(
            Long loginMemberId,
            Long reservationId
    ) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESERVATION_NOT_FOUND
                ));

        // 예약 소유자 확인
        doorAccessAuthorizationService.validateOwner(
                loginMemberId,
                reservation.getMemberId()
        );

        // // 확정 또는 이용 중인 예약인지 확인
        ReservationStatus status = reservation.getStatus();

        if (status != ReservationStatus.CONFIRMED && status != ReservationStatus.IN_USE) {
            throw new BusinessException(
                    ErrorCode.RESERVATION_STATE_CONFLICT
            );
        }

        LocalDateTime issuedAt =
                LocalDateTime.now(clock);

        // 예약 종료 전인지 확인
        if (!doorAccessTimePolicy.canIssueToken(
                issuedAt,
                reservation.getEndTime()
        )) {
            throw new BusinessException(
                    ErrorCode.RESERVATION_STATE_CONFLICT
            );
        }

        // Reservation -> DoorAccessToken 잠금 순서에 따라 활성 토큰 비관적 락 조회 후 폐기
        doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNullForUpdate(reservationId)
                .ifPresent(activeToken ->
                        activeToken.revoke(
                                issuedAt,
                                "REISSUED"
                        )
                );

        doorAccessTokenRepository.flush();

        // 새로운 원문 토큰 생성
        String rawToken =
                accessTokenGenerator.generate();

        // 원문 토큰을 SHA-256으로 해시
        String tokenHash =
                accessTokenHasher.hash(rawToken);

        // DB에는 해시된 토큰 저장. 같은 예약에 발급 요청이 동시에 들어오면 활성 토큰 UNIQUE 제약
        // (active_reservation_id)이 하나만 통과시키므로, 진 요청은 500이 아니라 409로 돌려준다.
        try {
            create(
                    reservation,
                    tokenHash,
                    issuedAt
            );
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(
                    ErrorCode.RESERVATION_STATE_CONFLICT,
                    "동시에 다른 발급 요청이 처리되었습니다. 다시 시도해주세요."
            );
        }

        // 사용자에게는 원문 토큰 반환
        return DoorAccessTokenResponse.of(
                reservationId,
                rawToken,
                issuedAt
        );
    }

    // 출입 토큰 단건 조회
    public DoorAccessToken findById(Long tokenId) {
        return doorAccessTokenRepository.findById(tokenId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCESS_TOKEN_NOT_FOUND
                ));
    }

    // 토큰 해시로 단건 조회
    public DoorAccessToken findByTokenHash(String tokenHash) {
        return doorAccessTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCESS_TOKEN_NOT_FOUND
                ));
    }

    // 원문 토큰으로 단건 조회
    public DoorAccessToken findByRawToken(String rawToken) {
        String tokenHash =
                accessTokenHasher.hash(rawToken);

        return findByTokenHash(tokenHash);
    }

    // 원문 토큰으로 조회하되, 검증 실패를 정상적인 거절 결과로 처리할 수 있도록 없으면 null 반환
    public DoorAccessToken findByRawTokenOrNull(String rawToken) {
        String tokenHash =
                accessTokenHasher.hash(rawToken);

        return doorAccessTokenRepository.findByTokenHash(tokenHash)
                .orElse(null);
    }

    // 특정 예약의 활성 토큰 조회
    public DoorAccessToken findActiveByReservationId(Long reservationId) {
        return doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACTIVE_ACCESS_TOKEN_NOT_FOUND
                ));
    }

    // 출입 토큰 전체 조회
    public List<DoorAccessToken> findAll() {
        return doorAccessTokenRepository.findAll();
    }

    // 출입 토큰 ID로 폐기 (내부 패키지 전용, 외부 우회 방지)
    @Transactional
    DoorAccessToken revoke(
            Long tokenId,
            LocalDateTime revokedAt,
            String revokeReason
    ) {
        DoorAccessToken token =
                findById(tokenId);

        token.revoke(
                revokedAt,
                revokeReason
        );

        return token;
    }

    // 예약 소유자가 활성 출입 토큰 폐기
    @Transactional
    public void revokeByReservationId(
            Long loginMemberId,
            Long reservationId,
            LocalDateTime revokedAt,
            String revokeReason
    ) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESERVATION_NOT_FOUND
                ));

        doorAccessAuthorizationService.validateOwner(
                loginMemberId,
                reservation.getMemberId()
        );

        DoorAccessToken token = doorAccessTokenRepository
                .findByReservationIdAndRevokedAtIsNullForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACTIVE_ACCESS_TOKEN_NOT_FOUND
                ));

        token.revoke(
                revokedAt,
                revokeReason
        );
    }

    /**
     * 예약 상태 변경에 따른 활성 출입 토큰 폐기 (시스템 내부 경로).
     *
     * [잠금 계약 주의]
     * 본 메서드는 호출자(AdminReservationService.forceCancel, ReservationBatchProcessor 등)가
     * 동일 트랜잭션 내에서 사전에 Reservation 상태 전이(조건부 UPDATE)로 인한 Reservation 행 배타적 잠금(X-Lock)을
     * 이미 획득한 상태에서 호출되어야 한다. 이를 통해 Reservation -> DoorAccessToken 잠금 순서를 일관되게 보장한다.
     */
    @Transactional
    public void revokeByReservation(
            Long reservationId,
            LocalDateTime revokedAt,
            String revokeReason
    ) {
        doorAccessTokenRepository
                .findByReservationIdAndRevokedAtIsNullForUpdate(
                        reservationId
                )
                .ifPresent(token ->
                        token.revoke(
                                revokedAt,
                                revokeReason
                        )
                );
    }
}
