package com.ovengers.slotkey.credit.service

/**
 * 크레딧 원장(credit_transaction)에 대한 포트. reservation 도메인은 이 인터페이스로만
 * 크레딧을 차감/지급하고, 실제 원장 기록은 credit 도메인 구현체가 담당한다
 * (space 도메인의 OccupiedSlotProvider와 같은 포트-어댑터 패턴 — 도메인 완성 순서와
 * 무관하게 reservation이 독립적으로 빌드/테스트되도록 하기 위함).
 *
 * 차감은 반드시 조건부 UPDATE(WHERE balance >= amount)로 구현해야 한다(core-domain-decisions 1-5).
 * dirty checking으로 잔액을 갱신하면 갱신 유실(lost update)이 발생한다.
 */
interface CreditService {
    /**
     * 예약 관련 크레딧을 차감한다(RESERVATION_CHARGE 또는 연장 추가 차감).
     * 잔액이 부족하면 예외를 던지고, 호출부가 있는 트랜잭션 전체가 롤백되어야 한다
     * (예약 상태 전이도 함께 롤백됨 — "실패 시 409, 크레딧도 롤백").
     *
     * @return 차감 후 잔액(balance_after)
     * @throws com.ovengers.slotkey.global.error.BusinessException INSUFFICIENT_BALANCE(422) — 잔액 부족
     */
    fun charge(
        memberId: Long?,
        reservationId: Long?,
        amount: Int,
    ): Int

    /**
     * 예약 취소에 따른 크레딧 환급을 원장에 기록한다(REFUND, +amount).
     * 취소와 같은 트랜잭션에서 호출되어야 한다 — "취소는 됐는데 환불은 실패"하는
     * 중간 상태를 구조적으로 만들지 않기 위함(§9).
     *
     * @param amount 양수로 전달한다(원장에는 +amount로 기록된다)
     * @return 환급 후 잔액(balance_after)
     */
    fun refund(
        memberId: Long?,
        reservationId: Long?,
        amount: Int,
    ): Int

    /**
     * 취소 위약금을 원장에 기록한다(PENALTY, -amount). 환불 등급이 100%가 아닌 취소에서만
     * 호출한다(§9) — REFUND(+전액)와 PENALTY(-위약금)를 한 줄로 합치지 않고 분리 기록한다.
     *
     * @param amount 양수로 전달한다(원장에는 -amount로 기록된다)
     * @return 위약금 차감 후 잔액(balance_after)
     */
    fun penalize(
        memberId: Long?,
        reservationId: Long?,
        amount: Int,
    ): Int
}
