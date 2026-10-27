@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.auth.entity.LocalCredential
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.repository.AuthSessionRepository
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.service.VerificationChallengeService
import com.ovengers.slotkey.auth.service.VerificationCheckService
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import jakarta.persistence.EntityManager
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MySqlTestContainerConfig::class)
@Transactional
class AuthControllerTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var localCredentialRepository: LocalCredentialRepository

    @Autowired
    private lateinit var clock: Clock

    @SpyBean // 스파이빈은 실제 레포지토리를 이용하면서 테스트에서 지정한 메스드만 결과를 바꿀수 있게 한다고 합니다.
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @Autowired
    private lateinit var challengeService: VerificationChallengeService

    @Autowired
    private lateinit var checkService: VerificationCheckService

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @SpyBean
    private lateinit var authSessionRepository: AuthSessionRepository

    @Test
    @DisplayName("회원가입 성공 시 일반 회원으로 저장하고 비밀번호를 해시한다")
    fun t1() {
        // 1. 가입할 회원 정보 준비
        val email = "signup-test@example.com"
        val grant: String = issueSignupGrant(email)
        val password = "Test1234!"
        val nickname = "테스트회원"

        // 2. 회원가입 요청
        val resultActions: ResultActions =
            mvc
                .perform(
                    post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "email": "$email",
                                "password": "$password",
                                "passwordConfirm": "$password",
                                "nickname": "$nickname",
                                "grant": "$grant"
                            }
                            """.trimIndent(),
                        ),
                ).andDo(print())

        // 3. HTTP 응답 확인
        resultActions
            .andExpect(handler().handlerType(AuthController::class.java))
            .andExpect(handler().methodName("signup"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))

        // 4. 실제 DB 저장 결과 확인
        val member: Member =
            memberRepository
                .findByEmail(email)
                .orElseThrow()

        assertThat(member.nickname).isEqualTo(nickname)
        assertThat(member.role).isEqualTo(MemberRole.USER)
        assertThat(member.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(member.createdAt).isNotNull()

        val credential: LocalCredential =
            localCredentialRepository
                .findById(member.id!!)
                .orElseThrow()
        // 원문이 그대로 저장되지 않았는지 확인
        assertThat(credential.passwordHash).isNotEqualTo(password)

        // 저장된 해시가 입력한 비밀번호와 일치하는지 확인
        assertThat(
            passwordEncoder.matches(
                password,
                credential.passwordHash,
            ),
        ).isTrue()
    }

    @Test
    @DisplayName("이미 가입된 이메일로 회원가입하면 409를 반환한다")
    fun t2() {
        // 1. 이미 가입된 회원을 직접 저장
        val email = "duplicate-test@example.com"
        val grant: String = issueSignupGrant(email)
        val password = "Test1234!"

        val existingMember: Member =
            memberRepository.save(
                Member(
                    email,
                    "기존회원",
                ),
            )

        val memberCountBefore: Long = memberRepository.count()

        // 2. 같은 이메일로 다시 회원가입 요청
        val resultActions: ResultActions =
            mvc
                .perform(
                    post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "email": "$email",
                                "password": "$password",
                                "passwordConfirm": "$password",
                                "nickname": "새회원",
                                "grant": "$grant"
                            }
                            """.trimIndent(),
                        ),
                ).andDo(print())

        // 3. 이메일 중복 오류 응답 확인
        resultActions
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"))
            .andExpect(
                jsonPath("$.message")
                    .value("이미 사용 중인 이메일입니다."),
            )

        // 4. 새 회원이 추가되지 않고 기존 회원도 유지되는지 확인
        assertThat(memberRepository.count()).isEqualTo(memberCountBefore)

        val savedMember: Member =
            memberRepository
                .findByEmail(email)
                .orElseThrow()

        assertThat(savedMember.id).isEqualTo(existingMember.id)
        assertThat(savedMember.nickname).isEqualTo("기존회원")
    }

    @Test
    @DisplayName("비밀번호와 비밀번호 확인이 다르면 회원가입을 거절한다")
    fun t3() {
        // 1. 서로 다른 비밀번호 준비
        val email = "password-mismatch@example.com"
        val grant: String = issueSignupGrant(email)
        val memberCountBefore: Long = memberRepository.count()

        // 2. 회원가입 요청
        val resultActions: ResultActions =
            mvc
                .perform(
                    post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "email": "$email",
                                "password": "Test1234!",
                                "passwordConfirm": "Different1234!",
                                "nickname": "테스트회원",
                                "grant": "$grant"
                            }
                            """.trimIndent(),
                        ),
                ).andDo(print())

        // 3. 비밀번호 불일치 오류 확인
        resultActions
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(
                jsonPath("$.code")
                    .value("PASSWORD_CONFIRM_MISMATCH"),
            )

        // 4. 가입이 처리되지 않았는지 확인
        assertThat(memberRepository.existsByEmail(email)).isFalse()
        assertThat(memberRepository.count()).isEqualTo(memberCountBefore)
    }

    @Test
    @DisplayName("로그인하면 액세스 토큰과 24시간 리프레시 쿠키를 발급한다")
    fun t4() {
        val result: MvcResult = loginForTest("login-success@example.com")

        val accessToken: String = extractAccessToken(result)
        assertThat(accessToken).isNotBlank()
        assertThat(JwtUtil.payloadOrNull(accessToken, secretKey)!!)
            .containsKey("sid")

        val refreshCookie = refreshCookie(result)
        assertThat(refreshCookie.value).isNotBlank()
        assertThat(refreshCookie.isHttpOnly).isTrue()
        assertThat(refreshCookie.path).isEqualTo("/api/v1/auth")
        assertThat(refreshCookie.maxAge).isBetween(86399, 86400)
    }

    @Test
    @DisplayName("비밀번호가 틀리면 로그인을 거절하고 쿠키를 발급하지 않는다")
    fun t5() {
        val email = "login-failure-${UUID.randomUUID()}@example.com"

        memberRepository.save(
            Member(
                email,
                "테스트회원",
            ),
        )

        val resultActions: ResultActions =
            mvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "email": "$email",
                                "password": "Wrong1234!"
                            }
                            """.trimIndent(),
                        ),
                ).andDo(print())

        resultActions
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(cookie().doesNotExist("refreshToken"))
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
    }

    @Test
    @DisplayName("유효한 리프레시 쿠키로 갱신한 액세스 토큰을 사용할 수 있다")
    fun t6() {
        val email = "refresh-success@example.com"

        val loginResult: MvcResult = loginForTest(email)

        val refreshCookie = refreshCookie(loginResult)

        val refreshResult: MvcResult =
            mvc
                .perform(
                    post("/api/v1/auth/refresh")
                        .cookie(refreshCookie),
                ).andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn()

        val accessToken: String = extractAccessToken(refreshResult)

        // 발급된 문자열이 실제 인증에도 사용 가능한지 확인
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header(
                        "Authorization",
                        "Bearer $accessToken",
                    ),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email").value(email))
    }

    @Test
    @DisplayName("리프레시 쿠키가 없으면 토큰 갱신을 거절한다")
    fun t7() {
        mvc
            .perform(
                post("/api/v1/auth/refresh"),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    @DisplayName("등록되지 않은 리프레시 토큰으로 갱신하면 거절한다")
    fun t8() {
        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(
                        Cookie(
                            "refreshToken",
                            "unknown-refresh-token",
                        ),
                    ),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    @DisplayName("로그아웃하면 쿠키를 삭제하고 기존 리프레시 토큰의 갱신을 차단한다")
    fun t9() {
        val loginResult: MvcResult =
            loginForTest("logout-test@example.com")

        val refreshCookie = refreshCookie(loginResult)

        // 로그아웃 전에 원문을 별도로 보관
        val rawRefreshToken: String = refreshCookie.value

        mvc
            .perform(
                post("/api/v1/auth/logout")
                    .cookie(refreshCookie),
            ).andDo(print())
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
            .andExpect(cookie().value("refreshToken", ""))
            .andExpect(cookie().maxAge("refreshToken", 0))
            .andExpect(cookie().path("refreshToken", "/api/v1/auth"))
            .andExpect(cookie().httpOnly("refreshToken", true))

        // 브라우저의 쿠키 삭제만 확인하는 것이 아니라,
        // 복사해둔 원문을 다시 보내도 서버가 거절하는지 확인
        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(
                        Cookie(
                            "refreshToken",
                            rawRefreshToken,
                        ),
                    ),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    @DisplayName("액세스 토큰으로 로그인한 회원 본인의 정보를 조회한다")
    fun t10() {
        val email = "my-info@example.com"

        val loginResult: MvcResult = loginForTest(email)
        val accessToken: String = extractAccessToken(loginResult)

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header(
                        "Authorization",
                        "Bearer $accessToken",
                    ),
            ).andDo(print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.nickname").value("테스트회원"))
            .andExpect(jsonPath("$.data.role").value("USER"))
            .andExpect(jsonPath("$.data.createdAt").exists())
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
    }

    @Test
    @DisplayName("액세스 토큰 없이 내 정보를 조회하면 거절한다")
    fun t11() {
        mvc
            .perform(
                get("/api/v1/members/me"),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
    }

    @Test
    @DisplayName("V1 세션 정보가 없는 기존 Access Token은 보호 API에서 거부한다")
    fun legacyAccessTokenWithoutSessionIsRejected() {
        val email = "legacy-access@example.com"
        loginForTest(email)
        val member: Member = memberRepository.findByEmail(email).orElseThrow()
        val legacyToken: String =
            JwtUtil.createToken(
                secretKey,
                300000L,
                mapOf("id" to member.id!!, "email" to member.email, "role" to member.role.name),
            )

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $legacyToken"),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("JWT 회원 ID와 세션 소유 회원이 다르면 보호 API를 거부한다")
    fun sessionOwnedByAnotherMemberIsRejected() {
        val loginResult: MvcResult = loginForTest("session-owner@example.com")
        val accessToken: String = extractAccessToken(loginResult)
        val claims: Map<String, Any?> = JwtUtil.payloadOrNull(accessToken, secretKey)!!
        val sessionId: String = claims.get("sid") as String
        val otherMemberId: Long = (claims.get("id") as Number).toLong() + 100000L
        val mismatchedToken: String =
            JwtUtil.createToken(
                secretKey,
                300000L,
                mapOf("id" to otherMemberId, "email" to "other-session-owner@example.com", "role" to "USER", "sid" to sessionId),
            )

        mvc
            .perform(get("/api/v1/members/me").header("Authorization", "Bearer $mismatchedToken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
    }

    @Test
    @DisplayName("존재하지 않는 세션 ID를 가진 Access Token은 거부한다")
    fun unknownSessionIsRejected() {
        val email = "unknown-session@example.com"
        loginForTest(email)
        val member: Member = memberRepository.findByEmail(email).orElseThrow()
        val token: String =
            JwtUtil.createToken(
                secretKey,
                300000L,
                mapOf("id" to member.id!!, "email" to email, "role" to "USER", "sid" to "missing-session"),
            )

        mvc
            .perform(get("/api/v1/members/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
    }

    @Test
    @DisplayName("만료된 DB 세션은 아직 만료되지 않은 Access Token도 거부한다")
    fun expiredSessionRejectsAccessToken() {
        val loginResult: MvcResult = loginForTest("expired-session@example.com")
        val accessToken: String = extractAccessToken(loginResult)
        val sessionId: String = JwtUtil.payloadOrNull(accessToken, secretKey)!!.get("sid") as String
        jdbcTemplate.update("UPDATE auth_session SET expires_at = '2000-01-01 00:00:00' WHERE id = ?", sessionId)
        entityManager.clear()

        mvc
            .perform(get("/api/v1/members/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
    }

    @Test
    @DisplayName("세션 저장소 장애 시 인증을 허용하지 않고 공통 503 응답을 반환한다")
    fun sessionStoreFailureFailsClosedWithServiceUnavailable() {
        val loginResult: MvcResult = loginForTest("session-store-failure@example.com")
        val accessToken: String = extractAccessToken(loginResult)
        doThrow(DataAccessResourceFailureException("sensitive-db-detail"))
            .whenever(authSessionRepository)
            .isUsable(any<String>(), any<Long>(), any(), any())

        mvc
            .perform(get("/api/v1/members/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_SERVICE_UNAVAILABLE"))
            .andExpect { result ->
                assertThat(result.response.contentAsString)
                    .doesNotContain("sensitive-db-detail", accessToken)
            }
    }

    @Test
    @DisplayName("세션 조회 트랜잭션을 시작할 수 없으면 인증을 허용하지 않고 공통 503 응답을 반환한다")
    fun sessionTransactionStartFailureFailsClosedWithServiceUnavailable() {
        val loginResult: MvcResult = loginForTest("session-transaction-failure@example.com")
        val accessToken: String = extractAccessToken(loginResult)
        doThrow(CannotCreateTransactionException("sensitive-db-detail"))
            .whenever(authSessionRepository)
            .isUsable(any<String>(), any<Long>(), any(), any())

        mvc
            .perform(get("/api/v1/members/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_SERVICE_UNAVAILABLE"))
            .andExpect { result ->
                assertThat(result.response.contentAsString)
                    .doesNotContain("sensitive-db-detail", accessToken)
            }
    }

    @Test
    @DisplayName("만료된 액세스 토큰으로 내 정보를 조회하면 401을 반환한다")
    fun t12() {
        val email = "expired-access@example.com"
        loginForTest(email)

        val member: Member =
            memberRepository
                .findByEmail(email)
                .orElseThrow()

        // 기다리지 않고 처음부터 만료된 토큰을 만든다.
        val expiredToken: String =
            JwtUtil.createToken(
                secretKey,
                -60000L,
                mapOf("id" to member.id!!, "email" to member.email),
            )

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $expiredToken"),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
    }

    @Test
    @DisplayName("다른 키로 서명한 액세스 토큰으로 접근하면 401을 반환한다")
    fun t13() {
        val email = "wrong-signature@example.com"
        loginForTest(email)

        val member: Member =
            memberRepository
                .findByEmail(email)
                .orElseThrow()

        // 서버가 사용하는 키와 다른 테스트용 키
        val wrongSecretKey: String =
            "wrong-signing-key-for-test-only-0123456789abcdef"

        assertThat(wrongSecretKey).isNotEqualTo(secretKey)

        val forgedToken: String =
            JwtUtil.createToken(
                wrongSecretKey,
                300000L,
                mapOf("id" to member.id!!, "email" to member.email),
            )

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $forgedToken"),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
    }

    @Test
    @DisplayName("DB에 저장된 리프레시 토큰이 만료되었으면 갱신을 거절한다")
    fun t14() {
        val email = "expired-refresh@example.com"
        val loginResult: MvcResult = loginForTest(email)

        val refreshCookie = refreshCookie(loginResult)

        val member: Member =
            memberRepository
                .findByEmail(email)
                .orElseThrow()

        // 로그인하면서 저장한 토큰을 DB에 반영한다.
        entityManager.flush()

        // 이번 테스트 회원의 토큰 만료 시각을 과거로 변경한다.
        val updatedCount: Int =
            entityManager
                .createQuery(
                    """
                    update RefreshToken r
                    set r.expiresAt = :expiresAt
                    where r.member.id = :memberId
                    """.trimIndent(),
                ).setParameter("expiresAt", LocalDateTime.of(2000, 1, 1, 0, 0))
                .setParameter("memberId", member.id!!)
                .executeUpdate()

        assertThat(updatedCount).isEqualTo(1)

        // 메모리에 남은 기존 엔티티 대신 변경된 DB 값을 읽도록 한다.
        entityManager.clear()

        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(refreshCookie),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    @DisplayName("로그인 후 정지되면 기존 액세스 토큰과 재발급이 거절된다")
    fun t15() {
        val email = "suspended-member@example.com"
        val loginResult: MvcResult = loginForTest(email)
        val accessToken: String = extractAccessToken(loginResult)

        entityManager.flush()

        // 정상적으로 로그인한 회원을 이후에 정지시킨 상황
        val updatedCount: Int =
            entityManager
                .createQuery(
                    """
                    update Member m
                    set m.status = :status
                    where m.email = :email
                    """.trimIndent(),
                ).setParameter("status", MemberStatus.SUSPENDED)
                .setParameter("email", email)
                .executeUpdate()

        assertThat(updatedCount).isEqualTo(1)

        entityManager.clear()

        // 보호 API는 현재 회원 상태를 확인해 정지 회원의 토큰을 거절한다.
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $accessToken"),
            ).andDo(print())
            .andExpect(status().isUnauthorized())

        // 정지된 회원은 리프레시 토큰으로 새 토큰을 발급받을 수 없다.
        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(refreshCookie(loginResult)),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"))
    }

    @Test
    @DisplayName("일반 회원이 관리자 공간 등록 API에 접근하면 403을 반환한다")
    fun t16() {
        val loginResult: MvcResult = loginForTest("user-admin-access@example.com")
        val accessToken: String = extractAccessToken(loginResult)

        mvc
            .perform(
                post("/api/v1/admin/spaces")
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andDo(print())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    // 회원 저장 후 실제 로그인 API를 호출한다.
    private fun loginForTest(email: String): MvcResult {
        val password = "Test1234!"

        val member: Member =
            memberRepository.saveAndFlush(
                Member(email, "테스트회원"),
            )

        localCredentialRepository.saveAndFlush(
            LocalCredential(
                member,
                passwordEncoder.encode(password),
                LocalDateTime.now(clock),
            ),
        )

        return mvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "email": "$email",
                            "password": "$password"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk())
            .andReturn()
    }

    // 로그인·갱신 응답에서 액세스 토큰을 꺼낸다.
    private fun refreshCookie(result: MvcResult): Cookie =
        checkNotNull(result.response.getCookie("refreshToken")) { "refreshToken 쿠키가 발급되지 않았다" }

    private fun extractAccessToken(result: MvcResult): String {
        val responseBody: String =
            result
                .response
                .getContentAsString(StandardCharsets.UTF_8)

        val accessToken: String =
            objectMapper
                .readTree(responseBody)
                .path("data")
                .path("accessToken")
                .asText()

        assertThat(accessToken).isNotBlank()

        return accessToken
    }

    @Test
    @DisplayName("로그아웃 후 기존 액세스 토큰은 즉시 사용할 수 없다")
    fun t17() {
        val email = "access-after-logout-${UUID.randomUUID()}@example.com"

        // 1. 실제 로그인으로 두 토큰 발급
        val loginResult: MvcResult = loginForTest(email)
        val accessToken: String = extractAccessToken(loginResult)

        val refreshCookie = refreshCookie(loginResult)

        // 2. 로그아웃
        mvc
            .perform(
                post("/api/v1/auth/logout")
                    .cookie(refreshCookie),
            ).andDo(print())
            .andExpect(status().isNoContent())

        // 3. 로그아웃 전에 보관한 액세스 토큰으로 요청
        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $accessToken"),
            ).andDo(print())
            .andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("로그아웃은 같은 회원의 다른 로그인 세션에는 영향을 주지 않는다")
    fun logoutRevokesOnlyTheSessionFromItsRefreshCookie() {
        val email = "separate-login-sessions-${UUID.randomUUID()}@example.com"
        val firstLogin: MvcResult = loginForTest(email)
        val secondLogin: MvcResult =
            mvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"email":"$email","password":"Test1234!"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk())
                .andReturn()
        val firstAccessToken: String = extractAccessToken(firstLogin)
        val secondAccessToken: String = extractAccessToken(secondLogin)

        mvc
            .perform(
                post("/api/v1/auth/logout")
                    .cookie(refreshCookie(firstLogin)),
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
            ).andExpect(status().isOk())
        mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .cookie(refreshCookie(secondLogin)),
            ).andExpect(status().isOk())
    }

    @Test
    @DisplayName("사전 중복 검사를 통과해도 DB에서 이메일이 중복되면 409를 반환한다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun t18() {
        val email = "duplicate-at-save-${UUID.randomUUID()}@example.com"

        val grant: String = issueSignupGrant(email)

        // 다른 가입 요청이 먼저 성공한 상황을 준비
        val existingMember: Member =
            memberRepository.saveAndFlush(
                Member(
                    email,
                    "기존회원",
                ),
            )

        try {
            // 사전 검사 당시에는 이메일이 없었다고 가정한다.
            // 저장 메서드는 실제 MySQL을 그대로 사용한다.
            doReturn(false)
                .whenever(memberRepository)
                .existsByEmail(email)

            mvc
                .perform(
                    post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "email": "$email",
                                "password": "Test1234!",
                                "passwordConfirm": "Test1234!",
                                "nickname": "새회원",
                                "grant": "$grant"
                            }
                            """.trimIndent(),
                        ),
                ).andDo(print())
                .andExpect(status().isConflict())
                .andExpect(
                    jsonPath("$.code")
                        .value("EMAIL_ALREADY_EXISTS"),
                )

            // 기존 회원이 그대로 유지되는지 확인
            val savedMember: Member =
                memberRepository
                    .findByEmail(email)
                    .orElseThrow()

            assertThat(savedMember.id)
                .isEqualTo(existingMember.id)

            assertThat(savedMember.nickname)
                .isEqualTo("기존회원")
        } finally {
            // 이 테스트는 자동 롤백을 사용하지 않으므로 직접 정리
            memberRepository.deleteById(existingMember.id!!)
        }
    }

    private fun issueSignupGrant(email: String): String {
        val deliveredCode: AtomicReference<String> = AtomicReference()

        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(
            eq(email),
            eq(VerificationPurpose.SIGNUP),
            any<String>(),
        )

        val issued =
            challengeService.issue(
                email,
                VerificationPurpose.SIGNUP,
            )

        val checked =
            checkService.verify(
                email,
                VerificationPurpose.SIGNUP,
                issued.challengeId,
                deliveredCode.get()!!,
            )

        assertThat(checked.grant).isNotBlank()

        return checked.grant!!
    }
}
