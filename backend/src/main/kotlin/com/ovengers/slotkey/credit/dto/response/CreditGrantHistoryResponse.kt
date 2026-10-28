package com.ovengers.slotkey.credit.dto.response

import com.ovengers.slotkey.credit.entity.CreditTransaction
import java.time.LocalDateTime

/** 내 관리자 지급 내역 한 줄. 사유는 지급 알림에 보낸 내용과 같다. */
@JvmRecord
data class CreditGrantHistoryResponse(
    val transactionId: Long,
    val amount: Int,
    val reason: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(transaction: CreditTransaction): CreditGrantHistoryResponse =
            CreditGrantHistoryResponse(
                requireNotNull(transaction.id),
                transaction.amount,
                transaction.reason,
                transaction.createdAt,
            )
    }
}
