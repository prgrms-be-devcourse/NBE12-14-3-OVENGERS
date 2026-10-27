package com.ovengers.slotkey.access.entity

import com.ovengers.slotkey.access.dto.response.DoorAccessLogResponse
import com.ovengers.slotkey.access.repository.DoorAccessLogRepository
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.access.service.DoorAccessLogService
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.Hibernate
import org.hibernate.proxy.HibernateProxy
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
    private lateinit var logService: DoorAccessLogService

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
            val tokenId = requireNotNull(saved.id)
            entityManager.clear()

            val loaded = tokenRepository.findById(tokenId).orElseThrow()
            assertThat(loaded.tokenHash).isEqualTo(hash)
            assertThat(loaded.issuedAt).isEqualTo(issuedAt)
            assertThat(loaded.revokedAt).isNull()
            assertThat(loaded.revokeReason).isNull()
            assertThat(loaded.isRevoked).isFalse()
            assertThat(tokenRepository.findByTokenHash(hash).orElseThrow().id).isEqualTo(tokenId)
            assertThat(tokenRepository.findByTokenHash(UUID.randomUUID().toString())).isEmpty()
            assertThat(tokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow().id).isEqualTo(tokenId)
            assertThat(Hibernate.isInitialized(loaded.reservation)).isFalse()
            // Reservation의 프록시 초기화를 지원하는 기존 공개 getter를 사용한다.
            assertThat(checkNotNull(loaded.reservation).getStartTime()).isEqualTo(tomorrowAt(14, 0))
            assertThat(Hibernate.isInitialized(loaded.reservation)).isTrue()

            entityManager.clear()
            val proxy = entityManager.getReference(DoorAccessToken::class.java, tokenId)
            assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
            assertThat(Hibernate.isInitialized(proxy)).isFalse()
            assertThat(proxy.tokenHash).isEqualTo(hash)
            assertThat(Hibernate.isInitialized(proxy)).isTrue()
            assertThat(proxy.id).isEqualTo(tokenId)
            assertThat(proxy.issuedAt).isEqualTo(issuedAt)
            assertThat(proxy.revokedAt).isNull()
            assertThat(proxy.revokeReason).isNull()
            assertThat(proxy.isRevoked).isFalse()
            assertThat(Hibernate.isInitialized(proxy.reservation)).isFalse()
        }
    }

    @Test
    @DisplayName("단건·회원별 일괄 폐기는 대상 활성 토큰만 변경하며 시각·사유와 영향 행 수를 유지한다")
    fun reloadRevokedToken() {
        val anotherReservationId =
            createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(15, 0), tomorrowAt(16, 0))
        val anotherMemberId = createMember(0)
        val anotherMemberReservationId =
            createReservation(anotherMemberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(16, 0), tomorrowAt(17, 0))
        rollbackTransaction {
            val token =
                tokenRepository.saveAndFlush(
                    DoorAccessToken(reservationRepository.getReferenceById(reservationId), UUID.randomUUID().toString(), now()),
                )
            val tokenId = requireNotNull(token.id)
            val revokedAt = now().plusMinutes(1)
            token.revoke(revokedAt, "REISSUED")
            entityManager.flush()
            entityManager.clear()

            val loaded = tokenRepository.findById(tokenId).orElseThrow()
            assertThat(loaded.isRevoked).isTrue()
            assertThat(loaded.revokedAt).isEqualTo(revokedAt)
            assertThat(loaded.revokeReason).isEqualTo("REISSUED")
            assertThat(tokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)).isEmpty()

            val active =
                tokenRepository.saveAndFlush(
                    DoorAccessToken(reservationRepository.getReferenceById(reservationId), UUID.randomUUID().toString(), now()),
                )
            val pendingRevocation =
                tokenRepository.saveAndFlush(
                    DoorAccessToken(reservationRepository.getReferenceById(anotherReservationId), UUID.randomUUID().toString(), now()),
                )
            val anotherMemberToken =
                tokenRepository.saveAndFlush(
                    DoorAccessToken(
                        reservationRepository.getReferenceById(anotherMemberReservationId),
                        UUID.randomUUID().toString(),
                        now(),
                    ),
                )
            val activeTokenId = requireNotNull(active.id)
            val pendingTokenId = requireNotNull(pendingRevocation.id)
            val anotherMemberTokenId = requireNotNull(anotherMemberToken.id)
            val manuallyRevokedAt = revokedAt.plusMinutes(1)
            val bulkRevokedAt = revokedAt.plusMinutes(2)

            // 일괄 UPDATE 전에 아직 flush하지 않은 단건 폐기도 반영되어야 한다.
            pendingRevocation.revoke(manuallyRevokedAt, "MANUAL")
            assertThat(tokenRepository.revokeAllByMemberId(memberId, bulkRevokedAt, "MEMBER_WITHDRAWAL")).isEqualTo(1)
            // bulk UPDATE는 영속성 컨텍스트를 자동으로 비우지 않는다.
            assertThat(entityManager.contains(active)).isTrue()
            entityManager.clear()

            val bulkRevoked = tokenRepository.findById(activeTokenId).orElseThrow()
            assertThat(bulkRevoked.revokedAt).isEqualTo(bulkRevokedAt)
            assertThat(bulkRevoked.revokeReason).isEqualTo("MEMBER_WITHDRAWAL")
            val manuallyRevoked = tokenRepository.findById(pendingTokenId).orElseThrow()
            assertThat(manuallyRevoked.revokedAt).isEqualTo(manuallyRevokedAt)
            assertThat(manuallyRevoked.revokeReason).isEqualTo("MANUAL")
            val previouslyRevoked = tokenRepository.findById(tokenId).orElseThrow()
            assertThat(previouslyRevoked.revokedAt).isEqualTo(revokedAt)
            assertThat(previouslyRevoked.revokeReason).isEqualTo("REISSUED")
            val unaffected = tokenRepository.findById(anotherMemberTokenId).orElseThrow()
            assertThat(unaffected.revokedAt).isNull()
            assertThat(unaffected.revokeReason).isNull()
            assertThat(tokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)).isEmpty()
            assertThat(tokenRepository.revokeAllByMemberId(memberId, bulkRevokedAt, "MEMBER_WITHDRAWAL")).isZero()
        }
    }

    @Test
    @DisplayName("출입 로그의 연관 관계는 LAZY로 읽히며 결과와 거절 사유는 문자열로 저장된다")
    fun reloadLogWithLazyAssociations() {
        rollbackTransaction {
            val attemptedAt = now()
            val log = logRepository.saveAndFlush(newLog(AccessResult.DENY, AccessDenyReason.MEMBER_INACTIVE, attemptedAt))
            val logId = requireNotNull(log.id)
            entityManager.clear()

            val loaded = logRepository.findById(logId).orElseThrow()
            assertThat(Hibernate.isInitialized(loaded.actorMember)).isFalse()
            assertThat(Hibernate.isInitialized(loaded.reservation)).isFalse()
            assertThat(Hibernate.isInitialized(loaded.requestedSpace)).isFalse()
            assertThat(checkNotNull(loaded.actorMember).email).isEqualTo(memberRepository.findById(memberId).orElseThrow().email)
            // Reservation의 프록시 초기화를 지원하는 기존 공개 getter를 사용한다.
            assertThat(checkNotNull(loaded.reservation).getStartTime()).isEqualTo(tomorrowAt(14, 0))
            val spaceName = checkNotNull(loaded.requestedSpace).name
            assertThat(spaceName).isEqualTo(spaceRepository.findById(spaceId).orElseThrow().name)
            assertThat(loaded.result).isEqualTo(AccessResult.DENY)
            assertThat(loaded.reasonCode).isEqualTo(AccessDenyReason.MEMBER_INACTIVE)
            assertThat(loaded.attemptedAt).isEqualTo(attemptedAt)
            assertThat(
                jdbcTemplate.queryForObject("SELECT result FROM door_access_log WHERE id = ?", String::class.java, logId),
            ).isEqualTo("DENY")
            assertThat(
                jdbcTemplate.queryForObject("SELECT reason_code FROM door_access_log WHERE id = ?", String::class.java, logId),
            ).isEqualTo("MEMBER_INACTIVE")

            entityManager.clear()
            val proxy = entityManager.getReference(DoorAccessLog::class.java, logId)
            assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
            assertThat(Hibernate.isInitialized(proxy)).isFalse()
            assertThat(proxy.result).isEqualTo(AccessResult.DENY)
            assertThat(Hibernate.isInitialized(proxy)).isTrue()
            assertThat(proxy.id).isEqualTo(logId)
            assertThat(proxy.reasonCode).isEqualTo(AccessDenyReason.MEMBER_INACTIVE)
            assertThat(proxy.attemptedAt).isEqualTo(attemptedAt)
            assertThat(Hibernate.isInitialized(proxy.actorMember)).isFalse()
            assertThat(Hibernate.isInitialized(proxy.reservation)).isFalse()
            assertThat(Hibernate.isInitialized(proxy.requestedSpace)).isFalse()

            val response = DoorAccessLogResponse.from(proxy)
            assertThat(response.accessLogId).isEqualTo(logId)
            assertThat(response.attemptedAt).isEqualTo(attemptedAt)
            assertThat(response.requestedSpaceName).isEqualTo(spaceName)
            assertThat(response.result).isEqualTo(AccessResult.DENY)
            assertThat(response.reasonCode).isEqualTo(AccessDenyReason.MEMBER_INACTIVE)
            assertThat(Hibernate.isInitialized(proxy.requestedSpace)).isTrue()
            assertThat(Hibernate.isInitialized(proxy.actorMember)).isFalse()
            assertThat(Hibernate.isInitialized(proxy.reservation)).isFalse()
        }
    }

    @Test
    @DisplayName("출입 로그의 nullable 필드는 유지하고 거절 로그의 사유 누락은 차단한다")
    fun reloadNullableLog() {
        val logCount = logRepository.count()
        assertThatThrownBy { logService.createDenyLog(null, null, null, null, now()) }
            .isInstanceOfSatisfying(BusinessException::class.java) { exception ->
                assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENY_REASON_REQUIRED)
            }
        assertThat(logRepository.count()).isEqualTo(logCount)

        rollbackTransaction {
            val attemptedAt = now()
            val log =
                logService.create(
                    actorMember = null,
                    reservation = null,
                    requestedSpace = null,
                    result = AccessResult.ALLOW,
                    reasonCode = null,
                    attemptedAt = attemptedAt,
                )
            val logId = requireNotNull(log.id)
            entityManager.flush()
            entityManager.clear()

            val loaded = logRepository.findById(logId).orElseThrow()
            assertThat(loaded.actorMember).isNull()
            assertThat(loaded.reservation).isNull()
            assertThat(loaded.requestedSpace).isNull()
            assertThat(loaded.reasonCode).isNull()
            assertThat(loaded.result).isEqualTo(AccessResult.ALLOW)
            assertThat(loaded.attemptedAt).isEqualTo(attemptedAt)
            assertThat(logService.findAllByReservationId(null).map { it.id }).contains(logId)
            assertThat(logService.hasSuccessfulAccess(null)).isTrue()

            entityManager.clear()
            val proxy = entityManager.getReference(DoorAccessLog::class.java, logId)
            assertThat(Hibernate.isInitialized(proxy)).isFalse()
            val response = DoorAccessLogResponse.from(proxy)
            assertThat(Hibernate.isInitialized(proxy)).isTrue()
            assertThat(response.accessLogId).isEqualTo(logId)
            assertThat(response.attemptedAt).isEqualTo(attemptedAt)
            assertThat(response.requestedSpaceName).isNull()
            assertThat(response.result).isEqualTo(AccessResult.ALLOW)
            assertThat(response.reasonCode).isNull()
        }
    }

    @Test
    @DisplayName("예약별 출입 로그는 최신순이며 출입 결과 존재 여부를 구분한다")
    fun queryLogsByReservationAndResult() {
        rollbackTransaction {
            val actorMember = memberRepository.getReferenceById(memberId)
            val reservation = reservationRepository.getReferenceById(reservationId)
            val requestedSpace = spaceRepository.getReferenceById(spaceId)
            val denied =
                logService.createDenyLog(
                    actorMember = actorMember,
                    reservation = reservation,
                    requestedSpace = requestedSpace,
                    reasonCode = AccessDenyReason.MEMBER_INACTIVE,
                    attemptedAt = now(),
                )
            assertThat(logRepository.existsByReservationIdAndResult(reservationId, AccessResult.ALLOW)).isFalse()
            assertThat(logService.hasSuccessfulAccess(reservationId)).isFalse()
            val allowed =
                logService.createAllowLog(
                    actorMember = actorMember,
                    reservation = reservation,
                    requestedSpace = requestedSpace,
                    attemptedAt = now().plusSeconds(1),
                )
            entityManager.flush()
            entityManager.clear()

            assertThat(
                logRepository.findAllByReservationIdOrderByAttemptedAtDesc(reservationId).map { it.id },
            ).containsExactly(allowed.id, denied.id)
            assertThat(logRepository.existsByReservationIdAndResult(reservationId, AccessResult.ALLOW)).isTrue()
            assertThat(logRepository.existsByReservationIdAndResult(reservationId, AccessResult.DENY)).isTrue()
            assertThat(logRepository.findAllByReservationIdOrderByAttemptedAtDesc(-1L)).isEmpty()
            assertThat(logService.findById(requireNotNull(allowed.id)).id).isEqualTo(allowed.id)
            assertThat(logService.findAll().map { it.id }).contains(allowed.id, denied.id)
            assertThat(logService.findAllByReservationId(reservationId).map { it.id }).containsExactly(allowed.id, denied.id)
            assertThat(logService.findAllByReservationId(-1L)).isEmpty()
            assertThat(logService.hasSuccessfulAccess(reservationId)).isTrue()
            assertThat(logService.hasSuccessfulAccess(-1L)).isFalse()

            val responses = logService.findResponsesByReservationId(memberId, reservationId)
            assertThat(responses.map { it.accessLogId }).containsExactly(allowed.id, denied.id)
            assertThat(responses.map { it.result }).containsExactly(AccessResult.ALLOW, AccessResult.DENY)
            assertThat(responses.map { it.reasonCode }).containsExactly(null, AccessDenyReason.MEMBER_INACTIVE)
            assertThatThrownBy { (responses as MutableList<DoorAccessLogResponse>).clear() }
                .isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    @DisplayName("상위 트랜잭션의 토큰 폐기가 롤백되어도 REQUIRES_NEW 비활성 회원 거절 로그는 남는다")
    fun rejectionLogSurvivesOuterRollback() {
        val token =
            tokenRepository.saveAndFlush(
                DoorAccessToken(reservationRepository.getReferenceById(reservationId), UUID.randomUUID().toString(), now()),
            )
        val tokenId = requireNotNull(token.id)
        val attemptedAt = now()
        rollbackTransaction {
            tokenRepository.findById(tokenId).orElseThrow().revoke(attemptedAt, "ROLLBACK_TEST")
            entityManager.flush()
            inactiveMemberAccessLogService.record(memberId, attemptedAt)
        }

        assertThat(tokenRepository.findById(tokenId).orElseThrow().isRevoked).isFalse()
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
