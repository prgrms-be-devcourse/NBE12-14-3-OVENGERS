package com.ovengers.slotkey.credit.dto.response

import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus

@JvmRecord
data class ChargeOrderConfirmResponse(
    val orderId: String,
    val amount: Long,
    val status: ChargeOrderStatus,
) {
    companion object {
        @JvmStatic
        fun from(chargeOrder: ChargeOrder): ChargeOrderConfirmResponse =
            ChargeOrderConfirmResponse(chargeOrder.orderId, chargeOrder.amount, chargeOrder.status)
    }
}
