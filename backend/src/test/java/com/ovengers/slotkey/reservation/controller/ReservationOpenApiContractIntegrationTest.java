package com.ovengers.slotkey.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReservationOpenApiContractIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void reservationSchemasDescribeValuesAndRequiredFields() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        JsonNode create = schemas.path("ReservationCreateRequest");
        assertThat(create.path("properties").path("spaceId").path("type").asText()).isEqualTo("integer");
        assertThat(create.path("properties").path("spaceId").path("format").asText()).isEqualTo("int64");
        assertThat(create.path("properties").path("date").path("format").asText()).isEqualTo("date");
        assertThat(create.path("properties").path("startTime").path("type").asText()).isEqualTo("string");
        assertThat(create.path("properties").path("startTime").path("example").asText()).isEqualTo("09:00:00");
        assertThat(create.path("properties").path("spaceId").path("description").asText()).isEqualTo("예약할 공간 ID");
        assertThat(create.path("required")).containsExactlyInAnyOrder(
                objectMapper.getNodeFactory().textNode("spaceId"), objectMapper.getNodeFactory().textNode("date"),
                objectMapper.getNodeFactory().textNode("startTime"), objectMapper.getNodeFactory().textNode("endTime"));
        JsonNode pay = schemas.path("ReservationPayRequest");
        assertThat(pay.path("properties").path("spaceVersion").path("type").asText()).isEqualTo("integer");
        assertThat(pay.path("properties").path("spaceVersion").path("format").asText()).isEqualTo("int32");
        assertThat(pay.path("properties").path("spaceVersion").path("example").asText()).isEqualTo("0");
        assertThat(pay.path("required")).containsExactly(objectMapper.getNodeFactory().textNode("spaceVersion"));
        assertThat(schemas.path("ForceCancelRequest").path("properties").path("reason").path("maxLength").intValue())
                .isEqualTo(500);
        assertThat(schemas.path("ReservationResponse").path("properties").path("checkedInAt").path("format").asText())
                .isEqualTo("date-time");
    }

    @Test
    void optionalQueryParametersKeepTheirDescriptionsExamplesAndOptionality() throws Exception {
        JsonNode paths = document().path("paths");
        JsonNode member = paths.path("/api/v1/reservations").path("get").path("parameters");
        assertParameter(member, "status", "예약 상태 필터 (미지정 시 전체)", "CONFIRMED");
        assertParameter(member, "keyword", "공간 이름 검색어 (부분 일치, 앞뒤 공백 trim 및 공백만 입력 시 전체)", "회의실");
        JsonNode admin = paths.path("/api/v1/admin/reservations").path("get").path("parameters");
        assertParameter(admin, "date", "이용 시작 날짜 (YYYY-MM-DD)", "2026-09-30");
        assertParameter(admin, "spaceId", "공간 ID (양수)", "1");
        JsonNode adminStatus = parameter(admin, "status");
        assertThat(adminStatus.findValue("description").asText()).isEqualTo("예약 상태");
        assertThat(adminStatus.path("required").booleanValue()).isFalse();
    }

    private JsonNode document() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private void assertParameter(JsonNode parameters, String name, String description, String example) {
        JsonNode parameter = parameter(parameters, name);
        assertThat(parameter.findValue("description")).as("%s 설명", name).isNotNull();
        assertThat(parameter.findValue("description").asText()).isEqualTo(description);
        assertThat(parameter.findValue("example")).as("%s 예제", name).isNotNull();
        assertThat(parameter.findValue("example").asText()).isEqualTo(example);
        assertThat(parameter.path("required").booleanValue()).isFalse();
    }

    private JsonNode parameter(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asText())) return parameter;
        }
        throw new AssertionError("검색 파라미터가 없음: " + name);
    }
}
