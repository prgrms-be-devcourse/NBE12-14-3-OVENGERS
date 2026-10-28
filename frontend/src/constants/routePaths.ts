/** 프론트엔드 라우트 경로. 결제 확인 화면은 HOLD 도입으로 추가된 별도 화면입니다. */
export const ROUTES = {
  home: '/',
  login: '/login',
  signup: '/signup',
  passwordReset: '/password-reset',
  myPage: '/mypage',

  creditCharge: '/credits/charge',
  creditHistory: '/credits/history',
  creditChargeSuccess: '/credits/charge/success',
  creditChargeFail: '/credits/charge/fail',

  spaces: '/spaces',
  spaceDetail: (spaceId: number | string) => `/spaces/${spaceId}`,

  ranking: '/ranking',

  reservations: '/reservations',
  waitlist: '/waitlist',
  notifications: '/notifications',
  reservationDetail: (reservationId: number | string) => `/reservations/${reservationId}`,
  reservationPayment: (reservationId: number | string) => `/reservations/${reservationId}/payment`,

  inquiries: '/inquiries',
  inquiryNew: '/inquiries/new',
  inquiryDetail: (id: number | string) => `/inquiries/detail?id=${id}`,
  adminInquiries: '/admin/inquiries',
  adminInquiryDetail: (id: number | string) => `/admin/inquiries/detail?id=${id}`,

  door: '/door',

  adminDashboard: '/admin/dashboard',
  adminSpaces: '/admin/spaces',
  adminSpaceNew: '/admin/spaces/new',
  adminSpaceEdit: (spaceId: number | string) => `/admin/spaces/${spaceId}/edit`,
  adminReservations: '/admin/reservations',
  adminReservationDetail: (reservationId: number | string) => `/admin/reservations/${reservationId}`,
  adminMembers: '/admin/members',
  adminMemberDetail: (memberId: number | string) => `/admin/members/${memberId}`,
};

export interface NavItem {
  href: string;
  label: string;
  requiresAuth?: boolean;
}

/**
 * 상단 메뉴 (일반 사용자). 비로그인 방문자에게도 같은 메뉴를 보여 주고,
 * requiresAuth 메뉴는 화면의 RequireAuth 가 로그인 화면으로 보낸다.
 */
export const USER_NAV: NavItem[] = [
  { href: ROUTES.spaces, label: '오피스 찾기' },
  { href: ROUTES.ranking, label: '인기 오피스' },
  { href: ROUTES.reservations, label: '예약 내역', requiresAuth: true },
  { href: ROUTES.door, label: '출입하기', requiresAuth: true },
];

/** 관리자 화면에서 숨기는 회원 전용 메뉴 */
const MEMBER_ONLY_NAV: Set<string> = new Set([ROUTES.ranking, ROUTES.reservations]);

export function visibleUserNav(hideMemberMenus: boolean): NavItem[] {
  return hideMemberMenus ? USER_NAV.filter((item) => !MEMBER_ONLY_NAV.has(item.href)) : USER_NAV;
}

/** 좌측 메뉴 (관리자) */
export const ADMIN_NAV: NavItem[] = [
  { href: ROUTES.adminDashboard, label: '대시보드' },
  { href: ROUTES.adminInquiries, label: '문의 관리' },
  { href: ROUTES.adminSpaces, label: '오피스 관리' },
  { href: ROUTES.adminReservations, label: '예약 관리' },
  { href: ROUTES.adminMembers, label: '회원 관리' },
];
