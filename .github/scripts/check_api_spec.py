#!/usr/bin/env python3
"""api-spec.md 와 코드의 엔드포인트·에러 코드 정합성을 검사한다.

사용법: python3 check_api_spec.py <repo_root>

실패(exit 1)로 보는 불일치:
  1. 컨트롤러에 있는데 api-spec.md 에 없는 엔드포인트
  2. ErrorCode enum 에 있는데 api-spec.md 에 없는 코드 (INTERNAL_ONLY 제외)
  3. 문서의 `CODE`(NNN) 표기가 enum 의 HTTP 상태와 다른 경우
  4. api-spec.md·AGENTS.md 가 상태와 함께 언급한 코드가 enum 에 없는 경우
     (AGENTS.md 는 레포에서 추적되지 않으므로 파일이 있을 때만 검사한다)
문서에만 있는 엔드포인트는 부정 문장·묶음 표기(`GET /stats/**`) 때문에 경고만 한다.
"""
import re
import sys
from pathlib import Path

HTTP_STATUS = {
    'BAD_REQUEST': 400, 'UNAUTHORIZED': 401, 'FORBIDDEN': 403, 'NOT_FOUND': 404,
    'CONFLICT': 409, 'PAYLOAD_TOO_LARGE': 413, 'UNPROCESSABLE_ENTITY': 422,
    'TOO_MANY_REQUESTS': 429, 'INTERNAL_SERVER_ERROR': 500, 'SERVICE_UNAVAILABLE': 503,
}

# 외부 API 응답으로 나가지 않는 서버 내부 불변식 코드. 추가할 때는 이유를 적는다.
INTERNAL_ONLY = {
    'ACCESS_DENY_REASON_REQUIRED',  # DoorAccessLogService.createDenyLog 내부 검사
    'ACCESS_LOG_NOT_FOUND',  # DoorAccessLogService.findById, 컨트롤러에서 호출하지 않음
}

METHODS = ('Get', 'Post', 'Put', 'Patch', 'Delete')


def normalize(path):
    path = path.replace('/api/v1', '')
    path = re.sub(r'\{[^}]*\}', '{}', path)
    return path.rstrip('/') or '/'


def annotation_args(text, start):
    """text[start] 가 '(' 이면 짝이 맞는 ')' 까지의 문자열을 돌려준다."""
    if start >= len(text) or text[start] != '(':
        return ''
    depth = 0
    for i in range(start, len(text)):
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return ''


def first_string(args):
    m = re.search(r'"([^"]*)"', args)
    return m.group(1) if m else ''


def code_endpoints(root):
    eps = set()
    for f in (root / 'backend/src/main').rglob('*'):
        if f.suffix not in ('.kt', '.java'):
            continue
        text = f.read_text()
        if '@RestController' not in text and '@Controller' not in text:
            continue
        base = ''
        m = re.search(r'@RequestMapping', text)
        if m:
            base = first_string(annotation_args(text, m.end()))
        for mm in re.finditer(r'@(%s)Mapping' % '|'.join(METHODS), text):
            sub = first_string(annotation_args(text, mm.end()))
            eps.add((mm.group(1).upper(), normalize(base + sub)))
    return eps


def spec_endpoints(spec):
    eps = set()
    for m in re.finditer(r'`?(GET|POST|PUT|PATCH|DELETE) +(/[A-Za-z0-9_/{}.\-]*)', spec):
        eps.add((m.group(1), normalize(m.group(2))))
    return eps


def enum_codes(root):
    src = (root / 'backend/src/main/kotlin/com/ovengers/slotkey/global/error/ErrorCode.kt').read_text()
    return {
        m.group(1): HTTP_STATUS[m.group(2)]
        for m in re.finditer(r'^\s*([A-Z_]+)\(\s*HttpStatus\.([A-Z_]+)', src, re.M)
    }


def doc_status_pairs(text):
    pairs = {}
    for m in re.finditer(r'`([A-Z][A-Z_]{3,})`\s*\((\d{3})', text):
        pairs.setdefault(m.group(1), set()).add(int(m.group(2)))
    return pairs


def check(root):
    root = Path(root)
    spec = (root / 'docs/api/api-spec.md').read_text()
    agents_file = root / 'AGENTS.md'  # .gitignore 대상이라 CI 체크아웃에는 없을 수 있다
    agents = agents_file.read_text() if agents_file.exists() else ''
    enum = enum_codes(root)
    failures, warnings = [], []

    code_eps, spec_eps = code_endpoints(root), spec_endpoints(spec)
    for method, path in sorted(code_eps - spec_eps):
        failures.append(f'엔드포인트가 문서에 없음: {method} {path}')
    for method, path in sorted(spec_eps - code_eps):
        warnings.append(f'문서에만 있는 엔드포인트(부정 문장·묶음 표기일 수 있음): {method} {path}')

    for code in sorted(enum):
        if code not in spec and code not in INTERNAL_ONLY:
            failures.append(f'에러 코드가 문서에 없음: {code}({enum[code]})')

    for label, text in (('api-spec.md', spec), ('AGENTS.md', agents)):
        for code, statuses in sorted(doc_status_pairs(text).items()):
            if code not in enum:
                failures.append(f'{label}: enum 에 없는 에러 코드 {code}{sorted(statuses)}')
            elif enum[code] not in statuses:
                failures.append(f'{label}: {code} 상태 불일치 문서 {sorted(statuses)} / 코드 {enum[code]}')

    return failures, warnings, len(code_eps), len(enum)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    failures, warnings, n_eps, n_codes = check(sys.argv[1])
    print(f'엔드포인트 {n_eps}개, 에러 코드 {n_codes}개 대조')
    for w in warnings:
        print('WARN', w)
    for f in failures:
        print('FAIL', f)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
