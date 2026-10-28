/* eslint-disable @typescript-eslint/no-require-imports -- Node test harness loads actual TS modules in CommonJS isolation. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '../src');

function load(relative, mocks = {}) {
  const file = path.resolve(root, relative);
  const source = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      jsx: ts.JsxEmit.ReactJSX,
      esModuleInterop: true,
    },
  }).outputText;
  const loadedModule = { exports: {} };
  const localRequire = (name) => {
    if (Object.hasOwn(mocks, name)) return mocks[name];
    if (name.endsWith('.css')) return new Proxy({}, { get: (_, key) => key === '__esModule' ? false : String(key) });
    if (!name.startsWith('.')) return require(name);
    const target = path.resolve(path.dirname(file), name);
    const resolved = ['', '.ts', '.tsx'].map((ext) => target + ext).find((p) => fs.existsSync(p));
    return load(path.relative(root, resolved), mocks);
  };
  vm.runInThisContext(`(function(require,module,exports){${source}\n})`, { filename: file })(
    localRequire,
    loadedModule,
    loadedModule.exports,
  );
  return loadedModule.exports;
}


test('알림 API 경로·파라미터와 응답을 보존한다', async () => {
  const calls = [];
  const page = {content: [{id: 7, waitlistEntryId: 3, message: '빈자리', read: false, createdAt: '2026-10-16T12:00:00'}], page: 2, size: 10, totalPages: 3, totalElements: 21};
  const api = { get: async (...args) => {calls.push(['get', ...args]); return args[0].endsWith('unread-count') ? {unreadCount: 4} : page;}, post: async (...args) => {calls.push(['post', ...args]); return {updatedCount: 4};} };
  const mod = load('api/notificationApi.ts', {'./client': {__esModule: true, default: api}});
  assert.deepEqual(await mod.getNotifications({page: 2, size: 10}), page);
  assert.deepEqual(await mod.getUnreadCount(), {unreadCount: 4});
  assert.deepEqual(await mod.readAllNotifications(7), {updatedCount: 4});
  await mod.getNotifications();
  assert.deepEqual(calls, [['get', '/notifications', {query: {page: 2, size: 10}}], ['get', '/notifications/unread-count'], ['post', '/notifications/read-all', undefined, {query: {upToId: 7}}], ['get', '/notifications', {query: {page: 0, size: 20}}]]);
});

test('배지는 0이면 숨기고 비로그인은 요청하지 않는다', async () => {
  const {renderToStaticMarkup} = require('react-dom/server');
  let calls = 0;
  const effects = [];
  const React = require('react');
  const mod = load('components/notification/NotificationLink.tsx', {
    react: {...React, useState: () => [null, () => {}], useEffect: (fn) => effects.push(fn)},
    '../../api/notificationApi': {getUnreadCount: async () => {calls++; return {unreadCount: 1};}},
    'next/link': {__esModule: true, default: ({href, children, ...props}) => React.createElement('a', {href, ...props}, children)},
  });
  assert.equal(renderToStaticMarkup(mod.default({memberId: undefined, pathname: '/'})), '');
  effects.forEach(fn => fn());
  await Promise.resolve();
  assert.equal(calls, 0);
  const badge = load('components/notification/UnreadBadge.tsx').default;
  assert.equal(renderToStaticMarkup(React.createElement(badge, {count: 0})), '');
  assert.match(renderToStaticMarkup(React.createElement(badge, {count: 3})), /3/);
});


test('상단 알림 진입점은 글자 대신 종 아이콘이고 접근성 이름은 알림으로 남긴다', () => {
  const {renderToStaticMarkup} = require('react-dom/server');
  const React = require('react');
  const mod = load('components/notification/NotificationLink.tsx', {
    react: {...React, useState: () => [{memberId: 1, count: 3}, () => {}], useEffect: () => {}},
    '../../api/notificationApi': {getUnreadCount: async () => ({unreadCount: 3})},
    'next/link': {__esModule: true, default: ({href, children, ...props}) => React.createElement('a', {href, ...props}, children)},
  });
  const html = renderToStaticMarkup(mod.default({memberId: 1, pathname: '/'}));
  assert.match(html, /<svg/);
  assert.match(html, /aria-label="알림, 안 읽은 알림 3개"/);
  assert.doesNotMatch(html.replace(/aria-label="[^"]*"/g, '').replace(/title="[^"]*"/g, ''), /알림/);
  const quiet = load('components/notification/NotificationLink.tsx', {
    react: {...React, useState: () => [null, () => {}], useEffect: () => {}},
    '../../api/notificationApi': {getUnreadCount: async () => ({unreadCount: 0})},
    'next/link': {__esModule: true, default: ({href, children, ...props}) => React.createElement('a', {href, ...props}, children)},
  });
  assert.match(renderToStaticMarkup(quiet.default({memberId: 1, pathname: '/'})), /aria-label="알림"/);
});

test('알림 페이지에서는 알림 링크에 aria-current 와 활성 표시를 준다', () => {
  const {renderToStaticMarkup} = require('react-dom/server');
  const React = require('react');
  const mod = load('components/notification/NotificationLink.tsx', {
    react: {...React, useState: () => [null, () => {}], useEffect: () => {}},
    '../../api/notificationApi': {getUnreadCount: async () => ({unreadCount: 0})},
    'next/link': {__esModule: true, default: ({href, children, ...props}) => React.createElement('a', {href, ...props}, children)},
  });
  const here = renderToStaticMarkup(mod.default({memberId: 1, pathname: '/notifications'}));
  assert.match(here, /aria-current="page"/);
  assert.match(here, /class="entry active"/);
  const elsewhere = renderToStaticMarkup(mod.default({memberId: 1, pathname: '/spaces'}));
  assert.doesNotMatch(elsewhere, /aria-current/);
  assert.doesNotMatch(elsewhere, /active/);
});

function renderHeader({member, isAdmin = false, pathname = '/spaces'}) {
  const {renderToStaticMarkup} = require('react-dom/server');
  const React = require('react');
  const mod = load('components/layout/Header.tsx', {
    'next/link': {__esModule: true, default: ({href, children, ...props}) => React.createElement('a', {href, ...props}, children)},
    'next/navigation': {usePathname: () => pathname},
    '../../hooks/useAuth': {useAuth: () => ({member, isAdmin, logout() {}})},
    '../brand/BrandMark': {__esModule: true, default: () => null},
    '../notification/NotificationLink': {__esModule: true, default: () => React.createElement('a', {href: '/notifications'}, 'bell')},
  });
  return renderToStaticMarkup(React.createElement(mod.default));
}

test('상단 오른쪽 그룹은 크레딧 → 계정 메뉴 → 알림 순이고 크레딧은 충전 페이지로 연결된다', () => {
  const html = renderHeader({member: {memberId: 1, nickname: '테스터', balance: 12000}});
  const credit = html.indexOf('href="/credits/charge" class="header-credit"');
  const account = html.indexOf('home-account-menu');
  const bell = html.indexOf('href="/notifications"');
  assert.ok(credit > 0 && account > credit && bell > account, `order credit=${credit} account=${account} bell=${bell}`);
  assert.match(html, /12,000 크레딧/);
  assert.match(html, /aria-label="12,000 크레딧, 충전하기"/);
});

test('크레딧 충전 페이지에서는 크레딧 링크에 aria-current 를 주고 관리자에게는 크레딧을 보이지 않는다', () => {
  const member = {memberId: 1, nickname: '테스터', balance: 500};
  assert.match(renderHeader({member, pathname: '/credits/charge'}), /class="header-credit"[^>]*aria-current="page"|aria-current="page"[^>]*class="header-credit"/);
  assert.doesNotMatch(renderHeader({member, pathname: '/spaces'}), /header-credit"[^>]*aria-current/);
  assert.doesNotMatch(renderHeader({member, isAdmin: true}), /header-credit/);
});

test('목록 렌더 후 최대 id를 읽음 상한으로 보내고 빈 목록은 요청하지 않는다', async () => {
  const React = require('react');
  const {renderToStaticMarkup} = require('react-dom/server');
  for (const ids of [[7, 9, 3], []]) {
    const effects = [];
    const calls = [];
    const data = {content: ids.map(id => ({id, waitlistEntryId: 1, message: '빈자리', read: false, createdAt: '2026-10-16T12:00:00'})), page: 0, size: 20, totalPages: ids.length ? 1 : 0, totalElements: ids.length};
    const mod = load('views/notifications/NotificationsPage.tsx', {
      react: {...React, useState: () => [0, () => {}], useRef: () => ({current: null}), useEffect: fn => effects.push(fn)},
      '../../api/notificationApi': {getNotifications: async () => data, readAllNotifications: async () => ({updatedCount: 0}), getUnreadCount: async () => ({unreadCount: 0})},
      '../../hooks/useApi': {
        useAsync: () => ({data, loading: false, error: null, run: async () => data}),
        useAction: () => ({execute: async (...args) => {calls.push(args);}, error: null}),
      },
      'next/link': {__esModule: true, default: ({href, children, ...props}) => React.createElement('a', {href, ...props}, children)},
    });
    const html = renderToStaticMarkup(React.createElement(mod.default));
    assert.deepEqual(calls, []);
    effects.forEach(fn => fn());
    await Promise.resolve();
    assert.deepEqual(calls, ids.length ? [[9]] : []);
    if (ids.length) assert.match(html, /안 읽음/);
  }
});

test('알림 타입별로 이동할 화면과 버튼 문구를 정한다', () => {
  const {notificationTarget} = load('utils/notificationTarget.ts');
  assert.deepEqual(notificationTarget({type: 'WAITLIST_SLOT_RELEASED', referenceId: 3}), {href: '/waitlist', label: '빈자리 알림 보기'});
  assert.deepEqual(notificationTarget({type: 'RESERVATION_FORCE_CANCELLED', referenceId: 12}), {href: '/reservations/12', label: '예약 보기'});
  assert.deepEqual(notificationTarget({type: 'CREDIT_GRANTED', referenceId: 5}), {href: '/credits/history', label: '충전 내역 보기'});
  assert.deepEqual(notificationTarget({type: 'INQUIRY_ANSWERED', referenceId: 8}), {href: '/inquiries/detail?id=8', label: '답변 보기'});
  assert.deepEqual(notificationTarget({type: 'INQUIRY_CREATED', referenceId: 9}), {href: '/admin/inquiries/detail?id=9', label: '문의 보기'});
});

test('모르는 알림 타입은 링크 없이 문구만 보여 준다', () => {
  const {notificationTarget} = load('utils/notificationTarget.ts');
  assert.equal(notificationTarget({type: 'SOMETHING_NEW', referenceId: 1}), null);
  assert.equal(notificationTarget({referenceId: 1}), null);
});

test('알림 목록은 타입별 링크를 그리고 waitlistEntryId가 null이어도 렌더한다', () => {
  const React = require('react');
  const {renderToStaticMarkup} = require('react-dom/server');
  const data = {content: [
    {id: 3, type: 'INQUIRY_CREATED', waitlistEntryId: null, referenceId: 9, message: '새 문의가 등록되었습니다', read: false, createdAt: '2026-10-28T12:00:00'},
    {id: 2, type: 'CREDIT_GRANTED', waitlistEntryId: null, referenceId: 5, message: '크레딧 지급', read: true, createdAt: '2026-10-28T11:00:00'},
    {id: 1, type: 'WAITLIST_SLOT_RELEASED', waitlistEntryId: 4, referenceId: 4, message: '빈자리', read: true, createdAt: '2026-10-28T10:00:00'},
  ], page: 0, size: 20, totalPages: 1, totalElements: 3};
  const mod = load('views/notifications/NotificationsPage.tsx', {
    react: {...React, useState: () => [0, () => {}], useRef: () => ({current: null}), useEffect: () => {}},
    '../../api/notificationApi': {getNotifications: async () => data, readAllNotifications: async () => ({updatedCount: 0}), getUnreadCount: async () => ({unreadCount: 0})},
    '../../hooks/useApi': {
      useAsync: () => ({data, loading: false, error: null, run: async () => data}),
      useAction: () => ({execute: async () => {}, error: null}),
    },
    'next/link': {__esModule: true, default: ({href, children, ...props}) => React.createElement('a', {href, ...props}, children)},
  });
  const html = renderToStaticMarkup(React.createElement(mod.default));
  assert.match(html, /href="\/admin\/inquiries\/detail\?id=9"[^>]*>문의 보기/);
  assert.match(html, /href="\/credits\/history"[^>]*>충전 내역 보기/);
  assert.match(html, /href="\/waitlist"[^>]*>빈자리 알림 보기/);
  assert.doesNotMatch(html, /빈자리 알림을 확인하세요/);
});
