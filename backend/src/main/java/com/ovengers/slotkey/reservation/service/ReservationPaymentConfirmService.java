package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.credit.service.CreditService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.global.idempotency.IdempotencyService;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 예약 2단계: 결제 확인(core-domain-decisions 2-1, core-domain-decisions 6-2). 돈이 움직이는 지점이라 Idempotency-Key가 여기 붙는다
 * (구 계획의 "8. 멱등성 연동"을 이 서비스에 통합).
 *
 * 순서가 중요하다: 크레딧 차감 -> 조건부 UPDATE(HELD -> CONFIRMED). 조건부 UPDATE가
 * 0행이면 예외를 던져 트랜잭션 전체를 롤백시킨다 — 크레딧 차감도 함께 롤백되어
 * "취소는 성공했는데 환불은 실패한" 것과 대칭인 "차감은 됐는데 확정은 안 된" 중간 상태가
 * 구조적으로 존재하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReservationPaymentConfirmService {

    private static final String REQUEST_PATH_TEMPLATE = "/reservations/%d/pay";

    private final ReservationRepository reservationRepository;
    private final ReservationStatusHistoryRepository reservationStatusHistoryRepository;
    private final SpaceRepository spaceRepository;
    private final CreditService creditService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    @Transactional
    public ReservationResponse confirm(Long memberId, Long reservationId, int expectedSpaceVersion, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String requestPath = REQUEST_PATH_TEMPLATE.formatted(reservationId);

        var cached = idempotencyService.find(idempotencyKey, memberId, requestPath, ReservationResponse.class);
        if (cached != null) {
            return cached;
        }

        // 1. 잠금 순서(Space -> Reservation) 준수를 위해 reservationId로부터 spaceId와 memberId를 먼저
        // 투영 조회
        var targetInfo = reservationRepository.findTargetInfoById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // 2. 비소유자의 접근을 Space 잠금 및 version 검증 전에 차단 (정보 탐색 및 불필요한 락 획득 방지)
        if (!Objects.equals(targetInfo.memberId(), memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER);
        }

        // 3. Space 공유 락 획득 및 버전 검증
        Space space = spaceRepository.findByIdForShare(targetInfo.spaceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
        if (space.getVersion() != expectedSpaceVersion) {
            throw new BusinessException(ErrorCode.SPACE_VERSION_MISMATCH);
        }

        // 4. Reservation 조회
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // 크레딧 차감(잔액 부족 시 BusinessException(INSUFFICIENT_BALANCE) — 여기서 전파되어
        // 트랜잭션이 롤백되므로 예약은 HOLD로 남는다).
        creditService.charge(memberId, reservationId, reservation.getTotalAmount());

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = reservationRepository.confirmIfHeldAndNotExpired(
                reservationId, now, ReservationStatus.HELD, ReservationStatus.CONFIRMED);
        if (updated == 0) {
            // 크레딧 차감을 포함해 이 트랜잭션 전체가 롤백된다.
            throw new BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "HOLD가 만료되었거나 이미 처리된 예약입니다.");
        }

        Reservation confirmed = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        reservationStatusHistoryRepository.save(
                ReservationStatusHistory.of(reservationId, memberId, ReservationStatus.HELD, ReservationStatus.CONFIRMED, null, now)
        );

        ReservationResponse response = ReservationResponse.from(confirmed);
        idempotencyService.save(idempotencyKey, memberId, requestPath, 200, response);
        return response;
    }
}
