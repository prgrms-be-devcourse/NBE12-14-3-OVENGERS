package com.ovengers.slotkey.space.entity;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest;
import jakarta.persistence.*;

import java.time.LocalTime;

@Entity
// 2026-09-15 수정(이태호): 테이블명이 V2 마이그레이션에서 spaces(복수) -> space(단수)로
// 정정됐는데 엔티티 매핑은 그대로 남아 있어 실제 스키마와 어긋나 있었다(연결된 채로는 부팅 시 테이블을
// 찾지 못한다). DDL(V2)과 맞춰 단수로 정정.
@Table(name = "spaces")
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // PK, 고유 식별자

    @Column(nullable = false, length = 100)
    private String name; // 공간 이름

    @Column(nullable = false)
    private String location; // 공간의 위치 or 지역명

    @Column(columnDefinition = "TEXT")
    private String description; // 공간 상세 소개

    @Column(nullable = false)
    private Integer capacity; // 공간 최대 수용 인원

    @Column(nullable = false, name = "price_per_slot")
    private Long pricePerSlot; // 공간의 슬롯 당 고정 이용 요금 (100원 단위)

    @Column(length = 500, name = "image_path")
    private String imagePath; // 대표 이미지 저장 경로

    @Column(nullable = false, name = "opening_time")
    private LocalTime openingTime; // 일일 운영 시작 시각

    @Column(nullable = false, name = "closing_time")
    private LocalTime closingTime; // 일일 운영 종료 시각

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SpaceStatus status; // 신규 예약 접수 가능 여부

    // 2026-09-15 추가(이태호, core-domain-decisions.md core-domain-decisions 5-2/§11): DDL(V2)에는 이미 있었으나
    // 엔티티 매핑이 빠져 있던 낙관적 비교용 버전. HOLD 응답에 실어 보냈다가 결제 확인(pay) 시
    // 되돌려받아 비교한다 — 가격 값이 아니라 이 값으로 비교해야 ABA 문제를 피할 수 있다.
    // int(참조형 아님)로 둔 이유: 이미 있는 Space.builder() 호출부(SpaceCreateRequest.toEntity() 등)가
    // version을 명시적으로 채우지 않아도 NULL이 아니라 0이 들어가 NOT NULL 제약을 통과한다.
    @Column(nullable = false)
    private int version;

    public static void validateOperatingHours(LocalTime openingTime, LocalTime closingTime) {
        if (openingTime == null
                || closingTime == null
                || !openingTime.isBefore(closingTime)
                || !isHalfHourBoundary(openingTime)
                || !isHalfHourBoundary(closingTime)) {
            throw new BusinessException(ErrorCode.INVALID_OPERATING_HOURS);
        }
    }

    private static boolean isHalfHourBoundary(LocalTime time) {
        return (time.getMinute() == 0 || time.getMinute() == 30)
                && time.getSecond() == 0
                && time.getNano() == 0;
    }

    public static void validatePricePerSlot(Long pricePerSlot) {
        if (pricePerSlot == null || pricePerSlot <= 0 || pricePerSlot % 100 != 0) {
            throw new BusinessException(ErrorCode.INVALID_PRICE_UNIT);
        }
    }

    public void updateDetail(SpaceUpdateRequest request) {
        if (request.name() != null) {
            this.name = request.name();
        }
        if (request.location() != null) {
            this.location = request.location();
        }
        if (request.description() != null) {
            this.description = request.description();
        }
        if (request.capacity() != null) {
            this.capacity = request.capacity();
        }
        if (request.pricePerSlot() != null) {
            validatePricePerSlot(request.pricePerSlot());
            if (!request.pricePerSlot().equals(this.pricePerSlot)) {
                this.version++;
            }
            this.pricePerSlot = request.pricePerSlot();
        }
        // Note: imagePath는 updateDetail로 직접 수정하지 않고, uploadAndAttachSpaceImage(updateImagePath)로만 변경된다.
        if (request.openingTime() != null && request.closingTime() != null) {
            validateOperatingHours(request.openingTime(), request.closingTime());
            this.openingTime = request.openingTime();
            this.closingTime = request.closingTime();
        } else if (request.openingTime() != null) {
            validateOperatingHours(request.openingTime(), this.closingTime);
            this.openingTime = request.openingTime();
        } else if (request.closingTime() != null) {
            validateOperatingHours(this.openingTime, request.closingTime());
            this.closingTime = request.closingTime();
        }
        if (request.status() != null) {
            this.status = request.status();
        }
    }

    public void updateStatus(SpaceStatus status) {
        this.status = status;
    }

    /**
     * 대표 이미지 경로를 수정한다.
     * 사진 변경은 결제 전후 가격 일치 검증용 낙관적 잠금 버전(version)을 변경하지 않는다.
     */
    public void updateImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public static SpaceBuilder builder() {
        return new SpaceBuilder();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public String getDescription() {
        return description;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public Long getPricePerSlot() {
        return pricePerSlot;
    }

    public String getImagePath() {
        return imagePath;
    }

    public LocalTime getOpeningTime() {
        return openingTime;
    }

    public LocalTime getClosingTime() {
        return closingTime;
    }

    public SpaceStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public Space() {
    }

    public Space(Long id, String name, String location, String description, Integer capacity,
                 Long pricePerSlot, String imagePath, LocalTime openingTime, LocalTime closingTime,
                 SpaceStatus status, int version) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.description = description;
        this.capacity = capacity;
        this.pricePerSlot = pricePerSlot;
        this.imagePath = imagePath;
        this.openingTime = openingTime;
        this.closingTime = closingTime;
        this.status = status;
        this.version = version;
    }

    public static class SpaceBuilder {
        private Long id;
        private String name;
        private String location;
        private String description;
        private Integer capacity;
        private Long pricePerSlot;
        private String imagePath;
        private LocalTime openingTime;
        private LocalTime closingTime;
        private SpaceStatus status;
        private int version;

        SpaceBuilder() {
        }

        public SpaceBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public SpaceBuilder name(String name) {
            this.name = name;
            return this;
        }

        public SpaceBuilder location(String location) {
            this.location = location;
            return this;
        }

        public SpaceBuilder description(String description) {
            this.description = description;
            return this;
        }

        public SpaceBuilder capacity(Integer capacity) {
            this.capacity = capacity;
            return this;
        }

        public SpaceBuilder pricePerSlot(Long pricePerSlot) {
            this.pricePerSlot = pricePerSlot;
            return this;
        }

        public SpaceBuilder imagePath(String imagePath) {
            this.imagePath = imagePath;
            return this;
        }

        public SpaceBuilder openingTime(LocalTime openingTime) {
            this.openingTime = openingTime;
            return this;
        }

        public SpaceBuilder closingTime(LocalTime closingTime) {
            this.closingTime = closingTime;
            return this;
        }

        public SpaceBuilder status(SpaceStatus status) {
            this.status = status;
            return this;
        }

        public SpaceBuilder version(int version) {
            this.version = version;
            return this;
        }

        public Space build() {
            return new Space(id, name, location, description, capacity, pricePerSlot, imagePath,
                    openingTime, closingTime, status, version);
        }

        @Override
        public String toString() {
            return "Space.SpaceBuilder(id=" + id
                    + ", name=" + name
                    + ", location=" + location
                    + ", description=" + description
                    + ", capacity=" + capacity
                    + ", pricePerSlot=" + pricePerSlot
                    + ", imagePath=" + imagePath
                    + ", openingTime=" + openingTime
                    + ", closingTime=" + closingTime
                    + ", status=" + status
                    + ", version=" + version
                    + ")";
        }
    }
}
