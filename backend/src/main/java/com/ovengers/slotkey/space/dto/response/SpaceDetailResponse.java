package com.ovengers.slotkey.space.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;

import java.time.LocalTime;

public record SpaceDetailResponse(
        Long id,
        String name,
        String location,
        String description,
        Integer capacity,
        Long pricePerSlot,
        String imagePath,
        @JsonFormat(pattern = "HH:mm") LocalTime openingTime,
        @JsonFormat(pattern = "HH:mm") LocalTime closingTime,
        SpaceStatus status,
        Integer version) {
    public static SpaceDetailResponse from(Space space) {
        return SpaceDetailResponse.builder()
                .id(space.getId())
                .name(space.getName())
                .location(space.getLocation())
                .description(space.getDescription())
                .capacity(space.getCapacity())
                .pricePerSlot(space.getPricePerSlot())
                .imagePath(space.getImagePath())
                .openingTime(space.getOpeningTime())
                .closingTime(space.getClosingTime())
                .status(space.getStatus())
                .version(space.getVersion())
                .build();
    }

    public static SpaceDetailResponseBuilder builder() {
        return new SpaceDetailResponseBuilder();
    }

    public static class SpaceDetailResponseBuilder {
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
        private Integer version;

        SpaceDetailResponseBuilder() {
        }

        public SpaceDetailResponseBuilder id(final Long id) {
            this.id = id;
            return this;
        }

        public SpaceDetailResponseBuilder name(final String name) {
            this.name = name;
            return this;
        }

        public SpaceDetailResponseBuilder location(final String location) {
            this.location = location;
            return this;
        }

        public SpaceDetailResponseBuilder description(final String description) {
            this.description = description;
            return this;
        }

        public SpaceDetailResponseBuilder capacity(final Integer capacity) {
            this.capacity = capacity;
            return this;
        }

        public SpaceDetailResponseBuilder pricePerSlot(final Long pricePerSlot) {
            this.pricePerSlot = pricePerSlot;
            return this;
        }

        public SpaceDetailResponseBuilder imagePath(final String imagePath) {
            this.imagePath = imagePath;
            return this;
        }

        @JsonFormat(pattern = "HH:mm")
        public SpaceDetailResponseBuilder openingTime(final LocalTime openingTime) {
            this.openingTime = openingTime;
            return this;
        }

        @JsonFormat(pattern = "HH:mm")
        public SpaceDetailResponseBuilder closingTime(final LocalTime closingTime) {
            this.closingTime = closingTime;
            return this;
        }

        public SpaceDetailResponseBuilder status(final SpaceStatus status) {
            this.status = status;
            return this;
        }

        public SpaceDetailResponseBuilder version(final Integer version) {
            this.version = version;
            return this;
        }

        public SpaceDetailResponse build() {
            return new SpaceDetailResponse(id, name, location, description, capacity, pricePerSlot, imagePath,
                    openingTime, closingTime, status, version);
        }

        public String toString() {
            return "SpaceDetailResponse.SpaceDetailResponseBuilder(id=" + id + ", name=" + name + ", location=" + location
                    + ", description=" + description + ", capacity=" + capacity + ", pricePerSlot=" + pricePerSlot
                    + ", imagePath=" + imagePath + ", openingTime=" + openingTime + ", closingTime=" + closingTime
                    + ", status=" + status + ", version=" + version + ")";
        }
    }
}
