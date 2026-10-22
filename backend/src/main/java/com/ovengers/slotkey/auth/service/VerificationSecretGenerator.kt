package com.ovengers.slotkey.auth.service

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.*

@Component
class VerificationSecretGenerator {
    private val secureRandom = SecureRandom()

    fun generateCode(): String? {
        return String.format(
            Locale.ROOT,
            "%08d",
            secureRandom.nextInt(100000000)
        )
    }

    fun generateGrant(): String? {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}