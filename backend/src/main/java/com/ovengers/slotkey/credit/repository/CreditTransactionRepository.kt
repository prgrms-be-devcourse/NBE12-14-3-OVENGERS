package com.ovengers.slotkey.credit.repository

import com.ovengers.slotkey.credit.entity.CreditTransaction
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CreditTransactionRepository : JpaRepository<CreditTransaction, Long> {
    // 관리자 회원 상세 - 특정 회원의 크레딧 거래 내역 조회
    @Suppress("ktlint:standard:function-naming")
    fun findByMember_Id(
        memberId: Long?,
        pageable: Pageable,
    ): Page<CreditTransaction>
}
