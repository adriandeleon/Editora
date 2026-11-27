package com.editora.dap.jvm;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmSourceMapTest {

    private static final Path SCRIPT = Path.of("/work/src/main/groovy/com/acme/Report.groovy");

    @Test
    void aClassBelongsToTheFileItsSourcePathEnds() {
        assertTrue(JvmSourceMap.compiledFrom("com/acme/Report.groovy", SCRIPT));
        assertTrue(JvmSourceMap.compiledFrom("com\\acme\\Report.groovy", SCRIPT), "a Windows-built class file");
        assertTrue(JvmSourceMap.compiledFrom("Report.groovy", SCRIPT), "the default package: by name");
        assertFalse(JvmSourceMap.compiledFrom("org/acme/Report.groovy", SCRIPT), "another package");
        assertFalse(JvmSourceMap.compiledFrom("com/acme/Report.java", SCRIPT));
        assertFalse(JvmSourceMap.compiledFrom("a/b/c/d/e/f/g/h/Report.groovy", Path.of("/Report.groovy")));
        assertFalse(JvmSourceMap.compiledFrom("", SCRIPT));
        assertFalse(JvmSourceMap.compiledFrom(null, SCRIPT));
        assertEquals("Report.groovy", JvmSourceMap.sourceName(SCRIPT));
    }

    @Test
    void aFramesFileIsABreakpointFileElseUnderASourceRoot() {
        Path other = Path.of("/work/src/main/groovy/com/acme/Util.groovy");
        Set<Path> onDisk = Set.of(other);
        assertEquals(
                SCRIPT, JvmSourceMap.resolve("com/acme/Report.groovy", List.of(SCRIPT), List.of(), onDisk::contains));
        assertEquals(
                other,
                JvmSourceMap.resolve(
                        "com/acme/Util.groovy",
                        List.of(SCRIPT),
                        List.of(Path.of("/elsewhere"), Path.of("/work/src/main/groovy")),
                        onDisk::contains));
        assertNull(
                JvmSourceMap.resolve(
                        "groovy/lang/Closure.java", List.of(SCRIPT), List.of(Path.of("/work")), p -> false),
                "a library class has no source here");
        assertNull(JvmSourceMap.resolve(null, List.of(SCRIPT), List.of(), p -> true));
    }

    @Test
    void groovysRuntimeAndParserAreSteppedOver() {
        List<String> filters = JvmSourceMap.stepFilters("groovy");
        assertTrue(filters.containsAll(List.of("java.*", "org.codehaus.groovy.*", "groovy.lang.*", "groovyjarjar*")));
        assertFalse(JvmSourceMap.stepFilters("java").contains("groovyjarjar*"));
    }

    @Test
    void scalaClassesMatchByFileNameSinceItsPackagesNeedNotFollowFolders() {
        Path main = Path.of("/work/app/Main.scala");
        assertTrue(JvmSourceMap.compiledFrom("demo/Main.scala", main, null, "scala"), "package demo in a flat folder");
        assertFalse(JvmSourceMap.compiledFrom("demo/Main.scala", main, null, "groovy"));
        assertFalse(JvmSourceMap.compiledFrom("demo/Other.scala", main, null, "scala"));
        assertEquals(main, JvmSourceMap.resolve("demo/Main.scala", List.of(main), List.of(), p -> false, "scala"));
        assertEquals(
                Path.of("/work/app/Util.scala"),
                JvmSourceMap.resolve(
                        "demo/Util.scala",
                        List.of(main),
                        List.of(Path.of("/work/app")),
                        Path.of("/work/app/Util.scala")::equals,
                        "scala"),
                "a frame in a file without breakpoints is found in the program's folder");
    }

    @Test
    void scalasStandardLibraryIsSteppedOver() {
        List<String> filters = JvmSourceMap.stepFilters("scala");
        assertTrue(filters.containsAll(List.of("java.*", "scala.runtime.*", "scala.collection.*", "scala.Predef*")));
        assertFalse(JvmSourceMap.stepFilters("groovy").contains("scala.runtime.*"));
    }

    @Test
    void scalaFramesAreNamedAsTheSourceNamesThem() {
        assertEquals("Util.twice", JvmSourceMap.frameName("demo.Util$", "twice", null, "scala"));
        assertEquals("Main.greet", JvmSourceMap.frameName("demo.Main$package$", "greet", null, "scala"));
        assertEquals(
                "Main.greet { lambda }",
                JvmSourceMap.frameName("demo.Main$package$", "greet$$anonfun$1", null, "scala"));
        assertEquals("Main.greet { lambda }", JvmSourceMap.frameName("demo.Main$", "$anonfun$greet$1", null, "scala"));
        assertEquals("Main { lambda }", JvmSourceMap.frameName("demo.Main$package$", "$anonfun$1", null, "scala"));
        assertEquals("hello", JvmSourceMap.frameName("hello$_", "<init>", null, "scala"), "a Scala CLI script's body");
        assertEquals("hello { lambda }", JvmSourceMap.frameName("hello$_", "$init$$$anonfun$1", null, "scala"));
        assertEquals("Report.run", JvmSourceMap.frameName("com.acme.Report", "run", null, "groovy"));
        assertTrue(JvmSourceMap.hiddenField("0bitmap$1", "scala"));
        assertFalse(JvmSourceMap.hiddenField("count", "scala"));
        assertFalse(JvmSourceMap.hiddenField("0bitmap$1", "groovy"));
    }

    @Test
    void clojuresRuntimeAndCoreAreSteppedOver() {
        List<String> filters = JvmSourceMap.stepFilters("clojure");
        assertTrue(filters.containsAll(List.of("java.*", "clojure.lang.*", "clojure.core*", "clojure.main*")));
        assertFalse(JvmSourceMap.stepFilters("groovy").contains("clojure.lang.*"));
    }

    @Test
    void aClojureFunctionsFrameReadsAsTheFunctionDemunged() {
        assertEquals("my-app.core/greet", JvmSourceMap.frameName("my_app.core$greet", "invokeStatic", "core.clj"));
        assertEquals("my-app.core/-main", JvmSourceMap.frameName("my_app.core$_main", "doInvoke", "core.clj"));
        assertEquals("app/valid?", JvmSourceMap.frameName("app$valid_QMARK_", "invoke", "app.clj"));
        assertEquals("app/swap->!", JvmSourceMap.frameName("app$swap__GT__BANG_", "invoke", "app.clj"));
        assertEquals("user/tally/fn--123", JvmSourceMap.frameName("user$tally$fn__123", "invoke", "app.clj"));
        assertEquals("Numbers.divide", JvmSourceMap.frameName("clojure.lang.Numbers", "divide", "Numbers.java"));
        assertEquals(
                "Report$Inner.invoke",
                JvmSourceMap.frameName("com.acme.Report$Inner", "invoke", "Report.groovy"),
                "only a Clojure file's classes are demunged");
    }

    @Test
    void aScriptsAbsoluteSourcePathIsTheFileItself() {
        Path script = Path.of("/work/tally.clj").toAbsolutePath();
        assertTrue(JvmSourceMap.compiledFrom(script.toString(), script));
        assertFalse(JvmSourceMap.compiledFrom(
                Path.of("/other/tally.clj").toAbsolutePath().toString(), script));
        assertTrue(JvmSourceMap.isAbsolute("C:\\work\\tally.clj"));
        assertTrue(JvmSourceMap.isAbsolute("/work/tally.clj"));
        assertFalse(JvmSourceMap.isAbsolute("my_app/core.clj"));
    }

    @Test
    void aClosuresFrameIsNamedForWhatEnclosesIt() {
        assertEquals("Report.run", JvmSourceMap.frameName("com.acme.Report", "run"));
        assertEquals("Report.run { closure }", JvmSourceMap.frameName("com.acme.Report$_run_closure1", "doCall"));
        assertEquals("Report.total { closure }", JvmSourceMap.frameName("Report$_total_closure2$_closure3", "doCall"));
        assertEquals("Report$Inner.go", JvmSourceMap.frameName("com.acme.Report$Inner", "go"));
    }

    @Test
    void aKotlinFileMatchesItsDeclaredPackageWhereverItSits() {
        Path app = Path.of("/work/src/App.kt");
        assertFalse(JvmSourceMap.compiledFrom("com/acme/App.kt", app), "by folder alone it does not");
        assertTrue(JvmSourceMap.compiledFrom("com/acme/App.kt", app, "com.acme"), "by its package line it does");
        assertFalse(JvmSourceMap.compiledFrom("org/acme/App.kt", app, "com.acme"));
        assertFalse(JvmSourceMap.compiledFrom("com/acme/Other.kt", app, "com.acme"));
        assertTrue(JvmSourceMap.compiledFrom("App.kt", app, ""), "the default package");
        assertEquals(
                app, JvmSourceMap.resolve("com/acme/App.kt", List.of(app), f -> "com.acme", List.of(), p -> false));
    }

    @Test
    void thePackageIsReadFromTheHeader() {
        assertEquals("com.acme", JvmSourceMap.declaredPackage("package com.acme\n\nfun main() {}\n"));
        assertEquals("com.acme.app", JvmSourceMap.declaredPackage("package com.acme.app;\nclass A {}\n"));
        assertEquals(
                "a.b",
                JvmSourceMap.declaredPackage(
                        "#!/usr/bin/env kotlin\n/*\n * Licence\n */\n@file:JvmName(\"Tools\")\n// note\npackage a.b\n"));
        assertEquals("", JvmSourceMap.declaredPackage("import x.y\nfun main() {}\npackage not.this\n"));
        assertEquals("", JvmSourceMap.declaredPackage("println(1)\n"));
        assertEquals("", JvmSourceMap.declaredPackage(null));
    }

    @Test
    void kotlinsRuntimeIsSteppedOverAndItsFramesReadAsKotlin() {
        List<String> filters = JvmSourceMap.stepFilters("kotlin");
        assertTrue(filters.containsAll(List.of("java.*", "kotlin.*", "kotlinx.coroutines.*")));
        assertFalse(JvmSourceMap.stepFilters("groovy").contains("kotlin.*"));
        assertEquals("Hello.main", JvmSourceMap.frameName("demo.HelloKt", "main", "Hello.kt"));
        assertEquals("Hello.greet", JvmSourceMap.frameName("demo.HelloKt", "greet$default", "Hello.kt"));
        assertEquals("Hello.main { lambda }", JvmSourceMap.frameName("demo.HelloKt", "main$lambda$0", "Hello.kt"));
        assertEquals("Hello.main { lambda }", JvmSourceMap.frameName("demo.HelloKt$main$1", "invoke", "Hello.kt"));
        assertEquals(
                "Hello.load { lambda }", JvmSourceMap.frameName("demo.HelloKt$load$2", "invokeSuspend", "Hello.kt"));
        assertEquals("Greeter.hi", JvmSourceMap.frameName("demo.Greeter", "hi", "Hello.kt"), "a class keeps its name");
        assertEquals("Tools.run", JvmSourceMap.frameName("Tools", "run", "Hello.kt"), "@file:JvmName");
        assertEquals("UtilKt.go", JvmSourceMap.frameName("UtilKt", "go"), "no source name: as it is");
    }

    @Test
    void aKotlinFileReadsAsItsNameWhateverTheFacadeKotlincMadeOfIt() {
        assertEquals("hello.main", JvmSourceMap.frameName("demo.HelloKt", "main", "hello.kt"), "capitalised");
        assertEquals("hello.main { lambda }", JvmSourceMap.frameName("demo.HelloKt", "main$lambda$0", "hello.kt"));
        assertEquals("hello.main { lambda }", JvmSourceMap.frameName("demo.HelloKt$main$1", "invoke", "hello.kt"));
        assertEquals("my-file.run", JvmSourceMap.frameName("My_fileKt", "run", "my-file.kt"), "- becomes _");
        assertEquals("my-file.run { lambda }", JvmSourceMap.frameName("My_fileKt$run$1", "invoke", "my-file.kt"));
        assertEquals("1st.go", JvmSourceMap.frameName("_1stKt", "go", "1st.kt"), "a digit cannot start a class");
        assertEquals("Hello.main", JvmSourceMap.frameName("HelloKt", "main", "Hello.kt"), "already capitalised");
        assertEquals("helloKt.main", JvmSourceMap.frameName("helloKt", "main", "hello.kt"), "not the facade");
        assertEquals("Hello", JvmSourceMap.kotlinFacadePrefix("hello"));
        assertEquals("My_file_v2", JvmSourceMap.kotlinFacadePrefix("my-file.v2"));
        assertEquals("_", JvmSourceMap.kotlinFacadePrefix(""));
    }

    @Test
    void absolutePathsOnEveryPlatformAndFilesWithoutAName() {
        assertTrue(JvmSourceMap.isAbsolute("/work/x.clj"));
        assertTrue(JvmSourceMap.isAbsolute("\\\\server\\share\\x.clj"));
        assertTrue(JvmSourceMap.isAbsolute("C:\\work\\x.clj"));
        assertTrue(JvmSourceMap.isAbsolute("c:/work/x.clj"));
        assertFalse(JvmSourceMap.isAbsolute("C:x.clj"));
        assertFalse(JvmSourceMap.isAbsolute("1:/x"));
        assertFalse(JvmSourceMap.isAbsolute("C:"));
        assertFalse(JvmSourceMap.isAbsolute("a/b.clj"));
        assertEquals("", JvmSourceMap.sourceName(null));
        assertEquals("", JvmSourceMap.sourceName(Path.of("/")));
        assertFalse(JvmSourceMap.compiledFrom(null, Path.of("/a/B.java")));
        assertFalse(JvmSourceMap.compiledFrom(" ", Path.of("/a/B.java")));
        assertFalse(JvmSourceMap.compiledFrom("B.java", null));
        assertFalse(JvmSourceMap.compiledFrom("a/b/c/B.java", Path.of("/B.java")), "more folders than the file has");
        assertFalse(JvmSourceMap.compiledFrom("/work/\u0000.clj", Path.of("/work/x.clj")));
    }

    @Test
    void aPackageMatchNeedsAPackageAndAPath() {
        Path file = Path.of("/src/Main.kt");
        assertTrue(JvmSourceMap.compiledFrom("demo/Main.kt", file, " demo "));
        assertFalse(JvmSourceMap.compiledFrom("demo/Main.kt", file, null));
        assertFalse(JvmSourceMap.compiledFrom("demo/Main.kt", file, ""));
        assertFalse(JvmSourceMap.compiledFrom(null, file, "demo"));
        assertFalse(JvmSourceMap.compiledFrom(null, file, null, "scala"));
        assertFalse(JvmSourceMap.compiledFrom("demo/Main.kt", null, null, "scala"));
        assertTrue(
                JvmSourceMap.compiledFrom("x\\Main.kt", file, null, "Scala"), "by name where packages need not follow");
        assertTrue(JvmSourceMap.packagesFollowFolders(null));
    }

    @Test
    void resolvingWithoutKnownFilesOrRootsFindsWhatIsThere() {
        Set<Path> there = Set.of(Path.of("/r2/demo/Main.scala"), Path.of("/r3/Main.scala"), Path.of("/r2/a/B.java"));
        java.util.List<Path> roots = new java.util.ArrayList<>();
        roots.add(null);
        roots.add(Path.of("/r1"));
        roots.add(Path.of("/r2"));
        assertEquals(Path.of("/r2/a/B.java"), JvmSourceMap.resolve("a/B.java", null, roots, there::contains));
        assertNull(JvmSourceMap.resolve("a/C.java", List.of(), roots, there::contains));
        assertNull(JvmSourceMap.resolve("a/B.java", null, null, there::contains));
        assertNull(JvmSourceMap.resolve(" ", null, roots, there::contains));
        assertEquals(
                Path.of("/r2/demo/Main.scala"),
                JvmSourceMap.resolve("demo/Main.scala", null, roots, there::contains, "scala"));
        assertEquals(
                Path.of("/r3/Main.scala"),
                JvmSourceMap.resolve("demo/Main.scala", null, List.of(Path.of("/r3")), there::contains, "scala"),
                "a flat Scala project");
        assertNull(JvmSourceMap.resolve("demo/Nope.scala", null, roots, there::contains, "scala"));
        assertNull(JvmSourceMap.resolve("demo/Main.scala", null, null, there::contains, "scala"));
        assertEquals(
                Path.of("/r2/a/B.java"),
                JvmSourceMap.resolve("a/B.java", null, f -> "a", roots, there::contains),
                "no known files: the roots");
        assertNull(JvmSourceMap.resolve(null, List.of(Path.of("/k/B.java")), f -> "", roots, there::contains));
        assertEquals(
                Path.of("/k/a/B.java"),
                JvmSourceMap.resolve("a/B.java", List.of(Path.of("/k/a/B.java")), null, roots, f -> false, "java"),
                "no package look-up: matched by path only");
    }

    @Test
    void scalaFrameNamesOfEveryShape() {
        assertEquals("hello.main", JvmSourceMap.frameName("hello$_", "main", "hello.sc", "scala"));
        assertEquals("Main.run", JvmSourceMap.frameName("demo.Main$package$", "run", null, "scala"));
        assertEquals("Util { lambda }", JvmSourceMap.frameName("Util$", "$init$$$anonfun$1", null, "scala"));
        assertEquals("Util { lambda }", JvmSourceMap.frameName("Util", "<init>$$anonfun$1", null, "scala"));
        assertEquals("Util.greet { lambda }", JvmSourceMap.frameName("Util", "$anonfun$greet$1", null, "scala"));
        assertEquals("Util { lambda }", JvmSourceMap.frameName("Util", "$anonfun$1", null, "scala"));
        assertEquals("Util", JvmSourceMap.frameName("Util$", "<clinit>", null, "scala"));
        assertEquals("Util", JvmSourceMap.frameName("Util$", "<init>", null, "scala"));
        assertEquals(".", JvmSourceMap.frameName(null, null, null, "scala"));
        assertEquals("A.b", JvmSourceMap.frameName("x.A", "b", null, null));
        assertTrue(JvmSourceMap.hiddenField("bitmap$0", "Scala"));
        assertFalse(JvmSourceMap.hiddenField("bitmap$0", null));
        assertFalse(JvmSourceMap.hiddenField(null, "scala"));
    }

    @Test
    void clojureAndGroovyFrameNamesInTheirEdgeCases() {
        assertEquals("my-app.core/greet", JvmSourceMap.frameName("my_app.core$greet", "invoke", "core.cljc"));
        assertEquals("core$.invoke", JvmSourceMap.frameName("core$", "invoke", "core.clj"), "nothing after the $");
        assertEquals("core$f.other", JvmSourceMap.frameName("core$f", "other", "core.clj"), "not a function's method");
        assertEquals(".invoke", JvmSourceMap.frameName(null, "invoke", "core.clj"));
        assertEquals("A.", JvmSourceMap.frameName("A", null, "A.java"));
        assertEquals("Script.run { closure }", JvmSourceMap.frameName("Script$_run_closure1", "doCall", null));
        assertEquals("Script.x { closure }", JvmSourceMap.frameName("Script$_x", "doCall", null));
        assertEquals("Script$_x.call", JvmSourceMap.frameName("Script$_x", "call", null));
        assertEquals("Hello.main", JvmSourceMap.frameName("HelloKt", "main", "Hello.kts"));
    }

    @Test
    void stepFiltersOfALanguageWithoutItsOwn() {
        assertEquals(JvmSourceMap.stepFilters(null), JvmSourceMap.stepFilters("java"));
        assertTrue(JvmSourceMap.stepFilters("CLOJURE").contains("clojure.lang.*"));
    }

    @Test
    void theDeclaredPackageAfterCommentsThatEndOnTheirLine() {
        assertEquals("a.b", JvmSourceMap.declaredPackage("/* x */ package a.b\n"));
        assertEquals("a", JvmSourceMap.declaredPackage("/* x\n y */ package a;\n"));
        assertEquals("", JvmSourceMap.declaredPackage(null));
        assertEquals("", JvmSourceMap.declaredPackage("// nothing\n"));
    }
}
