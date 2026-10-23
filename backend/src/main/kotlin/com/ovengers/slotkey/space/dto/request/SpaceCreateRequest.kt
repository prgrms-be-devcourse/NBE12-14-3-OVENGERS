package com.ovengers.slotkey.space.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceRegion
import com.ovengers.slotkey.space.entity.SpaceStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalTime

@JvmRecord
@Schema(description = "공간 등록 요청")
data class SpaceCreateRequest(
    @field:Schema(description = "공간 이름 (1~100자)", example = "판교 미팅룸")
    @field:NotBlank
    @field:Size(min = 1, max = 100, message = "오피스 이름은 1~100자여야 합니다.")
    val name: String?,
    @field:Schema(description = "공간 지역. 판교, 강남, 하남 중 하나", example = "판교")
    @field:NotBlank(message = "오피스 주소를 입력해주세요.")
    val location: String?,
    @field:Schema(description = "공간 소개", example = "최대 6명이 이용할 수 있는 회의실입니다.")
    val description: String?,
    @field:Schema(description = "최대 수용 인원 (1명 이상)", example = "6")
    @field:NotNull(message = "오피스 최소 수용 인원을 입력해주세요.")
    @field:Min(value = 1, message = "오피스 최소 수용 인원은 1명 이상이어야 합니다.")
    val capacity: Int?,
    @field:Schema(description = "30분당 이용 요금 (100원 이상, 100원 단위)", example = "2500")
    @field:NotNull(message = "슬롯당 요금을 입력해주세요.")
    @field:Min(value = 100, message = "오피스의 슬롯 당 고정 이용 요금은 100원 이상이어야 합니다.")
    val pricePerSlot: Long?,
    @field:Schema(
        description =
            "공간 사진 등록 시 직접 입력하지 않고 비워두어야 합니다. 비어 있지 않은 값을 전송하면 VALIDATION_FAILED(400) 오류가 발생합니다. " +
                "사진은 공간 등록 후 별도 사진 업로드 API(PUT /api/v1/admin/spaces/{spaceId}/image)로 등록합니다.",
        example = "",
    )
    @get:Schema(
        description =
            "공간 사진 등록 시 직접 입력하지 않고 비워두어야 합니다. 비어 있지 않은 값을 전송하면 VALIDATION_FAILED(400) 오류가 발생합니다. " +
                "사진은 공간 등록 후 별도 사진 업로드 API(PUT /api/v1/admin/spaces/{spaceId}/image)로 등록합니다.",
        example = "",
    )
    @param:Schema(
        description =
            "공간 사진 등록 시 직접 입력하지 않고 비워두어야 합니다. 비어 있지 않은 값을 전송하면 VALIDATION_FAILED(400) 오류가 발생합니다. " +
                "사진은 공간 등록 후 별도 사진 업로드 API(PUT /api/v1/admin/spaces/{spaceId}/image)로 등록합니다.",
        example = "",
    )
    @field:Size(max = 500, message = "이미지 경로는 500자 이하여야 합니다.")
    val imagePath: String?,
    @field:Schema(description = "운영 시작 시각 (HH:mm)", example = "09:00", type = "string")
    @field:NotNull(message = "오피스의 운영 시작 시각을 정해주세요.")
    @field:JsonFormat(pattern = "HH:mm")
    val openingTime: LocalTime?,
    @field:Schema(description = "운영 종료 시각 (HH:mm). 시작 시각보다 늦어야 합니다.", example = "22:00", type = "string")
    @field:NotNull(message = "오피스의 운영 종료 시각을 정해주세요.")
    @field:JsonFormat(pattern = "HH:mm")
    val closingTime: LocalTime?,
) {
    @AssertTrue(message = "오피스 사진은 별도 사진 업로드 API(PUT /api/v1/admin/spaces/{id}/image)로만 등록할 수 있습니다. imagePath를 비워두세요.")
    fun isImagePathEmpty(): Boolean = imagePath == null || imagePath.isJavaBlank()

    @AssertTrue(message = "오피스 지역은 판교, 강남, 하남 중 하나여야 합니다.")
    fun isLocationSupported(): Boolean = location == null || location.isJavaBlank() || SpaceRegion.isSupported(location)

    fun toEntity(): Space {
        Space.validateOperatingHours(openingTime, closingTime)
        Space.validatePricePerSlot(pricePerSlot)

        return Space
            .builder()
            .name(name)
            .location(location)
            .description(description)
            .capacity(capacity)
            .pricePerSlot(pricePerSlot)
            .imagePath(null)
            .openingTime(openingTime)
            .closingTime(closingTime)
            .status(SpaceStatus.ACTIVE)
            .build()
    }
}

private fun String.isJavaBlank(): Boolean = isEmpty() || codePoints().allMatch { Character.isWhitespace(it) }
