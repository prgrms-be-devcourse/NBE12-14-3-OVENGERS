import { ROUTES } from '../constants/routePaths';

export interface NotificationTarget { href: string; label: string }

/** 알림 타입별로 이동할 화면. 모르는 타입(서버가 먼저 배포된 경우)은 링크 없이 문구만 보여 준다. */
export function notificationTarget(notification: { type?: string; referenceId: number }): NotificationTarget | null {
  const { type, referenceId } = notification;
  switch (type) {
    case 'WAITLIST_SLOT_RELEASED': return { href: ROUTES.waitlist, label: '빈자리 알림 보기' };
    case 'RESERVATION_FORCE_CANCELLED': return { href: ROUTES.reservationDetail(referenceId), label: '예약 보기' };
    case 'CREDIT_GRANTED': return { href: ROUTES.creditHistory, label: '충전 내역 보기' };
    case 'INQUIRY_ANSWERED': return { href: ROUTES.inquiryDetail(referenceId), label: '답변 보기' };
    case 'INQUIRY_CREATED': return { href: ROUTES.adminInquiryDetail(referenceId), label: '문의 보기' };
    default: return null;
  }
}
