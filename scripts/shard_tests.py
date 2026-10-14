#!/usr/bin/env python3
"""Split the test classes into shards, and check afterwards that every one of them ran.

CI runs the Linux suite on several runners at once: each takes one shard, and a last job puts the
coverage back together (see `.github/workflows/ci.yml`). Surefire has no notion of a shard, so this
writes the list of classes a shard runs, in the form Surefire's `includesFile` reads:

    python3 scripts/shard_tests.py list 2 4 > target/shard-includes.txt
    ./mvnw -Dsurefire.includesFile=target/shard-includes.txt test

The classes are the files under `src/test/java` that Surefire would pick by itself (`Test*`, `*Test`,
`*Tests`, `*TestCase`). They are dealt out in turn from a sorted list, the JavaFX classes (`*FxTest`,
which take most of the time) separately from the rest, so every shard gets its share of both and
classes with neighbouring names land on different runners.

A list of includes replaces Surefire's own, so a class missing from every list would silently stop
running. `check` is the guard, run on the reports of all shards together:

    python3 scripts/shard_tests.py check target/surefire-reports

It fails when a class that declares a test has no report.
"""

import glob
import os
import re
import sys

TEST_ROOT = os.path.join("src", "test", "java")
SUREFIRE_DEFAULT = re.compile(r"^(Test.*|.*Test|.*Tests|.*TestCase)$")
DECLARES_A_TEST = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
ABSTRACT = re.compile(r"^\s*(public\s+)?abstract\s+class\b", re.MULTILINE)


def test_classes(root=TEST_ROOT):
    """Paths below `root`, with `/` separators, of the files Surefire's default includes match; sorted."""
    found = []
    for path in glob.glob(os.path.join(root, "**", "*.java"), recursive=True):
        if SUREFIRE_DEFAULT.match(os.path.basename(path)[: -len(".java")]):
            found.append(os.path.relpath(path, root).replace(os.sep, "/"))
    return sorted(found)


def shard(classes, index, count):
    """The classes of shard `index` (1-based) of `count`."""
    fx = [c for c in classes if c.endswith("FxTest.java")]
    rest = [c for c in classes if not c.endswith("FxTest.java")]
    return sorted(fx[index - 1 :: count] + rest[index - 1 :: count])


def unreported(classes, report_dir, root=TEST_ROOT):
    """The classes that declare a test of their own and have no Surefire report."""
    reported = set()
    for report in glob.glob(os.path.join(report_dir, "TEST-*.xml")):
        name = os.path.basename(report)[len("TEST-") : -len(".xml")]
        reported.add(name.split("$")[0])  # a nested class reports as Outer$Nested
    missing = []
    for path in classes:
        name = path[: -len(".java")].replace("/", ".")
        if name in reported:
            continue
        with open(os.path.join(root, path), encoding="utf-8") as fh:
            source = fh.read()
        if DECLARES_A_TEST.search(source) and not ABSTRACT.search(source):
            missing.append(name)
    return missing


def main(argv):
    if len(argv) == 4 and argv[1] == "list":
        index, count = int(argv[2]), int(argv[3])
        if not 1 <= index <= count:
            print(f"shard {index} of {count} does not exist", file=sys.stderr)
            return 2
        print("\n".join(shard(test_classes(), index, count)))
        return 0
    if len(argv) == 3 and argv[1] == "check":
        classes = test_classes()
        missing = unreported(classes, argv[2])
        if missing:
            print(f"{len(missing)} test class(es) ran on no shard:")
            for name in missing:
                print(f"  {name}")
                print(f"::error title=Test class did not run::{name} has no Surefire report", file=sys.stderr)
            return 1
        print(f"All {len(classes)} test classes are accounted for.")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
