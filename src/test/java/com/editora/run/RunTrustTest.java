package com.editora.run;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunTrustTest {

    private static final String MAIN = "(ns app.core)\n\n(defn -main [& args]\n  (println args))\n";

    private static Path abs(String path) {
        return Path.of(path).toAbsolutePath();
    }

    private static Predicate<Path> files(String... paths) {
        Set<Path> set = new java.util.HashSet<>();
        for (String p : paths) {
            set.add(abs(p));
        }
        return set::contains;
    }

    @Test
    void aClojureFileOfAProjectPassesItsToolsGateInTheProjectFolder() {
        assertEquals(
                new RunTrust.Gate(BuildTool.LEIN, abs("/p")),
                RunTrust.forFile("clojure", abs("/p/src/app/core.clj"), MAIN, null, files("/p/project.clj")));
        assertEquals(
                new RunTrust.Gate(BuildTool.CLOJURE, abs("/p")),
                RunTrust.forFile(
                        "clojure", abs("/p/src/app/core.clj"), MAIN, null, files("/p/deps.edn", "/p/project.clj")));
        assertEquals(
                new RunTrust.Gate(BuildTool.BABASHKA, abs("/p")),
                RunTrust.forFile("clojure", abs("/p/script.clj"), "(println 1)\n", null, files("/p/bb.edn")));
    }

    @Test
    void aClojureFileOnItsOwnPassesNoGate() {
        assertNull(RunTrust.forFile("clojure", abs("/p/script.clj"), "(println 1)\n", null, files()));
        // A Babashka script in a deps.edn project runs as bb, which reads no deps.edn.
        assertNull(RunTrust.forFile("clojure", abs("/p/tool.bb"), "(println 1)\n", null, files("/p/deps.edn")));
    }

    @Test
    void aConfigurationsOwnFolderIsWhereTheToolReadsItsProject() {
        assertEquals(
                new RunTrust.Gate(BuildTool.CLOJURE, abs("/elsewhere")),
                RunTrust.clojure(
                        abs("/p/script.clj"), "(println 1)\n", abs("/elsewhere"), files("/elsewhere/deps.edn")));
        assertNull(RunTrust.clojure(abs("/p/src/app/core.clj"), MAIN, abs("/elsewhere"), files("/p/project.clj")));
        assertNull(RunTrust.clojure(null, "", null, files("/p/deps.edn")), "no file and no folder: nothing to read");
    }

    @Test
    void aScalaFileOfAScalaCliProjectPassesScalaClisGateAndOtherScalaFilesNone() {
        assertEquals(
                new RunTrust.Gate(BuildTool.SCALA_CLI, abs("/p")),
                RunTrust.forFile("scala", abs("/p/Hello.scala"), "", null, files("/p/project.scala")));
        assertNull(RunTrust.forFile("scala", abs("/p/Hello.scala"), "", null, files()), "a lone file");
        assertNull(
                RunTrust.forFile("scala", abs("/p/src/Hello.scala"), "", null, files("/p/build.sbt")),
                "sbt runs it through its own tasks window, which asks itself");
    }

    @Test
    void otherLanguagesPassNoGateAndAnNpmScriptAlwaysDoes() {
        assertNull(RunTrust.forFile("groovy", abs("/p/x.groovy"), "", null, files("/p/deps.edn")));
        assertNull(RunTrust.forFile("python", abs("/p/x.py"), "", null, files("/p/package.json")));
        assertEquals(new RunTrust.Gate(BuildTool.NPM, abs("/p")), RunTrust.npm(abs("/p")));
    }
}
