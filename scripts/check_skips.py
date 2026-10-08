#!/usr/bin/env python3
"""Fail when a test was skipped that the allow-list does not expect.

A test guarded by an assumption (a tool on the PATH, a filesystem feature) reports "skipped", not
"failed", so a runner that loses the tool keeps a green build while the feature goes untested. This
reads the Surefire XML reports and compares every skipped test against an allow-list.

    python3 scripts/check_skips.py target/surefire-reports scripts/expected-skips-linux.txt

Allow-list lines are `fully.qualified.Class` (any test in it may skip) or
`fully.qualified.Class#method` (parameter lists and invocation indexes are ignored). `#` at the start
of a line is a comment. An entry that matches no skipped test is reported but does not fail the run:
the same list serves both JDK lanes.
"""

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def load_allowed(path):
    allowed = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                allowed.add(line)
    return allowed


def method_name(name):
    """`save(Alias, Path)[4]` -> `save`."""
    return re.split(r"[(\[]", name, maxsplit=1)[0].strip()


def skipped_tests(report_dir):
    found = set()
    for report in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        for case in ET.parse(report).getroot().iter("testcase"):
            if case.find("skipped") is not None:
                found.add((case.get("classname"), method_name(case.get("name") or "")))
    return found


def check(report_dir, allow_path):
    allowed = load_allowed(allow_path)
    skipped = skipped_tests(report_dir)
    unexpected = sorted(f"{c}#{m}" for c, m in skipped if c not in allowed and f"{c}#{m}" not in allowed)
    used = {c for c, _ in skipped} | {f"{c}#{m}" for c, m in skipped}
    return unexpected, sorted(allowed - used), len(skipped)


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    if not glob.glob(os.path.join(argv[1], "TEST-*.xml")):
        print(f"no Surefire reports in {argv[1]}", file=sys.stderr)
        return 2
    unexpected, unused, total = check(argv[1], argv[2])
    print(f"{total} skipped test(s); {len(unexpected)} not on the allow-list")
    for entry in unused:
        print(f"  note: allow-list entry did not skip here: {entry}")
    for entry in unexpected:
        print(f"  UNEXPECTED SKIP: {entry}")
    if unexpected:
        print(
            "A skipped test is usually a tool missing from the runner. Install it in ci.yml, or add the "
            "test to the allow-list with the reason."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
