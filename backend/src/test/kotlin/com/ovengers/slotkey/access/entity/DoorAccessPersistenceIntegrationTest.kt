package com.ovengers.slotkey.access.entity

import com.ovengers.slotkey.access.repository.DoorAccessLogRepository
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class DoorAccessPersistenceIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var tokenRepository: DoorAccessTokenRepository

    @Autowired
    private lateinit var logRepository: DoorAccessLogRepository

    @Autowired
    private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private var memberId = 0L
    private var spaceId = 0L
    private var reservationId = 0L

    @BeforeEach
    fun setUp() {
        memberId = createMember(0)
        spaceId = createSpace()
        reservationId =
            createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(14, 0), tomorrowAt(15, 0))
    }

    @Test
    @DisplayName("출입 토큰 저장 후 flush/clear 재조회 시 발급 값과 nullable 폐기 정보 및 LAZY 예약을 유지한다")
    fun reloadActiveToken() {
        rollbackTransaction {
            val issuedAt = now()
            val hash = UUID.randomUUID().toString()
            val saved = tokenRepository.saveAndFlush(DoorAccessToken(reservationRepository.getReferenceById(reservationId), hash, issuedAt))
            entityManager.clear()

            val loaded = tokenRepository.findById(saved.id).orElseThrow()
            assertThat(loaded.tokenHash).isEqualTo(hash)
            assertThat(loaded.issuedAt).isEqualTo(issuedAt)
            assertThat(loaded.revokedAt).isNull()
            assertThat(loaded.revokeReason).isNull()
            assertThat(loaded.isRevoked).isFalse()
            assertThat(Hibernate.isInitialized(loaded.reservation)).isFalse()
            // Reservation의 프록시 초기화를 지원하는 기존 공개 getter를 사용한다.
            assertThat(loaded.reservation.getStartTime()).isEqualTo(tomorrowAt(14, 0))
            assertThat(Hibernate.isInitialized(loaded.reservation)).isTrue()
        }
    }

    @Test
    @DisplayName("출입 토큰 폐기 시각과 사유가 DB 재조회 및 활성 토큰 조회에 반영된다")
    fun reloadRevokedToken() {
        rollbackTransaction {
            val token =
                tokenRepository.saveAndFlush(
                    DoorAccessToken(reservationRepository.getReferenceById(reservationId), UUID.randomUUID().toString(), now()),
                )
            val revokedAt = now().plusMinutes(1)
            token.revoke(revokedAt, "REISSUED")
            entityManager.flush()
            entityManager.clear()

            val loaded = tokenRepository.findById(token.id).orElseThrow()
            assertThat(loaded.isRevoked).isTrue()
            assertThat(loaded.revokedAt).isEqualTo(revokedAt)
            assertThat(loaded.revokeReason).isEqualTo("REISSUED")
            assertThat(tokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)).isEmpty()
        }
    }

    @Test
    @DisplayName("출입 로그의 연관 관계는 LAZY로 읽히며 결과와 거절 사유는 문자열로 저장된다")
    fun reloadLogWithLazyAssociations() {
        rollbackTransaction {
            val attemptedAt = now()
            val log = logRepository.saveAndFlush(newLog(AccessResult.DENY, AccessDenyReason.MEMBER_INACTIVE, attemptedAt))
            entityManager.clear()

            val loaded = logRepository.findById(log.id).orElseThrow()
            assertThat(Hibernate.isInitialized(loaded.actorMember)).isFalse()
            assertThat(Hibernate.isInitialized(loaded.reservation)).isFalse()
            assertThat(Hibernate.isInitialized(loaded.requestedSpace)).isFalse()
            assertThat(loaded.actorMember.email).isEqualTo(memberRepository.findById(memberId).orElseThrow().email)
            // Reservation의 프록시 초기화를 지원하는 기존 공개 getter를 사용한다.
            assertThat(loaded.reservation.getStartTime()).isEqualTo(tomorrowAt(14, 0))
            assertThat(loaded.requestedSpace.name).isEqualTo(spaceRepository.findById(spaceId).orElseThrow().name)
            assertThat(loaded.result).isEqualTo(AccessResult.DENY)
            assertThat(loaded.reasonCode).isEqualTo(AccessDenyReason.MEMBER_INACTIVE)
            assertThat(loaded.attemptedAt).isEqualTo(attemptedAt)
            assertThat(
                jdbcTemplate.queryForObject("SELECT result FROM door_access_log WHERE id = ?", String::class.java, log.id),
            ).isEqualTo("DENY")
            assertThat(
                jdbcTemplate.queryForObject("SELECT reason_code FROM door_access_log WHERE id = ?", String::class.java, log.id),
            ).isEqualTo("MEMBER_INACTIVE")
        }
    }

    @Test
    @DisplayName("출입 로그는 회원·예약·요청 공간·거절 사유가 모두 null이어도 저장하고 재조회할 수 있다")
    fun reloadNullableLog() {
        rollbackTransaction {
            val attemptedAt = now()
            val log = logRepository.saveAndFlush(DoorAccessLog(null, null, null, AccessResult.ALLOW, null, attemptedAt))
            entityManager.clear()

            val loaded = logRepository.findById(log.id).orElseThrow()
            assertThat(loaded.actorMember).isNull()
            assertThat(loaded.reservation).isNull()
            assertThat(loaded.requestedSpace).isNull()
            assertThat(loaded.reasonCode).isNull()
            assertThat(loaded.result).isEqualTo(AccessResult.ALLOW)
            assertThat(loaded.attemptedAt).isEqualTo(attemptedAt)
        }
    }

    @Test
    @DisplayName("예약별 출입 로그는 최신순이며 출입 결과 존재 여부를 구분한다")
    fun queryLogsByReservationAndResult() {
        rollbackTransaction {
            val denied = logRepository.saveAndFlush(newLog(AccessResult.DENY, AccessDenyReason.MEMBER_INACTIVE, now()))
            assertThat(logRepository.existsByReservationIdAndResult(reservationId, AccessResult.ALLOW)).isFalse()
            val allowed = logRepository.saveAndFlush(newLog(AccessResult.ALLOW, null, now().plusSeconds(1)))
            entityManager.clear()

            assertThat(
                logRepository.findAllByReservationIdOrderByAttemptedAtDesc(reservationId).map { it.id },
            ).containsExactly(allowed.id, denied.id)
            assertThat(logRepository.existsByReservationIdAndResult(reservationId, AccessResult.ALLOW)).isTrue()
            assertThat(logRepository.existsByReservationIdAndResult(reservationId, AccessResult.DENY)).isTrue()
            assertThat(logRepository.findAllByReservationIdOrderByAttemptedAtDesc(-1L)).isEmpty()
        }
    }

    @Test
    @DisplayName("상위 트랜잭션의 토큰 폐기가 롤백되어도 REQUIRES_NEW 비활성 회원 거절 로그는 남는다")
    fun rejectionLogSurvivesOuterRollback() {
        val token =
            tokenRepository.saveAndFlush(
                DoorAccessToken(reservationRepository.getReferenceById(reservationId), UUID.randomUUID().toString(), now()),
            )
        val attemptedAt = now()
        rollbackTransaction {
            tokenRepository.findById(token.id).orElseThrow().revoke(attemptedAt, "ROLLBACK_TEST")
            entityManager.flush()
            inactiveMemberAccessLogService.record(memberId, attemptedAt)
        }

        assertThat(tokenRepository.findById(token.id).orElseThrow().isRevoked).isFalse()
        val logs =
            jdbcTemplate.queryForList(
                """
                SELECT result, reason_code, reservation_id, requested_space_id, attempted_at
                FROM door_access_log
                WHERE actor_member_id = ?
                """.trimIndent(),
                memberId,
            )
        assertThat(logs).hasSize(1)
        assertThat(logs.single()["result"]).isEqualTo("DENY")
        assertThat(logs.single()["reason_code"]).isEqualTo("MEMBER_INACTIVE")
        assertThat(logs.single()["reservation_id"]).isNull()
        assertThat(logs.single()["requested_space_id"]).isNull()
        assertThat(logs.single()["attempted_at"]).isEqualTo(attemptedAt)
    }

    private fun newLog(
        result: AccessResult,
        reason: AccessDenyReason?,
        attemptedAt: java.time.LocalDateTime,
    ) = DoorAccessLog(
        memberRepository.getReferenceById(memberId),
        reservationRepository.getReferenceById(reservationId),
        spaceRepository.getReferenceById(spaceId),
        result,
        reason,
        attemptedAt,
    )

    private fun rollbackTransaction(action: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            action()
            status.setRollbackOnly()
        }
    }
}
