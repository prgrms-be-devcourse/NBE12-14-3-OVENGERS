package com.ovengers.slotkey.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.audit.dto.AuditLogResponse;
import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditLog;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.global.common.response.ApiResponse;
import com.ovengers.slotkey.global.common.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kotlin 전환 전 감사 조회 응답의 JSON 계약을 정적 골든으로 고정한다.
 * beforeValue/afterValue는 중첩 객체로 해석하지 않고 저장된 JSON 문자열 그대로 비교한다.
 */
@JsonTest
class AuditGoldenTest {

    private static final String GOLDEN_DIRECTORY = "/golden/audit/";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("AuditLogResponse는 모든 필드와 beforeValue/afterValue JSON 원본 문자열을 유지한다")
    void auditLogResponse() throws Exception {
        AuditLogResponse response = AuditLogResponse.from(auditLog(
                100L,
                7L,
                AuditAction.MODIFY_SPACE,
                "가격 변경",
                "{\"pricePerSlot\":5000}",
                "{\"pricePerSlot\":6000}",
                LocalDateTime.of(2026, 10, 14, 10, 15, 30)));

        JsonNode actual = serialize(response);

        assertThat(actual.get("beforeValue").asText()).isEqualTo("{\"pricePerSlot\":5000}");
        assertThat(actual.get("afterValue").asText()).isEqualTo("{\"pricePerSlot\":6000}");
        assertThat(actual.get("beforeValue").isObject()).isFalse();
        assertMatchesGolden(actual, "audit-log-response.json");
    }

    @Test
    @DisplayName("actorMemberId·reason·beforeValue·afterValue가 null이면 null 필드로 유지한다")
    void auditLogResponseWithNullFields() throws Exception {
        AuditLogResponse response = AuditLogResponse.from(auditLog(
                101L,
                null,
                AuditAction.FORCE_CANCEL_RESERVATION,
                null,
                null,
                null,
                LocalDateTime.of(2026, 10, 14, 10, 20, 0)));

        JsonNode actual = serialize(response);

        for (String field : List.of("actorMemberId", "reason", "beforeValue", "afterValue")) {
            assertThat(actual.get(field).isNull()).as("%s는 null이어야 한다", field).isTrue();
        }
        assertMatchesGolden(actual, "audit-log-response-with-null-fields.json");
    }

    @Test
    @DisplayName("감사 로그는 PageResponse와 ApiResponse로 감싸도 content·page·totalElements 계약을 유지한다")
    void auditLogPageResponse() throws Exception {
        AuditLogResponse response = AuditLogResponse.from(auditLog(
                100L,
                7L,
                AuditAction.MODIFY_SPACE,
                "가격 변경",
                "{\"pricePerSlot\":5000}",
                "{\"pricePerSlot\":6000}",
                LocalDateTime.of(2026, 10, 14, 10, 15, 30)));
        PageResponse<AuditLogResponse> page = PageResponse.from(
                new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        JsonNode actual = serialize(ApiResponse.success(page));

        assertThat(actual.at("/data/content").isArray()).isTrue();
        assertThat(actual.at("/data/totalElements").asLong()).isEqualTo(1L);
        assertMatchesGolden(actual, "audit-log-page-response.json");
    }

    private static AuditLog auditLog(Long id, Long actorMemberId, AuditAction action, String reason,
                                     String beforeValue, String afterValue, LocalDateTime createdAt) {
        return AuditLog.builder()
                .id(id)
                .actorMemberId(actorMemberId)
                .action(action)
                .targetType(action == AuditAction.FORCE_CANCEL_RESERVATION
                        ? AuditTargetType.RESERVATION
                        : AuditTargetType.SPACE)
                .targetId(action == AuditAction.FORCE_CANCEL_RESERVATION ? 200L : 11L)
                .reason(reason)
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .createdAt(createdAt)
                .build();
    }

    private JsonNode serialize(Object value) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    private void assertMatchesGolden(JsonNode actual, String goldenFile) throws Exception {
        try (InputStream source = Objects.requireNonNull(
                getClass().getResourceAsStream(GOLDEN_DIRECTORY + goldenFile),
                "골든 파일이 존재해야 한다: " + goldenFile)) {
            JsonNode expected = objectMapper.readTree(source);
            assertThat(actual)
                    .as("전환 전 Java 응답과 골든 JSON이 같아야 한다: %s", goldenFile)
                    .isEqualTo(expected);
        }
    }
}
