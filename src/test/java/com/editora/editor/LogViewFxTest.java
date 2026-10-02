package com.editora.editor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.editora.logviewer.LogLevel;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log viewer's retained text, driven with a small cap so the trim is reachable without megabytes of text.
 *
 * <p>While a filter was active the complete text was only trimmed when an appended chunk <em>matched</em> the
 * filter, so following a log through a filter most lines fail (the normal use: ERROR only) grew that text
 * without bound — and it was rebuilt by string concatenation on every 500 ms poll.
 */
@Tag("fx")
class LogViewFxTest {

    private static final int CAP = 200;
    private static final int SLACK = 50;

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void theFullTextIsTrimmedEvenWhenNothingAppendedMatchesTheFilter() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("ERROR first\n");
            LogView view = new LogView(area, () -> {}, CAP, SLACK);
            view.applyFilter(LogLevel.ERROR, null);

            for (int i = 0; i < 100; i++) {
                view.append("INFO filler line number " + i + "\n"); // never shown: below the level floor
            }

            assertTrue(view.fullLength() <= CAP + SLACK, "retained " + view.fullLength() + " chars");
            assertTrue(view.fullText().endsWith("INFO filler line number 99\n"), "the newest lines are kept");
            assertTrue(view.fullText().startsWith("INFO filler line number "), "and the cut is at a line start");
            assertTrue(view.trimmed(), "the buffer no longer holds the whole log");
            assertEquals("ERROR first\n", area.getText(), "the visible subset is unaffected");
            return null;
        });
    }

    @Test
    void anUnfilteredFollowIsTrimmedToWholeLinesAndReportsIt() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            LogView view = new LogView(area, () -> {}, CAP, SLACK);
            assertFalse(view.trimmed());

            for (int i = 0; i < 100; i++) {
                view.append("line " + i + "\n");
            }

            assertTrue(area.getLength() <= CAP + SLACK);
            assertTrue(area.getText().startsWith("line "), "no half line is left at the top");
            assertTrue(area.getText().endsWith("line 99\n"));
            assertTrue(view.trimmed());
            return null;
        });
    }

    @Test
    void aLogBelowTheCapIsNeverMarkedTrimmed() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            LogView view = new LogView(area, () -> {}, CAP, SLACK);
            view.append("INFO a\nERROR b\n");
            view.applyFilter(null, Pattern.compile("ERROR"));
            view.append("INFO c\nERROR d\n");

            assertFalse(view.trimmed());
            assertEquals("INFO a\nERROR b\nINFO c\nERROR d\n", view.fullText());
            view.applyFilter(null, null);
            assertEquals("INFO a\nERROR b\nINFO c\nERROR d\n", area.getText());
            assertFalse(view.filtered());
            return null;
        });
    }

    @Test
    void areaRewritesAreFlaggedAsAdjustmentsOnlyWhileTheyHappen() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("INFO a\nERROR b\n");
            LogView view = new LogView(area, () -> {}, CAP, SLACK);
            boolean[] sawUnflagged = {false};
            area.plainTextChanges().subscribe(change -> sawUnflagged[0] |= !view.adjusting());

            view.applyFilter(LogLevel.ERROR, null);
            view.append("ERROR c\n");
            view.reset("ERROR rotated\n");
            view.applyFilter(null, null);

            assertFalse(sawUnflagged[0], "every change the view makes is distinguishable from a user edit");
            assertFalse(view.adjusting());
            area.appendText("typed");
            assertTrue(sawUnflagged[0], "and a real edit is not flagged");
            return null;
        });
    }

    @Test
    void aFreshLoadClearsTheTrimmedStateButAComputedReplacementDoesNot() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            LogView view = new LogView(area, () -> {}, CAP, SLACK);
            for (int i = 0; i < 100; i++) {
                view.append("line " + i + "\n");
            }
            assertTrue(view.trimmed());

            view.suspendFilter(false).run();
            assertTrue(view.trimmed(), "text derived from this buffer is still only a tail");
            view.suspendFilter(true).run();
            assertFalse(view.trimmed(), "the file as read from disk is whole again");
            return null;
        });
    }

    private static <T> T onFx(Callable<T> body) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(body.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
