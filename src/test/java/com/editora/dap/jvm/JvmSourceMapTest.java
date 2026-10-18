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
