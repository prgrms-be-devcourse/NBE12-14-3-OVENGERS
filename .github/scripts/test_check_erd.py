import tempfile
import unittest
from pathlib import Path

import check_erd as mod

V1 = """CREATE TABLE member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (email)
);
CREATE TABLE space (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_r_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_r_space FOREIGN KEY (space_id) REFERENCES space (id),
    UNIQUE (member_id, space_id)
);
"""
V2 = "RENAME TABLE space TO spaces;\n"
V3 = "ALTER TABLE member\n    ADD COLUMN nickname VARCHAR(50) NULL;\n"

ERD = """# ERD
| 엔터티 | 담당자 | 주요 필드 | 역할 |
| --- | --- | --- | --- |
| `member` | a | id, email, nickname | 회원. `UNIQUE(email)` |
| `spaces` | a | id, name | 공간 |
| `reservation` | a | id, member_id, space_id | 예약. `UNIQUE(member_id, space_id)` |

## 관계

```
Member 1 --- N Reservation (DB FK: member_id)
Space 1 --- N Reservation (DB FK: space_id)
```
"""


def build(root, erd=ERD, v1=V1, v2=V2, v3=V3):
    mig = Path(root) / 'backend/src/main/resources/db/migration'
    mig.mkdir(parents=True)
    for name, text in (('V1__a.sql', v1), ('V2__b.sql', v2), ('V3__c.sql', v3)):
        (mig / name).write_text(text, encoding='utf-8')
    erd_path = Path(root) / 'docs/architecture/erd.md'
    erd_path.parent.mkdir(parents=True)
    erd_path.write_text(erd, encoding='utf-8')


def run(**kw):
    with tempfile.TemporaryDirectory() as d:
        build(d, **kw)
        return mod.check(d)[0]


class CheckErdTest(unittest.TestCase):
    def test_consistent_erd_passes_with_rename_and_alter(self):
        self.assertEqual(run(), [])

    def test_column_added_by_alter_but_missing_in_erd_is_detected(self):
        failures = run(erd=ERD.replace('id, email, nickname', 'id, email'))
        self.assertTrue(any('member' in f and 'nickname' in f for f in failures))

    def test_column_only_in_erd_is_detected(self):
        failures = run(erd=ERD.replace('id, name |', 'id, name, ghost |'))
        self.assertTrue(any('ghost' in f for f in failures))

    def test_dropped_column_must_leave_erd(self):
        failures = run(v3="ALTER TABLE member DROP COLUMN email;\n")
        self.assertTrue(any('email' in f for f in failures))

    def test_table_missing_in_erd_is_detected(self):
        erd = '\n'.join(l for l in ERD.split('\n') if not l.startswith('| `spaces`'))
        failures = run(erd=erd)
        self.assertTrue(any('spaces' in f for f in failures))

    def test_unique_claim_not_in_schema_is_detected(self):
        failures = run(erd=ERD.replace('`UNIQUE(email)`', '`UNIQUE(nickname)`'))
        self.assertTrue(any('UNIQUE' in f and 'nickname' in f for f in failures))

    def test_schema_unique_not_described_is_detected(self):
        failures = run(erd=ERD.replace('. `UNIQUE(member_id, space_id)`', ''))
        self.assertTrue(any('reservation' in f and 'UNIQUE' in f for f in failures))

    def test_fk_claimed_without_schema_fk_is_detected(self):
        failures = run(erd=ERD.replace('(DB FK: space_id)', '(DB FK: ghost_id)'))
        self.assertTrue(any('ghost_id' in f for f in failures))

    def test_actual_fk_missing_from_relations_is_detected(self):
        erd = '\n'.join(l for l in ERD.split('\n') if not l.startswith('Space 1'))
        failures = run(erd=erd)
        self.assertTrue(any('관계 블록에 없음' in f and 'spaces' in f for f in failures))


if __name__ == '__main__':
    unittest.main()
