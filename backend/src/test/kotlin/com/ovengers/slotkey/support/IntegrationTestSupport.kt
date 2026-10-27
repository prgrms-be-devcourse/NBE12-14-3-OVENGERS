package com.ovengers.slotkey.support

import com.ovengers.slotkey.auth.entity.AuthSession
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.Member
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
abstract class IntegrationTestSupport {
    @Autowired
    protected lateinit var authSessionService: AuthSessionService

    protected fun issueSessionBoundAccessToken(
        member: Member,
        jwtProvider: JwtProvider,
    ): String {
        val session: AuthSession = authSessionService.create(member)
        return jwtProvider.genAccessToken(
            member,
            session.id,
            session.expiresAt,
        )
    }
}
