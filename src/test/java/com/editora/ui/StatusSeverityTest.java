package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that routes failure messages to the status bar's error channel, stated over the catalog's own
 * key names: a {@code status.*} key whose last segment says fail/error is an error, cannot/invalid a
 * warning, and nothing else changes. A new failure message only has to be named accordingly.
 */
class StatusSeverityTest {

    @BeforeEach
    void english() {
        Messages.init("en");
    }

    @AfterEach
    void restore() {
        Messages.init("en");
    }

    @Test
    void theRuleOverCatalogKeyNames() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String key : new TreeSet<>(Messages.keys())) {
            String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
            boolean status = key.startsWith("status."); // the name decides: no key is exempt
            MessageLog.Severity expected = !status
                    ? MessageLog.Severity.INFO
                    : last.contains("fail") || last.contains("error")
                            ? MessageLog.Severity.ERROR
                            : last.contains("cannot") || last.contains("invalid")
                                    ? MessageLog.Severity.WARN
                                    : MessageLog.Severity.INFO;
            assertEquals(expected, StatusSeverity.ofKey(key), key);
            if (expected == MessageLog.Severity.ERROR) {
                errors.add(key);
            } else if (expected == MessageLog.Severity.WARN) {
                warnings.add(key);
            }
        }
        assertTrue(errors.size() >= 70, "failure keys routed to the error channel: " + errors.size());
        assertTrue(errors.containsAll(List.of(
                "status.pdf.exportFailed",
                "status.build.failed",
                "status.ai.failed",
                "status.failedOpen",
                "status.debug.error")));
        assertTrue(warnings.containsAll(List.of("status.narrow.cannot", "status.gotoInvalid")), warnings.toString());
        // A message that mentions a failure without being one is named for what it says, and other
        // namespaces stay ordinary messages.
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.ofKey("status.testrunner.filterNotPassed"));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.ofKey("status.testrunner.rerunsAllInstead"));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.ofKey("status.log.noProblemLines"));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.ofKey("status.saved"));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.ofKey("testrunner.failed"));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.ofKey(null));
    }

    @Test
    void aTranslatedFailureMessageIsRecognisedWhateverItsArguments() {
        assertEquals(MessageLog.Severity.ERROR, StatusSeverity.of(tr("status.pdf.exportFailed", "disk full")));
        // A pattern that opens with a placeholder: "{0} failed to start: {1}".
        assertEquals(MessageLog.Severity.ERROR, StatusSeverity.of(tr("status.build.failed", "Maven", "mvn not found")));
        assertEquals(MessageLog.Severity.ERROR, StatusSeverity.of(tr("status.failedOpen", "/tmp/a\nb.txt")));
        assertEquals(MessageLog.Severity.WARN, StatusSeverity.of(tr("status.narrow.cannot")));
        assertEquals(MessageLog.Severity.WARN, StatusSeverity.of(tr("status.gotoInvalid", "12:x")));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.of(tr("status.saved", "notes.md")));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.of(tr("status.testrunner.filterNotPassed")));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.of("Quit"));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.of(""));
        assertEquals(MessageLog.Severity.INFO, StatusSeverity.of(null));
    }

    @Test
    void everyFailureKeyRoundTripsInEveryLanguage() {
        for (String lang : Messages.available().keySet()) {
            Messages.init(lang);
            for (String key : Messages.keys()) {
                MessageLog.Severity expected = StatusSeverity.ofKey(key);
                if (expected == MessageLog.Severity.INFO) {
                    continue;
                }
                // Text for each argument, and a number where the pattern formats one ({1,number,#}): given
                // text there, tr hands back the pattern itself.
                String pattern = tr(key);
                Object[] args = {"alpha", "beta", "gamma", "delta"};
                for (int i = 0; i < args.length; i++) {
                    if (pattern.matches("(?s).*\\{" + i + "\\s*,.*")) {
                        args[i] = 7 + i;
                    }
                }
                String shown = tr(key, args);
                assertTrue(!shown.equals(pattern) || pattern.indexOf('{') < 0, lang + " " + key + " was not formatted");
                assertEquals(expected, StatusSeverity.of(shown), lang + " " + key + " → " + shown);
            }
        }
    }

    @Test
    void messageFormatPatternsBecomeWildcardRegexes() {
        assertTrue(StatusSeverity.toRegex("{0} failed: {1}")
                .matcher("Maven failed: boom")
                .matches());
        assertTrue(StatusSeverity.toRegex("Can''t read {0}")
                .matcher("Can't read a.txt")
                .matches());
        assertTrue(StatusSeverity.toRegex("{0,choice,0#no files|1#one file} failed")
                .matcher("one file failed")
                .matches());
        assertEquals("Failed to open: ", StatusSeverity.literalPrefix("Failed to open: {0}"));
        assertEquals("", StatusSeverity.literalPrefix("{0} failed"));
        assertEquals("plain text", StatusSeverity.literalPrefix("plain text"));
    }
}
