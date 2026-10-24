package com.ovengers.slotkey.space.controller

import com.jayway.jsonpath.JsonPath
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalTime
import javax.imageio.ImageIO

@AutoConfigureMockMvc
@Transactional
class AdminSpaceSecurityIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Test
    @DisplayName("미인증 요청은 관리자 공간 등록을 할 수 없고 데이터와 감사 로그를 남기지 않는다")
    fun createSpace_unauthenticated_returns401WithoutSideEffects() {
        val spacesBefore = spaceRepository.count()
        val auditsBefore = auditLogRepository.count()

        mockMvc
            .perform(
                post(ADMIN_SPACES_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validCreateRequest()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        assertThat(spaceRepository.count()).isEqualTo(spacesBefore)
        assertThat(auditLogRepository.count()).isEqualTo(auditsBefore)
    }

    @Test
    @DisplayName("미인증 요청은 관리자 공간 상세를 조회할 수 없다")
    fun getSpaceDetail_unauthenticated_returns401() {
        val space = saveSpace(SpaceStatus.ACTIVE)

        mockMvc
            .perform(get("$ADMIN_SPACES_PATH/{spaceId}", space.id))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
    }

    @Test
    @DisplayName("USER 역할은 관리자 공간 등록을 할 수 없고 데이터와 감사 로그를 남기지 않는다")
    fun createSpace_userRole_returns403WithoutSideEffects() {
        val user = saveMember(MemberRole.USER, MemberStatus.ACTIVE)
        val spacesBefore = spaceRepository.count()
        val auditsBefore = auditLogRepository.count()

        mockMvc
            .perform(
                post(ADMIN_SPACES_PATH)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validCreateRequest()),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        assertThat(spaceRepository.count()).isEqualTo(spacesBefore)
        assertThat(auditLogRepository.count()).isEqualTo(auditsBefore)
    }

    @Test
    @DisplayName("USER 역할은 관리자 공간 상세를 조회할 수 없다")
    fun getSpaceDetail_userRole_returns403() {
        val user = saveMember(MemberRole.USER, MemberStatus.ACTIVE)
        val space = saveSpace(SpaceStatus.ACTIVE)

        mockMvc
            .perform(
                get("$ADMIN_SPACES_PATH/{spaceId}", space.id)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken(user)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    @DisplayName("현재 회원 상태가 정지 또는 탈퇴인 ADMIN은 기존 AT로 공간을 등록할 수 없다")
    fun createSpace_statusChangedAfterIssue_rejectsInactiveMember() {
        for (memberStatus in arrayOf(MemberStatus.SUSPENDED, MemberStatus.WITHDRAWN)) {
            // 활성 상태에서 토큰 발급
            val admin = saveMember(MemberRole.ADMIN, MemberStatus.ACTIVE)
            val token = bearerToken(admin)

            // 발급 이후 회원 상태 변경
            ReflectionTestUtils.setField(admin, "status", memberStatus)
            memberRepository.saveAndFlush(admin)

            val spacesBefore = spaceRepository.count()
            val auditsBefore = auditLogRepository.count()

            mockMvc
                .perform(
                    post(ADMIN_SPACES_PATH)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRequest()),
                ).andExpect(status().isUnauthorized)

            assertThat(spaceRepository.count()).isEqualTo(spacesBefore)
            assertThat(auditLogRepository.count()).isEqualTo(auditsBefore)
        }
    }

    @Test
    @DisplayName("현재 회원 상태가 정지 또는 탈퇴인 ADMIN은 기존 AT로 공간 상세를 조회할 수 없다")
    fun getSpaceDetail_statusChangedAfterIssue_rejectsInactiveMember() {
        val space = saveSpace(SpaceStatus.ACTIVE)

        for (memberStatus in arrayOf(MemberStatus.SUSPENDED, MemberStatus.WITHDRAWN)) {
            val admin = saveMember(MemberRole.ADMIN, MemberStatus.ACTIVE)
            val token = bearerToken(admin)

            ReflectionTestUtils.setField(admin, "status", memberStatus)
            memberRepository.saveAndFlush(admin)

            mockMvc
                .perform(
                    get("$ADMIN_SPACES_PATH/{spaceId}", space.id)
                        .header(HttpHeaders.AUTHORIZATION, token),
                ).andExpect(status().isUnauthorized)
        }
    }

    @Test
    @DisplayName("ACTIVE ADMIN은 비활성 공간도 수정용 상세 정보로 조회할 수 있다")
    fun getSpaceDetail_activeAdmin_returnsInactiveSpace() {
        val admin = saveMember(MemberRole.ADMIN, MemberStatus.ACTIVE)
        val space = saveSpace(SpaceStatus.INACTIVE)

        mockMvc
            .perform(
                get("$ADMIN_SPACES_PATH/{spaceId}", space.id)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.id").value(space.id))
            .andExpect(jsonPath("$.data.name").value("관리자 상세 조회 공간"))
            .andExpect(jsonPath("$.data.status").value("INACTIVE"))
            .andExpect(jsonPath("$.data.version").value(3))
    }

    @Test
    @DisplayName("ACTIVE ADMIN은 공간을 등록 및 수정하고 각 작업의 감사 로그를 남긴다")
    fun createAndUpdateSpace_activeAdmin_succeedsAndWritesAuditLogs() {
        val admin = saveMember(MemberRole.ADMIN, MemberStatus.ACTIVE)
        val authorization = bearerToken(admin)

        val createResponse =
            mockMvc
                .perform(
                    post(ADMIN_SPACES_PATH)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRequest()),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("보안 테스트 공간"))
                .andReturn()
                .response
                .contentAsString

        val createdSpaceId: Number = JsonPath.read(createResponse, "$.data.id")
        val spaceId = createdSpaceId.toLong()
        mockMvc
            .perform(
                patch("$ADMIN_SPACES_PATH/{spaceId}", spaceId)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"수정된 보안 테스트 공간","openingTime":"09:30"}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정된 보안 테스트 공간"))

        assertThat(auditLogRepository.findAll())
            .filteredOn { auditLog -> auditLog.actorMemberId == admin.id }
            .extracting<AuditAction> { it.action }
            .containsExactlyInAnyOrder(AuditAction.REGISTER_SPACE, AuditAction.MODIFY_SPACE)
    }

    @Test
    @DisplayName("미인증 요청은 관리자 공간 대표 이미지를 업로드할 수 없고 파일/공간/감사로그에 부작용이 없다")
    fun uploadSpaceImage_unauthenticated_returns401WithoutSideEffects() {
        val space = saveSpace(SpaceStatus.ACTIVE)
        val auditsBefore = auditLogRepository.count()
        val bytes = createSampleJpegBytes(100, 100)
        val file = MockMultipartFile("file", "test.jpg", "image/jpeg", bytes)

        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "$ADMIN_SPACES_PATH/{spaceId}/image", space.id)
                    .file(file),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        val reloaded = spaceRepository.findById(space.id!!).orElseThrow()
        assertThat(reloaded.imagePath).isNull()
        assertThat(auditLogRepository.count()).isEqualTo(auditsBefore)
    }

    @Test
    @DisplayName("USER 역할은 관리자 공간 대표 이미지를 업로드할 수 없고 파일/공간/감사로그에 부작용이 없다")
    fun uploadSpaceImage_userRole_returns403WithoutSideEffects() {
        val user = saveMember(MemberRole.USER, MemberStatus.ACTIVE)
        val space = saveSpace(SpaceStatus.ACTIVE)
        val auditsBefore = auditLogRepository.count()
        val bytes = createSampleJpegBytes(100, 100)
        val file = MockMultipartFile("file", "test.jpg", "image/jpeg", bytes)

        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "$ADMIN_SPACES_PATH/{spaceId}/image", space.id)
                    .file(file)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken(user)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        val reloaded = spaceRepository.findById(space.id!!).orElseThrow()
        assertThat(reloaded.imagePath).isNull()
        assertThat(auditLogRepository.count()).isEqualTo(auditsBefore)
    }

    @Test
    @DisplayName("ADMIN 역할은 관리자 공간 대표 이미지를 업로드할 수 있고 공간 갱신과 MODIFY_SPACE 감사 로그를 기록한다")
    fun uploadSpaceImage_adminRole_returns200AndUpdatesSpaceAndLogsAudit() {
        val admin = saveMember(MemberRole.ADMIN, MemberStatus.ACTIVE)
        val space = saveSpace(SpaceStatus.ACTIVE)
        val bytes = createSampleJpegBytes(200, 150)
        val file = MockMultipartFile("file", "office.jpg", "image/jpeg", bytes)

        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "$ADMIN_SPACES_PATH/{spaceId}/image", space.id)
                    .file(file)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.imagePath").value(startsWith("/api/v1/space-images/")))

        val reloaded = spaceRepository.findById(space.id!!).orElseThrow()
        assertThat(reloaded.imagePath).startsWith("/api/v1/space-images/")

        assertThat(auditLogRepository.findAll())
            .filteredOn { auditLog -> auditLog.actorMemberId == admin.id }
            .extracting<AuditAction> { it.action }
            .contains(AuditAction.MODIFY_SPACE)
    }

    @Throws(IOException::class)
    private fun createSampleJpegBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d: Graphics2D = image.createGraphics()
        g2d.color = Color.MAGENTA
        g2d.fillRect(0, 0, width, height)
        g2d.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", baos)
        return baos.toByteArray()
    }

    private fun saveMember(
        role: MemberRole,
        status: MemberStatus,
    ): Member {
        val member =
            Member(
                "${role.name.lowercase()}-${status.name.lowercase()}-${System.nanoTime()}@test.com",
                "security-test-member",
            )
        ReflectionTestUtils.setField(member, "role", role)
        ReflectionTestUtils.setField(member, "status", status)
        return memberRepository.saveAndFlush(member)
    }

    private fun saveSpace(status: SpaceStatus): Space =
        spaceRepository.saveAndFlush(
            Space
                .builder()
                .name("관리자 상세 조회 공간")
                .location("서울시 강남구")
                .description("상세 조회 보안 통합 테스트")
                .capacity(8)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(status)
                .version(3)
                .build(),
        )

    private fun bearerToken(member: Member): String = "Bearer " + issueSessionBoundAccessToken(member, jwtProvider)

    private fun validCreateRequest(): String =
        """
        {
          "name": "보안 테스트 공간",
          "location": "강남",
          "description": "관리자 보안 통합 테스트",
          "capacity": 8,
          "pricePerSlot": 5000,
          "openingTime": "09:00",
          "closingTime": "18:00"
        }
        """.trimIndent()

    companion object {
        private const val ADMIN_SPACES_PATH = "/api/v1/admin/spaces"
    }
}
