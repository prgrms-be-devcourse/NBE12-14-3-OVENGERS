@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.auth.entity.LocalCredential
import com.ovengers.slotkey.auth.entity.RefreshToken
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.repository.RefreshTokenRepository
import com.ovengers.slotkey.auth.service.AuthService
import com.ovengers.slotkey.auth.service.PasswordResetService
import com.ovengers.slotkey.auth.service.VerificationChallengeService
import com.ovengers.slotkey.auth.service.VerificationCheckService
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.HexFormat
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * V2 보안 회귀 테스트.
 *
 * 완료된 V2 보안 범위의 요구사항을 회귀 테스트로 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MySqlTestContainerConfig::class, SettableClockConfig::class)
internal class V2SecurityRedTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @SpyBean
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var localCredentialRepository: LocalCredentialRepository

    @SpyBean
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var passwordResetService: PasswordResetService

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var challengeService: VerificationChallengeService

    @Autowired
    private lateinit var checkService: VerificationCheckService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var settableClock: SettableClock

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @BeforeEach fun setTestTime() {
        settableClock.set(
            ZonedDateTime.of(
                2027,
                3,
                1,
                0,
                1,
                0,
                0,
                SettableClockConfig.KST,
            ),
        )
    }

    @Test
    @DisplayName("V2: 로그아웃하면 기존 Access Token은 즉시 폐기된다")
    fun logout_invalidates_existing_access_token() {
        val email: String = newEmail("logout")
        val login: MvcResult = login(email)
        val accessToken: String = accessToken(login)
        val refreshCookie = refreshCookie(login)

        mvc
            .perform(post("/api/v1/auth/logout").cookie(refreshCookie))
            .andExpect(status().isNoContent())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("V2: 비밀번호 재설정이 성공하면 기존 Access Token은 즉시 폐기된다")
    fun password_reset_invalidates_existing_access_token() {
        val email: String = newEmail("password-reset")
        val login: MvcResult = login(email)
        val accessToken: String = accessToken(login)
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        mvc
            .perform(
                post("/api/v1/auth/password-reset")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("email" to email, "grant" to grant, "password" to "New1234!", "passwordConfirm" to "New1234!"),
                        ),
                    ),
            ).andExpect(status().isOk())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("V2: Refresh Token은 재사용할 수 없다")
    fun refresh_token_is_rotated_and_cannot_be_reused() {
        val email: String = newEmail("rotation")
        val login: MvcResult = login(email)
        val oldAccessToken: String = accessToken(login)
        val originalRefreshCookie = refreshCookie(login)
        val siblingLogin: MvcResult = loginExisting(email, PASSWORD)
        val siblingAccessToken: String = accessToken(siblingLogin)
        val siblingRefreshCookie: Cookie = refreshCookie(siblingLogin)

        val rotation: MvcResult =
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(originalRefreshCookie),
                ).andExpect(status().isOk())
                .andReturn()
        val rotatedCookie: Cookie = refreshCookie(rotation)

        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(originalRefreshCookie),
            ).andExpect(status().isUnauthorized())

        assertThat(refreshToken(originalRefreshCookie.value).consumedAt)
            .isNotNull()
        assertThat(refreshToken(originalRefreshCookie.value).revokedAt)
            .isNotNull()
        assertThat(refreshToken(rotatedCookie.value).revokedAt)
            .isNotNull()
        assertThatSessionRevoked(originalRefreshCookie.value)

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $oldAccessToken"),
            ).andExpect(status().isUnauthorized())
        mvc
            .perform(post("/api/v1/auth/refresh").cookie(rotatedCookie))
            .andExpect(status().isUnauthorized())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $siblingAccessToken"),
            ).andExpect(status().isOk())
        mvc
            .perform(post("/api/v1/auth/refresh").cookie(siblingRefreshCookie))
            .andExpect(status().isOk())
    }

    @Test
    @DisplayName("V2: 관리자가 회원을 정지하면 기존 AT와 RT 세션이 영구 폐기된다")
    fun suspended_member_cannot_use_existing_access_token() {
        val email: String = newEmail("suspended")
        val firstLogin: MvcResult = login(email)
        val secondLogin: MvcResult = loginExisting(email, PASSWORD)
        val firstAccessToken: String = accessToken(firstLogin)
        val secondAccessToken: String = accessToken(secondLogin)
        val firstRefreshToken: Cookie = refreshCookie(firstLogin)
        val secondRefreshToken: Cookie = refreshCookie(secondLogin)

        val member: Member = memberRepository.findByEmail(email).orElseThrow()
        val admin: Member = createAdminWithCredential()
        val adminAccessToken: String =
            accessToken(loginExisting(admin.email, PASSWORD))

        mvc
            .perform(
                patch("/api/v1/admin/members/{memberId}/suspend", member.id!!)
                    .header("Authorization", "Bearer $adminAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("reason" to "V2 세션 폐기 검증"),
                        ),
                    ),
            ).andExpect(status().isOk())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $firstAccessToken"),
            ).andExpect(status().isUnauthorized())
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $secondAccessToken"),
            ).andExpect(status().isUnauthorized())

        mvc
            .perform(post("/api/v1/auth/refresh").cookie(firstRefreshToken))
            .andExpect(status().isUnauthorized())
        mvc
            .perform(post("/api/v1/auth/refresh").cookie(secondRefreshToken))
            .andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("V2: 성공·실패 합산 10회 로그인은 허용하고 11번째 요청은 제한한다")
    fun repeated_login_failures_are_rate_limited() {
        val email: String = newEmail("rate-limit")
        val successfulLogin: MvcResult = login(email)

        for (attempt in 2..10) {
            mvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf("email" to email, "password" to "Wrong1234!"),
                            ),
                        ),
                ).andExpect(status().isUnauthorized())
        }

        mvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("email" to email, "password" to "Wrong1234!"),
                        ),
                    ),
            ).andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "840"))
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer ${accessToken(successfulLogin)}"),
            ).andExpect(status().isOk())
    }

    @Test
    @DisplayName("V2: 정상 사용자는 RT Rotation 후 새 RT로 계속 갱신할 수 있다")
    fun refresh_token_rotation_allows_next_refresh_with_new_token() {
        val email: String = newEmail("rotation-success")
        val login: MvcResult = login(email)
        val rt1: Cookie = refreshCookie(login)
        val sessionExpiry: LocalDateTime = refreshToken(rt1.value).expiresAt
        assertThat(cookieMaxAge(login)).isEqualTo(24 * 60 * 60L)

        settableClock.set(
            ZonedDateTime.of(
                2027,
                3,
                1,
                4,
                1,
                0,
                0,
                SettableClockConfig.KST,
            ),
        )

        val firstRefresh: MvcResult =
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(rt1),
                ).andExpect(status().isOk())
                .andReturn()

        assertAccessTokenLifetime(accessToken(firstRefresh), sessionExpiry)
        val rt2: Cookie = refreshCookie(firstRefresh)
        assertThat(rt2)
            .`as`("첫 refresh 응답은 RT2를 발급해야 한다")
            .isNotNull()
        assertThat(rt2.value).isNotBlank().isNotEqualTo(rt1.value)
        assertThat(cookieMaxAge(firstRefresh)).isEqualTo(20 * 60 * 60L)
        assertThat(refreshToken(rt1.value).consumedAt)
            .isEqualTo(LocalDateTime.of(2027, 3, 1, 4, 1))
        assertThat(refreshToken(rt2.value).expiresAt).isEqualTo(sessionExpiry)

        settableClock.set(
            ZonedDateTime.of(
                2027,
                3,
                1,
                7,
                1,
                0,
                0,
                SettableClockConfig.KST,
            ),
        )

        val secondRefresh: MvcResult =
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(rt2),
                ).andExpect(status().isOk())
                .andReturn()

        val rt3: Cookie = refreshCookie(secondRefresh)
        assertThat(rt3)
            .`as`("두 번째 refresh 응답은 RT3를 발급해야 한다")
            .isNotNull()
        assertThat(rt3.value).isNotBlank().isNotEqualTo(rt2.value)
        assertAccessTokenLifetime(accessToken(secondRefresh), sessionExpiry)
        assertThat(secondRefresh.response.getHeader("Set-Cookie"))
            .contains("HttpOnly", "Max-Age=")
        assertThat(cookieMaxAge(secondRefresh)).isEqualTo(17 * 60 * 60L)
        assertThat(refreshToken(rt2.value).consumedAt)
            .isEqualTo(LocalDateTime.of(2027, 3, 1, 7, 1))
        assertThat(refreshToken(rt3.value).expiresAt).isEqualTo(sessionExpiry)

        settableClock.set(
            ZonedDateTime.of(
                2027,
                3,
                2,
                0,
                1,
                0,
                0,
                SettableClockConfig.KST,
            ),
        )
        mvc
            .perform(post("/api/v1/auth/refresh").cookie(rt3))
            .andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("V2: 비밀번호 재설정 후 기존 Refresh Token은 폐기된다")
    fun password_reset_invalidates_existing_refresh_token() {
        val email: String = newEmail("password-reset-refresh")
        val login: MvcResult = login(email)
        val oldRefreshToken: Cookie = refreshCookie(login)
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        resetPassword(email, grant, NEW_PASSWORD)

        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(oldRefreshToken),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("V2: 비밀번호 재설정은 여러 로그인 세션의 AT와 RT를 모두 폐기한다")
    fun password_reset_invalidates_all_existing_sessions() {
        val email: String = newEmail("password-reset-sessions")
        val otherEmail: String = newEmail("unaffected-member")
        createMemberWithCredential(email, PASSWORD)
        val otherMemberLogin: MvcResult = login(otherEmail)
        val otherMemberAccessToken: String = accessToken(otherMemberLogin)
        val otherMemberRefreshToken: Cookie = refreshCookie(otherMemberLogin)

        val firstLogin: MvcResult = loginExisting(email, PASSWORD)
        val secondLogin: MvcResult = loginExisting(email, PASSWORD)
        val at1: String = accessToken(firstLogin)
        val at2: String = accessToken(secondLogin)
        val rt1: Cookie = refreshCookie(firstLogin)
        val rt2: Cookie = refreshCookie(secondLogin)
        assertThat(rt1.value).isNotEqualTo(rt2.value)

        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)
        resetPassword(email, grant, NEW_PASSWORD)

        val at1Status: Int =
            mvc
                .perform(
                    get("/api/v1/members/me")
                        .header("Authorization", "Bearer $at1"),
                ).andReturn()
                .response
                .status
        val at2Status: Int =
            mvc
                .perform(
                    get("/api/v1/members/me")
                        .header("Authorization", "Bearer $at2"),
                ).andReturn()
                .response
                .status
        val rt1Status: Int =
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(rt1),
                ).andReturn()
                .response
                .status
        val rt2Status: Int =
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(rt2),
                ).andReturn()
                .response
                .status

        assertThat(listOf(at1Status, at2Status, rt1Status, rt2Status))
            .`as`("비밀번호 재설정 후 모든 기존 세션은 401이어야 한다")
            .containsOnly(401)

        val freshLogin: MvcResult = loginExisting(email, NEW_PASSWORD)
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer ${accessToken(freshLogin)}"),
            ).andExpect(status().isOk())
        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(refreshCookie(freshLogin)),
            ).andExpect(status().isOk())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $otherMemberAccessToken"),
            ).andExpect(status().isOk())
        mvc
            .perform(post("/api/v1/auth/refresh").cookie(otherMemberRefreshToken))
            .andExpect(status().isOk())
    }

    @Test
    @DisplayName("V2: 회원 정지 후 복구해도 정지 전 AT와 RT는 다시 살아나지 않는다")
    fun member_restore_does_not_revive_old_session() {
        val email: String = newEmail("restore-session")
        val login: MvcResult = login(email)
        val oldAccessToken: String = accessToken(login)
        val oldRefreshToken: Cookie = refreshCookie(login)
        val member: Member = memberRepository.findByEmail(email).orElseThrow()
        val admin: Member = createAdminWithCredential()
        val adminAccessToken: String =
            accessToken(
                loginExisting(admin.email, PASSWORD),
            )

        mvc
            .perform(
                patch("/api/v1/admin/members/{memberId}/suspend", member.id!!)
                    .header("Authorization", "Bearer $adminAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("reason" to "V2 레드 테스트 정지"),
                        ),
                    ),
            ).andExpect(status().isOk())

        mvc
            .perform(
                patch("/api/v1/admin/members/{memberId}/restore", member.id!!)
                    .header("Authorization", "Bearer $adminAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("reason" to "V2 레드 테스트 복구"),
                        ),
                    ),
            ).andExpect(status().isOk())

        val oldAccessStatus: Int =
            mvc
                .perform(
                    get("/api/v1/members/me")
                        .header("Authorization", "Bearer $oldAccessToken"),
                ).andReturn()
                .response
                .status
        val oldRefreshStatus: Int =
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(oldRefreshToken),
                ).andReturn()
                .response
                .status

        assertThat(listOf(oldAccessStatus, oldRefreshStatus))
            .`as`("회원 정지·복구 후 정지 전 세션은 모두 401이어야 한다")
            .containsOnly(401)

        val freshLogin: MvcResult = loginExisting(email, PASSWORD)
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer ${accessToken(freshLogin)}"),
            ).andExpect(status().isOk())
        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(refreshCookie(freshLogin)),
            ).andExpect(status().isOk())
    }

    @Test
    @DisplayName("V2: 회원탈퇴 후 기존 모든 로그인 세션의 AT와 RT가 거부된다")
    fun withdrawal_invalidates_existing_access_token() {
        val email: String = newEmail("withdrawal")
        val firstLogin: MvcResult = login(email)
        val secondLogin: MvcResult = loginExisting(email, PASSWORD)
        val firstAccessToken: String = accessToken(firstLogin)
        val secondAccessToken: String = accessToken(secondLogin)
        val firstRefreshToken: Cookie = refreshCookie(firstLogin)
        val secondRefreshToken: Cookie = refreshCookie(secondLogin)

        mvc
            .perform(
                post("/api/v1/auth/withdraw")
                    .header("Authorization", "Bearer $firstAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("currentPassword" to PASSWORD),
                        ),
                    ),
            ).andExpect(status().isNoContent())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $firstAccessToken"),
            ).andExpect(status().isUnauthorized())
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $secondAccessToken"),
            ).andExpect(status().isUnauthorized())

        mvc
            .perform(post("/api/v1/auth/refresh").cookie(firstRefreshToken))
            .andExpect(status().isUnauthorized())
        mvc
            .perform(post("/api/v1/auth/refresh").cookie(secondRefreshToken))
            .andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("V2: 인증 최종 잠금의 오류 응답 후 정지·세션 폐기는 커밋되고 복구 후에도 옛 토큰은 거부된다")
    fun final_verification_lock_commits_and_permanently_revokes_sessions() {
        val email: String = newEmail("verification-final-lock")
        val userLogin: MvcResult = login(email)
        val oldAccessToken: String = accessToken(userLogin)
        val oldRefreshToken: Cookie = refreshCookie(userLogin)
        val member: Member = memberRepository.findByEmail(email).orElseThrow()

        val admin: Member = createAdminWithCredential()
        val adminAccessToken: String =
            accessToken(loginExisting(admin.email, PASSWORD))
        val purpose: VerificationPurpose = VerificationPurpose.PASSWORD_RESET

        for (stage in 1..3) {
            val issued: IssuedChallenge = issueChallengeFromApi(email, purpose)
            val wrongCode: String =
                if (issued.deliveredCode.equals("00000000")) {
                    "00000001"
                } else {
                    "00000000"
                }

            for (attempt in 1..5) {
                val response =
                    mvc
                        .perform(
                            post("/api/v1/auth/verification/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    objectMapper.writeValueAsString(
                                        mapOf(
                                            "email" to email,
                                            "purpose" to purpose.name,
                                            "challengeId" to issued.challengeId,
                                            "code" to wrongCode,
                                        ),
                                    ),
                                ),
                        ).andReturn()
                        .response

                if (attempt < 5) {
                    assertThat(response.status).isEqualTo(400)
                    assertThat(
                        objectMapper
                            .readTree(response.contentAsString)
                            .path("code")
                            .asText(),
                    ).isEqualTo("VERIFICATION_CODE_MISMATCH")
                } else if (stage < 3) {
                    assertThat(response.status).isEqualTo(429)
                    assertThat(
                        objectMapper
                            .readTree(response.contentAsString)
                            .path("code")
                            .asText(),
                    ).isEqualTo("VERIFICATION_TEMPORARILY_BLOCKED")
                } else {
                    assertThat(response.status).isEqualTo(403)
                    assertThat(
                        objectMapper
                            .readTree(response.contentAsString)
                            .path("code")
                            .asText(),
                    ).isEqualTo("VERIFICATION_ADMIN_LOCKED")
                }
            }

            if (stage < 3) {
                releaseVerificationBlock(email, purpose)
            }
        }

        assertThat(memberRepository.findById(member.id!!).orElseThrow().status)
            .isEqualTo(MemberStatus.SUSPENDED)

        mvc
            .perform(
                patch("/api/v1/admin/members/{memberId}/restore", member.id!!)
                    .header("Authorization", "Bearer $adminAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("reason" to "V2 인증 잠금 복구 검증"),
                        ),
                    ),
            ).andExpect(status().isOk())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $oldAccessToken"),
            ).andExpect(status().isUnauthorized())
        mvc
            .perform(post("/api/v1/auth/refresh").cookie(oldRefreshToken))
            .andExpect(status().isUnauthorized())

        val freshLogin: MvcResult = loginExisting(email, PASSWORD)
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer ${accessToken(freshLogin)}"),
            ).andExpect(status().isOk())
    }

    @Test
    @DisplayName("V2: 비밀번호 재설정과 이미 시작된 refresh가 경합해도 옛 세션은 재사용할 수 없다")
    fun password_reset_racing_refresh_cannot_leave_old_session_usable() {
        val email: String = newEmail("reset-refresh-race")
        val login: MvcResult = login(email)
        val oldAccessToken: String = accessToken(login)
        val oldRefreshToken: Cookie = refreshCookie(login)
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val resetFuture =
                executor.submit(
                    Callable {
                        concurrentRequest(
                            ready,
                            start,
                            Callable<MvcResult> {
                                mvc
                                    .perform(
                                        post("/api/v1/auth/password-reset")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                objectMapper.writeValueAsString(
                                                    mapOf(
                                                        "email" to email,
                                                        "grant" to grant,
                                                        "password" to NEW_PASSWORD,
                                                        "passwordConfirm" to NEW_PASSWORD,
                                                    ),
                                                ),
                                            ),
                                    ).andReturn()
                            },
                        )
                    },
                )
            val refreshFuture =
                executor.submit(
                    Callable {
                        concurrentRequest(
                            ready,
                            start,
                            Callable<MvcResult> {
                                mvc
                                    .perform(
                                        post("/api/v1/auth/refresh")
                                            .cookie(oldRefreshToken),
                                    ).andReturn()
                            },
                        )
                    },
                )

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val resetResult = resetFuture.get(10, TimeUnit.SECONDS)
            val refreshResult = refreshFuture.get(10, TimeUnit.SECONDS)
            assertThat(resetResult.failure).isNull()
            assertThat(refreshResult.failure).isNull()
            assertThat(resetResult.status).isEqualTo(200)
            assertThat(refreshResult.status).isIn(200, 401)

            mvc
                .perform(
                    get("/api/v1/members/me")
                        .header("Authorization", "Bearer $oldAccessToken"),
                ).andExpect(status().isUnauthorized())
            mvc
                .perform(post("/api/v1/auth/refresh").cookie(oldRefreshToken))
                .andExpect(status().isUnauthorized())

            if (refreshResult.status == 200) {
                assertThat(refreshResult.accessToken).isNotBlank()
                mvc
                    .perform(
                        get("/api/v1/members/me")
                            .header("Authorization", "Bearer ${refreshResult.accessToken}"),
                    ).andExpect(status().isUnauthorized())
            }

            val freshLogin: MvcResult = loginExisting(email, NEW_PASSWORD)
            mvc
                .perform(
                    get("/api/v1/members/me")
                        .header("Authorization", "Bearer ${accessToken(freshLogin)}"),
                ).andExpect(status().isOk())
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun concurrentRequest(
        ready: CountDownLatch,
        start: CountDownLatch,
        request: Callable<MvcResult>,
    ): ConcurrentResponse {
        ready.countDown()
        try {
            check(start.await(10, TimeUnit.SECONDS)) { "동시 계정 사건 요청 시작 대기 시간 초과" }
            val result: MvcResult = request.call()
            val accessToken: String? =
                if (result.response.status == 200 &&
                    result.response.contentType != null
                ) {
                    objectMapper
                        .readTree(result.response.contentAsString)
                        .path("data")
                        .path("accessToken")
                        .asText(null)
                } else {
                    null
                }
            return ConcurrentResponse(result.response.status, accessToken, null)
        } catch (failure: Throwable) {
            return ConcurrentResponse(-1, null, failure)
        }
    }

    @Test
    @DisplayName("V2: 동일 Refresh Token 동시 사용은 정확히 하나만 성공한다")
    fun same_refresh_token_concurrent_use_allows_exactly_one_request() {
        val login: MvcResult = login(newEmail("refresh-concurrent"))
        val oldAccessToken: String = accessToken(login)
        val refreshToken: Cookie = refreshCookie(login)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        val futures = mutableListOf<Future<RefreshAttempt>>()

        val request =
            Callable<RefreshAttempt> {
                ready.countDown()
                if (!start.await(10, TimeUnit.SECONDS)) {
                    return@Callable RefreshAttempt.failed(
                        IllegalStateException(
                            "동시 refresh 시작 신호 대기 시간 초과",
                        ),
                    )
                }
                try {
                    val result: MvcResult =
                        mvc
                            .perform(
                                post("/api/v1/auth/refresh")
                                    .cookie(refreshToken),
                            ).andReturn()
                    return@Callable RefreshAttempt.success(
                        result.response.status,
                        result.response.getCookie("refreshToken"),
                    )
                } catch (failure: Throwable) {
                    return@Callable RefreshAttempt.failed(failure)
                }
            }

        try {
            futures.add(executor.submit(request))
            futures.add(executor.submit(request))
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(results).allSatisfy { result -> assertThat(result.failure).isNull() }
            assertThat(results.filter { it.status == 200 }).hasSize(1)
            assertThat(results.filter { it.status == 401 }).hasSize(1)
            val winningAttempt = results.first { it.status == 200 }
            assertThat(winningAttempt.newRefreshToken)
                .`as`("동시 refresh 성공 응답은 새 RT를 발급해야 한다")
                .isNotNull()

            assertThat(refreshToken(refreshToken.value).consumedAt).isNotNull()
            assertThat(refreshToken(refreshToken.value).revokedAt).isNotNull()
            assertThat(refreshToken(winningAttempt.newRefreshToken!!.value).revokedAt)
                .isNotNull()
            assertThatSessionRevoked(refreshToken.value)

            mvc
                .perform(
                    get("/api/v1/members/me")
                        .header("Authorization", "Bearer $oldAccessToken"),
                ).andExpect(status().isUnauthorized())
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(winningAttempt.newRefreshToken),
                ).andExpect(status().isUnauthorized())
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("V2: 새 RT 저장 실패 시 RT1 소비가 롤백되어 다시 교체할 수 있다")
    fun replacement_insert_failure_rolls_back_consumption() {
        val login: MvcResult = login(newEmail("rotation-rollback"))
        val rt1: Cookie = refreshCookie(login)
        doThrow(IllegalStateException("replacement insert failure"))
            .whenever(refreshTokenRepository)
            .saveAndFlush(any<RefreshToken>())

        assertThatThrownBy { authService.refresh(rt1.value) }.isInstanceOf(RuntimeException::class.java)

        assertThat(refreshToken(rt1.value).consumedAt).isNull()
        assertThat(refreshToken(rt1.value).revokedAt).isNull()

        reset(refreshTokenRepository)
        val retried = authService.refresh(rt1.value)
        assertThat(retried.accessToken).isNotBlank()
        assertThat(retried.refreshToken).isNotBlank().isNotEqualTo(rt1.value)
        assertThat(refreshToken(rt1.value).consumedAt).isNotNull()
    }

    @Test
    @DisplayName("V2: RT 식별 정보 조회 후 재설정이 먼저 커밋되면 이후 회원 잠금 조회에서 오래된 RT를 거부한다")
    fun refresh_revalidates_after_password_reset_wins_member_lock() {
        val email: String = newEmail("refresh-reset-order")
        val login: MvcResult = login(email)
        val oldAccessToken: String = accessToken(login)
        val rt1: Cookie = refreshCookie(login)
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)
        val memberId: Long = memberRepository.findByEmail(email).orElseThrow().id!!

        val memberLocked = CountDownLatch(1)
        val refreshLockRequested = CountDownLatch(1)
        val allowResetCommit = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val resetFuture: Future<*> =
                executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        memberRepository.findByEmailForUpdate(email).orElseThrow()
                        memberLocked.countDown()
                        try {
                            check(
                                refreshLockRequested.await(10, TimeUnit.SECONDS) &&
                                    allowResetCommit.await(10, TimeUnit.SECONDS),
                            ) { "refresh lock ordering timed out" }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IllegalStateException(e)
                        }
                        passwordResetService.reset(
                            email,
                            grant,
                            NEW_PASSWORD,
                            NEW_PASSWORD,
                        )
                    }
                }
            assertThat(memberLocked.await(10, TimeUnit.SECONDS)).isTrue()

            doAnswer { invocation ->
                refreshLockRequested.countDown()
                Optional.ofNullable(
                    entityManager.find(
                        Member::class.java,
                        invocation.getArgument(0),
                        LockModeType.PESSIMISTIC_WRITE,
                    ),
                )
            }.whenever(memberRepository).findByIdForUpdate(eq(memberId))

            val refreshFuture: Future<MvcResult> =
                executor.submit(
                    Callable { mvc.perform(post("/api/v1/auth/refresh").cookie(rt1)).andReturn() },
                )

            assertThat(refreshLockRequested.await(10, TimeUnit.SECONDS)).isTrue()
            // The RT reference was read before the reset transaction commits. The latch is
            // released immediately before the member-lock query, so the test verifies that
            // the subsequent query observes the committed reset state; it does not assert
            // that the refresh thread was already blocked inside the database lock query.
            allowResetCommit.countDown()

            resetFuture.get(10, TimeUnit.SECONDS)
            val refreshResult: MvcResult = refreshFuture.get(10, TimeUnit.SECONDS)
            assertThat(refreshResult.response.status).isEqualTo(401)
            assertThat(refreshToken(rt1.value).consumedAt).isNull()
            assertThat(refreshToken(rt1.value).revokedAt).isNotNull()
            assertThatSessionRevoked(rt1.value)

            mvc
                .perform(
                    get("/api/v1/members/me")
                        .header("Authorization", "Bearer $oldAccessToken"),
                ).andExpect(status().isUnauthorized())
            assertThat(
                loginExisting(email, NEW_PASSWORD).response.status,
            ).isEqualTo(200)
        } finally {
            allowResetCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun login(email: String): MvcResult {
        createMemberWithCredential(email, PASSWORD)
        return loginExisting(email, PASSWORD)
    }

    private fun loginExisting(
        email: String,
        password: String,
    ): MvcResult =
        mvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("email" to email, "password" to password),
                        ),
                    ),
            ).andExpect(status().isOk())
            .andReturn()

    private fun createMemberWithCredential(
        email: String,
        password: String,
    ) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            val member: Member = memberRepository.save(Member(email, "테스트회원"))
            localCredentialRepository.save(
                LocalCredential(
                    member,
                    passwordEncoder.encode(password),
                    LocalDateTime.now(),
                ),
            )
        }
    }

    private fun createAdminWithCredential(): Member {
        val admin = Member(newEmail("admin"), "관리자")
        ReflectionTestUtils.setField(admin, "role", MemberRole.ADMIN)

        TransactionTemplate(transactionManager).executeWithoutResult {
            memberRepository.save(admin)
            localCredentialRepository.save(
                LocalCredential(
                    admin,
                    passwordEncoder.encode(PASSWORD),
                    LocalDateTime.now(),
                ),
            )
        }
        return admin
    }

    private fun resetPassword(
        email: String,
        grant: String,
        password: String,
    ) {
        mvc
            .perform(
                post("/api/v1/auth/password-reset")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("email" to email, "grant" to grant, "password" to password, "passwordConfirm" to password),
                        ),
                    ),
            ).andExpect(status().isOk())
    }

    private fun refreshCookie(result: MvcResult): Cookie {
        val cookie = result.response.getCookie("refreshToken")
        assertThat(cookie)
            .`as`("응답에 Refresh Token 쿠키가 있어야 한다")
            .isNotNull()
        assertThat(cookie!!.value).isNotBlank()
        return cookie
    }

    private fun cookieMaxAge(result: MvcResult): Long {
        val header = result.response.getHeader("Set-Cookie")
        assertThat(header).contains("refreshToken=", "Max-Age=")
        return header!!.substringAfter("Max-Age=").substringBefore(";").toLong()
    }

    private fun refreshToken(rawToken: String): RefreshToken = refreshTokenRepository.findByTokenHash(hashToken(rawToken)).orElseThrow()

    private fun hashToken(rawToken: String): String {
        try {
            return HexFormat.of().formatHex(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(rawToken.toByteArray(StandardCharsets.UTF_8)),
            )
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    private fun assertThatSessionRevoked(rawToken: String) {
        val revoked =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_session s
                JOIN refresh_token t ON t.auth_session_id = s.id
                WHERE t.token_hash = ? AND s.revoked_at IS NOT NULL
                """.trimIndent(),
                Int::class.javaObjectType,
                hashToken(rawToken),
            )
        assertThat(revoked).isEqualTo(1)
    }

    private fun assertAccessTokenLifetime(
        token: String,
        sessionExpiry: LocalDateTime,
    ) {
        val claims: Map<String, Any?> = jwtProvider.payloadOrNull(token)!!
        val issuedAt: Long = (claims.get("iat") as Number).toLong()
        val expiresAt: Long = (claims.get("exp") as Number).toLong()
        assertThat(expiresAt - issuedAt).isLessThanOrEqualTo(300L)
        assertThat(Instant.ofEpochSecond(expiresAt))
            .isBeforeOrEqualTo(sessionExpiry.atZone(clock.zone).toInstant())
    }

    private fun accessToken(result: MvcResult): String {
        val data: JsonNode =
            objectMapper
                .readTree(
                    result.response.contentAsString,
                ).path("data")
        val token: String = data.path("accessToken").asText()
        assertThat(token).isNotBlank()
        return token
    }

    private fun issueGrant(
        email: String,
        purpose: VerificationPurpose,
    ): String {
        val deliveredCode: AtomicReference<String> = AtomicReference()

        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(eq(email), eq(purpose), any<String>())

        val issued = challengeService.issue(email, purpose)
        return checkService
            .verify(
                email,
                purpose,
                issued.challengeId,
                deliveredCode.get()!!,
            ).grant!!
    }

    private fun issueChallengeFromApi(
        email: String,
        purpose: VerificationPurpose,
    ): IssuedChallenge {
        val deliveredCode: AtomicReference<String> = AtomicReference()
        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(eq(email), eq(purpose), any<String>())

        val response: MvcResult =
            mvc
                .perform(
                    post("/api/v1/auth/verification/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf("email" to email, "purpose" to purpose.name),
                            ),
                        ),
                ).andExpect(status().isOk())
                .andReturn()
        val challengeId: Long =
            objectMapper
                .readTree(response.response.contentAsString)
                .path("data")
                .path("challengeId")
                .asLong()
        assertThat(deliveredCode.get()!!).isNotBlank()
        return IssuedChallenge(challengeId, deliveredCode.get()!!)
    }

    private fun releaseVerificationBlock(
        email: String,
        purpose: VerificationPurpose,
    ) {
        val now: LocalDateTime = LocalDateTime.now(clock)
        jdbcTemplate.update(
            """
            UPDATE verification_guard
            SET blocked_until = ?, last_sent_at = ?
            WHERE email = ? AND purpose = ?
            """.trimIndent(),
            now.minusSeconds(1),
            now.minusSeconds(31),
            email,
            purpose.name,
        )
    }

    private fun newEmail(prefix: String): String = "v2-$prefix-${UUID.randomUUID()}@example.com"

    private data class RefreshAttempt(
        val status: Int,
        val newRefreshToken: Cookie?,
        val failure: Throwable?,
    ) {
        companion object {
            fun success(
                status: Int,
                newRefreshToken: Cookie?,
            ): RefreshAttempt = RefreshAttempt(status, newRefreshToken, null)

            fun failed(failure: Throwable): RefreshAttempt = RefreshAttempt(-1, null, failure)
        }
    }

    private data class IssuedChallenge(
        val challengeId: Long,
        val deliveredCode: String,
    )

    private data class ConcurrentResponse(
        val status: Int,
        val accessToken: String?,
        val failure: Throwable?,
    )

    companion object {
        private val PASSWORD = "Test1234!"
        private val NEW_PASSWORD = "New1234!"
    }
}
