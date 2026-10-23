package com.ovengers.slotkey.audit.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Table(name = "audit_logs")
@Entity
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // PK, 감사 로그 고유 식별자

    @Column(name = "actor_member_id")
    private Long actorMemberId; // 관리자 작업 수행 회원 ID. 시스템 작업인 경우 NULL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuditAction action; // 관리자가 수행한 작업의 종류

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, name = "target_type")
    private AuditTargetType targetType; // 작업 대상 유형

    @Column(nullable = false, name = "target_id")
    private Long targetId; // 작업 대상의 식별자

    @Column(length = 500)
    private String reason; // 관리자 작업 사유. 사유 없는 작업은 NULL

    @Column(columnDefinition = "TEXT", name = "before_value")
    private String beforeValue; // 변경 전 데이터 (JSON)

    @Column(columnDefinition = "TEXT", name = "after_value")
    private String afterValue; // 변경 후 데이터 (JSON)

    @CreatedDate
    @Column(nullable = false, name = "created_at", updatable = false)
    private LocalDateTime createdAt; // 관리자 작업이 수행된 시각

    public static AuditLogBuilder builder() {
        return new AuditLogBuilder();
    }

    public Long getId() {
        return id;
    }

    public Long getActorMemberId() {
        return actorMemberId;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getReason() {
        return reason;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public AuditLog() {
    }

    public AuditLog(Long id, Long actorMemberId, AuditAction action, AuditTargetType targetType,
                    Long targetId, String reason, String beforeValue, String afterValue,
                    LocalDateTime createdAt) {
        this.id = id;
        this.actorMemberId = actorMemberId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.createdAt = createdAt;
    }

    public static class AuditLogBuilder {
        private Long id;
        private Long actorMemberId;
        private AuditAction action;
        private AuditTargetType targetType;
        private Long targetId;
        private String reason;
        private String beforeValue;
        private String afterValue;
        private LocalDateTime createdAt;

        AuditLogBuilder() {
        }

        public AuditLogBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public AuditLogBuilder actorMemberId(Long actorMemberId) {
            this.actorMemberId = actorMemberId;
            return this;
        }

        public AuditLogBuilder action(AuditAction action) {
            this.action = action;
            return this;
        }

        public AuditLogBuilder targetType(AuditTargetType targetType) {
            this.targetType = targetType;
            return this;
        }

        public AuditLogBuilder targetId(Long targetId) {
            this.targetId = targetId;
            return this;
        }

        public AuditLogBuilder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public AuditLogBuilder beforeValue(String beforeValue) {
            this.beforeValue = beforeValue;
            return this;
        }

        public AuditLogBuilder afterValue(String afterValue) {
            this.afterValue = afterValue;
            return this;
        }

        public AuditLogBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AuditLog build() {
            return new AuditLog(id, actorMemberId, action, targetType, targetId, reason, beforeValue,
                    afterValue, createdAt);
        }

        @Override
        public String toString() {
            return "AuditLog.AuditLogBuilder(id=" + id
                    + ", actorMemberId=" + actorMemberId
                    + ", action=" + action
                    + ", targetType=" + targetType
                    + ", targetId=" + targetId
                    + ", reason=" + reason
                    + ", beforeValue=" + beforeValue
                    + ", afterValue=" + afterValue
                    + ", createdAt=" + createdAt
                    + ")";
        }
    }
}
