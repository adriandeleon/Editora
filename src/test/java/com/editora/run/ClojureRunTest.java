package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClojureRunTest {

    private static final String CORE = "(ns my-app.core\n  (:gen-class))\n\n(defn -main [& args]\n  (println args))\n";

    private static Predicate<Path> files(String... paths) {
        Set<Path> set = new java.util.HashSet<>();
        for (String p : paths) {
            set.add(Path.of(p));
        }
        return set::contains;
    }

    @Test
    void aNamespaceAndItsMainAreFoundPastMetadataAndComments() {
        assertEquals("my-app.core", ClojureRun.namespace(CORE));
        assertEquals("app.util", ClojureRun.namespace(";; utils\n(ns ^:no-doc app.util)\n"));
        assertEquals("a.b", ClojureRun.namespace("(ns ^{:doc \"x\"} a.b)"));
        assertNull(ClojureRun.namespace("(println 1)"));
        assertEquals(3, ClojureRun.mainLine(CORE));
        assertEquals(0, ClojureRun.mainLine("(defn ^:export -main [] 1)"));
        assertEquals(-1, ClojureRun.mainLine("(defn -main-helper [] 1)"), "not -main itself");
        assertEquals(-1, ClojureRun.mainLine("(defn main [] 1)"));
    }

    @Test
    void onlySourceFilesRunNotDataProjectFilesOrClojureScript() {
        for (String name : List.of("core.clj", "shared.cljc", "tasks.bb", "deploy")) {
            assertTrue(ClojureRun.runnable(name), name);
        }
        for (String name : List.of("deps.edn", "bb.edn", "project.clj", "build.boot", "app.cljs", "old.cljx")) {
            assertFalse(ClojureRun.runnable(name), name);
        }
    }

    @Test
    void babashkaIsTheShebangsInterpreterThroughEnv() {
        assertTrue(ClojureRun.babashkaShebang("#!/usr/bin/env bb"));
        assertTrue(ClojureRun.babashkaShebang("#!/usr/local/bin/bb --init x"));
        assertTrue(ClojureRun.babashkaShebang("#!/usr/bin/env -S bb -cp src"));
        assertFalse(ClojureRun.babashkaShebang("#!/usr/bin/env clojure -M"));
        assertFalse(ClojureRun.babashkaShebang("(ns bb)"));
    }

    @Test
    void aDepsProjectsNamespaceRunsThroughDashMInTheProjectFolder() {
        Path file = Path.of("/p/src/my_app/core.clj");
        ClojureRun.Plan plan = ClojureRun.plan(file, CORE, files("/p/deps.edn"));
        assertEquals(ClojureRun.Tool.CLI, plan.tool());
        assertEquals(Path.of("/p"), plan.directory());
        assertEquals("my-app.core", plan.mainNamespace());
        assertTrue(plan.inProject());
        assertNull(plan.extraPath(), "src is on the default classpath");
        assertEquals(
                List.of("clojure", "-J-Dclojure.main.report=stderr", "-M", "-m", "my-app.core", "a"),
                ClojureRun.argv(plan, "clojure", file.toString(), List.of(ClojureRun.REPORT_TO_STDERR), List.of("a")));
    }

    @Test
    void aNamespaceOutsideSrcIsPutOnTheClasspathThroughAnAlias() {
        Path file = Path.of("/p/scripts/tool.clj");
        ClojureRun.Plan plan = ClojureRun.plan(file, "(ns tool)\n(defn -main [])\n", files("/p/deps.edn"));
        assertEquals("scripts", plan.extraPath());
        assertEquals(
                List.of(
                        "clojure",
                        "-Sdeps",
                        "{:aliases {:editora/run {:extra-paths [\"scripts\"]}}}",
                        "-M:editora/run",
                        "-m",
                        "tool"),
                ClojureRun.argv(plan, "clojure", file.toString(), List.of(), List.of()));
    }

    @Test
    void aStandaloneFileRunsAsAScriptOrItsFolderBecomesTheClasspath() {
        Path script = Path.of("/w/tally.clj");
        ClojureRun.Plan plain = ClojureRun.plan(script, "(println 1)\n", files());
        assertEquals(ClojureRun.Tool.CLI, plain.tool());
        assertEquals(Path.of("/w"), plain.directory());
        assertNull(plain.mainNamespace());
        assertEquals(
                List.of("clojure", "-M", "/w/tally.clj", "x"),
                ClojureRun.argv(plain, "clojure", "/w/tally.clj", List.of(), List.of("x")));

        Path hello = Path.of("/w/hello.clj");
        ClojureRun.Plan main = ClojureRun.plan(hello, "(ns hello)\n(defn -main [])\n", files());
        assertEquals("hello", main.mainNamespace());
        assertFalse(main.inProject());
        assertEquals(
                List.of("clojure", "-Sdeps", "{:paths [\".\"]}", "-M", "-m", "hello"),
                ClojureRun.argv(main, "clojure", "/w/hello.clj", List.of(), List.of()));

        // A -main in a namespace that does not match the file's path cannot be found by name: run as a script.
        ClojureRun.Plan mismatch = ClojureRun.plan(Path.of("/w/other.clj"), "(ns hello)\n(defn -main [])\n", files());
        assertNull(mismatch.mainNamespace());
    }

    @Test
    void leiningenRunsAProjectWithoutDepsEdnAndAScriptThroughClojureMain() {
        Path file = Path.of("/l/src/my_app/core.clj");
        ClojureRun.Plan plan = ClojureRun.plan(file, CORE, files("/l/project.clj"));
        assertEquals(ClojureRun.Tool.LEIN, plan.tool());
        assertEquals(Path.of("/l"), plan.directory());
        assertEquals(
                List.of("lein", "run", "-m", "my-app.core", "a"),
                ClojureRun.argv(plan, "lein", file.toString(), List.of("-Dx"), List.of("a")),
                "JVM options go in JVM_OPTS, not the command line");
        assertEquals("JVM_OPTS", ClojureRun.jvmOptionsVariable(ClojureRun.Tool.LEIN));
        ClojureRun.Plan script = ClojureRun.plan(Path.of("/l/dev/scratch.clj"), "(println 1)", files("/l/project.clj"));
        assertEquals(
                List.of("lein", "run", "-m", "clojure.main", "/l/dev/scratch.clj"),
                ClojureRun.argv(script, "lein", "/l/dev/scratch.clj", List.of(), List.of()));
        assertEquals(
                ClojureRun.Tool.CLI,
                ClojureRun.plan(file, CORE, files("/l/project.clj", "/l/deps.edn"))
                        .tool(),
                "deps.edn wins when a project has both");
    }

    @Test
    void babashkaRunsItsScriptsAndBbEdnProjectsButIsNeverDebugged() {
        ClojureRun.Plan shebang = ClojureRun.plan(Path.of("/w/deploy"), "#!/usr/bin/env bb\n(println 1)", files());
        assertEquals(ClojureRun.Tool.BB, shebang.tool());
        assertEquals(
                List.of("bb", "/w/deploy", "x"),
                ClojureRun.argv(shebang, "bb", "/w/deploy", List.of(ClojureRun.REPORT_TO_STDERR), List.of("x")),
                "Babashka has no JVM to take options");
        assertEquals(
                ClojureRun.Tool.BB,
                ClojureRun.plan(Path.of("/w/t.bb"), "(println 1)", files()).tool());
        ClojureRun.Plan project = ClojureRun.plan(Path.of("/b/src/x.clj"), "(println 1)", files("/b/bb.edn"));
        assertEquals(ClojureRun.Tool.BB, project.tool());
        assertEquals(Path.of("/b"), project.directory());
        assertFalse(ClojureRun.debuggable(ClojureRun.Tool.BB));
        assertTrue(ClojureRun.debuggable(ClojureRun.Tool.CLI));
        assertNull(ClojureRun.jvmOptionPrefix(ClojureRun.Tool.BB));
        assertEquals("-J", ClojureRun.jvmOptionPrefix(ClojureRun.Tool.CLI));
    }

    @Test
    void theNearestProjectWins() {
        ClojureRun.Plan plan = ClojureRun.plan(
                Path.of("/mono/svc/src/a.clj"), "(println 1)", files("/mono/deps.edn", "/mono/svc/project.clj"));
        assertEquals(ClojureRun.Tool.LEIN, plan.tool());
        assertEquals(Path.of("/mono/svc"), plan.directory());
    }
}
