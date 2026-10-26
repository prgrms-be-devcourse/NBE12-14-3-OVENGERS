
package com.ovengers.slotkey.member.repository

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface MemberRepository : JpaRepository<Member, Long> {
    fun findByEmail(email: String): Optional<Member>

    fun existsByEmail(email: String): Boolean

    @Query(
        """
        SELECT m FROM Member m
        WHERE (:status IS NULL OR m.status = :status)
        AND (:keyword IS NULL OR :keyword = '' OR m.email
        LIKE %:keyword% OR m.nickname LIKE %:keyword%)
        """,
    )
    fun searchMembers(
        @Param("keyword") keyword: String?,
        @Param("status") status: MemberStatus?,
        pageable: Pageable,
    ): Page<Member>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :memberId")
    fun findByIdForUpdate(
        @Param("memberId") memberId: Long,
    ): Optional<Member>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.email = :email")
    fun findByEmailForUpdate(
        @Param("email") email: String,
    ): Optional<Member>
}
