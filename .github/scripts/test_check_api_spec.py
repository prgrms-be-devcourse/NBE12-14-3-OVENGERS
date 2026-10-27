import tempfile
import unittest
from pathlib import Path

import check_api_spec as mod

ERROR_CODE = """enum class ErrorCode(val httpStatus: HttpStatus, val code: String) {
    ITEM_MISSING(HttpStatus.NOT_FOUND, "ITEM_MISSING"),
    ITEM_CLASH(HttpStatus.CONFLICT, "ITEM_CLASH"),
    ACCESS_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "ACCESS_LOG_NOT_FOUND"),
}
"""

CONTROLLER = """@RestController
@RequestMapping("/api/v1/things")
class ThingController {
    @GetMapping("/{id}")
    fun get() = Unit

    @PutMapping(
        value = ["/{id}/image"],
        consumes = ["multipart/form-data"],
    )
    fun put() = Unit
}
"""

SPEC = """- `GET /things/{thingId}`: 오류 `ITEM_MISSING`(404)
- `PUT /things/{id}/image`: 오류 `ITEM_CLASH`(409)
"""

AGENTS = "자주 참조: `ITEM_MISSING`(404)\n"


def build(root, spec=SPEC, agents=AGENTS, controller=CONTROLLER, error_code=ERROR_CODE):
    root = Path(root)
    files = {
        'docs/api/api-spec.md': spec,
        'AGENTS.md': agents,
        'backend/src/main/kotlin/com/ovengers/slotkey/global/error/ErrorCode.kt': error_code,
        'backend/src/main/kotlin/com/ovengers/slotkey/ThingController.kt': controller,
    }
    for rel, text in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding='utf-8')


def run(**kw):
    with tempfile.TemporaryDirectory() as d:
        build(d, **kw)
        return mod.check(d)[:2]


class CheckApiSpecTest(unittest.TestCase):
    def test_consistent_docs_pass_including_multiline_annotation(self):
        failures, _ = run()
        self.assertEqual(failures, [])

    def test_undocumented_endpoint_is_detected(self):
        failures, _ = run(spec="- `GET /things/{id}`: 오류 `ITEM_MISSING`(404), `ITEM_CLASH`(409)\n")
        self.assertTrue(any('PUT /things/{}/image' in f for f in failures))

    def test_undocumented_error_code_is_detected(self):
        failures, _ = run(spec=SPEC.replace('`ITEM_CLASH`(409)', ''))
        self.assertTrue(any('ITEM_CLASH' in f for f in failures))

    def test_internal_only_code_is_not_required_in_docs(self):
        failures, _ = run()
        self.assertFalse(any('ACCESS_LOG_NOT_FOUND' in f for f in failures))

    def test_wrong_status_in_docs_is_detected(self):
        failures, _ = run(spec=SPEC.replace('`ITEM_MISSING`(404)', '`ITEM_MISSING`(400)'))
        self.assertTrue(any('ITEM_MISSING' in f and '상태 불일치' in f for f in failures))

    def test_unknown_code_in_agents_is_detected(self):
        failures, _ = run(agents="자주 참조: `GONE_CODE`(403)\n")
        self.assertTrue(any('AGENTS.md' in f and 'GONE_CODE' in f for f in failures))

    def test_missing_agents_file_is_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            build(d)
            (Path(d) / 'AGENTS.md').unlink()
            self.assertEqual(mod.check(d)[0], [])

    def test_spec_only_endpoint_is_only_a_warning(self):
        failures, warnings = run(spec=SPEC + "- `DELETE /things/{id}` 는 없다\n")
        self.assertEqual(failures, [])
        self.assertTrue(any('DELETE /things/{}' in w for w in warnings))


if __name__ == '__main__':
    unittest.main()
