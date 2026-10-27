package com.ovengers.slotkey.space.dto.response

import com.fasterxml.jackson.annotation.JsonFormat
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import java.time.LocalTime

@JvmRecord
data class SpaceDetailResponse(
    val id: Long,
    val name: String,
    val location: String,
    val description: String?,
    val capacity: Int,
    val pricePerSlot: Long,
    val imagePath: String?,
    @field:JsonFormat(pattern = "HH:mm") @get:JsonFormat(pattern = "HH:mm") val openingTime: LocalTime,
    @field:JsonFormat(pattern = "HH:mm") @get:JsonFormat(pattern = "HH:mm") val closingTime: LocalTime,
    val status: SpaceStatus,
    val version: Int,
) {
    companion object {
        /**
         * 상세 응답에 필요한 필수 상태가 모두 있어야 한다. 누락된 엔티티를 0/빈 문자열/MIN·MAX/INACTIVE 같은
         * 그럴듯한 기본값으로 숨기지 않고, 어느 필드가 비었는지 밝히는 [IllegalStateException]으로 즉시 실패한다.
         * 이는 사용자 입력 검증이 아니라 내부 불변식 위반이며, description/imagePath의 선택 null과
         * 영속화 전 id null은 허용하되 응답 변환 시점에는 id가 있어야 한다.
         */
        @JvmStatic
        fun from(space: Space): SpaceDetailResponse =
            SpaceDetailResponse(
                checkNotNull(space.id) { "Space.id must not be null when creating a SpaceDetailResponse" },
                checkNotNull(space.name) { "Space.name must not be null when creating a SpaceDetailResponse" },
                checkNotNull(space.location) { "Space.location must not be null when creating a SpaceDetailResponse" },
                space.description,
                checkNotNull(space.capacity) { "Space.capacity must not be null when creating a SpaceDetailResponse" },
                checkNotNull(space.pricePerSlot) { "Space.pricePerSlot must not be null when creating a SpaceDetailResponse" },
                space.imagePath,
                checkNotNull(space.openingTime) { "Space.openingTime must not be null when creating a SpaceDetailResponse" },
                checkNotNull(space.closingTime) { "Space.closingTime must not be null when creating a SpaceDetailResponse" },
                checkNotNull(space.status) { "Space.status must not be null when creating a SpaceDetailResponse" },
                space.version,
            )
    }
}
