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
}
