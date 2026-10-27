package com.ovengers.slotkey.auth.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.auth.entity.LocalCredential
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MySqlTestContainerConfig::class, SettableClockConfig::class)
internal class LoginRateLimitIntegrationTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var credentialRepository: LocalCredentialRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var clock: SettableClock

    @Autowired
    private lateinit var rateLimitService: LoginRateLimitService

    @BeforeEach fun setClock() {
        clock.set(ZonedDateTime.of(2027, 3, 1, 0, 1, 0, 0, SettableClockConfig.KST))
    }

    @Test
    fun unknownEmailGetsSameTenAttemptsAndEleventhIsLimited() {
        val email: String = newEmail()
        for (i in 0..9) {
            login(email, "Wrong1234!").andExpect(status().isUnauthorized())
        }
        login(email, "Wrong1234!")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
    }

    @Test
    fun separateEmailsHaveIndependentBuckets() {
        val first: String = newEmail()
        val second: String = newEmail()
        for (i in 0..9) {
            login(first, "Wrong1234!").andExpect(status().isUnauthorized())
        }
        login(first, "Wrong1234!").andExpect(status().isTooManyRequests())
        login(second, "Wrong1234!").andExpect(status().isUnauthorized())
    }

    @Test
    fun nextFixedWindowIsAllowedAndRetryAfterRoundsUpToBoundary() {
        val email: String = newEmail()
        for (i in 0..9) {
            login(email, "Wrong1234!").andExpect(status().isUnauthorized())
        }
        clock.set(
            ZonedDateTime.of(
                2027,
                3,
                1,
                0,
                14,
                59,
                999000000,
                SettableClockConfig.KST,
            ),
        )
        login(email, "Wrong1234!")
            .andExpect(status().isTooManyRequests())
            .andExpect(
                header()
                    .string("Retry-After", "1"),
            )

        clock.set(ZonedDateTime.of(2027, 3, 1, 0, 15, 0, 0, SettableClockConfig.KST))
        login(email, "Wrong1234!").andExpect(status().isUnauthorized())
    }

    @Test
    fun malformedAndInvalidDtoRequestsAreRejectedBeforeTheLimiter() {
        val email: String = newEmail()
        mvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{"),
            ).andExpect(status().isBadRequest())
        mvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("email" to email))),
            ).andExpect(status().isBadRequest())
        for (i in 0..9) {
            login(email, "Wrong1234!").andExpect(status().isUnauthorized())
        }
        login(email, "Wrong1234!").andExpect(status().isTooManyRequests())
    }

    @Test
    fun simultaneousFirstRequestsConsumeExactlyTenSlots() {
        val email: String = newEmail()
        val requestCount: Int = 12
        val ready = CountDownLatch(requestCount)
        val start = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(requestCount)
        val futures = mutableListOf<Future<Int>>()
        try {
            repeat(requestCount) {
                futures.add(
                    executor.submit(
                        Callable<Int> {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS)) { "start barrier timed out" }
                            login(email, "Wrong1234!").andReturn().response.status
                        },
                    ),
                )
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val statuses = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(statuses.count { it == 401 }).isEqualTo(10)
            assertThat(statuses.count { it == 429 }).isEqualTo(2)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test fun databaseFailureFailsClosedAsAuthenticationServiceUnavailable() {
        val unavailable =
            LoginRateLimitService(
                object : LoginAttemptStore {
                    override fun tryAcquire(
                        email: String,
                        windowId: Long,
                        limit: Int,
                    ): Boolean = throw DataAccessResourceFailureException("unavailable")
                },
                clock,
            )
        assertThatThrownBy { unavailable.acquire(newEmail()) }
            .isInstanceOf(BusinessException::class.java)
            .satisfies({ error ->
                assertThat((error as BusinessException).errorCode)
                    .isEqualTo(ErrorCode.AUTHENTICATION_SERVICE_UNAVAILABLE)
            })
    }

    @Test
    fun rateLimitingDoesNotSuspendMemberOrRevokeExistingSession() {
        val email: String = newEmail()
        createMember(email)
        val successful: MvcResult =
            login(
                email,
                PASSWORD,
            ).andExpect(status().isOk()).andReturn()
        val token: String =
            objectMapper
                .readTree(successful.response.contentAsString)
                .path("data")
                .path("accessToken")
                .asText()
        val member: Member = memberRepository.findByEmail(email).orElseThrow()
        val sessionCountBefore =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE member_id = ? AND revoked_at IS NULL",
                Int::class.javaObjectType,
                member.id!!,
            )

        for (i in 1..8) {
            login(email, "Wrong1234!").andExpect(status().isUnauthorized())
        }
        login(email, "Wrong1234!").andExpect(status().isUnauthorized())
        login(email, "Wrong1234!").andExpect(status().isTooManyRequests())

        mvc
            .perform(get("/api/v1/members/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        assertThat(
            memberRepository
                .findById(member.id!!)
                .orElseThrow()
                .status.name,
        ).isEqualTo("ACTIVE")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE member_id = ? AND revoked_at IS NULL",
                Int::class.javaObjectType,
                member.id!!,
            ),
        ).isEqualTo(sessionCountBefore)
    }

    private fun login(
        email: String,
        password: String,
    ): ResultActions =
        mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
        )

    private fun createMember(email: String) {
        transactionTemplate.executeWithoutResult {
            val member: Member = memberRepository.save(Member(email, "rate-limit-test"))
            credentialRepository.save(
                LocalCredential(
                    member,
                    passwordEncoder.encode(PASSWORD),
                    LocalDateTime.of(2027, 3, 1, 0, 1),
                ),
            )
        }
    }

    private fun newEmail(): String = "login-limit-${UUID.randomUUID()}@example.com"

    companion object {
        private val PASSWORD = "Test1234!"
    }
}
