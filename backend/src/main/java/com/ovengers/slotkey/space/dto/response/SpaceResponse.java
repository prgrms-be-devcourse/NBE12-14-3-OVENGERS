package com.ovengers.slotkey.space.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;

import java.time.LocalTime;

public record SpaceResponse(
        Long id,
        String name,
        String location,
        Integer capacity,
        Long pricePerSlot,
        String imagePath,
        @JsonFormat(pattern = "HH:mm") LocalTime openingTime,
        @JsonFormat(pattern = "HH:mm") LocalTime closingTime,
        SpaceStatus status) {
    public static SpaceResponse from(Space space) {
        return SpaceResponse.builder()
                .id(space.getId())
                .name(space.getName())
                .location(space.getLocation())
                .capacity(space.getCapacity())
                .pricePerSlot(space.getPricePerSlot())
                .imagePath(space.getImagePath())
                .openingTime(space.getOpeningTime())
                .closingTime(space.getClosingTime())
                .status(space.getStatus())
                .build();
    }

    protected static SpaceResponseBuilder builder() {
        return new SpaceResponseBuilder();
    }

    protected static class SpaceResponseBuilder {
        private Long id;
        private String name;
        private String location;
        private Integer capacity;
        private Long pricePerSlot;
        private String imagePath;
        private LocalTime openingTime;
        private LocalTime closingTime;
        private SpaceStatus status;

        SpaceResponseBuilder() {
        }

        public SpaceResponseBuilder id(final Long id) {
            this.id = id;
            return this;
        }

        public SpaceResponseBuilder name(final String name) {
            this.name = name;
            return this;
        }

        public SpaceResponseBuilder location(final String location) {
            this.location = location;
            return this;
        }

        public SpaceResponseBuilder capacity(final Integer capacity) {
            this.capacity = capacity;
            return this;
        }

        public SpaceResponseBuilder pricePerSlot(final Long pricePerSlot) {
            this.pricePerSlot = pricePerSlot;
            return this;
        }

        public SpaceResponseBuilder imagePath(final String imagePath) {
            this.imagePath = imagePath;
            return this;
        }

        @JsonFormat(pattern = "HH:mm")
        public SpaceResponseBuilder openingTime(final LocalTime openingTime) {
            this.openingTime = openingTime;
            return this;
        }

        @JsonFormat(pattern = "HH:mm")
        public SpaceResponseBuilder closingTime(final LocalTime closingTime) {
            this.closingTime = closingTime;
            return this;
        }

        public SpaceResponseBuilder status(final SpaceStatus status) {
            this.status = status;
            return this;
        }

        public SpaceResponse build() {
            return new SpaceResponse(id, name, location, capacity, pricePerSlot, imagePath, openingTime, closingTime, status);
        }

        public String toString() {
            return "SpaceResponse.SpaceResponseBuilder(id=" + id + ", name=" + name + ", location=" + location
                    + ", capacity=" + capacity + ", pricePerSlot=" + pricePerSlot + ", imagePath=" + imagePath
                    + ", openingTime=" + openingTime + ", closingTime=" + closingTime + ", status=" + status + ")";
        }
    }
}
