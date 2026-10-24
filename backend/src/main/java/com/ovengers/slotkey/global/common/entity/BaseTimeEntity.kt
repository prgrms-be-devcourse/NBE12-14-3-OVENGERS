package com.ovengers.slotkey.global.common.entity

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseTimeEntity {
    @field:CreatedDate
    @field:Column(name = "created_at", nullable = false, updatable = false)
    final var createdAt: LocalDateTime? = null
        private set

    @field:LastModifiedDate
    @field:Column(name = "updated_at")
    final var updatedAt: LocalDateTime? = null
        private set
}
