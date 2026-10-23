package com.ovengers.slotkey.member.dto.response

import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import java.time.LocalDateTime

@JvmRecord
data class AdminMemberCreditTransactionResponse(
    val transactionId: Long,
    val type: CreditTransactionType,
    val amount: Int,
    val balanceAfter: Int,
    val reason: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(transaction: CreditTransaction) =
            AdminMemberCreditTransactionResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getBalanceAfter(),
                transaction.getReason(),
                transaction.getCreatedAt(),
            )
    }
}
