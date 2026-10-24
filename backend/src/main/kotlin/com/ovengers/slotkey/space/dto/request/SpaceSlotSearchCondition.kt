package com.ovengers.slotkey.space.dto.request

import com.ovengers.slotkey.space.entity.SpaceStatus
import jakarta.validation.constraints.NotNull

@JvmRecord
data class SpaceSlotSearchCondition(
    @field:NotNull(message = "오피스 상태를 입력해주세요.")
    val status: SpaceStatus?,
)
