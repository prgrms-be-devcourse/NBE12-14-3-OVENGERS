package com.ovengers.slotkey.credit.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import java.time.LocalDateTime

/** 내 충전 내역 한 줄. 결제수단과 제공사는 PG 응답에 없을 수 있다. */
@JvmRecord
data class ChargeOrderHistoryResponse(
    val orderId: String,
    val amount: Long,
    val status: ChargeOrderStatus,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentMethod: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentProvider: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(order: ChargeOrder): ChargeOrderHistoryResponse =
            ChargeOrderHistoryResponse(
                order.orderId,
                order.amount,
                order.status,
                order.paymentMethod,
                order.paymentProvider,
                order.createdAt,
                order.updatedAt,
            )
    }
}
