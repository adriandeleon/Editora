package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmScriptRunTest {

    @Test
    void aGroovyFileRunsThroughGroovy() {
        assertTrue(JvmScriptRun.supports("groovy"));
        assertFalse(JvmScriptRun.supports("java"), "Java has its own launcher");
        assertFalse(JvmScriptRun.supports(null));
        assertEquals("groovy", JvmScriptRun.tool("groovy"));
        assertEquals(
                List.of("/opt/groovy/bin/groovy", "/p/Report.groovy", "--month", "10"),
                JvmScriptRun.argv("/opt/groovy/bin/groovy", "/p/Report.groovy", List.of("--month", "10")));
        assertEquals("status.run.toolMissing.groovy", JvmScriptRun.missingToolKey("groovy"));
    }

    @Test
    void jvmOptionsJoinTheUsersOwnInTheLaunchersVariable() {
        assertEquals("JAVA_OPTS", JvmScriptRun.jvmOptionsVariable("groovy"));
        assertNull(JvmScriptRun.jvmOptionsVariable("python"));
        assertEquals(
                Map.of("JAVA_HOME", "/jdk", "JAVA_OPTS", "-Xmx1g -ea"),
                JvmScriptRun.withJvmOption(
                        Map.of("JAVA_HOME", "/jdk"), Map.of("JAVA_OPTS", "-Xmx1g"), "JAVA_OPTS", "-ea"));
        assertEquals(
                Map.of("JAVA_OPTS", "-Dset=here -ea"),
                JvmScriptRun.withJvmOption(
                        Map.of("JAVA_OPTS", "-Dset=here"), Map.of("JAVA_OPTS", "-Xmx1g"), "JAVA_OPTS", "-ea"),
                "the launch's own value replaces the inherited one");
        assertEquals(Map.of(), JvmScriptRun.withJvmOption(Map.of(), Map.of(), "JAVA_OPTS", " "));
    }

    @Test
    void aScalaFileRunsThroughScalaCliWithItsJvmOptionsAsArguments() {
        assertTrue(JvmScriptRun.supports("scala"));
        assertEquals("scala-cli", JvmScriptRun.tool("scala"));
        assertEquals("status.run.toolMissing.scala-cli", JvmScriptRun.missingToolKey("scala"));
        assertNull(JvmScriptRun.jvmOptionsVariable("scala"), "the options go on the command line");
        JvmScriptRun.Launch run = JvmScriptRun.launch(
                "scala",
                "/sdk/scala-cli",
                "/p/hello.sc",
                List.of("-Xmx1g", "-ea"),
                List.of("-v", "x"),
                Map.of("JAVA_HOME", "/jdk"),
                Map.of("JAVA_OPTS", "-Dno=thanks"));
        assertEquals(
                List.of(
                        "/sdk/scala-cli",
                        "run",
                        "--java-opt",
                        "-Xmx1g",
                        "--java-opt",
                        "-ea",
                        "/p/hello.sc",
                        "--",
                        "-v",
                        "x"),
                run.argv());
        assertEquals(Map.of("JAVA_HOME", "/jdk"), run.env(), "JAVA_OPTS is not Scala CLI's");
        assertEquals(
                List.of("scala-cli", "run", "/p/hello.sc"),
                JvmScriptRun.scalaCli("scala-cli", List.of("/p/hello.sc"), List.of(), List.of()));
    }

    @Test
    void groovyKeepsItsOptionsInJavaOpts() {
        JvmScriptRun.Launch run = JvmScriptRun.launch(
                "groovy",
                "groovy",
                "/p/R.groovy",
                List.of("-ea"),
                List.of("a"),
                Map.of(),
                Map.of("JAVA_OPTS", "-Xmx1g"));
        assertEquals(List.of("groovy", "/p/R.groovy", "a"), run.argv());
        assertEquals(Map.of("JAVA_OPTS", "-Xmx1g -ea"), run.env());
        assertEquals(
                List.of("groovy", "/p/R.groovy"),
                JvmScriptRun.debugArgv("groovy", "groovy", "/p/R.groovy", List.of(), List.of()));
    }

    @Test
    void aScalaDebugCommandCarriesTheAgentPlaceholderFirst() {
        assertEquals(
                List.of(
                        "scala-cli",
                        "run",
                        "--java-opt",
                        JvmScriptRun.AGENT_ARGUMENT,
                        "--java-opt",
                        "-Xmx1g",
                        "--main-class",
                        "demo.hello",
                        "/p",
                        "--",
                        "a"),
                JvmScriptRun.scalaCliDebug(
                        "scala-cli", List.of("--main-class", "demo.hello", "/p"), List.of("-Xmx1g"), List.of("a")));
    }

    @Test
    void scalaCliIsLookedForInSdkmansScalacliThenScalaCandidates() {
        Path home = Path.of("/home/u");
        assertEquals(
                List.of(
                        home.resolve(".sdkman/candidates/scalacli/current/bin/scala-cli"),
                        home.resolve(".sdkman/candidates/scala/current/bin/scala")),
                JvmTools.candidates("scala-cli", Map.of(), home, false));
        assertEquals(
                List.of(
                        Path.of("/sdk/candidates/scalacli/current/bin/scala-cli.exe"),
                        Path.of("/sdk/candidates/scala/current/bin/scala.exe")),
                JvmTools.candidates("scala-cli", Map.of("SDKMAN_DIR", "/sdk"), home, true));
        assertTrue(JvmTools.knows("scala-cli"));
    }

    @Test
    void aClojureFilesJvmOptionsGoWhereItsToolReadsThem(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("deps.edn"), "{}");
        Path core =
                java.nio.file.Files.createDirectories(dir.resolve("src/app")).resolve("core.clj");
        java.nio.file.Files.writeString(core, "(ns app.core)\n(defn -main [& args])\n");
        assertTrue(JvmScriptRun.supports("clojure"));
        assertEquals("clojure", JvmScriptRun.tool("clojure", core));

        JvmScriptRun.Launch debug = JvmScriptRun.launch(
                "clojure",
                "clojure",
                "/opt/clj",
                core,
                core.toString(),
                List.of("a"),
                "-Xmx1g -Dk=\"a b\"",
                true,
                Map.of("JAVA_HOME", "/jdk"),
                Map.of("JAVA_OPTS", "-Dmine"));
        assertEquals(
                List.of(
                        "/opt/clj",
                        "-J-Dclojure.main.report=stderr",
                        "-J-Dclojure.compiler.disable-locals-clearing=true",
                        "-J-Xmx1g",
                        "-J-Dk=a b",
                        "-M",
                        "-m",
                        "app.core",
                        "a"),
                debug.argv());
        assertEquals(dir, debug.directory(), "it runs in the project, where deps.edn is read");
        assertEquals(Map.of("JAVA_HOME", "/jdk"), debug.env(), "the user's own JAVA_OPTS are the CLI's to read");
        assertEquals("-J", JvmScriptRun.jvmOptionPrefix("clojure"));
        assertNull(JvmScriptRun.jvmOptionsVariable("clojure", "clojure"));

        JvmScriptRun.Launch lein = JvmScriptRun.launch(
                "clojure", "lein", "lein", core, core.toString(), List.of(), "-Xmx1g", false, Map.of(), Map.of());
        assertEquals(List.of("lein", "run", "-m", "app.core"), lein.argv());
        assertEquals(Map.of("JVM_OPTS", "-Dclojure.main.report=stderr -Xmx1g"), lein.env());
        assertEquals("JVM_OPTS", JvmScriptRun.jvmOptionsVariable("clojure", "lein"));
        assertFalse(JvmScriptRun.debuggable("bb"));
        assertTrue(JvmScriptRun.debuggable("groovy"));
        assertEquals("status.run.toolMissing.lein", JvmScriptRun.missingToolKeyFor("lein"));
    }

    @Test
    void aGroovyLaunchIsUnchanged(@org.junit.jupiter.api.io.TempDir Path dir) {
        Path script = dir.resolve("Report.groovy");
        JvmScriptRun.Launch how = JvmScriptRun.launch(
                "groovy",
                "groovy",
                "/g/groovy",
                script,
                "/p/Report.groovy",
                List.of("x"),
                "-ea",
                false,
                Map.of(),
                Map.of("JAVA_OPTS", "-Xmx1g"));
        assertEquals(List.of("/g/groovy", "/p/Report.groovy", "x"), how.argv());
        assertNull(how.directory());
        assertEquals(Map.of("JAVA_OPTS", "-Xmx1g -ea"), how.env());
        assertEquals("groovy", JvmScriptRun.tool("groovy", script));
    }

    @Test
    void groovyIsLookedForInItsHomeThenSdkman() {
        Path home = Path.of("/home/u");
        assertEquals(
                List.of(
                        Path.of("/opt/groovy-5/bin/groovy"),
                        home.resolve(".sdkman/candidates/groovy/current/bin/groovy")),
                JvmTools.candidates("groovy", Map.of("GROOVY_HOME", "/opt/groovy-5"), home, false));
        assertEquals(
                List.of(Path.of("/sdk/candidates/groovy/current/bin/groovy.bat")),
                JvmTools.candidates("groovy", Map.of("SDKMAN_DIR", "/sdk"), home, true));
        assertEquals(List.of(), JvmTools.candidates("no-such-tool", Map.of(), home, false), "not a known tool");
        assertTrue(JvmTools.knows("groovy"));
        for (String tool : List.of("clojure", "lein", "bb")) {
            assertTrue(JvmTools.knows(tool));
            assertEquals(
                    List.of(), JvmTools.candidates(tool, Map.of("SDKMAN_DIR", "/sdk"), home, false), tool + ": PATH");
        }
    }

    @Test
    void kotlincIsLookedForInKotlinHomeThenSdkmansKotlinCandidate() {
        Path home = Path.of("/home/u");
        assertEquals(
                List.of(
                        Path.of("/opt/kotlinc/bin/kotlinc"),
                        home.resolve(".sdkman/candidates/kotlin/current/bin/kotlinc")),
                JvmTools.candidates("kotlinc", Map.of("KOTLIN_HOME", "/opt/kotlinc"), home, false));
        assertEquals("kotlinc", JvmScriptRun.tool("kotlin"));
        assertEquals("JAVA_OPTS", JvmScriptRun.jvmOptionsVariable("kotlin"), "kotlinc's launcher reads it");
        assertEquals("status.run.toolMissing.kotlinc", JvmScriptRun.missingToolKey("kotlin"));
    }
}
