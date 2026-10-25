package com.ovengers.slotkey.space.repository

import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalTime
import java.util.Optional

@Repository
@JvmSuppressWildcards
interface SpaceRepository : JpaRepository<Space, Long> {
    @Query(
        "SELECT s FROM Space s " +
            "WHERE (:status IS NULL OR s.status = :status) " +
            "AND (:keyword IS NULL OR :keyword = '' OR s.name LIKE %:keyword% " +
            "OR s.description LIKE %:keyword% OR s.location LIKE %:keyword%)",
    )
    fun searchSpacesByNameOrDescription(
        @Param("keyword") keyword: String?,
        @Param("status") status: SpaceStatus?,
        pageable: Pageable?,
    ): Page<Space>

    fun findAllByStatus(
        status: SpaceStatus?,
        pageable: Pageable?,
    ): Page<Space>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Space s WHERE s.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long?,
    ): Optional<Space>

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM Space s WHERE s.id = :id")
    fun findByIdForShare(
        @Param("id") id: Long?,
    ): Optional<Space>

    /**
     * 공간 찾기 화면(오피스 찾기) 필터: 키워드/지역/가격 범위 + 영업시간 포함 + 특정 시간대 점유 공간 제외.
     * excludedSpaceIds는 시간대 필터를 쓰지 않을 때도 항상 채워서 넘긴다(빈 컬렉션 바인딩을 피하기 위해
     * SpaceQueryService가 매치되지 않는 sentinel id 하나짜리 리스트를 기본값으로 넣는다).
     */
    @Query(
        "SELECT s FROM Space s " +
            "WHERE s.status = :status " +
            "AND (:keyword IS NULL OR :keyword = '' OR s.name LIKE %:keyword% " +
            "OR s.description LIKE %:keyword% OR s.location LIKE %:keyword%) " +
            "AND (:location IS NULL OR :location = '' OR s.location LIKE %:location%) " +
            "AND (:minPrice IS NULL OR s.pricePerSlot >= :minPrice) " +
            "AND (:maxPrice IS NULL OR s.pricePerSlot <= :maxPrice) " +
            "AND (:startTime IS NULL OR s.openingTime <= :startTime) " +
            "AND (:endTime IS NULL OR s.closingTime >= :endTime) " +
            "AND s.id NOT IN :excludedSpaceIds",
    )
    fun searchSpaces(
        @Param("status") status: SpaceStatus?,
        @Param("keyword") keyword: String?,
        @Param("location") location: String?,
        @Param("minPrice") minPrice: Long?,
        @Param("maxPrice") maxPrice: Long?,
        @Param("startTime") startTime: LocalTime?,
        @Param("endTime") endTime: LocalTime?,
        @Param("excludedSpaceIds") excludedSpaceIds: List<Long>?,
        pageable: Pageable?,
    ): Page<Space>
}
