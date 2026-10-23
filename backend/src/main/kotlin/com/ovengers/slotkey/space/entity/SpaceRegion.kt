package com.ovengers.slotkey.space.entity

/**
 * 공간 지역. 별도 region 테이블 없이 {@code spaces.location}에 [label]을 그대로 저장하고,
 * 등록·수정 요청에서만 이 enum으로 검증한다(DB 제약 아님). 근거: docs/decisions/space-ranking.md §2-5.
 */
enum class SpaceRegion(
    private val label: String,
) {
    PANGYO("판교"),
    GANGNAM("강남"),
    HANAM("하남"),
    ;

    fun label(): String = label

    companion object {
        @JvmStatic
        fun isSupported(location: String?): Boolean = location?.let { candidate -> values().any { it.label == candidate } } ?: false
    }
}
