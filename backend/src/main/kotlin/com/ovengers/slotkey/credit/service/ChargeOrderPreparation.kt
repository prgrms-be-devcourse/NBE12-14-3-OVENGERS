package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.pg.PgConfirmRequest
import com.ovengers.slotkey.global.error.ErrorCode

@JvmRecord
data class ChargeOrderPreparation(
    val pgRequest: PgConfirmRequest?,
    val currentResponse: ChargeOrderConfirmResponse?,
    val rejection: ErrorCode?,
) {
    fun shouldCallPg(): Boolean = pgRequest != null

    companion object {
        @JvmStatic
        fun callPg(request: PgConfirmRequest): ChargeOrderPreparation = ChargeOrderPreparation(request, null, null)

        @JvmStatic
        fun current(response: ChargeOrderConfirmResponse): ChargeOrderPreparation = ChargeOrderPreparation(null, response, null)

        @JvmStatic
        fun reject(errorCode: ErrorCode): ChargeOrderPreparation = ChargeOrderPreparation(null, null, errorCode)
    }
}
