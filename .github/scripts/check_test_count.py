"""Fail the build when the Gradle test run is weaker than the recorded baseline.

usage: check_test_count.py <test-results-dir> <baseline-file>

Sums the testsuite counters of every TEST-*.xml and requires
tests >= baseline, skipped == 0, failures == 0, errors == 0.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def read_baseline(path):
    match = re.search(r"^tests=(\d+)\s*$", path.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        raise ValueError(f"{path}: expected a line like 'tests=1048'")
    return int(match.group(1))


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    results_dir, baseline_file = Path(argv[1]), Path(argv[2])
    try:
        baseline = read_baseline(baseline_file)
    except (OSError, ValueError) as e:
        print(f"::error::cannot read baseline: {e}")
        return 1

    files = sorted(results_dir.glob("TEST-*.xml"))
    if not files:
        print(f"::error::no TEST-*.xml found in {results_dir}")
        return 1

    total = {"tests": 0, "skipped": 0, "failures": 0, "errors": 0}
    for f in files:
        root = ET.parse(f).getroot()
        for key in total:
            total[key] += int(root.get(key, 0))

    print(
        f"suites={len(files)} tests={total['tests']} skipped={total['skipped']} "
        f"failures={total['failures']} errors={total['errors']} baseline={baseline}"
    )

    problems = []
    if total["tests"] < baseline:
        problems.append(f"tests {total['tests']} is below baseline {baseline}")
    for key in ("skipped", "failures", "errors"):
        if total[key] != 0:
            problems.append(f"{key}={total[key]} (must be 0)")
    if problems:
        for p in problems:
            print(f"::error::{p}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
