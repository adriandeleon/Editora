package com.editora.dap;

import java.util.List;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DapErrorsTest {

    @AfterEach
    void english() {
        Messages.init("en");
    }

    @Test
    void theJavaExceptionNamesAnAdapterPutsBeforeItsMessageAreDropped() {
        assertEquals(
                "generated value is not a String",
                DapErrors.userText("org.eclipse.debug.core.DebugException: com.sun.jdi.InvalidTypeException: "
                        + "generated value is not a String"));
        assertEquals(
                "Thread is not suspended",
                DapErrors.userText("com.sun.jdi.IncompatibleThreadStateException: " + "Thread is not suspended"));
        assertEquals("out of memory", DapErrors.userText("java.lang.OutOfMemoryError: out of memory"));
    }

    @Test
    void textThatIsNotAClassNameIsLeftAsItIs() {
        for (String text : List.of(
                "Main class 'App' not found",
                "Error: Could not find or load main class demo.App",
                "Cannot evaluate because of compilation error(s): x cannot be resolved.",
                "/home/me/App.java:3: error: ';' expected",
                "C:\\work\\App.java: not found")) {
            assertEquals(text, DapErrors.userText(text));
        }
        assertEquals("", DapErrors.userText(null));
        // Nothing after the class name: the name is all there is to say.
        assertEquals("java.lang.IllegalStateException:", DapErrors.userText("java.lang.IllegalStateException: "));
    }

    @Test
    void theStatusBarGetsTheFirstLineOfAReportThatHasSeveral() {
        assertFalse(DapErrors.multiLine("launch failed: no class"));
        assertFalse(DapErrors.multiLine("one line\n\n"));
        assertTrue(DapErrors.multiLine("Could not compile:\nApp.java:3: error"));
        assertEquals("Could not compile:", DapErrors.firstLine("\n  Could not compile:\nApp.java:3: error"));
        assertEquals("", DapErrors.firstLine(null));
    }

    @Test
    void theKnownExceptionFiltersAreShownInTheUiLanguageAndOthersAsTheAdapterNamesThem() {
        Messages.init("de");
        List<DapModels.ExceptionFilterOption> java = ExceptionFilterCatalog.known("java");
        assertEquals(
                List.of("Abgefangene Ausnahmen", "Nicht abgefangene Ausnahmen"),
                java.stream().map(ExceptionFilterCatalog::displayLabel).toList());
        assertEquals(
                "Promise Rejects",
                ExceptionFilterCatalog.displayLabel(
                        new DapModels.ExceptionFilterOption("promiseRejects", "Promise Rejects", false)));
    }
}
