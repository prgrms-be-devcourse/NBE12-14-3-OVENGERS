package com.ovengers.slotkey.auth.mail

import com.ovengers.slotkey.auth.entity.VerificationPurpose

fun interface VerificationMailSender {
    fun sendCode(
        email: String,
        purpose: VerificationPurpose,
        rawCode: String,
    )
}
