package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Clojure CLI, Leiningen and Babashka as build tools: their markers, what they parse, how they launch. */
class ClojureBuildToolsTest {

    @TempDir
    Path dir;

    @Test
    void theClojureToolsAreWiredInAfterGradle() {
        List<BuildTool> tools = BuildTool.enabled();
        assertEquals(
                List.of(BuildTool.CLOJURE, BuildTool.LEIN, BuildTool.BABASHKA),
                tools.subList(tools.indexOf(BuildTool.CLOJURE), tools.size()));
        assertTrue(tools.indexOf(BuildTool.CLOJURE) > tools.indexOf(BuildTool.GRADLE));
        assertEquals(List.of("deps.edn"), BuildTool.CLOJURE.markers());
        assertEquals(List.of("project.clj"), BuildTool.LEIN.markers());
        assertEquals(List.of("bb.edn"), BuildTool.BABASHKA.markers());
        assertEquals("view.toggleClojureCliSupport", BuildTool.CLOJURE.toggleCommandId());
        assertEquals("Leiningen", BuildTool.LEIN.displayName());
        assertEquals("bb", BuildTool.BABASHKA.commandExample());
    }

    @Test
    void aDepsEdnsRunnableAliasesBecomeTasks() throws Exception {
        Path project = Files.createDirectories(dir.resolve("my-app"));
        Files.writeString(
                project.resolve("deps.edn"),
                "﻿{:aliases {:test {:main-opts [\"-m\" \"cognitect.test-runner\"]} :dev {:extra-paths [\"dev\"]}}}");
        BuildTool.Detected d = BuildTool.CLOJURE.parse(project);
        assertEquals("my-app", d.label());
        BuildAction.Section aliases = d.provider().sections(Set.of()).get(0);
        assertEquals(List.of("-M:test"), ((BuildAction.Task) aliases.rows().get(0)).args());
        assertEquals(1, aliases.rows().size(), "a BOM is skipped, and :dev runs nothing");
    }

    @Test
    void aProjectCljIsNamedAndABbEdnsTasksAreListed() throws Exception {
        Files.writeString(dir.resolve("project.clj"), "(defproject org/lapp \"0.1.0\")");
        assertEquals("lapp", BuildTool.LEIN.parse(dir).label());
        Files.writeString(dir.resolve("bb.edn"), "{:tasks {test (shell \"clojure -M:test\")}}");
        BuildTool.Detected bb = BuildTool.BABASHKA.parse(dir);
        assertEquals(
                List.of("run", "test"),
                ((BuildAction.Task)
                                bb.provider().sections(Set.of()).get(0).rows().get(0))
                        .args());
    }

    @Test
    void eachRunsItsToolOrTheSettingsOverrideAndShipsNoWrapper() {
        assertEquals(List.of("clojure"), BuildTool.CLOJURE.executable(dir, false, ""));
        assertEquals(List.of("lein"), BuildTool.LEIN.executable(dir, false, " "));
        assertEquals(List.of("/opt/bb", "--debug"), BuildTool.BABASHKA.executable(dir, false, "/opt/bb --debug"));
        for (BuildTool t : List.of(BuildTool.CLOJURE, BuildTool.LEIN, BuildTool.BABASHKA)) {
            assertNull(t.repoWrapper(dir, false), t + " runs no repository script");
        }
    }

    @Test
    void theirSettingsAreOnByDefaultAndRoundTrip() {
        Settings s = new Settings();
        for (BuildTool t : List.of(BuildTool.CLOJURE, BuildTool.LEIN, BuildTool.BABASHKA)) {
            assertTrue(t.enabledIn(s), t.toString());
            assertEquals("", t.commandIn(s));
            t.setEnabledIn(s, false);
            t.setCommandIn(s, "/x/" + t.id());
            assertFalse(t.enabledIn(s));
            assertEquals("/x/" + t.id(), t.commandIn(s));
            t.setCommandIn(s, null);
            assertEquals("", t.commandIn(s));
        }
    }
}
