package com.ovengers.slotkey.space.controller

import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.time.LocalTime

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
class AdminSpaceImageMultipartLimitIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var authSessionService: AuthSessionService

    @Test
    @DisplayName("5MB 초과 multipart 요청 시 실제 서블릿 multipart 파서가 MaxUploadSizeExceededException을 발생시키고 413 IMAGE_SIZE_EXCEEDED를 반환한다")
    fun uploadSpaceImage_exceedsServletMultipartLimit_returns413() {
        val admin = saveMember(MemberRole.ADMIN, MemberStatus.ACTIVE)
        val space = saveSpace(SpaceStatus.ACTIVE)

        // 5MB + 10KB 크기의 페이로드 (서블릿 max-file-size 5MB 초과)
        val payloadSize = (5 * 1024 * 1024) + (10 * 1024)
        val oversizedBytes = ByteArray(payloadSize)
        oversizedBytes[0] = 0xFF.toByte()
        oversizedBytes[1] = 0xD8.toByte()
        oversizedBytes[2] = 0xFF.toByte()

        val resource =
            object : ByteArrayResource(oversizedBytes) {
                override fun getFilename(): String = "oversized.jpg"
            }

        val headers = HttpHeaders()
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        val session = authSessionService.create(admin)
        headers.set(
            HttpHeaders.AUTHORIZATION,
            "Bearer " +
                jwtProvider.genAccessToken(
                    admin,
                    session.id,
                    session.expiresAt,
                ),
        )

        val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
        body.add("file", resource)

        val requestEntity = HttpEntity<MultiValueMap<String, Any>>(body, headers)

        val response: ResponseEntity<String> =
            restTemplate.exchange(
                "/api/v1/admin/spaces/{spaceId}/image",
                HttpMethod.PUT,
                requestEntity,
                String::class.java,
                space.id,
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
        assertThat(response.body).contains("\"status\":\"FAIL\"")
        assertThat(response.body).contains("\"code\":\"IMAGE_SIZE_EXCEEDED\"")
    }

    private fun saveMember(
        role: MemberRole,
        status: MemberStatus,
    ): Member {
        val member =
            Member(
                "${role.name.lowercase()}-${status.name.lowercase()}-${System.nanoTime()}@test.com",
                "limit-test-member",
            )
        ReflectionTestUtils.setField(member, "role", role)
        ReflectionTestUtils.setField(member, "status", status)
        return memberRepository.saveAndFlush(member)
    }

    private fun saveSpace(status: SpaceStatus): Space =
        spaceRepository.saveAndFlush(
            Space
                .builder()
                .name("용량 제한 테스트 공간")
                .location("서울시 강남구")
                .description("용량 제한 통합 테스트")
                .capacity(8)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(status)
                .version(1)
                .build(),
        )
}
