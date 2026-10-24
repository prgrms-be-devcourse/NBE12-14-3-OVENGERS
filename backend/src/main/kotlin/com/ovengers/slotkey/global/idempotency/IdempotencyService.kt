package com.ovengers.slotkey.global.idempotency

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * Idempotency-Key 재생 서비스(api-명세서.md 1-7, core-domain-decisions.md core-domain-decisions 2-1).
 * "돈이 움직이는 지점"인 결제 확인(pay) 등에서 사용한다.
 *
 * 동시에 같은 키로 두 요청이 동시에 들어와 저장 레코드를 둘 다 못 찾는 극히 좁은
 * 경합 창은 의도적으로 남겨둔다 — 실제 차감/상태전이는 각 도메인의 조건부 UPDATE가
 * 이미 원자적으로 막아주므로(HELD -> CONFIRMED 전이는 한 쪽만 성공), 이 좁은 창에서는
 * 최악의 경우 한쪽이 정상적인 409를 받을 뿐이고, 그 요청을 같은 키로 재시도하면 그때는
 * 저장된 성공 응답을 정상적으로 돌려받는다.
 */
@Service
class IdempotencyService(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /** 이전에 처리된 동일 요청이 있으면 저장된 응답을 역직렬화해 돌려주고, 없으면 null을 돌려준다. */
    @Transactional(readOnly = true)
    fun <T> find(
        key: String,
        memberId: Long?,
        requestPath: String,
        type: Class<T>,
    ): T? {
        val resolvedMemberId = requireNotNull(memberId)

        val stored =
            idempotencyKeyRepository.findByKeyAndMemberIdAndRequestPath(
                key,
                resolvedMemberId,
                requestPath,
            )

        val record = stored.orElse(null) ?: return null

        return readValue(record.responseBody, type)
    }

    /** 최초 처리 결과를 저장한다. 이후 재요청은 find()로 그대로 재생된다. */
    @Transactional
    fun save(
        key: String,
        memberId: Long?,
        requestPath: String,
        responseStatus: Int,
        responseBody: Any?,
    ) {
        idempotencyKeyRepository.save(
            IdempotencyKey.of(
                key,
                memberId,
                requestPath,
                responseStatus,
                writeValue(responseBody),
                LocalDateTime.now(clock),
            ),
        )
    }

    private fun <T> readValue(
        json: String,
        type: Class<T>,
    ): T? =
        try {
            objectMapper.readValue(json, type)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("저장된 idempotency 응답을 읽을 수 없습니다.", e)
        }

    private fun writeValue(value: Any?): String =
        try {
            objectMapper.writeValueAsString(value)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("idempotency 응답을 저장할 수 없습니다.", e)
        }
}
