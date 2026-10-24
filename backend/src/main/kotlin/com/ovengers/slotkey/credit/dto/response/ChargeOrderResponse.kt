package com.ovengers.slotkey.credit.dto.response

import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus

@JvmRecord
data class ChargeOrderResponse(
    val orderId: String,
    val amount: Long,
    val status: ChargeOrderStatus,
) {
    companion object {
        @JvmStatic
        fun from(chargeOrder: ChargeOrder): ChargeOrderResponse =
            ChargeOrderResponse(chargeOrder.orderId, chargeOrder.amount, chargeOrder.status)
    }
}
