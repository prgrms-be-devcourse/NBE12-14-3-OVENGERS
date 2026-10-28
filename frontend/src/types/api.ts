/**
 * 서버 응답·요청 타입.
 *
 * 기준 문서: docs/api/api-spec.md, docs/core-domain-decisions.md.
 * 서버 DTO 가 바뀌면 이 파일을 먼저 고치고, 컴파일 오류가 가리키는 화면을 따라갑니다.
 */

/* ------------------------------------------------------------------ 공통 */

/** 서버 페이지네이션 응답 */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** global.css 의 badge 클래스와 1:1 대응 */
export type Tone = 'default' | 'green' | 'red' | 'gray' | 'cyan';

export interface Meta {
  label: string;
  tone: Tone;
}

export interface SelectOption {
  value: string | number;
  label: string;
}

/* ------------------------------------------------------------------ 회원 */

export type MemberRole = 'USER' | 'ADMIN';
export type MemberStatus = 'ACTIVE' | 'SUSPENDED' | 'WITHDRAWN';

export interface Member {
  memberId: number;
  email: string;
  nickname: string;
  role: MemberRole;
  status: MemberStatus;
  /** 크레딧 잔액. 1크레딧 = 1원 (core-domain-decisions.md 1-2) */
  balance: number;
  createdAt: string;
}

export interface AdminMember extends Member {
  lastStatusChangedAt: string | null;
  lastStatusChangeReason: string | null;
}


/** 관리자 회원 상세 조회 응답 */
export interface AdminMemberDetail {
  member: AdminMember;
  totalReservationCount: number;
  suspensionCount: number;
}

/** 크레딧 거래 유형 */
export type CreditTransactionType =
    | 'SIGNUP_GRANT'
    | 'ADMIN_GRANT'
    | 'RESERVATION_CHARGE'
    | 'REFUND'
    | 'PENALTY'
    | 'WITHDRAWAL_EXPIRATION'
    | 'TOP_UP';

/** 관리자 회원별 크레딧 거래 내역 */
export interface AdminMemberCreditTransaction {
  transactionId: number;
  type: CreditTransactionType;
  amount: number;
  balanceAfter: number;
  reason: string | null;
  createdAt: string;
}

/** 관리자 회원별 예약 내역 */
export interface AdminMemberReservation {
  reservationId: number;
  spaceId: number;
  spaceName: string;
  startTime: string;
  endTime: string;
  status: ReservationStatus;
  totalAmount: number | null;
  createdAt: string;
}

/** 관리자 회원 상태 변경 감사 로그 유형 */
export type AdminMemberStatusHistoryAction =
    | 'SUSPEND_MEMBER'
    | 'REACTIVATE_MEMBER'
    | 'LOCK_MEMBER_BY_VERIFICATION';

/** 관리자 회원별 계정 상태 변경 이력 */
export interface AdminMemberStatusHistory {
  historyId: number;
  action: AdminMemberStatusHistoryAction;
  actorMemberId: number | null;
  reason: string | null;
  beforeValue: string | null;
  afterValue: string | null;
  changedAt: string;
}

export interface AuthTokens {
  accessToken: string;
}

/** 로그인 응답. member 를 함께 내려주지 않으면 화면이 /members/me 로 한 번 더 조회합니다. */
export interface LoginResponse extends AuthTokens {
  member?: Member;
}

export interface SignupRequest {
  email: string;
  password: string;
  passwordConfirm: string;
  nickname: string;
  grant: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export type VerificationPurpose = 'SIGNUP' | 'PASSWORD_RESET';

export interface VerificationIssueResponse {
  challengeId: number;
  expiresAt: string;
  resendAvailableAt: string;
}

export interface VerificationCheckResponse {
  grant: string;
  expiresAt: string;
}

export interface PasswordResetRequest {
  email: string;
  grant: string;
  password: string;
  passwordConfirm: string;
}

/* ------------------------------------------------------------------ 크레딧 충전 */

export type ChargeOrderStatus = 'PENDING' | 'CONFIRMING' | 'PAID' | 'FAILED';

export interface ChargeOrder {
  orderId: string;
  amount: number;
  status: ChargeOrderStatus;
}

/** 내 충전(PG 결제) 내역 한 줄. updatedAt 은 승인·실패 등 마지막 상태 변경 시각이다. */
export interface ChargeOrderHistoryItem extends ChargeOrder {
  /** 토스가 알려 준 결제수단(예: 카드, 간편결제). 이전 주문·복구 승인 주문은 null. */
  paymentMethod: string | null;
  /** 간편결제 제공사(예: 토스페이). 간편결제가 아니면 null. */
  paymentProvider: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 내 관리자 지급(ADMIN_GRANT) 내역 한 줄. */
export interface CreditGrantHistoryItem {
  transactionId: number;
  amount: number;
  reason: string | null;
  createdAt: string;
}

export interface ConfirmChargeOrderRequest {
  paymentKey: string;
  orderId: string;
  amount: number;
}

/* ------------------------------------------------------------------ 공간 */

export type SpaceStatus = 'ACTIVE' | 'INACTIVE';

export interface Space {
  id: number;
  name: string;
  location: string;
  description: string | null;
  capacity: number;
  /** 30분당 정액 요금(100원 단위). 시간당 요금 표현은 쓰지 않습니다. */
  pricePerSlot: number;
  imagePath: string | null;
  openingTime: string;
  closingTime: string;
  status: SpaceStatus;
  /** 낙관적 잠금용. HOLD 응답의 spaceVersion 과 비교합니다. */
  version: number;
  /** 목록 조회에서 date 를 넘겼을 때만 내려옵니다. */
  availability?: SlotAvailability[];
  updatedAt?: string;
  updatedByNickname?: string;
}

/** 공간 등록·수정 폼의 값. 숫자 입력은 제출 직전에 Number 로 정규화합니다. */
export interface SpaceFormValues {
  name: string;
  location: string;
  description: string | null;
  capacity: number | string;
  pricePerSlot: number | string;
  imagePath: string | null;
  openingTime: string;
  closingTime: string;
  status: SpaceStatus;
}

/* ------------------------------------------------------------------ 슬롯 */

export interface SlotAvailability {
  startTime: string;
  endTime?: string;
  available: boolean;
}

/** 서버 가용성 + 화면 판단(지난 시간 여부)을 합친 값 */
export interface DecoratedSlot extends SlotAvailability {
  past: boolean;
  selectable: boolean;
  reason: string | null;
}

export interface SpaceSlotsResponse {
  spaceId: number;
  date: string;
  slots: SlotAvailability[];
}

/* ------------------------------------------------------------------ 예약 */

/** 예약 상태 7종 (core-domain-decisions.md 3-2) */
export type ReservationStatus =
  | 'HELD'
  | 'EXPIRED'
  | 'CONFIRMED'
  | 'IN_USE'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_SHOW';

export interface StatusHistory {
  id?: number;
  fromStatus: ReservationStatus | null;
  toStatus: ReservationStatus;
  /** 없으면 스케줄러가 처리한 것입니다. */
  actorNickname?: string | null;
  reason: string | null;
  changedAt: string;
  createdAt?: string;
}

export interface ReservationSummary {
  holdExpiresAt?: string | null;
  reservationId: number;
  spaceId: number;
  spaceName: string;
  spaceLocation?: string;
  spaceImagePath: string | null;
  date: string;
  startTime: string;
  endTime: string;
  status: ReservationStatus;
  totalAmount: number;
}

export interface Reservation extends ReservationSummary {
  spaceType?: string | null;
  partySize?: number | null;
  slotCount?: number;
  /** 확정 당시 요금. 이후 공간 요금이 바뀌어도 이 값은 유지됩니다. */
  pricePerSlotSnapshot: number;
  termsVersion: string;
  /** HELD 상태에서만 의미가 있습니다. */
  holdExpiresAt: string | null;
  checkedInAt: string | null;
  checkedOutAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
  statusHistories: StatusHistory[];
  accessKey: { active: boolean; issuedAt: string | null } | null;
}

export interface AdminReservation extends Reservation {
  memberNickname: string;
  memberEmail: string;
  accessLogs: DoorAccessLog[];
}

/** POST /reservations 의 응답. spaceVersion 은 이 응답에만 있습니다(api-spec.md 5-1). */
export interface HoldReservation {
  reservationId: number;
  spaceVersion: number;
  totalAmount: number;
  holdExpiresAt: string;
}

export interface CreateReservationRequest {
  spaceId: number;
  date: string;
  startTime: string;
  endTime: string;
  termsVersion: string;
}

/* ------------------------------------------------------------------ 출입 */

export type AccessResult = 'ALLOW' | 'DENY';

export type AccessReasonCode =
    | 'ALLOWED'
    | 'TOKEN_NOT_FOUND'
    | 'TOKEN_REVOKED'
    | 'RESERVATION_NOT_ACTIVE'
    | 'OUTSIDE_ALLOWED_TIME'
    | 'SPACE_MISMATCH'
    | 'MEMBER_MISMATCH';

/** 토큰 원문은 발급 응답에만 있습니다. 서버에는 해시만 저장되어 재조회할 수 없습니다. */
export interface IssuedDoorToken {
  reservationId: number;
  token: string;
  issuedAt: string;
}

export interface AccessVerifyResult {
  result: AccessResult;
  reasonCode: AccessReasonCode | null;
  spaceName: string | null;
  firstCheckIn: boolean | null;
  attemptedAt: string;
}

export interface DoorAccessLog {
  accessLogId: number;
  attemptedAt: string;
  requestedSpaceName: string | null;
  result: AccessResult;
  reasonCode: AccessReasonCode | string | null;
}

/* ------------------------------------------------------------------ 예약 대기 */

/** 서버 WaitlistStatus. /waitlist/me 는 WAITING 만 돌려준다. */
export type WaitlistStatus = 'WAITING' | 'CANCELLED' | 'EXPIRED';

/** 대기 1건. startTime·endTime 은 서버 LocalDateTime('2026-10-15T22:30:00'). */
export interface WaitlistEntry {
  id: number;
  spaceId: number;
  startTime: string;
  endTime: string;
  status: WaitlistStatus;
  /** 같은 공간에서 구간이 겹치는 먼저 신청한 건수 + 1. 안내용이며 자리를 보장하지 않는다. */
  position: number;
  createdAt: string;
}

export interface ApplyWaitlistRequest {
  spaceId: number;
  /** 'YYYY-MM-DD' */
  date: string;
  /** 'HH:mm' */
  startTime: string;
  endTime: string;
}

/** 내 알림. createdAt은 서버 LocalDateTime 문자열이다. */
export type NotificationType =
  | 'WAITLIST_SLOT_RELEASED'
  | 'RESERVATION_FORCE_CANCELLED'
  | 'CREDIT_GRANTED'
  | 'INQUIRY_ANSWERED'
  | 'INQUIRY_CREATED';
export interface Notification {
  id: number;
  /** 대기자 알림에만 있다. 그 밖의 타입은 null. */
  waitlistEntryId: number | null;
  type: NotificationType;
  /** 타입별 대상 id: 대기 항목·예약·회원(크레딧 지급)·문의. */
  referenceId: number;
  message: string;
  read: boolean;
  createdAt: string;
}
export interface UnreadCountResponse { unreadCount: number }
export interface ReadAllResponse { updatedCount: number }
