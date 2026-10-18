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
        assertTrue(JvmSourceMap.compiledFrom("demo/Main.scala", main, "scala"), "package demo in a flat folder");
        assertFalse(JvmSourceMap.compiledFrom("demo/Main.scala", main, "groovy"));
        assertFalse(JvmSourceMap.compiledFrom("demo/Other.scala", main, "scala"));
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
        assertEquals("Util.twice", JvmSourceMap.frameName("demo.Util$", "twice", "scala"));
        assertEquals("Main.greet", JvmSourceMap.frameName("demo.Main$package$", "greet", "scala"));
        assertEquals(
                "Main.greet { lambda }", JvmSourceMap.frameName("demo.Main$package$", "greet$$anonfun$1", "scala"));
        assertEquals("Main.greet { lambda }", JvmSourceMap.frameName("demo.Main$", "$anonfun$greet$1", "scala"));
        assertEquals("Main { lambda }", JvmSourceMap.frameName("demo.Main$package$", "$anonfun$1", "scala"));
        assertEquals("hello", JvmSourceMap.frameName("hello$_", "<init>", "scala"), "a Scala CLI script's body");
        assertEquals("hello { lambda }", JvmSourceMap.frameName("hello$_", "$init$$$anonfun$1", "scala"));
        assertEquals("Report.run", JvmSourceMap.frameName("com.acme.Report", "run", "groovy"));
        assertTrue(JvmSourceMap.hiddenField("0bitmap$1", "scala"));
        assertFalse(JvmSourceMap.hiddenField("count", "scala"));
        assertFalse(JvmSourceMap.hiddenField("0bitmap$1", "groovy"));
    }

    @Test
    void aClosuresFrameIsNamedForWhatEnclosesIt() {
        assertEquals("Report.run", JvmSourceMap.frameName("com.acme.Report", "run"));
        assertEquals("Report.run { closure }", JvmSourceMap.frameName("com.acme.Report$_run_closure1", "doCall"));
        assertEquals("Report.total { closure }", JvmSourceMap.frameName("Report$_total_closure2$_closure3", "doCall"));
        assertEquals("Report$Inner.go", JvmSourceMap.frameName("com.acme.Report$Inner", "go"));
    }
}
