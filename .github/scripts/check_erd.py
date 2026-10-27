#!/usr/bin/env python3
"""erd.md 와 Flyway 마이그레이션이 만든 최종 스키마의 정합성을 검사한다.

사용법: python3 check_erd.py <repo_root>

Flyway SQL(V*.sql)을 번호 순서로 읽어 최종 테이블·컬럼·UNIQUE·FK 를 만들고 erd.md 와 비교한다.
실패(exit 1)로 보는 불일치:
  1. 스키마에 있는데 엔터티 표에 없는 테이블, 또는 반대
  2. 엔터티 표 "주요 필드"에 없는 컬럼 / 스키마에 없는 컬럼
  3. 엔터티 설명의 UNIQUE(...) 가 스키마에 없거나, 스키마의 UNIQUE 가 설명에 없는 경우
  4. "관계" 블록의 DB FK 표기와 실제 FK 가 다른 경우
검사하지 않는 것: 인덱스 설명(다른 테이블 인덱스를 함께 적는 행이 있다), CHECK, 컬럼 타입.
"""
import re
import sys
from pathlib import Path

SPECIAL_NAMES = {'Space': 'spaces', 'AuditLog': 'audit_logs', 'Inquiries': 'inquiries'}


def strip_comments(sql):
    sql = re.sub(r'/\*.*?\*/', '', sql, flags=re.S)
    lines = []
    for line in sql.split('\n'):
        if line.lstrip().startswith(('#', '--')):
            continue
        lines.append(re.sub(r'\s--\s.*$', '', line))
    return '\n'.join(lines)


def split_top(body):
    parts, depth, cur = [], 0, ''
    for ch in body:
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


def cols_of(text):
    return [c.strip().strip('`') for c in text.split(',')]


class Table:
    def __init__(self):
        self.cols = {}
        self.uniques = []
        self.fks = []  # (컬럼 목록, 참조 테이블)
        self.indexes = []


def parse_item(table, item):
    m = re.match(r'(?:CONSTRAINT\s+`?\w+`?\s+)?PRIMARY KEY\s*\(', item, re.I)
    if m:
        return
    m = re.match(r'(?:CONSTRAINT\s+`?\w+`?\s+)?UNIQUE(?:\s+(?:KEY|INDEX))?\s*(?:`?\w+`?)?\s*\(([^)]*)\)', item, re.I)
    if m:
        table.uniques.append(cols_of(m.group(1)))
        return
    m = re.match(r'(?:CONSTRAINT\s+`?\w+`?\s+)?FOREIGN KEY\s*\(([^)]*)\)\s*REFERENCES\s+`?(\w+)`?', item, re.I)
    if m:
        table.fks.append((cols_of(m.group(1)), m.group(2)))
        return
    if re.match(r'(?:CONSTRAINT\s+`?\w+`?\s+)?CHECK\s*\(', item, re.I):
        return
    m = re.match(r'(?:KEY|INDEX)\s+`?\w+`?\s*\(([^)]*)\)', item, re.I)
    if m:
        table.indexes.append(cols_of(m.group(1)))
        return
    m = re.match(r'`?(\w+)`?\s+(\w+(?:\([^)]*\))?)(.*)$', item, re.S)
    if m:
        name, rest = m.group(1), m.group(3)
        table.cols[name] = m.group(2).upper()
        if re.search(r'\bUNIQUE\b', rest, re.I):
            table.uniques.append([name])
        ref = re.search(r'REFERENCES\s+`?(\w+)`?', rest, re.I)
        if ref:
            table.fks.append(([name], ref.group(1)))


def load_schema(root):
    migration_dir = Path(root) / 'backend/src/main/resources/db/migration'
    files = sorted(migration_dir.glob('V*.sql'), key=lambda p: int(re.match(r'V(\d+)', p.name).group(1)))
    tables, renames = {}, {}
    for f in files:
        for stmt in (s.strip() for s in strip_comments(f.read_text()).split(';')):
            if not stmt:
                continue
            m = re.match(r'CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?(\w+)`?\s*\((.*)\)[^)]*$', stmt, re.I | re.S)
            if m:
                tables[m.group(1)] = Table()
                for item in split_top(m.group(2)):
                    parse_item(tables[m.group(1)], item)
                continue
            m = re.match(r'RENAME TABLE\s+`?(\w+)`?\s+TO\s+`?(\w+)`?', stmt, re.I)
            if m:
                tables[m.group(2)] = tables.pop(m.group(1))
                renames[m.group(1)] = m.group(2)
                continue
            m = re.match(r'CREATE\s+(UNIQUE\s+)?INDEX\s+`?\w+`?\s+ON\s+`?(\w+)`?\s*\(([^)]*)\)', stmt, re.I)
            if m:
                cols = [re.sub(r'\s+(ASC|DESC)$', '', c, flags=re.I) for c in cols_of(m.group(3))]
                (tables[m.group(2)].uniques if m.group(1) else tables[m.group(2)].indexes).append(cols)
                continue
            m = re.match(r'ALTER TABLE\s+`?(\w+)`?\s+(.*)$', stmt, re.I | re.S)
            if m:
                table = tables[m.group(1)]
                for item in split_top(m.group(2)):
                    drop = re.match(r'DROP\s+COLUMN\s+`?(\w+)`?', item, re.I)
                    if drop:
                        table.cols.pop(drop.group(1), None)
                        continue
                    add = re.match(r'(?:ADD|MODIFY)\s+(?:COLUMN\s+)?(.*)$', item, re.I | re.S)
                    if add:
                        parse_item(table, add.group(1))
    # FK 가 RENAME 전 이름(space)을 가리키는 경우 최종 이름으로 맞춘다
    for table in tables.values():
        table.fks = [(cols, renames.get(ref, ref)) for cols, ref in table.fks]
    return tables


def snake(name):
    return SPECIAL_NAMES.get(name) or re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower()


def entity_rows(erd):
    rows = {}
    for line in erd.split('\n'):
        if not line.startswith('| `'):
            continue
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        name = re.match(r'`(\w+)`', cells[0])
        if name and len(cells) >= 4:
            rows[name.group(1)] = cells
    return rows


def erd_columns(field_cell):
    text = re.sub(r'\([^)]*\)', '', field_cell).replace('**', '').replace('~~', '')
    cols = [c.strip().strip('`') for c in text.split(',')]
    return [c for c in cols if re.fullmatch(r'\w+', c)]


def check(root):
    root = Path(root)
    tables = load_schema(root)
    erd = (root / 'docs/architecture/erd.md').read_text()
    rows = entity_rows(erd)
    failures = []

    for name in sorted(set(tables) - set(rows)):
        failures.append(f'테이블이 erd 엔터티 표에 없음: {name}')
    for name in sorted(set(rows) - set(tables)):
        failures.append(f'erd 에만 있는 테이블: {name}')

    for name, cells in sorted(rows.items()):
        if name not in tables:
            continue
        table, text = tables[name], ' '.join(cells)
        listed = erd_columns(cells[2])
        for col in table.cols:
            if col not in listed:
                failures.append(f'[{name}] 컬럼이 erd 주요 필드에 없음: {col}')
        for col in listed:
            if col not in table.cols:
                failures.append(f'[{name}] erd 에만 있는 컬럼: {col}')
        for m in re.finditer(r'UNIQUE\s*\(([^)]*)\)', text):
            cols = cols_of(m.group(1))
            if cols not in table.uniques:
                failures.append(f'[{name}] erd 의 UNIQUE{cols} 가 스키마에 없음 (스키마: {table.uniques})')
        for cols in table.uniques:
            if 'UNIQUE' not in text or not all(c in text for c in cols):
                failures.append(f'[{name}] 스키마의 UNIQUE{cols} 가 erd 에 설명되지 않음')

    failures += check_relations(erd, tables)
    return failures, len(tables)


def check_relations(erd, tables):
    block = re.search(r'## 관계\s*```\n(.*?)```', erd, re.S)
    if not block:
        return ['erd 에 "관계" 블록이 없음']
    actual = {(child, tuple(cols), ref) for child, t in tables.items() for cols, ref in t.fks}
    claimed, failures = set(), []
    for line in block.group(1).split('\n'):
        m = re.match(r'\s*(\w+)(?:\.\w+)?\s+(?:0\.\.1|1)\s+---\s+(?:0\.\.[12N]|N)\s+(\w+)(?:\.\w+)?', line)
        if not m:
            continue
        parent, child = snake(m.group(1)), snake(m.group(2))
        if parent not in tables or child not in tables:
            failures.append(f'관계 블록의 알 수 없는 엔터티: {line.strip()[:70]}')
            continue
        fk = re.search(r'DB FK[^:]*:\s*(\w+)', line)
        if fk:
            key = (child, (fk.group(1),), parent)
            claimed.add(key)
            if key not in actual:
                failures.append(f'관계 블록의 FK 가 스키마에 없음: {child}.{fk.group(1)} -> {parent}')
        elif '논리적' in line or 'FK 없음' in line:
            for c, cols, ref in actual:
                if c == child and ref == parent:
                    failures.append(f'관계 블록은 FK 없음이라는데 스키마에 FK 가 있음: {c}{list(cols)} -> {ref}')
    for child, cols, ref in sorted(actual - claimed):
        failures.append(f'스키마의 FK 가 관계 블록에 없음: {child}{list(cols)} -> {ref}')
    return failures


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    failures, n_tables = check(sys.argv[1])
    print(f'테이블 {n_tables}개 대조')
    for f in failures:
        print('FAIL', f)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
