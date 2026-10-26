
package com.ovengers.slotkey.inquiry.repository

import com.ovengers.slotkey.inquiry.entity.Inquiry
import com.ovengers.slotkey.inquiry.entity.InquiryStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface InquiryRepository : JpaRepository<Inquiry, Long> {
    fun findAllByMemberId(
        memberId: Long,
        pageable: Pageable,
    ): Page<Inquiry>

    fun findAllByStatus(
        status: InquiryStatus,
        pageable: Pageable,
    ): Page<Inquiry>
}
