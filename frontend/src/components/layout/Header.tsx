'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useRef } from 'react';
import { ROUTES, visibleUserNav } from '../../constants/routePaths';
import { useAuth } from '../../hooks/useAuth';
import { formatCredit } from '../../utils/price';
import BrandMark from '../brand/BrandMark';
import NotificationLink from '../notification/NotificationLink';
import ActiveLink from './ActiveLink';

export default function Header() {
  const { member, isAdmin, logout } = useAuth();
  const pathname = usePathname();
  const isHome = pathname === '/';
  const hideMemberMenus = isAdmin || pathname === '/admin' || pathname.startsWith('/admin/');
  const accountRef = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    const menu = accountRef.current;
    if (!menu) return;
    menu.open = false;
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !menu.contains(event.target)) menu.open = false;
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && menu.open) {
        menu.open = false;
        menu.querySelector('summary')?.focus();
      }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [pathname, member?.memberId]);
  const headerRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const header = headerRef.current;
    const hero = document.querySelector<HTMLElement>('[data-home-hero]');
    if (!isHome || !header || !hero) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      // Keep navigation over the hero until its lower edge pushes it offscreen.
      const height = header.offsetHeight;
      const offset = Math.min(height, Math.max(0, height - hero.getBoundingClientRect().bottom));
      header.style.setProperty('--home-header-offset', `${-offset}px`);
      header.inert = offset >= height;
      if (header.inert) header.querySelector('details')?.removeAttribute('open');
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    const observer = new ResizeObserver(schedule);
    observer.observe(header);
    observer.observe(hero);
    update();
    window.addEventListener('scroll', schedule, { passive: true });
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      window.removeEventListener('scroll', schedule);
      header.style.removeProperty('--home-header-offset');
      header.inert = false;
    };
  }, [isHome]);

  return (
    <header ref={headerRef} className="header">
      <Link href={ROUTES.home} className="brand">
        <BrandMark className="brandmark" size={40} />
        <span>Slot <em>Key</em></span>
      </Link>

      <nav className="nav" aria-label="주요 메뉴">
        {visibleUserNav(hideMemberMenus).map((item) => (
          <ActiveLink key={item.href} href={item.href}>{item.label}</ActiveLink>
        ))}
      </nav>

      <div className="header-actions">
        {member ? !isAdmin && (
          <Link href={ROUTES.creditCharge} className="header-credit" title="크레딧은 1크레딧 = 1원, 예약 결제에만 사용됩니다."
            aria-label={`${formatCredit(member.balance)}, 충전하기`}
            aria-current={pathname === ROUTES.creditCharge ? 'page' : undefined}>
            {formatCredit(member.balance)}
          </Link>
        ) : <>
          <Link href={ROUTES.login} className="btn small">로그인</Link>
          <Link href={ROUTES.signup} className="btn small primary">회원가입</Link>
        </>}
      <details ref={accountRef} className={`home-account-menu${member ? '' : ' guest-account-menu'}`}
        onBlur={(event) => {
          if (event.relatedTarget instanceof Node && !event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
        }}>
        <summary aria-label={member ? `${member.nickname} 계정 메뉴` : '로그인 메뉴'}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
            <circle cx="12" cy="8" r="3.5" /><path d="M4.5 21v-2a7.5 7.5 0 0 1 15 0v2" />
          </svg>
          <span className="account-trigger-label" title={member?.nickname}>{member ? member.nickname : '로그인'}</span>
          <svg className="account-chevron" width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="m3 4.5 3 3 3-3" /></svg>
        </summary>
        <div className="home-account-panel" onClick={(event) => {
          if (event.target instanceof Element && event.target.closest('a, button') && accountRef.current) accountRef.current.open = false;
        }}>
          {member ? <>
            <div className="account-identity">
              <strong>{member.nickname}님</strong>
              {!isAdmin && <span className="account-mobile-credit">{formatCredit(member.balance)}</span>}
            </div>
            <ActiveLink href={ROUTES.myPage}>마이페이지</ActiveLink>
            {!isAdmin && <ActiveLink href={ROUTES.waitlist}>빈자리 알림</ActiveLink>}
            {!isAdmin && <ActiveLink href={ROUTES.creditCharge}>크레딧 충전하기</ActiveLink>}
            {!isAdmin && <ActiveLink href={ROUTES.creditHistory}>충전 내역</ActiveLink>}
            {!isAdmin && <ActiveLink href={ROUTES.inquiries}>내 문의</ActiveLink>}
            {isAdmin && <ActiveLink href={ROUTES.adminDashboard}>관리자</ActiveLink>}
            <div className="account-signout"><button onClick={logout}>로그아웃</button></div>
          </> : <>
            <Link href={ROUTES.login}>로그인</Link>
            <Link href={ROUTES.signup}>회원가입</Link>
          </>}
        </div>
      </details>
        <NotificationLink memberId={member?.memberId} pathname={pathname} />
      </div>
    </header>
  );
}
