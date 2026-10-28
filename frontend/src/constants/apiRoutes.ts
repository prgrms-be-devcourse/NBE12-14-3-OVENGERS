/**
 * 백엔드 엔드포인트 경로를 한곳에 모읍니다.
 *
 * 기준 문서: docs/api/api-spec.md (2026-09-15, core-domain-decisions.md 확정본 반영).
 * 화면·API 모듈에서 경로 문자열을 직접 쓰지 않는 이유는, 경로가 바뀌어도 이 파일만
 * 고치면 되도록 하기 위함입니다.
 */

export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';

export const API_ROUTES = {
  auth: {
    signup: '/auth/signup',
    login: '/auth/login',
    refresh: '/auth/refresh',
    logout: '/auth/logout',
    withdraw: '/auth/withdraw',
    verificationIssue: '/auth/verification/issue',
    verificationCheck: '/auth/verification/check',
    passwordReset: '/auth/password-reset',
  },
  members: {
    me: '/members/me',
  },
  credits: {
    chargeOrders: '/credits/charge-orders',
    chargeOrder: (orderId: string) => `/credits/charge-orders/${orderId}`,
    confirmChargeOrder: '/credits/charge-orders/confirm',
    grants: '/credits/grants',
  },
  spaces: {
    list: '/spaces',
    detail: (spaceId: number | string) => `/spaces/${spaceId}`,
    slots: (spaceId: number | string) => `/spaces/${spaceId}/slots`,
  },
  waitlist: {
    // 예약 대기 신청·내 목록·취소(docs/plans/waitlist-outbox/01-waitlist-rules.md §4).
    create: '/waitlist',
    mine: '/waitlist/me',
    entry: (waitlistEntryId: number | string) => `/waitlist/${waitlistEntryId}`,
  },
  notifications: {
    list: '/notifications',
    unreadCount: '/notifications/unread-count',
    readAll: '/notifications/read-all',
  },
  inquiries: {
    list: '/inquiries',
    detail: (id: number | string) => `/inquiries/${id}`,
  },
  reservations: {
    // 예약 생성 = 슬롯 확보(HOLD). 결제는 아직 일어나지 않는다(api-spec.md 5-1).
    create: '/reservations',
    list: '/reservations',
    detail: (reservationId: number | string) => `/reservations/${reservationId}`,
    // 결제 확인 및 확정. Idempotency-Key 필수(api-spec.md 5-2).
    pay: (reservationId: number | string) => `/reservations/${reservationId}/pay`,
    // 취소는 POST .../cancel. 시작 전 CONFIRMED 상태에서만 가능(api-spec.md 5-4).
    cancel: (reservationId: number | string) => `/reservations/${reservationId}/cancel`,
    extend: (reservationId: number | string) => `/reservations/${reservationId}/extend`,
    checkOut: (reservationId: number | string) => `/reservations/${reservationId}/check-out`,
    doorToken: (reservationId: number | string) => `/reservations/${reservationId}/door-token`,
  },
  access: {
    // Mock Door 검증. 로그인 필요 — 서버가 예약자 본인 여부도 확인한다(api-spec.md 7).
    verify: '/door-access/verify',
  },
  // 일반 회원용 통계(api-spec §14-2). 관리자 랭킹과 같은 확정값이지만 순매출은 내려오지 않는다.
  stats: {
    spaceRanking: '/stats/space-ranking',
    locationRanking: '/stats/location-ranking',
  },
  admin: {
    inquiries: '/admin/inquiries',
    inquiry: (id: number | string) => `/admin/inquiries/${id}`,
    inquiryAnswer: (id: number | string) => `/admin/inquiries/${id}/answer`,
    spaces: '/admin/spaces',
    space: (spaceId: number | string) => `/admin/spaces/${spaceId}`,
    reservations: '/admin/reservations',
    reservation: (reservationId: number | string) => `/admin/reservations/${reservationId}`,
    forceCancel: (reservationId: number | string) => `/admin/reservations/${reservationId}/force-cancel`,
    members: '/admin/members',
    memberDetail: (memberId: number | string) => `/admin/members/${memberId}`,
    memberCreditTransactions: (memberId: number | string) => `/admin/members/${memberId}/credit-transactions`,
    memberReservations: (memberId: number | string) => `/admin/members/${memberId}/reservations`,
    memberStatusHistory: (memberId: number | string) => `/admin/members/${memberId}/status-history`,
    memberSuspend: (memberId: number | string) => `/admin/members/${memberId}/suspend`,
    memberRestore: (memberId: number | string) => `/admin/members/${memberId}/restore`,
    // 크레딧 지급(ADMIN_GRANT). 회수 없음, 자기 자신 지급 불가(core-domain-decisions.md 1-3).
    memberCredit: (memberId: number | string) => `/admin/members/${memberId}/credits`,
    spaceImage: (spaceId: number | string) => `/admin/spaces/${spaceId}/image`,
    // 관리자 통계(api-spec §14). 랭킹은 끝난 기간의 확정값, 상태별 건수는 실시간 값이다.
    statusCounts: '/admin/stats/status-counts',
    spaceRanking: '/admin/stats/space-ranking',
    locationRanking: '/admin/stats/location-ranking',
  },
};

/** 인증 헤더를 붙이지 않는 경로 (비회원도 호출 가능) */
export const PUBLIC_PATH_PREFIXES = ['/auth/', '/spaces', '/space-images'];
