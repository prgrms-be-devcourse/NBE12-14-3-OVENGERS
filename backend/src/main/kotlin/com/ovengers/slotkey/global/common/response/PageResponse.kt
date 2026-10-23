package com.ovengers.slotkey.global.common.response

import org.springframework.data.domain.Page

/**
 * Spring Data Page 를 클라이언트 친화적인 형태로 변환하는 페이지네이션 응답 래퍼.
 *
 * <pre>
 * { "content": [...], "page": 0, "size": 20, "totalElements": 100, "totalPages": 5 }
 * </pre>
 */
@JvmRecord
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        @JvmStatic
        fun <T> from(page: Page<T>): PageResponse<T> =
            page.run {
                PageResponse(content, number, size, totalElements, totalPages)
            }
    }
}
