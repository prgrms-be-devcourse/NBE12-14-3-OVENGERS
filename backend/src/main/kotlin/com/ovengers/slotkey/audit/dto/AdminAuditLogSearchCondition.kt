package com.ovengers.slotkey.audit.dto

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

@Schema(description = "감사 로그 검색 조건. 모든 조건은 선택 사항입니다.")
@JvmRecord
data class AdminAuditLogSearchCondition(
    @field:Schema(description = "작업을 수행한 관리자 회원 ID", example = "1")
    @get:Schema(description = "작업을 수행한 관리자 회원 ID", example = "1")
    @param:Schema(description = "작업을 수행한 관리자 회원 ID", example = "1")
    val actorMemberId: Long?,
    @field:Schema(description = "관리자가 수행한 작업 종류")
    @get:Schema(description = "관리자가 수행한 작업 종류")
    @param:Schema(description = "관리자가 수행한 작업 종류")
    val action: AuditAction?,
    @field:Schema(description = "작업 대상의 종류")
    @get:Schema(description = "작업 대상의 종류")
    @param:Schema(description = "작업 대상의 종류")
    val targetType: AuditTargetType?,
    @field:Schema(description = "작업 대상 ID. 대상 종류와 함께 지정하면 구분이 명확해집니다.", example = "10")
    @get:Schema(description = "작업 대상 ID. 대상 종류와 함께 지정하면 구분이 명확해집니다.", example = "10")
    @param:Schema(description = "작업 대상 ID. 대상 종류와 함께 지정하면 구분이 명확해집니다.", example = "10")
    val targetId: Long?,
    @field:Schema(description = "조회 시작 날짜 (포함)", example = "2026-09-01")
    @get:Schema(description = "조회 시작 날짜 (포함)", example = "2026-09-01")
    @param:Schema(description = "조회 시작 날짜 (포함)", example = "2026-09-01")
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @get:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val dateFrom: LocalDate?,
    @field:Schema(description = "조회 종료 날짜 (해당 날짜 전체 포함)", example = "2026-09-30")
    @get:Schema(description = "조회 종료 날짜 (해당 날짜 전체 포함)", example = "2026-09-30")
    @param:Schema(description = "조회 종료 날짜 (해당 날짜 전체 포함)", example = "2026-09-30")
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @get:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val dateTo: LocalDate?,
)
