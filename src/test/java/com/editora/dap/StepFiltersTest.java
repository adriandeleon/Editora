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

    @Test
    void aNullOrRepeatedFilterIsSkippedAndTheLanguageMustMatch() {
        List<StepFilter> all = new java.util.ArrayList<>();
        all.add(null);
        all.add(new StepFilter(true, "", "dup"));
        all.add(new StepFilter(true, "go", "dup"));
        all.add(new StepFilter(true, "rust", "core::*"));
        assertEquals(List.of("dup"), StepFilters.patterns(all, "go"));
        assertEquals(List.of("dup"), StepFilters.patterns(all, null));
        StepFilter blank = new StepFilter();
        blank.setLanguage(null);
        blank.setPattern(null);
        assertEquals("", blank.getLanguage());
        assertEquals(":", blank.toString());
        assertEquals("(off) go:x", new StepFilter(false, "go", "x").toString());
        assertFalse(new StepFilter(true, "go", "x").equals("go:x"));
        assertEquals(new StepFilter(true, "go", "x").hashCode(), new StepFilter(true, " go", "x ").hashCode());
    }

    @Test
    void windowsPathsAndRootedGlobsAreFiles() {
        assertTrue(StepFilters.pathLike("python", "lib\\util"));
        assertTrue(StepFilters.pathLike("go", "/usr/lib/go/src/fmt"), "a rooted path is a file even in Go");
        assertTrue(StepFilters.pathLike("cpp", "vector.hpp"));
        assertFalse(StepFilters.pathLike("python", ".hidden"), "a leading dot is no extension");
        assertFalse(StepFilters.pathLike("java", "Main.Inner"));
        assertEquals("C:/lib/x.py", StepFilters.relativeGlob("C:\\lib\\x.py"));
        assertEquals("<node_internals>/**", StepFilters.relativeGlob("<node_internals>/**"));
        assertEquals("/abs/x.py", StepFilters.relativeGlob(" /abs/x.py "));
        assertEquals("**/x/**", StepFilters.relativeGlob("**/x/**"));
    }

    @Test
    void aLaunchOfALanguageWithoutStepOptionsOrOfNoLanguageIsLeftAlone() {
        Map<String, Object> launch = Map.of("program", "a");
        assertSame(launch, StepFilters.decorate(null, launch, List.of("x")));
        assertSame(launch, StepFilters.decorate("java", launch, List.of("x")), "java-debug takes them elsewhere");
        Map<String, Object> js = StepFilters.decorate(
                "typescript", Map.of("skipFiles", "not a list"), List.of("lodash", "lodash", "/abs/*.ts"));
        assertEquals(List.of("**/node_modules/lodash/**", "/abs/*.ts"), js.get("skipFiles"));
        Map<String, Object> py = StepFilters.decorate("python", Map.of(), List.of("requests"));
        assertEquals(List.of(Map.of("module", "requests", "include", false)), py.get("rules"));
        assertEquals(
                List.of("old", "a.B"),
                StepFilters.decorate("groovy", Map.of("skipClasses", List.of("old")), List.of("a.B", "x.groovy"))
                        .get("skipClasses"));
        assertEquals(List.of("a.*"), StepFilters.classPatterns("scala", List.of("a.*", "**/gen/**")));
    }

    @Test
    void globsMatchOneOrManyCharactersAndFoldersAsAPathOrANameDoes() {
        assertTrue(StepFilters.glob("a?c", false).matcher("ABC").matches(), "a name matches without case");
        assertTrue(StepFilters.glob("a?c", true).matcher("abc").matches());
        assertFalse(StepFilters.glob("a?c", true).matcher("a/c").matches(), "? is no folder separator in a path");
        assertTrue(StepFilters.glob("**/x.py", true).matcher("/a/b/x.py").matches());
        assertTrue(StepFilters.glob("**/x.py", true).matcher("x.py").matches(), "**/ may be no folder at all");
        assertTrue(StepFilters.glob("/a/**", true).matcher("/a/b/c.py").matches(), "** at the end crosses folders");
        assertFalse(StepFilters.glob("/a/*", true).matcher("/a/b/c.py").matches(), "* does not");
        assertTrue(StepFilters.glob("a*", false).matcher("a/b").matches(), "a name's * crosses everything");
        assertEquals("", StepFilters.sourcePackage(null));
        assertEquals("", StepFilters.sourcePackage("class A {}"));
        assertEquals("/com/x/A.kt", StepFilters.packagePart("/p/src/main/kotlin/com/x/A.kt"));
        assertEquals("/p/com/x/A.kt", StepFilters.packagePart("/p/com/x/A.kt"));
    }

    @Test
    void aFrameIsMatchedByItsBareNameOrNotAtAll() {
        assertFalse(StepFilters.matches("go", "  ", "fmt.Println", null), "a blank pattern filters nothing");
        assertTrue(
                StepFilters.matches("java", "Strings.pad", "Strings.pad(int)", null), "the name without its arguments");
        assertFalse(StepFilters.matches("java", "Strings.pad", null, null));
        assertFalse(StepFilters.matches("java", "Strings.pad", "", "/p/Other.java"));
        assertTrue(StepFilters.matches("java", "com.shop.*", " ", "C:\\p\\com\\shop\\A.java"), "by its package folder");
        assertTrue(StepFilters.matches("java", "com.shop.A", "x", "/p/com/shop/A.java"), "by its file");
        assertFalse(StepFilters.matches("cpp", "std::*", "main", "/p/main.cpp"), "not dotted: no folder look-up");
        assertTrue(StepFilters.matchesAny("go", List.of("os.*", "fmt.*"), "fmt.Println", null));
        assertFalse(StepFilters.matchesAny("go", List.of("os.*"), "fmt.Println", null));
        assertFalse(StepFilters.matchesAny("go", List.of(), "fmt.Println", null));
    }

    @Test
    void everyLanguagesSuggestionWithAndWithoutAFile() {
        assertEquals(
                "com.x.Strings", StepFilters.suggest("kotlin", "Strings.pad", null, "com.x"), "the class of the name");
        assertEquals("pad", StepFilters.suggest("scala", "pad", "", ""), "no class at all: the name");
        assertEquals("Main", StepFilters.suggest("groovy", "x", "/p/Main.groovy", "  "));
        assertEquals("Main", StepFilters.suggest("clojure", "Main", "/p/README", ""), "a file without extension");
        assertEquals("shout", StepFilters.suggest("python", " shout() ", null, ""));
        assertEquals(
                "**/node_modules/lodash/**",
                StepFilters.suggest("typescript", "f", "C:\\a\\node_modules\\lodash\\x.js", ""));
        assertEquals("**/node_modules/@scope/**", StepFilters.suggest("javascript", "f", "/a/node_modules/@scope", ""));
        assertEquals("f", StepFilters.suggest("javascript", "f", null, ""));
        assertEquals("main", StepFilters.suggest("go", "main", null, ""), "no package in the name");
        assertEquals("Format", StepFilters.suggest("csharp", "Format", null, ""));
        assertEquals("<lambda>::*", StepFilters.suggest("rust", "<lambda>::call", null, ""));
        assertEquals("**/main.cpp", StepFilters.suggest("cpp", "main", "/p/main.cpp", ""));
        assertEquals("main", StepFilters.suggest("cpp", "main", null, ""));
        assertEquals("**/m.rb", StepFilters.suggest("ruby", "go", "/p/m.rb", ""));
        assertEquals("go", StepFilters.suggest(null, "go", null, ""));
        assertEquals("", StepFilters.suggest("ruby", null, null, ""));
    }
}
