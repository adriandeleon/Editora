#!/usr/bin/env python3
"""List the tests that failed and then passed when Surefire ran them again.

CI reruns a failing test (`-Dsurefire.rerunFailingTestsCount`), so a test that only fails now and then
no longer turns the build red. That must not hide it: a flaky test is a race in the test or in the
application. This reads the Surefire XML reports and prints every test that needed a rerun, as a
GitHub warning annotation and as Markdown for the job summary.

    python3 scripts/report_flakes.py target/surefire-reports >> "$GITHUB_STEP_SUMMARY"

Always exits 0: the tests passed in the end. It is the report that matters.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

FLAKY_TAGS = ("flakyFailure", "flakyError")


def first_line(text):
    lines = (text or "").strip().splitlines()
    return lines[0].strip() if lines else ""


def flaky_tests(report_dir):
    """`[(class, test, failed attempts, first message)]`, sorted."""
    found = []
    for report in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        for case in ET.parse(report).getroot().iter("testcase"):
            attempts = [child for child in case if child.tag in FLAKY_TAGS]
            if attempts:
                message = attempts[0].get("message") or first_line(attempts[0].text)
                found.append((case.get("classname"), case.get("name") or "", len(attempts), first_line(message)))
    return sorted(found)


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    if not glob.glob(os.path.join(argv[1], "TEST-*.xml")):
        return 0  # the build stopped before any test ran: nothing to say
    flakes = flaky_tests(argv[1])
    print("### Flaky tests")
    if not flakes:
        print("None: no test needed a rerun.")
        return 0
    print(f"{len(flakes)} test(s) failed and then passed on a rerun. Each one is a race worth fixing.")
    print()
    print("| Test | Failed attempts | First failure |")
    print("|---|---|---|")
    for classname, name, attempts, message in flakes:
        print(f"| `{classname}.{name}` | {attempts} | {message.replace('|', '/')[:200]} |")
        # Annotations go to the log, not the summary file.
        print(f"::warning title=Flaky test::{classname}.{name} passed only on a rerun: {message[:200]}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
