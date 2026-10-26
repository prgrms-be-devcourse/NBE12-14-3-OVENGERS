package com.ovengers.slotkey.space.entity

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalTime

@Entity
// 2026-09-15 수정(이태호): 테이블명이 V2 마이그레이션에서 spaces(복수) -> space(단수)로
// 정정됐는데 엔티티 매핑은 그대로 남아 있어 실제 스키마와 어긋나 있었다(연결된 채로는 부팅 시 테이블을
// 찾지 못한다). DDL(V2)과 맞춰 단수로 정정.
@Table(name = "spaces")
class Space {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @set:JvmSynthetic
    var id: Long? = null
        internal set

    @field:Column(nullable = false, length = 100)
    @set:JvmSynthetic
    var name: String? = null
        internal set

    @field:Column(nullable = false)
    @set:JvmSynthetic
    var location: String? = null
        internal set

    @field:Column(columnDefinition = "TEXT")
    @set:JvmSynthetic
    var description: String? = null
        internal set

    @field:Column(nullable = false)
    @set:JvmSynthetic
    var capacity: Int? = null
        internal set

    @field:Column(nullable = false, name = "price_per_slot")
    @set:JvmSynthetic
    var pricePerSlot: Long? = null
        internal set

    @field:Column(length = 500, name = "image_path")
    @set:JvmSynthetic
    var imagePath: String? = null
        internal set

    @field:Column(nullable = false, name = "opening_time")
    @set:JvmSynthetic
    var openingTime: LocalTime? = null
        internal set

    @field:Column(nullable = false, name = "closing_time")
    @set:JvmSynthetic
    var closingTime: LocalTime? = null
        internal set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    @set:JvmSynthetic
    var status: SpaceStatus? = null
        internal set

    // 2026-09-15 추가(이태호, core-domain-decisions.md core-domain-decisions 5-2/§11): DDL(V2)에는 이미 있었으나
    // 엔티티 매핑이 빠져 있던 낙관적 비교용 버전. HOLD 응답에 실어 보냈다가 결제 확인(pay) 시
    // 되돌려받아 비교한다 — 가격 값이 아니라 이 값으로 비교해야 ABA 문제를 피할 수 있다.
    // int(참조형 아님)로 둔 이유: 이미 있는 Space.builder() 호출부(SpaceCreateRequest.toEntity() 등)가
    // version을 명시적으로 채우지 않아도 NULL이 아니라 0이 들어가 NOT NULL 제약을 통과한다.
    @field:Column(nullable = false)
    @set:JvmSynthetic
    var version: Int = 0
        internal set

    constructor()

    constructor(
        id: Long?,
        name: String?,
        location: String?,
        description: String?,
        capacity: Int?,
        pricePerSlot: Long?,
        imagePath: String?,
        openingTime: LocalTime?,
        closingTime: LocalTime?,
        status: SpaceStatus?,
        version: Int,
    ) {
        this.id = id
        this.name = name
        this.location = location
        this.description = description
        this.capacity = capacity
        this.pricePerSlot = pricePerSlot
        this.imagePath = imagePath
        this.openingTime = openingTime
        this.closingTime = closingTime
        this.status = status
        this.version = version
    }

    fun updateDetail(request: SpaceUpdateRequest) {
        if (request.name != null) {
            this.name = request.name
        }
        if (request.location != null) {
            this.location = request.location
        }
        if (request.description != null) {
            this.description = request.description
        }
        if (request.capacity != null) {
            this.capacity = request.capacity
        }
        if (request.pricePerSlot != null) {
            validatePricePerSlot(request.pricePerSlot)
            if (request.pricePerSlot != this.pricePerSlot) {
                this.version++
            }
            this.pricePerSlot = request.pricePerSlot
        }
        // Note: imagePath는 updateDetail로 직접 수정하지 않고, uploadAndAttachSpaceImage(updateImagePath)로만 변경된다.
        if (request.openingTime != null && request.closingTime != null) {
            validateOperatingHours(request.openingTime, request.closingTime)
            this.openingTime = request.openingTime
            this.closingTime = request.closingTime
        } else if (request.openingTime != null) {
            validateOperatingHours(request.openingTime, this.closingTime)
            this.openingTime = request.openingTime
        } else if (request.closingTime != null) {
            validateOperatingHours(this.openingTime, request.closingTime)
            this.closingTime = request.closingTime
        }
        if (request.status != null) {
            this.status = request.status
        }
    }

    fun updateStatus(status: SpaceStatus?) {
        this.status = status
    }

    /**
     * 대표 이미지 경로를 수정한다.
     * 사진 변경은 결제 전후 가격 일치 검증용 낙관적 잠금 버전(version)을 변경하지 않는다.
     */
    fun updateImagePath(imagePath: String?) {
        this.imagePath = imagePath
    }

    class SpaceBuilder internal constructor() {
        private var id: Long? = null
        private var name: String? = null
        private var location: String? = null
        private var description: String? = null
        private var capacity: Int? = null
        private var pricePerSlot: Long? = null
        private var imagePath: String? = null
        private var openingTime: LocalTime? = null
        private var closingTime: LocalTime? = null
        private var status: SpaceStatus? = null
        private var version: Int = 0

        fun id(id: Long?): SpaceBuilder {
            this.id = id
            return this
        }

        fun name(name: String?): SpaceBuilder {
            this.name = name
            return this
        }

        fun location(location: String?): SpaceBuilder {
            this.location = location
            return this
        }

        fun description(description: String?): SpaceBuilder {
            this.description = description
            return this
        }

        fun capacity(capacity: Int?): SpaceBuilder {
            this.capacity = capacity
            return this
        }

        fun pricePerSlot(pricePerSlot: Long?): SpaceBuilder {
            this.pricePerSlot = pricePerSlot
            return this
        }

        fun imagePath(imagePath: String?): SpaceBuilder {
            this.imagePath = imagePath
            return this
        }

        fun openingTime(openingTime: LocalTime?): SpaceBuilder {
            this.openingTime = openingTime
            return this
        }

        fun closingTime(closingTime: LocalTime?): SpaceBuilder {
            this.closingTime = closingTime
            return this
        }

        fun status(status: SpaceStatus?): SpaceBuilder {
            this.status = status
            return this
        }

        fun version(version: Int): SpaceBuilder {
            this.version = version
            return this
        }

        fun build(): Space =
            Space(
                id = id,
                name = name,
                location = location,
                description = description,
                capacity = capacity,
                pricePerSlot = pricePerSlot,
                imagePath = imagePath,
                openingTime = openingTime,
                closingTime = closingTime,
                status = status,
                version = version,
            )

        override fun toString(): String =
            "Space.SpaceBuilder(id=" + id +
                ", name=" + name +
                ", location=" + location +
                ", description=" + description +
                ", capacity=" + capacity +
                ", pricePerSlot=" + pricePerSlot +
                ", imagePath=" + imagePath +
                ", openingTime=" + openingTime +
                ", closingTime=" + closingTime +
                ", status=" + status +
                ", version=" + version +
                ")"
    }

    companion object {
        @JvmStatic
        fun builder(): SpaceBuilder = SpaceBuilder()

        @JvmStatic
        fun validateOperatingHours(
            openingTime: LocalTime?,
            closingTime: LocalTime?,
        ) {
            if (openingTime == null ||
                closingTime == null ||
                !openingTime.isBefore(closingTime) ||
                !isHalfHourBoundary(openingTime) ||
                !isHalfHourBoundary(closingTime)
            ) {
                throw BusinessException(ErrorCode.INVALID_OPERATING_HOURS)
            }
        }

        private fun isHalfHourBoundary(time: LocalTime): Boolean =
            (time.minute == 0 || time.minute == 30) &&
                time.second == 0 &&
                time.nano == 0

        @JvmStatic
        fun validatePricePerSlot(pricePerSlot: Long?) {
            if (pricePerSlot == null || pricePerSlot <= 0 || pricePerSlot % 100 != 0L) {
                throw BusinessException(ErrorCode.INVALID_PRICE_UNIT)
            }
        }
    }
}
