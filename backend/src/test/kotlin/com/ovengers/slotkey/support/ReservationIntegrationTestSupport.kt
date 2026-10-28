package com.ovengers.slotkey.support

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationSlot
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

abstract class ReservationIntegrationTestSupport : IntegrationTestSupport() {
    @Autowired protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired protected lateinit var memberRepository: MemberRepository

    @Autowired protected lateinit var spaceRepository: SpaceRepository

    @Autowired protected lateinit var reservationRepository: ReservationRepository

    @Autowired protected lateinit var reservationSlotRepository: ReservationSlotRepository

    @Autowired protected lateinit var clock: Clock

    private val memberIds = mutableListOf<Long>()
    private val spaceIds = mutableListOf<Long>()

    @AfterEach
    protected fun cleanUpFixtures() {
        val members = inList(memberIds)
        val spaces = inList(spaceIds)
        val reservationsOfSpaces = "SELECT id FROM reservation WHERE space_id IN ($spaces)"
        jdbcTemplate.update("DELETE FROM outbox_event WHERE partition_key IN (SELECT CAST(id AS CHAR) FROM spaces WHERE id IN ($spaces))")
        jdbcTemplate.update("DELETE FROM door_access_log WHERE actor_member_id IN ($members) OR reservation_id IN ($reservationsOfSpaces)")
        jdbcTemplate.update("DELETE FROM door_access_token WHERE reservation_id IN ($reservationsOfSpaces)")
        jdbcTemplate.update("DELETE FROM credit_transaction WHERE member_id IN ($members)")
        jdbcTemplate.update("DELETE FROM idempotency_key WHERE member_id IN ($members)")
        jdbcTemplate.update(
            "DELETE FROM reservation_status_history WHERE changed_by_member_id IN ($members) OR reservation_id IN ($reservationsOfSpaces)",
        )
        jdbcTemplate.update("DELETE FROM reservation_slot WHERE space_id IN ($spaces)")
        jdbcTemplate.update("DELETE FROM reservation WHERE space_id IN ($spaces) OR member_id IN ($members)")
        jdbcTemplate.update("DELETE FROM waitlist_entry WHERE member_id IN ($members) OR space_id IN ($spaces)")
        jdbcTemplate.update("DELETE FROM notification WHERE member_id IN ($members)")
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_member_id IN ($members)")
        jdbcTemplate.update("DELETE FROM spaces WHERE id IN ($spaces)")
        jdbcTemplate.update(
            "DELETE FROM refresh_token WHERE auth_session_id IN (SELECT id FROM auth_session WHERE member_id IN ($members))",
        )
        jdbcTemplate.update("DELETE FROM auth_session WHERE member_id IN ($members)")
        jdbcTemplate.update("DELETE FROM member WHERE id IN ($members)")
    }

    protected fun createMember(balance: Int): Long {
        val member = memberRepository.save(Member("fixture-${System.nanoTime()}-${memberIds.size}@slotkey.test", "fixture"))
        val id = requireNotNull(member.id)
        memberIds += id
        setBalance(id, balance)
        return id
    }

    protected fun createSpace(): Long {
        val space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("fixture-space-${System.nanoTime()}")
                    .location("서울")
                    .capacity(10)
                    .pricePerSlot(PRICE_PER_SLOT.toLong())
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )
        val id = requireNotNull(space.id)
        spaceIds += id
        return id
    }

    protected fun createReservation(
        memberId: Long,
        spaceId: Long,
        status: ReservationStatus,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Long {
        val slotCount = Duration.between(start, end).toMinutes().toInt() / 30
        val now = now()
        val reservation =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(memberId)
                    .spaceId(spaceId)
                    .startTime(start)
                    .endTime(end)
                    .status(status)
                    .pricePerSlotSnapshot(PRICE_PER_SLOT)
                    .totalAmount(PRICE_PER_SLOT * slotCount)
                    .holdExpiresAt(if (status == ReservationStatus.HELD) now.plusMinutes(10) else null)
                    .createdAt(now)
                    .build(),
            )
        val reservationId = requireNotNull(reservation.id)
        var slot = start
        while (slot.isBefore(end)) {
            reservationSlotRepository.save(ReservationSlot.of(reservationId, spaceId, slot))
            slot = slot.plusMinutes(30)
        }
        return reservationId
    }

    protected fun now(): LocalDateTime = LocalDateTime.now(clock).withNano(0)

    protected fun tomorrowAt(
        hour: Int,
        minute: Int,
    ): LocalDateTime =
        now()
            .plusDays(1)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)

    protected fun setBalance(
        memberId: Long,
        balance: Int,
    ) {
        jdbcTemplate.update("UPDATE member SET balance = ? WHERE id = ?", balance, memberId)
    }

    protected fun balanceOf(memberId: Long): Int =
        jdbcTemplate.queryForObject("SELECT balance FROM member WHERE id = ?", Int::class.java, memberId)

    protected fun statusOf(reservationId: Long): String =
        jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String::class.java, reservationId)

    protected fun countSlots(reservationId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation_slot WHERE reservation_id = ?", Int::class.java, reservationId)

    protected fun countSlotsInRange(
        spaceId: Long,
        fromInclusive: LocalDateTime,
        toExclusive: LocalDateTime,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation_slot WHERE space_id = ? AND slot_start >= ? AND slot_start < ?",
            Int::class.java,
            spaceId,
            fromInclusive,
            toExclusive,
        )

    protected fun countLedger(
        reservationId: Long,
        type: String,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM credit_transaction WHERE reservation_id = ? AND type = ?",
            Int::class.java,
            reservationId,
            type,
        )

    protected fun sumLedger(
        reservationId: Long,
        type: String,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM credit_transaction WHERE reservation_id = ? AND type = ?",
            Int::class.java,
            reservationId,
            type,
        )

    protected fun countHistory(
        reservationId: Long,
        toStatus: String,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation_status_history WHERE reservation_id = ? AND to_status = ?",
            Int::class.java,
            reservationId,
            toStatus,
        )

    protected fun countIdempotencyKeys(memberId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_key WHERE member_id = ?", Int::class.java, memberId)

    protected fun runConcurrently(tasks: List<Runnable>): List<Throwable> {
        val executor = Executors.newFixedThreadPool(tasks.size)
        try {
            val ready = CountDownLatch(tasks.size)
            val go = CountDownLatch(1)
            val futures =
                tasks.map { task ->
                    executor.submit(
                        Callable<Throwable?> {
                            ready.countDown()
                            go.await()
                            try {
                                task.run()
                                null
                            } catch (
                                t: Throwable,
                            ) {
                                t
                            }
                        },
                    )
                }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            @Suppress("UNCHECKED_CAST")
            return futures.map { it.get(60, TimeUnit.SECONDS) } as List<Throwable>
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(e)
        } catch (e: ExecutionException) {
            throw IllegalStateException(e)
        } catch (e: TimeoutException) {
            throw IllegalStateException(e)
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        const val PRICE_PER_SLOT: Int = 5000

        @JvmStatic protected fun successCount(results: List<out Throwable?>): Int = results.count { it == null }

        @JvmStatic protected fun failures(results: List<out Throwable?>): List<Any> =
            results.filterNotNull().map {
                if (it is BusinessException) it.errorCode else it
            }

        private fun inList(ids: List<Long>): String = ids.joinToString(",").ifEmpty { "0" }
    }
}
