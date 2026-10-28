'use client';

import { useCallback } from 'react';
import Link from 'next/link';
import { getChargeHistory, getCreditGrantHistory } from '../../api/creditApi';
import { useAsync } from '../../hooks/useApi';
import { usePagination } from '../../hooks/usePagination';
import { ROUTES } from '../../constants/routePaths';
import { formatCredit } from '../../utils/price';
import { formatDateTime } from '../../utils/date';
import { getPaymentMethodView } from '../../utils/paymentMethod';
import type { ChargeOrderStatus, Tone } from '../../types/api';
import Badge from '../../components/common/Badge';
import EmptyState from '../../components/common/EmptyState';
import ErrorMessage from '../../components/common/ErrorMessage';
import LoadingSpinner from '../../components/common/LoadingSpinner';
import Pagination from '../../components/common/Pagination';

const STATUS_VIEW: Record<ChargeOrderStatus, { label: string; tone: Tone }> = {
  PAID: { label: '충전 완료', tone: 'green' },
  CONFIRMING: { label: '승인 확인 중', tone: 'cyan' },
  FAILED: { label: '충전 실패', tone: 'red' },
  PENDING: { label: '결제 대기', tone: 'gray' },
};

/** PG 결제 충전과 관리자 지급 내역. 예약 결제·환불 같은 크레딧 사용 내역은 포함하지 않는다. */
export default function ChargeHistoryPage() {
  const { page, size, setPage } = usePagination({ initialSize: 10 });
  const fetchHistory = useCallback(() => getChargeHistory({ page, size }), [page, size]);
  const { data, loading, error, run } = useAsync(fetchHistory, [fetchHistory]);
  const items = data?.content ?? [];
  const grantPagination = usePagination({ initialSize: 10 });
  const fetchGrants = useCallback(
    () => getCreditGrantHistory({ page: grantPagination.page, size: grantPagination.size }),
    [grantPagination.page, grantPagination.size],
  );
  const grants = useAsync(fetchGrants, [fetchGrants]);
  const grantItems = grants.data?.content ?? [];

  return (
    <div className="charge-history">
      <div className="pagehead">
        <div>
          <p className="page-kicker">CHARGE HISTORY</p>
          <h1>충전 내역</h1>
          <p>PG 결제로 충전한 내역과 관리자가 지급한 크레딧 내역입니다.</p>
        </div>
        <Link href={ROUTES.creditCharge} className="btn primary">크레딧 충전하기</Link>
      </div>

      {loading && <LoadingSpinner label="충전 내역을 불러오는 중입니다…" />}
      <ErrorMessage error={error} onRetry={() => { void run().catch(() => {}); }} />

      {!loading && !error && items.length === 0 && (
        <EmptyState
          title="충전 내역이 없습니다"
          description="크레딧을 충전하면 이곳에서 결제 내역을 확인할 수 있어요."
          action={<Link href={ROUTES.creditCharge} className="btn primary">크레딧 충전하기</Link>}
        />
      )}

      {items.map((item) => {
        const view = STATUS_VIEW[item.status];
        const method = getPaymentMethodView(item.paymentMethod, item.paymentProvider);
        return (
          <div className="panel charge-history-row" key={item.orderId}>
            <div>
              <strong>{formatCredit(item.amount)}</strong>
              {method && (
                <p className="charge-history-method">
                  <span aria-hidden="true">{method.icon}</span> {method.label}
                </p>
              )}
              <p className="muted">{formatDateTime(item.updatedAt)}</p>
              <p className="muted charge-history-order">주문번호 {item.orderId}</p>
            </div>
            <Badge tone={view.tone}>{view.label}</Badge>
          </div>
        );
      })}

      {data && (
        <Pagination
          page={data.page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          onChange={setPage}
        />
      )}

      {grantItems.length > 0 && (
        <section className="charge-history-grants" aria-label="관리자 지급 내역">
          <h2>관리자 지급 내역</h2>
          {grantItems.map((grant) => (
            <div className="panel charge-history-row" key={grant.transactionId}>
              <div>
                <strong>{formatCredit(grant.amount)}</strong>
                {grant.reason && <p className="charge-history-method">사유: {grant.reason}</p>}
                <p className="muted">{formatDateTime(grant.createdAt)}</p>
              </div>
              <Badge tone="green">관리자 지급</Badge>
            </div>
          ))}
          {grants.data && (
            <Pagination
              page={grants.data.page}
              totalPages={grants.data.totalPages}
              totalElements={grants.data.totalElements}
              onChange={grantPagination.setPage}
            />
          )}
        </section>
      )}
    </div>
  );
}
