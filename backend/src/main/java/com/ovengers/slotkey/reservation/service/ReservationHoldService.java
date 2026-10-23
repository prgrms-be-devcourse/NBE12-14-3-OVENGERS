package com.ovengers.slotkey.reservation.service;


import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.policy.ReservationTimePolicy;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.entity.MemberStatus;
import com.ovengers.slotkey.member.repository.MemberRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 1단계: HOLD 생성(core-domain-decisions 2-1). 공간 검증 -> 가격계산 -> 회원 잠금 및 탈퇴 검증 -> HOLD 저장 -> 슬롯확보까지
 * 하나의 트랜잭션이며, 결제는 이 단계에 없다(구 ReservationCreateService는 1단계 설계
 * 흔적이라 2단계 확정 이후 역할을 HOLD 생성으로 좁히며 이 이름으로 정리했다).
 *
 * Space 공유 잠금 및 공간/시간/가격 검증 후, 예약 INSERT 이전에 비관적 락(findByIdForUpdate)으로
 * 회원을 조회하여 회원 존재 여부와 탈퇴 여부(WITHDRAWN)를 직접 검증한다.
 * 예약 INSERT 시 FK 검사로 회원 행에 공유 잠금(S)이 걸리므로, INSERT 전에 회원 배타 잠금(X)을
 * 선점하여 동일 회원의 동시 HOLD 요청 간 S->X 잠금 전환 데드락(MySQL 1213)을 방지한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationHoldService {

    private static final int HOLD_MINUTES = 10;
    private final MemberRepository memberRepository;
    private final SpaceRepository spaceRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationStatusHistoryRepository reservationStatusHistoryRepository;
    private final PricingService pricingService;
    private final ReservationSlotService reservationSlotService;
    private final Clock clock;

    @Transactional
    public ReservationResponse createHold(Long memberId, Long spaceId, LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime now = LocalDateTime.now(clock);

        Space space = spaceRepository.findByIdForShare(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
        if (space.getStatus() != SpaceStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SPACE_INACTIVE);
        }

        ReservationTimePolicy.validate(startTime, endTime, space.getOpeningTime(), space.getClosingTime(), now);

        int slotCount = pricingService.calculateSlotCount(startTime, endTime);
        int pricePerSlotSnapshot = Math.toIntExact(space.getPricePerSlot());
        int totalAmount = pricingService.calculateTotalAmount(pricePerSlotSnapshot, slotCount);

        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
                );

        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.ACCOUNT_WITHDRAWN);
        }

        Reservation reservation = Reservation.createHeld(
                memberId, spaceId, startTime, endTime,
                pricePerSlotSnapshot, totalAmount,
                now, now.plusMinutes(HOLD_MINUTES)
        );
        reservation = reservationRepository.save(reservation);

        List<LocalDateTime> slotStarts = reservationSlotService.buildSlotStarts(startTime, endTime);
        // 슬롯 확보 실패(RESERVATION_SLOT_CONFLICT) 시 예외가 전파되어 위의 예약 INSERT를
        // 포함한 트랜잭션 전체가 롤백된다 — HELD 잔재가 남지 않는다.
        reservationSlotService.secureSlots(reservation.getId(), spaceId, slotStarts);

        reservationStatusHistoryRepository.save(
                ReservationStatusHistory.of(reservation.getId(), memberId, null, ReservationStatus.HELD, null, now)
        );

        return ReservationResponse.from(reservation, space.getVersion());
    }
}
