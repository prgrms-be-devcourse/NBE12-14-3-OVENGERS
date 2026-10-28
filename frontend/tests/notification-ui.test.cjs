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
