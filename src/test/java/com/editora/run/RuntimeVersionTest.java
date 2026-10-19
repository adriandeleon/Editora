package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeVersionTest {

    private static final String TEMURIN_25 = """
            Property settings:
                file.encoding = UTF-8
                java.home = /home/u/.sdkman/candidates/java/25.0.4-tem
                java.library.path = /usr/java/packages/lib
                    /usr/lib64
                    /lib64
                java.vendor = Eclipse Adoptium
                java.vendor.version = Temurin-25.0.4+7
                java.version = 25.0.4
                java.vm.name = OpenJDK 64-Bit Server VM

            openjdk version "25.0.4" 2026-07-21 LTS
            OpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)
            OpenJDK 64-Bit Server VM Temurin-25.0.4+7 (build 25.0.4+7-LTS, mixed mode, sharing)
            """;

    private static RuntimeVersion parse(List<String> launch, String output) {
        return RuntimeVersion.parse(RuntimeVersion.probeFor(launch), output);
    }

    @Test
    void javaIsAskedForItsPropertiesWithTheSameExecutable() {
        RuntimeVersion.Probe probe = RuntimeVersion.probeFor(List.of("/opt/jdk-25/bin/java", "-cp", "out", "Main"));
        assertEquals(RuntimeVersion.Kind.JAVA, probe.kind());
        assertEquals(List.of("/opt/jdk-25/bin/java", "-XshowSettings:properties", "-version"), probe.argv());
    }

    @Test
    void javaShowsItsExactVersionAndDistribution() {
        RuntimeVersion v = parse(List.of("java", "Main.java"), TEMURIN_25);
        assertEquals("Java 25.0.4 (Temurin)", v.label());
        assertEquals("""
                openjdk version "25.0.4" 2026-07-21 LTS
                OpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)
                OpenJDK 64-Bit Server VM Temurin-25.0.4+7 (build 25.0.4+7-LTS, mixed mode, sharing)
                /home/u/.sdkman/candidates/java/25.0.4-tem""", v.detail());
    }

    @Test
    void aJavaWithoutAVendorVersionShowsJustItsVersion() {
        String oracleOpenJdk = """
                Property settings:
                    java.home = /opt/jdk-21.0.2
                    java.vendor = Oracle Corporation
                    java.version = 21.0.2
                openjdk version "21.0.2" 2024-01-16
                OpenJDK Runtime Environment (build 21.0.2+13-58)
                """;
        assertEquals("Java 21.0.2", parse(List.of("java"), oracleOpenJdk).label());
    }

    @Test
    void aJavaThatPrintsOnlyItsBannerFallsBackToTheQuotedVersion() {
        String banner = """
                Picked up JAVA_TOOL_OPTIONS: -Xmx1g
                java version "1.8.0_392"
                Java(TM) SE Runtime Environment (build 1.8.0_392-b08)
                """;
        RuntimeVersion v = parse(List.of("C:\\jdk8\\bin\\java.exe"), banner);
        assertEquals("Java 1.8.0_392", v.label());
        assertTrue(v.detail().startsWith("java version \"1.8.0_392\""), v.detail());
    }

    @Test
    void distributionsAreReadFromTheVendorVersion() {
        assertEquals("Temurin", RuntimeVersion.distribution("Temurin-25.0.4+7"));
        assertEquals("Corretto", RuntimeVersion.distribution("Corretto-27.0.0.34.1"));
        assertEquals("Zulu", RuntimeVersion.distribution("Zulu21.38+21-CA"));
        assertEquals("GraalVM CE", RuntimeVersion.distribution("GraalVM CE 21.0.2+13.1"));
        assertEquals("Oracle GraalVM", RuntimeVersion.distribution("Oracle GraalVM 21.0.2+13.1"));
        assertEquals("JBR", RuntimeVersion.distribution("JBR-21.0.5+13-631.4-nomod"));
        assertNull(RuntimeVersion.distribution("25.0.4+7"));
        assertNull(RuntimeVersion.distribution(null));
    }

    @Test
    void pythonInterpretersAreRecognizedByName() {
        for (String exe :
                List.of("python3", "python", "python3.13", "/w/.venv/bin/python", "pypy3", "py", "PYTHON.EXE")) {
            RuntimeVersion.Probe probe = RuntimeVersion.probeFor(List.of(exe, "main.py"));
            assertEquals(RuntimeVersion.Kind.PYTHON, probe.kind(), exe);
            assertEquals(List.of(exe, "--version"), probe.argv());
        }
    }

    @Test
    void pythonShowsItsVersionAndTheInterpreterAsked() {
        RuntimeVersion v = parse(List.of("/w/.venv/bin/python", "main.py"), "\nPython 3.13.5\n");
        assertEquals("Python 3.13.5", v.label());
        assertEquals("Python 3.13.5\n/w/.venv/bin/python", v.detail());
    }

    @Test
    void pypySaysBothItsOwnAndItsPythonVersion() {
        String out = "Python 3.10.14 (39dc8d3c85a7, Aug 27 2024, 14:32:27)\n[PyPy 7.3.17 with GCC 10.2.1]\n";
        assertEquals(
                "PyPy 7.3.17 (Python 3.10.14)", parse(List.of("pypy3"), out).label());
    }

    @Test
    void nodeAndAnNpmScriptReportNode() {
        assertEquals(
                "Node.js 24.18.1",
                parse(List.of("node", "app.js"), "v24.18.1\n").label());
        assertEquals(
                List.of("node", "--version"),
                RuntimeVersion.probeFor(List.of("npm", "run", "dev")).argv());
        assertEquals(
                List.of("/opt/node/bin/node", "--version"),
                RuntimeVersion.probeFor(List.of("/opt/node/bin/npm", "run", "dev"))
                        .argv());
        assertEquals(
                List.of("C:\\nodejs\\node", "--version"),
                RuntimeVersion.probeFor(List.of("C:\\nodejs\\npm.cmd", "run", "dev"))
                        .argv());
    }

    @Test
    void shellsAndMakeShowTheirVersions() {
        assertEquals(
                "Bash 5.2.37",
                parse(List.of("bash", "-x", "deploy.sh"), "GNU bash, version 5.2.37(1)-release (x86_64-pc-linux-gnu)\n")
                        .label());
        assertEquals(
                "Zsh 5.9",
                parse(List.of("/bin/zsh", "x.zsh"), "zsh 5.9 (x86_64-apple-darwin23.0)\n")
                        .label());
        assertEquals(
                "fish 3.7.1",
                parse(List.of("fish", "x.fish"), "fish, version 3.7.1\n").label());
        assertEquals(
                "GNU Make 4.4.1",
                parse(List.of("make", "test"), "GNU Make 4.4.1\nBuilt for x86_64\n")
                        .label());
    }

    @Test
    void theClojureToolsSayTheirOwnVersions() {
        assertEquals(
                List.of("/usr/local/bin/clojure", "--version"),
                RuntimeVersion.probeFor(List.of("/usr/local/bin/clojure", "-M", "x.clj"))
                        .argv());
        assertEquals(
                "Clojure CLI 1.12.6.1673",
                parse(List.of("clojure", "-M", "x.clj"), "Clojure CLI version 1.12.6.1673\n")
                        .label());
        assertEquals(
                "Leiningen 2.13.0",
                parse(List.of("lein", "run"), "Leiningen 2.13.0 on Java 25.0.4 OpenJDK 64-Bit Server VM\n")
                        .label());
        assertEquals(
                "Babashka 1.13.226",
                parse(List.of("bb.exe", "x.bb"), "babashka v1.13.226\n").label());
    }

    @Test
    void groovySaysItsOwnVersionAndItsJava() {
        assertEquals(
                List.of("/sdk/groovy/bin/groovy", "--version"),
                RuntimeVersion.probeFor(List.of("/sdk/groovy/bin/groovy", "x.groovy"))
                        .argv());
        assertEquals(
                "Groovy 5.1.3 (Java 25.0.4)",
                parse(
                                List.of("groovy", "x.groovy"),
                                "Groovy Version: 5.1.3 JVM: 25.0.4 Vendor: Eclipse Adoptium OS: Linux\n")
                        .label());
        assertEquals(
                "Groovy 4.0.33",
                parse(List.of("groovy.bat"), "Groovy Version: 4.0.33\n").label());
    }

    @Test
    void kotlinSaysTheCompilersVersionAndItsJavaButNotTheToolchainClis() {
        assertEquals(
                List.of("/sdk/kotlin/bin/kotlinc", "-version"),
                RuntimeVersion.probeFor(List.of("/sdk/kotlin/bin/kotlinc", "-script", "a.main.kts"))
                        .argv());
        assertEquals(
                "Kotlin 2.4.21 (Java 25.0.4)",
                parse(List.of("kotlinc", "-script", "a.kts"), "info: kotlinc-jvm 2.4.21 (JRE 25.0.4+7-LTS)\n")
                        .label());
        assertEquals(
                "Kotlin 2.4.21",
                parse(List.of("kotlinc.bat"), "info: kotlinc-jvm 2.4.21\n").label());
        assertNull(parse(List.of("kotlinc"), "kotlin toolchain 0.1.0\n"));
        assertNull(RuntimeVersion.probeFor(List.of("kotlin", "a.main.kts")), "`kotlin` may be the toolchain CLI");
    }

    @Test
    void unknownRuntimesAreNotProbed() {
        assertNull(RuntimeVersion.probeFor(List.of("sh", "x.sh")));
        assertNull(RuntimeVersion.probeFor(List.of("/w/build/app")));
        assertNull(RuntimeVersion.probeFor(List.of()));
        assertNull(RuntimeVersion.probeFor(null));
    }

    @Test
    void outputWithoutAVersionShowsNothing() {
        assertNull(parse(List.of("java"), "bash: java: command not found\n"));
        assertNull(parse(List.of("python3"), ""));
        assertNull(parse(List.of("make"), "make: unknown option -- -\n")); // a BSD make
        assertNull(parse(List.of("node"), null));
    }

    @Test
    void bunAndDenoReportTheirOwnRuntime() {
        RuntimeVersion.Probe bun = RuntimeVersion.probeFor(java.util.List.of("/home/u/.bun/bin/bun", "run", "x"));
        assertEquals(java.util.List.of("/home/u/.bun/bin/bun", "--version"), bun.argv());
        assertEquals("Bun 1.1.30", RuntimeVersion.parse(bun, "1.1.30\n").label());
        RuntimeVersion.Probe deno = RuntimeVersion.probeFor(java.util.List.of("deno", "run", "a.ts"));
        assertEquals(
                "Deno 2.0.1",
                RuntimeVersion.parse(deno, "deno 2.0.1 (stable, release, x86_64)\nv8 12\n")
                        .label());
    }
}
