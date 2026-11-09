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
                List.of("java", "python", "javascript", "groovy", "kotlin", "clojure", "scala", "go", "rust", "cpp"),
                List.copyOf(DebugLanguages.ids()));
        assertEquals(DebugLanguages.ids(), DapServerRegistry.languageIdsForDebug());
        for (String ts : List.of("typescript", "javascriptreact", "typescriptreact", "javascript")) {
            assertSame(DebugLanguages.JAVASCRIPT, DebugLanguages.forLanguage(ts), ts);
        }
        assertNull(DebugLanguages.byId("typescript"), "an editor language is not an adapter language");
        assertSame(DebugLanguages.GO, DebugLanguages.forLanguage("go"));
        assertSame(DebugLanguages.RUST, DebugLanguages.forLanguage("rust"));
        assertNull(DebugLanguages.forLanguage("zig"));
        assertNull(DebugLanguages.forLanguage(null));
        assertNull(DebugLanguages.byId(null));
        for (DebugLanguage l : DebugLanguages.all()) {
            assertSame(l, DebugLanguages.byId(l.id()));
            assertTrue(l.editorLanguages().contains(l.id()), l.id());
            assertEquals(
                    l == DebugLanguages.GO || l == DebugLanguages.RUST || l == DebugLanguages.CPP,
                    l.external() != null,
                    l.id() + ": Go's, Rust's and C/C++'s adapters are started by the generic path");
        }
    }

    @Test
    void howEachIsReachedAndWhatMakesItAvailable() {
        assertEquals(DebugLanguage.Availability.JAVA_DEBUG_PLUGIN, DebugLanguages.JAVA.availability());
        assertEquals(DebugLanguage.Availability.LOCATED, DebugLanguages.GO.availability());
        assertEquals(DapServerRegistry.Kind.EXTERNAL, DapServerRegistry.kindFor("go"));
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
        assertEquals("debug.error.goUnavailable", DebugLanguages.GO.unavailableKey());
    }

    @Test
    void goIsDelvesDapServerOnAPortItAnnounces() {
        ExternalAdapter dlv = DebugLanguages.GO.external();
        assertEquals(ExternalAdapter.Transport.ANNOUNCED_PORT, dlv.transport());
        assertEquals(List.of("/home/u/go/bin/dlv", "dap", "--listen=127.0.0.1:0"), dlv.argv("/home/u/go/bin/dlv", -1));
        assertEquals(46317, dlv.announcedPort("DAP server listening at: 127.0.0.1:46317"), "as Delve 1.27 prints it");
        assertEquals(List.of(), ExceptionFilterCatalog.known("go"), "Delve stops on a panic by itself");
        assertEquals("go", DebugLanguages.GO.launchType());
    }

    @Test
    void rustIsCodelldbOnAPortItIsGivenAndStopsOnAPanic() {
        ExternalAdapter codelldb = DebugLanguages.RUST.external();
        assertEquals(ExternalAdapter.Transport.PORT_ARGUMENT, codelldb.transport());
        assertEquals(List.of("/x/codelldb", "--port", "4711"), codelldb.argv("/x/codelldb", 4711));
        assertEquals("lldb", DebugLanguages.RUST.launchType());
        assertEquals(
                List.of(DebugLanguages.RUST_PANIC_FILTER),
                ExceptionFilterCatalog.known("rust").stream()
                        .filter(DapModels.ExceptionFilterOption::byDefault)
                        .map(DapModels.ExceptionFilterOption::filter)
                        .toList(),
                "a panic stops, as in VS Code");
        assertEquals(List.of(DebugLanguages.RUST_PANIC_FILTER), ExternalAdapterFilters.defaults(DebugLanguages.RUST));
        assertEquals(List.of(), ExternalAdapterFilters.defaults(DebugLanguages.GO));
        assertFalse(DapManager.debuggableOnHost("rust"));
    }

    @Test
    void cAndCppAreOneProfileOnRustsCodelldbAndStopOnAThrow() {
        assertSame(DebugLanguages.CPP, DebugLanguages.forLanguage("c"));
        assertSame(DebugLanguages.CPP, DebugLanguages.forLanguage("cpp"));
        assertEquals("cpp", DapServerRegistry.adapterLanguage("c"));
        assertSame(DebugLanguages.CODELLDB, DebugLanguages.CPP.external());
        assertSame(DebugLanguages.CODELLDB, DebugLanguages.RUST.external(), "one CodeLLDB, found once");
        assertEquals(List.of(DebugLanguages.CPP_THROW_FILTER), ExternalAdapterFilters.defaults(DebugLanguages.CPP));
        assertFalse(DapManager.debuggableOnHost("cpp"));
    }
}
