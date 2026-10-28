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

test('USER_NAV 는 오피스 찾기 → 인기 오피스 → 예약 내역 → 출입하기 순서이고 빈자리 알림은 계정 메뉴로 옮겼다', () => {
  const { USER_NAV, ROUTES } = load('constants/routePaths.ts');
  assert.deepEqual(USER_NAV.map((item) => [item.href, item.label]), [
    [ROUTES.spaces, '오피스 찾기'],
    [ROUTES.ranking, '인기 오피스'],
    [ROUTES.reservations, '예약 내역'],
    [ROUTES.door, '출입하기'],
  ]);
  assert.equal(ROUTES.creditHistory, '/credits/history');
  assert.ok(!USER_NAV.some((item) => item.href === ROUTES.waitlist));
});

test('비로그인 상단 메뉴는 로그인 후와 같고, 회원 정보가 필요한 메뉴만 requiresAuth 로 표시한다', () => {
  const { USER_NAV, ROUTES, visibleUserNav } = load('constants/routePaths.ts');
  assert.deepEqual(visibleUserNav(false), USER_NAV);
  assert.deepEqual(USER_NAV.filter((item) => item.requiresAuth).map((item) => item.href), [
    ROUTES.reservations, ROUTES.door,
  ]);
});

test('관리자 화면에서는 회원 전용 메뉴(인기 오피스·예약 내역)를 숨긴다', () => {
  const { ROUTES, visibleUserNav } = load('constants/routePaths.ts');
  assert.deepEqual(visibleUserNav(true).map((item) => item.href), [ROUTES.spaces, ROUTES.door]);
});

test('getChargeHistory 는 충전 주문 목록 경로에 page/size 쿼리로 GET 한다', async () => {
  const calls = [];
  const api = { get: async (url, options) => { calls.push([url, options]); return { content: [] }; } };
  const { getChargeHistory } = load('api/creditApi.ts', { './client': { __esModule: true, default: api } });

  await getChargeHistory({ page: 2, size: 5 });
  await getChargeHistory();

  assert.deepEqual(calls, [
    ['/credits/charge-orders', { query: { page: 2, size: 5 } }],
    ['/credits/charge-orders', { query: { page: 0, size: 10 } }],
  ]);
});

test('cancelReservation 은 예약 취소 경로로 POST 한다 (결제 대기 취소도 같은 API)', async () => {
  const calls = [];
  const api = { post: async (url) => { calls.push(url); }, get: async () => ({}) };
  const { cancelReservation } = load('api/reservationApi.ts', {
    './client': { __esModule: true, default: api },
    './spaceApi': { getSpace: async () => ({}) },
  });

  await cancelReservation(16);

  assert.deepEqual(calls, ['/reservations/16/cancel']);
});

test('getPaymentMethodView 는 결제수단별 아이콘과 간편결제 제공사를 문구로 만든다', () => {
  const { getPaymentMethodView } = load('utils/paymentMethod.ts');

  assert.deepEqual(getPaymentMethodView('카드'), { icon: '💳', label: '카드' });
  assert.deepEqual(getPaymentMethodView('간편결제', '토스페이'), { icon: '📱', label: '간편결제 · 토스페이' });
  assert.deepEqual(getPaymentMethodView('계좌이체'), { icon: '🏦', label: '계좌이체' });
  assert.deepEqual(getPaymentMethodView('가상계좌'), { icon: '🧾', label: '가상계좌' });
  assert.deepEqual(getPaymentMethodView('새로운수단'), { icon: '💰', label: '새로운수단' });
  assert.equal(getPaymentMethodView(null), null);
  assert.equal(getPaymentMethodView('  '), null);
});
