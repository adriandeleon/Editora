package com.editora.build;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClojureProjectsTest {

    private static final String DEPS = """
            {:paths ["src" "resources"]
             :deps {org.clojure/clojure {:mvn/version "1.12.3"}}
             ;; {:aliases {:commented {:main-opts []}}}
             :aliases
             {:test {:extra-paths ["test"]
                     :extra-deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
                     :main-opts ["-m" "cognitect.test-runner"]}
              :build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.10"}}
                      :ns-default build}
              :run-x {:exec-fn my-app.core/greet :exec-args {:name "x {y}"}}
              #_:ignored #_{:main-opts []}
              :dev {:extra-paths ["dev"]}
              :neil ^:tool {:main-opts ["-m" "babashka.neil"]}}}
            """;

    @Test
    void aliasesThatRunSomethingAreListedWithTheirFlag() {
        assertEquals(
                List.of(
                        new ClojureProjects.Alias("test", ClojureProjects.AliasKind.MAIN),
                        new ClojureProjects.Alias("build", ClojureProjects.AliasKind.TOOL),
                        new ClojureProjects.Alias("run-x", ClojureProjects.AliasKind.EXEC),
                        new ClojureProjects.Alias("neil", ClojureProjects.AliasKind.MAIN)),
                ClojureProjects.aliases(DEPS),
                ":dev only adds a path");
        assertEquals(List.of(), ClojureProjects.aliases("{:paths [\"src\"]}"));
        assertEquals(List.of(), ClojureProjects.aliases("{:aliases {:test {:main-opts ["), "mid-edit");
        assertEquals(List.of(), ClojureProjects.aliases(null));
    }

    @Test
    void babashkaTasksAreTheSymbolKeys() {
        String bb = """
                {:paths ["src"]
                 :tasks {:requires ([babashka.fs :as fs])
                         :init (def target "target")
                         clean (fs/delete-tree target)
                         -helper (println "private")
                         test {:doc "Run the tests" :task (shell "clojure -M:test")}
                         build:uberjar {:depends [clean] :task (println "#{not a set}")}}}
                """;
        assertEquals(List.of("clean", "test", "build:uberjar"), ClojureProjects.babashkaTasks(bb));
        assertEquals(List.of(), ClojureProjects.babashkaTasks("{:paths [\"src\"]}"));
    }

    @Test
    void aLeiningenProjectIsNamedByItsDefproject() {
        assertEquals("my-app", ClojureProjects.leiningenName("(defproject my-org/my-app \"0.1.0\"\n :deps [])"));
        assertEquals("tiny", ClojureProjects.leiningenName("(defproject tiny \"1\")"));
        assertNull(ClojureProjects.leiningenName("(ns not-a-project)"));
    }

    @Test
    void theProvidersRunAliasesTasksAndTheToolsOwnCommands() {
        List<BuildAction.Section> cli = new ClojureCliActionsProvider(ClojureProjects.aliases(DEPS)).sections(Set.of());
        assertEquals(2, cli.size());
        BuildAction.Task test = (BuildAction.Task) cli.get(0).rows().get(0);
        assertEquals(List.of("-M:test"), test.args());
        assertEquals(
                List.of("-X:deps", "tree"),
                ((BuildAction.Task) cli.get(1).rows().get(1)).args());
        assertEquals(
                1, new ClojureCliActionsProvider(List.of()).sections(Set.of()).size(), "no aliases section");

        List<BuildAction.Section> bb = new BabashkaActionsProvider(List.of("test")).sections(Set.of());
        assertEquals(
                List.of("run", "test"), ((BuildAction.Task) bb.get(0).rows().get(0)).args());

        List<BuildAction.Section> lein = new LeiningenActionsProvider().sections(Set.of());
        assertEquals(List.of("run"), ((BuildAction.Task) lein.get(0).rows().get(0)).args());
    }
}
