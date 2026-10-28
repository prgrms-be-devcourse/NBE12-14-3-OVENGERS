'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { getNotifications, readAllNotifications } from '../../api/notificationApi';
import { useAction, useAsync } from '../../hooks/useApi';
import { notificationTarget } from '../../utils/notificationTarget';
import { formatDateTime } from '../../utils/date';
import Pagination from '../../components/common/Pagination';
import EmptyState from '../../components/common/EmptyState';
import ErrorMessage from '../../components/common/ErrorMessage';
import LoadingSpinner from '../../components/common/LoadingSpinner';
import { NOTIFICATIONS_READ_EVENT } from '../../components/notification/NotificationLink';
import styles from '../../components/notification/Notification.module.css';

export default function NotificationsPage() {
  const [page, setPage] = useState(0);
  return <div className={styles.page}>
    <div className="pagehead"><div><h1>내 알림</h1><p>빈자리, 예약 취소, 크레딧 지급, 문의 소식을 확인하세요. 목록을 열면 표시된 최신 알림까지 읽음 처리됩니다.</p></div></div>
    <NotificationResults key={page} page={page} onPage={setPage} />
  </div>;
}

function NotificationResults({ page, onPage }: { page: number; onPage: (page: number) => void }) {
  const { data, loading, error, run } = useAsync(() => getNotifications({ page }), [page]);
  const renderedUpToId = useRef<number | null>(null);
  const { execute, error: readError } = useAction(async (upToId: number) => {
    await readAllNotifications(upToId);
    window.dispatchEvent(new Event(NOTIFICATIONS_READ_EVENT));
  });
  useEffect(() => {
    if (data && !loading && !error) {
      renderedUpToId.current = data.content.length
        ? Math.max(...data.content.map(notification => notification.id)) : null;
      if (renderedUpToId.current !== null) void execute(renderedUpToId.current).catch(() => {});
    }
    // 목록 스냅샷이 렌더링된 다음 읽음 처리한다. 읽음 응답으로 data를 덮어쓰지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, loading, error]);
  if (loading) return <LoadingSpinner label="알림을 불러오고 있습니다…" />;
  if (error) return <ErrorMessage error={error} onRetry={() => { void run().catch(() => {}); }} />;
  return <>
    <ErrorMessage error={readError} onRetry={() => {
      if (renderedUpToId.current !== null) void execute(renderedUpToId.current).catch(() => {});
    }} />
    {!data?.content.length ? <EmptyState title="아직 받은 알림이 없습니다" description="빈자리, 예약 취소, 크레딧 지급, 문의 소식이 생기면 여기서 확인할 수 있어요." /> :
      <ul className={styles.list}>{data.content.map((notification) => {
        const target = notificationTarget(notification);
        return <li className={styles.item} key={notification.id}>
          <div className={styles.meta}>{!notification.read && <span className={styles.unread}>안 읽음</span>}<time dateTime={notification.createdAt}>{formatDateTime(notification.createdAt)}</time></div>
          <p>{notification.message}</p>{target && <Link className="btn small" href={target.href}>{target.label}</Link>}
        </li>;
      })}</ul>}
    <Pagination page={data?.page} totalPages={data?.totalPages} totalElements={data?.totalElements} onChange={onPage} />
  </>;
}
