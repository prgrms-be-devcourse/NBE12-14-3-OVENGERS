'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { getUnreadCount } from '../../api/notificationApi';
import { ROUTES } from '../../constants/routePaths';
import UnreadBadge from './UnreadBadge';
import styles from './Notification.module.css';

export const NOTIFICATIONS_READ_EVENT = 'slotkey:notifications-read';

export default function NotificationLink({ memberId, pathname }: { memberId?: number; pathname: string }) {
  const [result, setResult] = useState<{ memberId: number; count: number } | null>(null);
  const onNotifications = pathname === ROUTES.notifications;
  useEffect(() => {
    if (!memberId) return;
    let active = true;
    let request = 0;
    const refresh = () => {
      const current = ++request;
      void getUnreadCount().then(({ unreadCount }) => {
        if (active && current === request) setResult({ memberId, count: unreadCount });
      }).catch(() => {});
    };
    refresh();
    window.addEventListener(NOTIFICATIONS_READ_EVENT, refresh);
    return () => { active = false; window.removeEventListener(NOTIFICATIONS_READ_EVENT, refresh); };
  }, [memberId, onNotifications]);
  if (!memberId) return null;
  const count = result?.memberId === memberId ? result.count : 0;
  const label = count > 0 ? `알림, 안 읽은 알림 ${count}개` : '알림';
  return <Link className={onNotifications ? `${styles.entry} ${styles.active}` : styles.entry} href={ROUTES.notifications}
    aria-label={label} aria-current={onNotifications ? 'page' : undefined} title="알림">
    <svg className={styles.icon} width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 9a6 6 0 0 1 12 0c0 6 2.5 7.5 2.5 7.5h-17S6 15 6 9Z" /><path d="M10 20a2 2 0 0 0 4 0" />
    </svg>
    <UnreadBadge count={count} />
  </Link>;
}
