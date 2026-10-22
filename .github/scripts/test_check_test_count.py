import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_test_count.py")


def suite(tests, skipped=0, failures=0, errors=0):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="x" tests="{tests}" skipped="{skipped}" '
        f'failures="{failures}" errors="{errors}"></testsuite>'
    )


def run(results, baseline):
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        res = root / "results"
        res.mkdir()
        for i, xml in enumerate(results):
            (res / f"TEST-{i}.xml").write_text(xml, encoding="utf-8")
        base = root / "baseline.txt"
        base.write_text(baseline, encoding="utf-8")
        p = subprocess.run(
            [sys.executable, str(SCRIPT), str(res), str(base)],
            capture_output=True,
            text=True,
        )
        return p.returncode, p.stdout + p.stderr


class CheckTestCount(unittest.TestCase):
    def test_passes_when_count_equals_baseline(self):
        code, _ = run([suite(5), suite(5)], "tests=10\n")
        self.assertEqual(code, 0)

    def test_passes_when_count_exceeds_baseline(self):
        code, _ = run([suite(5), suite(6)], "tests=10\n")
        self.assertEqual(code, 0)

    def test_fails_when_count_below_baseline(self):
        code, out = run([suite(5), suite(4)], "tests=10\n")
        self.assertNotEqual(code, 0)
        self.assertIn("9", out)

    def test_fails_when_any_test_skipped(self):
        code, _ = run([suite(5), suite(5, skipped=1)], "tests=10\n")
        self.assertNotEqual(code, 0)

    def test_fails_when_failures_or_errors(self):
        code, _ = run([suite(10, failures=1)], "tests=10\n")
        self.assertNotEqual(code, 0)
        code, _ = run([suite(10, errors=1)], "tests=10\n")
        self.assertNotEqual(code, 0)

    def test_fails_when_no_result_files(self):
        code, _ = run([], "tests=10\n")
        self.assertNotEqual(code, 0)

    def test_fails_when_baseline_is_malformed(self):
        code, _ = run([suite(10)], "garbage\n")
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
