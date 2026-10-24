package com.ovengers.slotkey.global.idempotency;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Idempotency-Key 재요청 시 원래 응답을 그대로 돌려주기 위한 저장 레코드.
 * (idempotency_key, member_id, request_path) 조합이 유일해야 한다 — 같은 키라도
 * 회원이나 엔드포인트가 다르면 서로 다른 요청으로 취급한다.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String key;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "request_path", nullable = false, length = 255)
    private String requestPath;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // @NoArgsConstructor 대체
    public IdempotencyKey() {
    }

    // @AllArgsConstructor 대체
    public IdempotencyKey(
            Long id,
            String key,
            Long memberId,
            String requestPath,
            int responseStatus,
            String responseBody,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.key = key;
        this.memberId = memberId;
        this.requestPath = requestPath;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    // @Getter 대체
    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 기존 정적 팩토리 메서드 유지
    public static IdempotencyKey of(String key, Long memberId, String requestPath,
                                    int responseStatus, String responseBody, LocalDateTime now) {
        return IdempotencyKey.builder()
                .key(key)
                .memberId(memberId)
                .requestPath(requestPath)
                .responseStatus(responseStatus)
                .responseBody(responseBody)
                .createdAt(now)
                .build();
    }

    // @Builder 대체
    public static IdempotencyKeyBuilder builder() {
        return new IdempotencyKeyBuilder();
    }

    public static class IdempotencyKeyBuilder {

        private Long id;
        private String key;
        private Long memberId;
        private String requestPath;
        private int responseStatus;
        private String responseBody;
        private LocalDateTime createdAt;

        IdempotencyKeyBuilder() {
        }

        public IdempotencyKeyBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public IdempotencyKeyBuilder key(String key) {
            this.key = key;
            return this;
        }

        public IdempotencyKeyBuilder memberId(Long memberId) {
            this.memberId = memberId;
            return this;
        }

        public IdempotencyKeyBuilder requestPath(String requestPath) {
            this.requestPath = requestPath;
            return this;
        }

        public IdempotencyKeyBuilder responseStatus(int responseStatus) {
            this.responseStatus = responseStatus;
            return this;
        }

        public IdempotencyKeyBuilder responseBody(String responseBody) {
            this.responseBody = responseBody;
            return this;
        }

        public IdempotencyKeyBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public IdempotencyKey build() {
            return new IdempotencyKey(
                    id,
                    key,
                    memberId,
                    requestPath,
                    responseStatus,
                    responseBody,
                    createdAt
            );
        }

        @Override
        public String toString() {
            return "IdempotencyKey.IdempotencyKeyBuilder("
                    + "id=" + id
                    + ", key=" + key
                    + ", memberId=" + memberId
                    + ", requestPath=" + requestPath
                    + ", responseStatus=" + responseStatus
                    + ", responseBody=" + responseBody
                    + ", createdAt=" + createdAt
                    + ")";
        }
    }
}
