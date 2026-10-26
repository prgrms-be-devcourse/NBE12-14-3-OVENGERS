package com.ovengers.slotkey.access.service;

import com.ovengers.slotkey.access.entity.AccessDenyReason;
import com.ovengers.slotkey.access.entity.AccessResult;
import com.ovengers.slotkey.access.entity.DoorAccessLog;
import com.ovengers.slotkey.access.repository.DoorAccessLogRepository;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class InactiveMemberAccessLogService {

    private final MemberRepository memberRepository;
    private final DoorAccessLogRepository doorAccessLogRepository;

    public InactiveMemberAccessLogService(
            MemberRepository memberRepository,
            DoorAccessLogRepository doorAccessLogRepository
    ) {
        this.memberRepository = memberRepository;
        this.doorAccessLogRepository = doorAccessLogRepository;
    }

    // 인증 단계에서 비활성으로 판정된 회원의 출입 시도 기록
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long memberId,
            LocalDateTime attemptedAt
    ) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MEMBER_NOT_FOUND
                ));

        DoorAccessLog accessLog = new DoorAccessLog(
                member,
                null,
                null,
                AccessResult.DENY,
                AccessDenyReason.MEMBER_INACTIVE,
                attemptedAt
        );

        doorAccessLogRepository.save(accessLog);
    }
}