package com.ovengers.slotkey.space.repository

import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.dto.response.SpaceResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.hibernate.proxy.HibernateProxy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime

@Transactional
class SpaceResponsePersistenceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    @DisplayName("실제 MySQL 저장 전 id는 null이고 flush 후 생성되며, clear 후 재조회한 값이 두 응답 팩토리에 그대로 매핑된다")
    fun saveFlushClearReload_generatesId_andMapsCompleteState() {
        // given
        val space = completeSpace(imagePath = null, description = null)
        assertThat(space.id).isNull()

        // when
        val saved = spaceRepository.save(space)
        entityManager.flush()
        val generatedId = saved.id
        entityManager.clear()

        // then
        assertThat(generatedId).isNotNull()
        val reloaded = spaceRepository.findById(generatedId!!).orElseThrow()
        assertThat(reloaded.id).isEqualTo(generatedId)
        assertThat(reloaded.name).isEqualTo("영속 회의실")
        assertThat(reloaded.location).isEqualTo("강남")
        assertThat(reloaded.description).isNull()
        assertThat(reloaded.capacity).isEqualTo(6)
        assertThat(reloaded.pricePerSlot).isEqualTo(5000L)
        assertThat(reloaded.imagePath).isNull()
        assertThat(reloaded.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(reloaded.closingTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(reloaded.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(reloaded.version).isEqualTo(0)

        val listResponse = SpaceResponse.from(reloaded)
        val detailResponse = SpaceDetailResponse.from(reloaded)
        assertThat(listResponse.id).isEqualTo(generatedId)
        assertThat(listResponse.imagePath).isNull()
        assertThat(detailResponse.id).isEqualTo(generatedId)
        assertThat(detailResponse.description).isNull()
        assertThat(detailResponse.imagePath).isNull()
        assertThat(detailResponse.version).isEqualTo(0)
    }

    @Test
    @DisplayName("실제 Hibernate 프록시는 응답 변환 전까지 초기화되지 않고, 변환 시 초기화되어 올바른 값을 반환한다")
    fun hibernateProxy_initializesOnlyDuringResponseConversion() {
        // given
        val saved = spaceRepository.save(completeSpace(imagePath = "/images/p.png", description = "설명"))
        entityManager.flush()
        val spaceId = saved.id!!
        entityManager.clear()

        // when
        val proxy = entityManager.getReference(Space::class.java, spaceId)

        // then
        assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()

        val listResponse = SpaceResponse.from(proxy)
        assertThat(Hibernate.isInitialized(proxy)).isTrue()
        assertThat(listResponse.id).isEqualTo(spaceId)
        assertThat(listResponse.name).isEqualTo("영속 회의실")
        assertThat(listResponse.location).isEqualTo("강남")
        assertThat(listResponse.capacity).isEqualTo(6)
        assertThat(listResponse.pricePerSlot).isEqualTo(5000L)
        assertThat(listResponse.imagePath).isEqualTo("/images/p.png")
        assertThat(listResponse.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(listResponse.closingTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(listResponse.status).isEqualTo(SpaceStatus.ACTIVE)
    }

    @Test
    @DisplayName("실제 MySQL에서 가격 변경 부분 수정은 version만 1 증가하고 null 요청 필드·이미지가 보존된다")
    fun partialUpdate_priceChange_incrementsVersionOnce_andPreservesOtherColumns() {
        // given
        val saved = spaceRepository.save(completeSpace(imagePath = "/images/original.png", description = "원래 설명"))
        entityManager.flush()
        val spaceId = saved.id!!
        entityManager.clear()

        val space = spaceRepository.findById(spaceId).orElseThrow()
        val request =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                6000L,
                "/images/ignored.png",
                null,
                null,
                null,
            )

        // when
        space.updateDetail(request)
        entityManager.flush()
        entityManager.clear()

        // then
        val reloaded = spaceRepository.findById(spaceId).orElseThrow()
        assertThat(reloaded.pricePerSlot).isEqualTo(6000L)
        assertThat(reloaded.version).isEqualTo(1)
        assertThat(reloaded.name).isEqualTo("영속 회의실")
        assertThat(reloaded.location).isEqualTo("강남")
        assertThat(reloaded.description).isEqualTo("원래 설명")
        assertThat(reloaded.capacity).isEqualTo(6)
        assertThat(reloaded.imagePath).isEqualTo("/images/original.png")
        assertThat(reloaded.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(reloaded.closingTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(reloaded.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(SpaceDetailResponse.from(reloaded).version).isEqualTo(1)

        // when: 같은 가격 재전송
        reloaded.updateDetail(request)
        entityManager.flush()
        entityManager.clear()

        // then
        assertThat(spaceRepository.findById(spaceId).orElseThrow().version).isEqualTo(1)
    }

    private fun completeSpace(
        imagePath: String?,
        description: String?,
    ): Space =
        Space
            .builder()
            .name("영속 회의실")
            .location("강남")
            .description(description)
            .capacity(6)
            .pricePerSlot(5000L)
            .imagePath(imagePath)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(0)
            .build()
}
