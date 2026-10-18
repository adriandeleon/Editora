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
    void clojuresRuntimeAndCoreAreSteppedOver() {
        List<String> filters = JvmSourceMap.stepFilters("clojure");
        assertTrue(filters.containsAll(List.of("java.*", "clojure.lang.*", "clojure.core*", "clojure.main*")));
        assertFalse(JvmSourceMap.stepFilters("groovy").contains("clojure.lang.*"));
    }

    @Test
    void aClojureFunctionsFrameReadsAsTheFunctionDemunged() {
        assertEquals("my-app.core/greet", JvmSourceMap.frameName("my_app.core$greet", "invokeStatic", "clojure"));
        assertEquals("my-app.core/-main", JvmSourceMap.frameName("my_app.core$_main", "doInvoke", "clojure"));
        assertEquals("app/valid?", JvmSourceMap.frameName("app$valid_QMARK_", "invoke", "clojure"));
        assertEquals("app/swap->!", JvmSourceMap.frameName("app$swap__GT__BANG_", "invoke", "clojure"));
        assertEquals("user/tally/fn--123", JvmSourceMap.frameName("user$tally$fn__123", "invoke", "clojure"));
        assertEquals("Numbers.divide", JvmSourceMap.frameName("clojure.lang.Numbers", "divide", "clojure"));
        assertEquals(
                "Report$Inner.invoke",
                JvmSourceMap.frameName("com.acme.Report$Inner", "invoke", "groovy"),
                "only a Clojure program's classes are demunged");
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
}
