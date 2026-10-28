import type { ChargeOrder, ChargeOrderHistoryItem, ConfirmChargeOrderRequest, CreditGrantHistoryItem, Page } from '../types/api';
import { API_ROUTES } from '../constants/apiRoutes';
import api from './client';

export function createChargeOrder(amount: number): Promise<ChargeOrder> {
  return api.post<ChargeOrder>(API_ROUTES.credits.chargeOrders, { amount });
}
export function confirmChargeOrder(
  request: ConfirmChargeOrderRequest,
  idempotencyKey: string,
): Promise<ChargeOrder> {
  return api.post<ChargeOrder>(
    API_ROUTES.credits.confirmChargeOrder,
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  );
}

/** 내 충전 내역(최신순). 결제가 시도되지 않은 PENDING 주문은 서버가 제외한다. */
export function getChargeHistory({ page = 0, size = 10 }: { page?: number; size?: number } = {}): Promise<Page<ChargeOrderHistoryItem>> {
  return api.get<Page<ChargeOrderHistoryItem>>(API_ROUTES.credits.chargeOrders, { query: { page, size } });
}

/** 내 관리자 지급 내역(최신순). */
export function getCreditGrantHistory({ page = 0, size = 10 }: { page?: number; size?: number } = {}): Promise<Page<CreditGrantHistoryItem>> {
  return api.get<Page<CreditGrantHistoryItem>>(API_ROUTES.credits.grants, { query: { page, size } });
}

export function getChargeOrder(orderId: string): Promise<ChargeOrder> {
  return api.get<ChargeOrder>(API_ROUTES.credits.chargeOrder(orderId));
}
