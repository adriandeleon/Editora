package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepFiltersTest {

    @Test
    void aLanguageHasItsOwnFiltersAndEveryLanguagesThatAreOn() {
        List<StepFilter> all = List.of(
                new StepFilter(true, "python", "requests"),
                new StepFilter(true, "", "**/vendor/**"),
                new StepFilter(false, "python", "urllib3"),
                new StepFilter(true, "go", "fmt.*"),
                new StepFilter(true, "python", " "));
        assertEquals(List.of("requests", "**/vendor/**"), StepFilters.patterns(all, "python"));
        assertEquals(List.of("**/vendor/**", "fmt.*"), StepFilters.patterns(all, "go"));
    }

    @Test
    void aPatternIsAFileWhenItSaysSo() {
        assertTrue(StepFilters.pathLike("python", "**/vendor/**"));
        assertTrue(StepFilters.pathLike("python", "util.py"));
        assertTrue(StepFilters.pathLike("javascript", "lib/helper.js"));
        assertTrue(StepFilters.pathLike("javascript", "lib/legacy"));
        assertTrue(StepFilters.pathLike("cpp", "/usr/include/*"));
        assertFalse(StepFilters.pathLike("java", "com.shop.util.*"));
        assertFalse(StepFilters.pathLike("python", "requests"));
        assertFalse(StepFilters.pathLike("go", "github.com/x/y.*"), "Go's functions are named by import path");
        assertFalse(StepFilters.pathLike("cpp", "std::*"));
    }

    @Test
    void eachDebuggerThatSkipsCodeIsToldAtLaunch() {
        Map<String, Object> python = StepFilters.decorate(
                "python",
                Map.of("program", "m.py", "rules", List.of(Map.of("module", "x", "include", true))),
                List.of("requests.*", "util.py"));
        assertEquals(
                List.of(
                        Map.of("module", "x", "include", true),
                        Map.of("module", "requests", "include", false),
                        Map.of("path", "**/util.py", "include", false)),
                python.get("rules"));
        assertEquals("m.py", python.get("program"));

        Map<String, Object> js = StepFilters.decorate(
                "javascript", Map.of("skipFiles", List.of(JsLaunch.NODE_INTERNALS)), List.of("lodash", "lib/*.js"));
        assertEquals(List.of(JsLaunch.NODE_INTERNALS, "**/node_modules/lodash/**", "**/lib/*.js"), js.get("skipFiles"));

        assertEquals(
                List.of("com.shop.util.*"),
                StepFilters.decorate("kotlin", Map.of(), List.of("com.shop.util.*", "**/gen/**"))
                        .get("skipClasses"));

        Map<String, Object> go = Map.of("mode", "debug");
        assertSame(go, StepFilters.decorate("go", go, List.of("fmt.*")), "Delve has no such option");
        assertSame(go, StepFilters.decorate("python", go, List.of()), "nothing to add");
    }

    @Test
    void javaDebugSkipsTheUsersClassesBesideTheJdks() {
        String json = JavaDebugSettings.defaults().toJson(List.of("com.shop.util.*"));
        assertTrue(json.contains("\"skipClasses\":[\"java.*\""), json);
        assertTrue(json.contains("\"com.shop.util.*\"]"), json);
        String off = new JavaDebugSettings(false, true, List.of(), true, false).toJson(List.of("com.shop.*"));
        assertTrue(off.contains("\"skipClasses\":[\"com.shop.*\"]"), "the user's even with the JDK's off: " + off);
    }

    @Test
    void aFrameIsMatchedByItsFunctionItsPackageOrItsFile() {
        assertTrue(StepFilters.matches("go", "fmt.*", "fmt.Println", "/usr/lib/go/src/fmt/print.go"));
        assertFalse(StepFilters.matches("go", "fmt.*", "main.main", "/app/main.go"));
        assertTrue(StepFilters.matches("cpp", "std::*", "std::vector<int>::push_back(int const&)", null));
        assertTrue(StepFilters.matches(
                "java", "com.shop.util.*", "Strings.pad(int)", "/p/src/com/shop/util/Strings.java"));
        assertFalse(StepFilters.matches("java", "com.shop.util.*", "Main.main(String[])", "/p/src/com/shop/Main.java"));
        assertTrue(StepFilters.matches("python", "helper", "shout", "/app/helper.py"), "a module: its file");
        assertTrue(StepFilters.matches("python", "pkg", "f", "/app/pkg/inner.py"), "a package: its folder");
        assertFalse(StepFilters.matches("python", "helper", "shout", "/app/helpers.py"));
        assertTrue(StepFilters.matches("javascript", "**/node_modules/**", "map", "/app/node_modules/lodash/map.js"));
        assertTrue(StepFilters.matches("javascript", "helper.js", "shout", "/app/lib/helper.js"));
        assertFalse(StepFilters.matches("javascript", "helper.js", "shout", null), "a file pattern needs a file");
        assertTrue(StepFilters.matches("rust", "core::*", "core::fmt::write", null));
    }

    @Test
    void aSourceRootFolderIsNotTakenForALibrarysPackage() {
        assertFalse(StepFilters.matches("kotlin", "kotlin.*", "MainKt.main", "/p/src/main/kotlin/com/shop/Main.kt"));
        assertFalse(
                StepFilters.matches("java", "java.*", "Main.main(String[])", "/p/src/main/java/com/shop/Main.java"));
        assertFalse(StepFilters.matches("scala", "scala.*", "Main$.main", "/p/src/test/scala/com/shop/Main.scala"));
        assertFalse(StepFilters.matches("kotlin", "kotlin.*", "AppKt.run", "/p/src/jvmMain/kotlin/App.kt"));
        assertFalse(StepFilters.matches("java", "java.*", "Main.main(String[])", "/home/me/src/java/demo/Main.java"));
        assertTrue(StepFilters.matches(
                "kotlin", "kotlin.*", "CollectionsKt.map", "/cache/kotlin-stdlib-sources/kotlin/collections/Maps.kt"));
        assertTrue(StepFilters.matches(
                "java", "java.*", "ArrayList.add(Object)", "/jdk/lib/src/java.base/java/util/ArrayList.java"));
        assertTrue(StepFilters.matches(
                "kotlin", "com.shop.util.*", "Strings.pad", "/p/src/main/kotlin/com/shop/util/Strings.kt"));
    }

    @Test
    void theCodeOfAFrameIsSuggestedAsItsLanguageNamesIt() {
        assertEquals(
                "com.shop.util.Strings",
                StepFilters.suggest(
                        "java",
                        "Strings.pad(int)",
                        "/p/src/com/shop/util/Strings.java",
                        StepFilters.sourcePackage("// x\npackage com.shop.util;\nclass Strings {}")));
        assertEquals("Strings", StepFilters.suggest("java", "Strings.pad(int)", "/p/Strings.java", ""));
        assertEquals("**/helper.py", StepFilters.suggest("python", "shout", "/app/helper.py", ""));
        assertEquals(
                "**/node_modules/@scope/pkg/**",
                StepFilters.suggest("javascript", "f", "/app/node_modules/@scope/pkg/lib/f.js", ""));
        assertEquals("**/helper.js", StepFilters.suggest("javascript", "shout", "/app/helper.js", ""));
        assertEquals("fmt.*", StepFilters.suggest("go", "fmt.Println", "/go/src/fmt/print.go", ""));
        assertEquals("github.com/x/y.*", StepFilters.suggest("go", "github.com/x/y.(*T).Do", "/m/y/t.go", ""));
        assertEquals("std::*", StepFilters.suggest("cpp", "std::vector<int>::push_back", "/usr/include/v", ""));
        assertEquals("core::*", StepFilters.suggest("rust", "core::fmt::write", null, ""));
        assertEquals("Shop.Util.*", StepFilters.suggest("csharp", "Shop.Util.Format", null, ""));
    }

    @Test
    void aFilterIsKeptAsItsFieldsAndReadBack() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        StepFilter f = new StepFilter(false, " python ", " requests ");
        StepFilter back = json.readValue(json.writeValueAsString(f), StepFilter.class);
        assertEquals(new StepFilter(false, "python", "requests"), back);
        assertEquals(f, f.copy());
    }
}
