package com.editora.dap;

import java.util.List;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The exception filters each adapter offers before it says so itself ({@link ExceptionFilterCatalog}): which ones
 * are on by default, which id the Caught and Uncaught toggles mean per language, and how a filter's label is shown.
 */
class ExceptionFilterCatalogTest {

    @BeforeAll
    static void messages() {
        Messages.init("en");
    }

    private static List<String> ids(String language) {
        return ExceptionFilterCatalog.known(language).stream()
                .map(o -> o.filter() + (o.byDefault() ? "*" : ""))
                .toList();
    }

    @Test
    void eachAdapterHasItsOwnFiltersAndDefaults() {
        assertEquals(List.of("raised", "uncaught*", "userUnhandled"), ids("python"), "debugpy stops on uncaught");
        assertEquals(List.of("caught", "uncaught"), ids("java"), "java-debug stops on none");
        assertEquals(List.of("all", "uncaught"), ids("javascript"), "js-debug stops on none");
        for (String jvm : List.of("groovy", "kotlin", "clojure", "scala")) {
            assertEquals(List.of("caught", "uncaught*"), ids(jvm), jvm + ": the JVM adapter stops on uncaught");
        }
        assertEquals(List.of(), ids("ruby"), "an adapter Editora does not know declares its own");
        assertEquals(List.of(), ids(null));
    }

    @Test
    void theCaughtAndUncaughtTogglesMeanEachAdaptersOwnIds() {
        assertEquals("caught", ExceptionFilterCatalog.caughtFilter("java"));
        assertEquals("caught", ExceptionFilterCatalog.caughtFilter("kotlin"));
        assertEquals("raised", ExceptionFilterCatalog.caughtFilter("python"));
        assertEquals("all", ExceptionFilterCatalog.caughtFilter("javascript"));
        assertNull(ExceptionFilterCatalog.caughtFilter("ruby"));
        assertNull(ExceptionFilterCatalog.caughtFilter(null));
        assertEquals("uncaught", ExceptionFilterCatalog.uncaughtFilter("python"));
        assertNull(ExceptionFilterCatalog.uncaughtFilter("ruby"), "no filters at all: no Uncaught toggle either");
    }

    @Test
    void theKnownLabelsAreTranslatedAndAnyOtherIsShownAsSent() {
        assertEquals(
                tr("debug.exceptionFilter.caught"),
                ExceptionFilterCatalog.displayLabel(
                        new DapModels.ExceptionFilterOption("caught", "Caught Exceptions", false)));
        assertEquals(
                tr("debug.exceptionFilter.uncaught"),
                ExceptionFilterCatalog.displayLabel(
                        new DapModels.ExceptionFilterOption("uncaught", "Uncaught Exceptions", true)));
        assertEquals(
                tr("debug.exceptionFilter.raised"),
                ExceptionFilterCatalog.displayLabel(
                        new DapModels.ExceptionFilterOption("raised", "Raised Exceptions", false)));
        assertEquals(
                tr("debug.exceptionFilter.userUncaught"),
                ExceptionFilterCatalog.displayLabel(
                        new DapModels.ExceptionFilterOption("userUnhandled", "User Uncaught Exceptions", false)));
        assertEquals(
                "Promise Rejections",
                ExceptionFilterCatalog.displayLabel(
                        new DapModels.ExceptionFilterOption("rejections", "Promise Rejections", false)));
        assertEquals("", ExceptionFilterCatalog.displayLabel(null));
    }
}
