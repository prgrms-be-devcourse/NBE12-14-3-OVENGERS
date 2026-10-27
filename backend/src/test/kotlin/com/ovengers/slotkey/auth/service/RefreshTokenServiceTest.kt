package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.RefreshToken
import com.ovengers.slotkey.auth.repository.RefreshTokenRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.util.HexFormat

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
@Transactional
class RefreshTokenServiceTest {
    @Autowired
    private lateinit var refreshTokenService: RefreshTokenService

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var clock: Clock

    @Test
    @DisplayName("리프레시 토큰의 DB 만료 시각은 발급 시점부터 약 24시간이다")
    fun t1() {
        // 1. 회원 준비
        val member: Member =
            memberRepository.save(
                Member(
                    "refresh-lifetime@example.com",
                    "테스트회원",
                ),
            )

        // 2. 발급 직전과 직후의 시각 기록
        val beforeIssue: LocalDateTime = LocalDateTime.now(clock)

        val issued = refreshTokenService.issueRefreshToken(member)
        val rawToken: String = issued.rawToken

        val afterIssue: LocalDateTime = LocalDateTime.now(clock)

        assertThat(rawToken).isNotBlank()

        // 3. 반환된 원문을 해시해서 해당 DB 기록을 찾는다.
        val tokenHash: String =
            HexFormat.of().formatHex(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(rawToken.toByteArray(StandardCharsets.UTF_8)),
            )

        // 메모리에 있는 객체가 아닌 DB 저장 값을 확인
        entityManager.flush()
        entityManager.clear()

        val savedToken: RefreshToken =
            refreshTokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow()

        // 4. 발급 시점 + 24시간 범위에 있는지 확인
        // DB 시간 정밀도 차이를 고려해 1초의 여유를 둔다.
        assertThat(savedToken.expiresAt).isBetween(
            beforeIssue.plusHours(24).minusSeconds(1),
            afterIssue.plusHours(24).plusSeconds(1),
        )
        assertThat(savedToken.authSession.id).isEqualTo(issued.sessionId)
    }
}
