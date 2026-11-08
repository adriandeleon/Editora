package com.editora.dap;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language profiles answer what the debugger's language switches answered before they were moved into them:
 * every value is pinned here as those switches had it.
 */
class DebugLanguagesTest {

    @Test
    void everyLanguageAndWhatServesIt() {
        assertEquals(
                List.of("java", "python", "javascript", "groovy", "kotlin", "clojure", "scala"),
                List.copyOf(DebugLanguages.ids()));
        assertEquals(DebugLanguages.ids(), DapServerRegistry.languageIdsForDebug());
        for (String ts : List.of("typescript", "javascriptreact", "typescriptreact", "javascript")) {
            assertSame(DebugLanguages.JAVASCRIPT, DebugLanguages.forLanguage(ts), ts);
        }
        assertNull(DebugLanguages.byId("typescript"), "an editor language is not an adapter language");
        assertNull(DebugLanguages.forLanguage("go"));
        assertNull(DebugLanguages.forLanguage(null));
        assertNull(DebugLanguages.byId(null));
        for (DebugLanguage l : DebugLanguages.all()) {
            assertSame(l, DebugLanguages.byId(l.id()));
            assertTrue(l.editorLanguages().contains(l.id()), l.id());
            assertNull(l.external(), "none of today's adapters is started by the generic path");
        }
    }

    @Test
    void howEachIsReachedAndWhatMakesItAvailable() {
        assertEquals(DebugLanguage.Availability.JAVA_DEBUG_PLUGIN, DebugLanguages.JAVA.availability());
        assertEquals(DebugLanguage.Availability.LOCATED, DebugLanguages.PYTHON.availability());
        assertEquals(DebugLanguage.Availability.LOCATED, DebugLanguages.JAVASCRIPT.availability());
        for (DebugLanguage l :
                List.of(DebugLanguages.GROOVY, DebugLanguages.KOTLIN, DebugLanguages.CLOJURE, DebugLanguages.SCALA)) {
            assertEquals(DebugLanguage.Availability.BUILT_IN, l.availability(), l.id());
        }
        assertEquals(DapServerRegistry.Kind.JVM, DapServerRegistry.fallbackKind("scala"));
        assertNull(DapServerRegistry.fallbackKind("kotlin"));
        assertEquals("debug-adapter-start", HostedDebugAdapter.startCommand("scala"));
        assertNull(HostedDebugAdapter.startCommand("java"));
        assertNull(HostedDebugAdapter.startCommand("rust"));
    }

    @Test
    void whichAreDebuggedOnARemoteHost() {
        for (String yes : List.of("java", "python", "javascript", "typescript", "typescriptreact")) {
            assertTrue(DapManager.debuggableOnHost(yes), yes);
        }
        for (String no : List.of("kotlin", "groovy", "clojure", "scala", "go", "")) {
            assertFalse(DapManager.debuggableOnHost(no), no);
        }
    }

    @Test
    void theExceptionStopsEachOffers() {
        assertEquals(
                List.of("raised", "uncaught", "userUnhandled"),
                ExceptionFilterCatalog.known("python").stream()
                        .map(DapModels.ExceptionFilterOption::filter)
                        .toList());
        assertEquals(
                List.of("all", "uncaught"),
                ExceptionFilterCatalog.known("javascript").stream()
                        .map(DapModels.ExceptionFilterOption::filter)
                        .toList());
        assertEquals(List.of(), ExceptionFilterCatalog.known("typescript"), "asked by adapter language");
        for (String jvm : List.of("groovy", "kotlin", "clojure", "scala")) {
            assertEquals(
                    List.of(true),
                    ExceptionFilterCatalog.known(jvm).stream()
                            .filter(o -> "uncaught".equals(o.filter()))
                            .map(DapModels.ExceptionFilterOption::byDefault)
                            .toList(),
                    jvm + ": an uncaught exception stops");
        }
        assertEquals(
                Set.of(false),
                Set.copyOf(ExceptionFilterCatalog.known("java").stream()
                        .map(DapModels.ExceptionFilterOption::byDefault)
                        .toList()));
        assertEquals("caught", ExceptionFilterCatalog.caughtFilter("kotlin"));
        assertEquals("raised", ExceptionFilterCatalog.caughtFilter("python"));
        assertEquals("all", ExceptionFilterCatalog.caughtFilter("javascript"));
        assertNull(ExceptionFilterCatalog.caughtFilter("go"));
        assertNull(ExceptionFilterCatalog.caughtFilter(null));
        assertEquals("uncaught", ExceptionFilterCatalog.uncaughtFilter("java"));
        assertNull(ExceptionFilterCatalog.uncaughtFilter("go"));
        assertEquals(List.of(), ExceptionFilterCatalog.known(null));
    }

    @Test
    void whatIsSaidWhenOneIsUnavailable() {
        assertEquals("debug.error.pythonUnavailable", DebugLanguages.PYTHON.unavailableKey());
        assertEquals("debug.error.jsUnavailable", DebugLanguages.JAVASCRIPT.unavailableKey());
        assertEquals("status.debug.unavailable", DebugLanguages.KOTLIN.unavailableKey(), "it said JavaScript's");
    }
}
