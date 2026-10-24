package com.ovengers.slotkey.credit.repository

import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Transactional
@DisplayName("회원별 크레딧 거래 내역 Repository 통합 테스트")
class CreditTransactionRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Test
    @DisplayName("해당 회원의 거래만 최신순으로 조회하고 페이지네이션한다")
    fun findByMemberId_returnsSortedPage() {
        // given
        val member =
            memberRepository.save(
                Member("credit-test@slotkey.test", "테스트회원"),
            )
        val otherMember =
            memberRepository.save(
                Member("credit-other@slotkey.test", "다른회원"),
            )

        val earlier = LocalDateTime.of(2026, 10, 10, 10, 0)
        val later = LocalDateTime.of(2026, 10, 11, 10, 0)

        val first =
            creditTransactionRepository.save(
                CreditTransaction(
                    member,
                    10000,
                    CreditTransactionType.ADMIN_GRANT,
                    null,
                    10000,
                    "지급",
                    earlier,
                ),
            )

        val second =
            creditTransactionRepository.save(
                CreditTransaction(
                    member,
                    -4000,
                    CreditTransactionType.RESERVATION_CHARGE,
                    null,
                    6000,
                    "예약 사용",
                    later,
                ),
            )

        val third =
            creditTransactionRepository.save(
                CreditTransaction(
                    member,
                    2000,
                    CreditTransactionType.REFUND,
                    null,
                    8000,
                    "환급",
                    later,
                ),
            )

        creditTransactionRepository.save(
            CreditTransaction(
                otherMember,
                50000,
                CreditTransactionType.TOP_UP,
                null,
                50000,
                "다른 회원 충전",
                later,
            ),
        )

        val pageable =
            PageRequest.of(
                0,
                2,
                Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"),
                ),
            )

        // when
        val page0 =
            creditTransactionRepository.findByMember_Id(
                member.id,
                pageable,
            )

        val page1 =
            creditTransactionRepository.findByMember_Id(
                member.id,
                pageable.next(),
            )

        // then
        assertThat(page0.totalElements).isEqualTo(3)
        assertThat(page0.totalPages).isEqualTo(2)

        assertThat(page0.content.map { it.id })
            .containsExactly(third.id, second.id)

        assertThat(page1.content.map { it.id })
            .containsExactly(first.id)
    }
}
