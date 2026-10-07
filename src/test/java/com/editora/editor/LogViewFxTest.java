package com.editora.editor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
            assertEquals("", area.getText(), "a visible line goes when the line it shows is dropped");
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
            view.applyFilter(null, "ERROR");
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

    @Test
    void followingThroughAPatternKeepsEveryLineOnItsOwnLine() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("INFO a\nERROR b\n");
            LogView view = new LogView(area, () -> {});
            view.applyFilter(null, "ERROR");
            assertEquals("ERROR b\n", area.getText());

            view.append("ERROR c\n");
            view.append("INFO d\nERROR e\n");

            // The kept lines used to be joined without a trailing newline: "ERROR bERROR cERROR e".
            assertEquals("ERROR b\nERROR c\nERROR e\n", area.getText());
            assertEquals(3, view.visibleLines());
            assertEquals(5, view.totalLines());
            return null;
        });
    }

    @Test
    void aLineWrittenAcrossTwoReadsIsJudgedWhole() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("INFO b\n");
            LogView view = new LogView(area, () -> {});
            view.applyFilter(null, "timeout");

            view.append("ERROR connection tim");
            assertEquals("", area.getText(), "half a line that does not match yet shows nothing");
            view.append("eout after 30s\n");
            assertEquals("ERROR connection timeout after 30s\n", area.getText());

            view.append("ERROR timeout again, still being wr");
            assertEquals(
                    "ERROR connection timeout after 30s\nERROR timeout again, still being wr\n",
                    area.getText(),
                    "an unfinished line that matches is shown as it stands");
            view.append("itten\n");
            assertEquals(
                    "ERROR connection timeout after 30s\nERROR timeout again, still being written\n",
                    area.getText(),
                    "and replaced, not repeated, when the rest arrives");
            assertEquals(2, view.visibleLines());
            return null;
        });
    }

    @Test
    void aFileWithoutAFinalNewlineStillShowsItsLastLine() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("INFO a\nERROR last line, no newline");
            LogView view = new LogView(area, () -> {});
            view.applyFilter(LogLevel.ERROR, null);
            assertEquals("ERROR last line, no newline\n", area.getText());
            assertEquals("INFO a\nERROR last line, no newline", view.fullText(), "the document is untouched");
            assertEquals(2, view.lineNumberAt(0));
            return null;
        });
    }

    @Test
    void aFilteredLineKeepsItsNumberAndLevelFromTheWholeLog() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(
                    "WARN slow\nINFO ok\nERROR boom\njava.io.IOException: Read timed out\n\tat x\nWARN slow\n");
            LogView view = new LogView(area, () -> {});
            assertEquals(-1, view.lineNumberAt(0), "unfiltered: a paragraph is its own line");

            view.applyFilter(LogLevel.WARN, null);
            view.applyFilter(null, "timed out"); // the ERROR record, sitting under no WARN line now

            assertEquals("ERROR boom\njava.io.IOException: Read timed out\n\tat x\n", area.getText());
            assertEquals(3, view.lineNumberAt(0));
            assertEquals(5, view.lineNumberAt(2));
            assertEquals(0, view.lineNumberAt(3), "the empty row after the last line has no number");
            assertEquals(LogLevel.ERROR, view.levelAt(1), "the trace is its record's level whatever sits above it");
            assertEquals(6, view.lineNumberSpan());
            return null;
        });
    }

    @Test
    void changingTheFilterKeepsTheCaretOnItsLine() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                text.append(i % 4 == 0 ? "ERROR line " : "INFO line ").append(i).append('\n');
            }
            area.replaceText(text.toString());
            LogView view = new LogView(area, () -> {});
            area.moveTo(20, 0); // "ERROR line 20"

            view.applyFilter(LogLevel.ERROR, null);
            assertEquals(
                    "ERROR line 20",
                    area.getParagraph(area.getCurrentParagraph()).getText());

            area.moveTo(7, 0); // "ERROR line 28"
            view.applyFilter(null, null);
            assertEquals(28, area.getCurrentParagraph(), "clearing the filter lands on the same line, not at the end");

            area.moveTo(21, 0); // an INFO line the next filter hides
            view.applyFilter(LogLevel.ERROR, null);
            assertEquals(
                    "ERROR line 24",
                    area.getParagraph(area.getCurrentParagraph()).getText(),
                    "the next visible line");
            return null;
        });
    }

    @Test
    void anAppendMovesNeitherTheCaretNorTheSelection() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("INFO a\nINFO b\nINFO c\n");
            LogView view = new LogView(area, () -> {});
            view.setFollowing(true);
            area.selectRange(7, 13); // "INFO b"

            view.append("INFO d\n");

            assertEquals("INFO b", area.getSelectedText(), "reading or copying an older line survives new ones");
            assertEquals(13, area.getCaretPosition());
            assertTrue(area.getText().endsWith("INFO d\n"));
            return null;
        });
    }

    @Test
    void followingKeepsWhatWasOpenAndCapsOnlyWhatItAdds() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            String opened = "opened line\n".repeat(60); // 720 chars: well over the cap
            area.replaceText(opened);
            LogView view = new LogView(area, () -> {}, CAP, SLACK);
            view.setFollowing(true);

            view.append("first new line\n");
            assertFalse(view.trimmed(), "one new line must not cost the top of the log that was opened");
            assertTrue(area.getText().startsWith(opened));

            for (int i = 0; i < 100; i++) {
                view.append("new line " + i + "\n");
            }
            assertTrue(view.trimmed());
            assertTrue(area.getLength() <= opened.length() + CAP + SLACK);
            return null;
        });
    }

    @Test
    void reapplyingTheSameFilterDoesNothing() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("INFO a\nERROR b\n");
            LogView view = new LogView(area, () -> {});
            view.applyFilter(LogLevel.ERROR, "b");
            int[] changes = {0};
            area.plainTextChanges().subscribe(c -> changes[0]++);

            view.applyFilter(LogLevel.ERROR, "b"); // Enter, then the pending debounce
            assertEquals(0, changes[0]);
            assertTrue(view.showsFilter(LogLevel.ERROR, "b"));
            assertFalse(view.showsFilter(LogLevel.ERROR, null));
            return null;
        });
    }

    @Test
    void aFilterComputedElsewhereIsInstalledOnlyIfTheTextOnlyGrew() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText("INFO a\nERROR b\n");
            LogView view = new LogView(area, () -> {});

            String source = view.filterSource();
            int epoch = view.epoch();
            var run = com.editora.logviewer.LogFilter.run(source, LogLevel.ERROR, null);
            view.append("ERROR c\nINFO d\n"); // arrives while the worker is busy
            assertTrue(view.install(run, epoch, LogLevel.ERROR, null));
            assertEquals("ERROR b\nERROR c\n", area.getText(), "the lines appended meanwhile are filtered too");
            assertEquals("INFO a\nERROR b\nERROR c\nINFO d\n", view.fullText());

            view.applyFilter(null, null);
            source = view.filterSource();
            epoch = view.epoch();
            run = com.editora.logviewer.LogFilter.run(source, LogLevel.ERROR, null);
            area.insertText(0, "typed "); // a user edit: the computed result describes other text
            assertFalse(view.install(run, epoch, LogLevel.ERROR, null));
            assertFalse(view.filtered());
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
