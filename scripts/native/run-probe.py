#!/usr/bin/env python3
"""Run the opt-in EditorBuffer probe with identical arguments on either runtime."""
import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--native", type=Path, help="native probe executable; default is HotSpot")
    p.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java")
    p.add_argument("--sizes", default="102400,1048576,5242880,10485760")
    p.add_argument("--edits", type=int, default=300)
    p.add_argument("--desktop", action="store_true", help="use real display instead of Headless/software")
    p.add_argument("--trace", type=Path, help="unreviewed agent output directory (GraalVM JVM only)")
    p.add_argument("--print-command", action="store_true", help="emit argv JSON for benchmark config")
    args = p.parse_args()
    options = ["-Xmx2g", "-Xms64m"]
    if not args.desktop:
        options += ["-Dglass.platform=Headless", "-Dprism.order=sw"]
    if args.native:
        if args.trace:
            p.error("tracing agent runs on the JVM, not the native executable")
        command = [str(args.native.resolve()), "-XX:MissingRegistrationReportingMode=Exit"] + options
    else:
        classpath = ROOT / "target/probe-classpath.txt"
        if not classpath.exists():
            p.error("first run mvn -Peditor-probe compile dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt")
        command = [args.java, "--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC"] + options
        if args.trace:
            args.trace.mkdir(parents=True, exist_ok=False)
            command += ["-agentlib:native-image-agent=config-output-dir=" + str(args.trace.resolve())]
        command += ["-cp", str(ROOT / "target/classes") + os.pathsep + classpath.read_text().strip(),
                    "com.editora.experiment.EditorProbeLauncher"]
    command += ["--sizes=" + args.sizes, "--edits=" + str(args.edits)]
    if args.print_command:
        print(json.dumps(command))
    else:
        os.execvpe(command[0], command, os.environ)


if __name__ == "__main__":
    main()
