package com.editora.experiment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LspDiagnostic;
import com.editora.editor.SemanticToken;
import com.editora.editor.TextMateHighlighter;
import com.editora.i18n.Messages;
import com.editora.ui.Fonts;
import com.editora.ui.Themes;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Opt-in, standalone acceptance gate. The same bytecode drives the production EditorBuffer on both
 * runtimes. No JUnit, TestFX, reflection into editor internals, or native-specific editor branch.
 * Samples are JSON lines; checks fail the process. A headless pulse is not a desktop paint measurement.
 */
public final class EditorProbe extends Application {
    private final StackPane root = new StackPane();
    private Stage stage;
    private EditorBuffer buffer;
    private CodeArea area;
    private int bytes;

    @Override
    public void start(Stage primary) {
        stage = primary;
        event("milestone", "fx-ready", 0);
        Messages.init("en");
        Fonts.load();
        Application.setUserAgentStylesheet(Themes.stylesheetFor("dark"));
        var scene = new Scene(root, 1000, 700);
        scene.getStylesheets()
                .add(EditorBuffer.class
                        .getResource("/com/editora/styles/syntax.css")
                        .toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Editora editor acceptance probe");
        stage.show();
        event("milestone", "window-shown", 0);
        memory("window-open");
        Thread.ofPlatform().name("editor-probe").start(() -> {
            try {
                runWorkload();
                event("result", "PASS", 0);
                fx(() -> {
                    stage.close();
                    Platform.exit();
                });
            } catch (Exception | AssertionError error) {
                error.printStackTrace();
                System.exit(2);
            }
        });
    }

    private void runWorkload() throws Exception {
        String sizes = getParameters().getNamed().getOrDefault("sizes", "102400,1048576,5242880,10485760");
        int edits = Integer.parseInt(getParameters().getNamed().getOrDefault("edits", "300"));
        check(edits >= 200 && edits <= 300, "edits must be in [200,300] (bounded undo history)");
        for (String size : sizes.split(",")) {
            bytes = Integer.parseInt(size);
            check(bytes >= 1024 && bytes <= 10 * 1024 * 1024, "supported probe size");
            String initial = document(bytes);
            Path file = Files.createTempFile("editora-native-probe-", ".java");
            try {
                Files.writeString(file, initial, StandardCharsets.UTF_8);
                long start = System.nanoTime();
                String loaded = Files.readString(file, StandardCharsets.UTF_8);
                fx(() -> {
                    buffer = new EditorBuffer();
                    area = buffer.getArea();
                    buffer.setPath(file);
                    // Mirror the production loader: >=5 MiB intentionally disables highlighting/undo.
                    buffer.setLargeFile(bytes >= EditorBuffer.LARGE_FILE_BYTES);
                    buffer.setHeavyFile(
                            loaded.lines().count() >= new com.editora.config.Settings().getLargeFileThreshold());
                    buffer.setInitialContent(loaded);
                    buffer.setMultiCaretEnabled(true);
                    root.getChildren().setAll(buffer.getNode());
                    area.requestFocus();
                    area.getUndoManager().forgetHistory();
                });
                pulses();
                sample("open-file-with-layout", start);
                assertText(initial);
                fx(() -> {
                    check(buffer.isEditable(), "file editable");
                    type("z");
                    area.deletePreviousChar();
                });
                assertText(initial);
                event("milestone", "file-editable", 0);
                memory("file-open");
                if (bytes < EditorBuffer.LARGE_FILE_BYTES) highlighting();
                editing(initial, edits);
                memory("after-sized-document-editing");
                viewport();
                multiCaret();
                decorations();
                pathologicalLines();
                memory("after-workload");
                fx(() -> {
                    buffer.dispose();
                    root.getChildren().clear();
                });
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    /** ASCII by design: char count == UTF-8 file size. No large fixtures in Git. */
    public static String document(int size) {
        String line = "public class Sample { int value = 42; String text = \"hello\"; } // probe\n";
        return line.repeat((size + line.length() - 1) / line.length()).substring(0, size);
    }

    private void editing(String initial, int edits) throws Exception {
        fx(() -> {
            area.moveTo(0);
            area.getUndoManager().forgetHistory();
        });
        StringBuilder expected = new StringBuilder(initial);
        for (int i = 0; i < edits; i++) {
            long start = System.nanoTime();
            fx(() -> {
                area.getUndoManager().preventMerge();
                type("x");
            });
            sample("insert-dispatch", start);
            expected.insert(i, 'x');
        }
        assertText(expected.toString());
        fx(() -> check(area.getCaretPosition() == edits, "caret after sequential input"));
        if (!fxValue(buffer::isLargeFile)) {
            for (int i = 0; i < edits; i++) {
                long start = System.nanoTime();
                fx(area::undo);
                sample("undo-dispatch", start);
            }
            assertText(initial);
            fx(() -> check(!area.isUndoAvailable(), "exact undo history boundary"));
            for (int i = 0; i < edits; i++) {
                long start = System.nanoTime();
                fx(area::redo);
                sample("redo-dispatch", start);
            }
            assertText(expected.toString());
        } else {
            fx(() -> check(!area.isUndoAvailable(), "large-file mode intentionally has no undo"));
        }
        for (int i = 0; i < 30; i++) {
            fx(() -> {
                area.moveTo(area.getLength() / 2);
                area.wordBreaksBackwards(1, SelectionPolicy.CLEAR);
                area.wordBreaksForwards(1, SelectionPolicy.ADJUST);
                validSelection();
                area.nextPage(SelectionPolicy.CLEAR);
                area.prevPage(SelectionPolicy.CLEAR);
                validSelection();
            });
        }
        // Exercise RichTextFX's real clipboard API, including a large paste and large selection deletion.
        String paste = "PASTE \u03bb \ud83d\ude80\n".repeat(8192);
        long start = System.nanoTime();
        fx(() -> {
            var clip = new javafx.scene.input.ClipboardContent();
            clip.putString(paste);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
            area.moveTo(0);
            area.paste();
        });
        pulses();
        sample("bulk-paste-with-layout", start);
        assertText(paste + expected);
        start = System.nanoTime();
        fx(() -> {
            area.selectRange(0, paste.length());
            area.copy();
            area.replaceSelection("");
        });
        pulses();
        sample("delete-region-with-layout", start);
        assertText(expected.toString());
        fx(() -> {
            check(paste.equals(javafx.scene.input.Clipboard.getSystemClipboard().getString()), "copy content");
            area.selectAll();
            check(area.getSelection().getLength() == area.getLength(), "select entire document");
            area.moveTo(0);
        });
    }

    private void highlighting() throws Exception {
        long start = System.nanoTime();
        var grammar = GrammarRegistry.shared().forLanguageName("java");
        check(grammar != null, "Java TextMate grammar must load (no silent fallback)");
        String text = fxValue(buffer::getContent);
        var full = TextMateHighlighter.analyzeFrom(text, grammar, 0, null);
        check(full != null && full.spans().length() == text.length(), "full span extent");
        check(full.spans().stream().anyMatch(s -> !s.getStyle().isEmpty()), "actual syntax tokens");
        sample("full-tokenization", start);
        // Assert the buffer's real asynchronous pipeline paints a known Java keyword.
        awaitStyle(0, "keyword");
        fx(() -> area.getUndoManager().forgetHistory());
        int offset = text.lastIndexOf('\n') + 1;
        int line =
                (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
        start = System.nanoTime();
        fx(() -> area.insertText(offset, "public "));
        awaitStyle(offset, "keyword");
        sample("incremental-highlight-settled", start);
        String changed = fxValue(buffer::getContent);
        var incremental = TextMateHighlighter.analyzeFrom(
                changed, grammar, line, line == 0 ? null : full.endStates().get(line - 1));
        var oracle = TextMateHighlighter.compute(changed, grammar);
        check(incremental.spans().equals(oracle.subView(offset, changed.length())), "incremental equals full oracle");
        fx(() -> area.deleteText(offset, offset + 7));
        assertText(text);
        // Clearing and reapplying real spans must preserve text and span extent.
        for (int i = 0; i < 12; i++) {
            start = System.nanoTime();
            fx(() -> {
                area.setStyleSpans(
                        0,
                        new StyleSpansBuilder<Collection<String>>()
                                .add(List.of(), text.length())
                                .create());
                area.setStyleSpans(0, full.spans());
                check(area.getStyleSpans(0, area.getLength()).length() == text.length(), "style extent");
            });
            sample("style-spans-dispatch", start);
        }
    }

    private void viewport() throws Exception {
        for (int i = 0; i < 24; i++) {
            final boolean last = i % 2 == 0;
            long start = System.nanoTime();
            fx(() -> buffer.setSearchMatches(List.of(new int[] {0, 6}), 0));
            sample("search-highlight-dispatch", start);
            start = System.nanoTime();
            fx(() -> {
                int line = last ? area.getParagraphs().size() - 1 : 0;
                buffer.setSearchMatches(List.of(new int[] {0, 6}), 0);
                area.setParagraphStyle(line, List.of("probe-paragraph"));
                stage.setWidth(900 + (last ? 50 : 0));
                stage.setHeight(620 + (last ? 40 : 0));
                root.applyCss();
                root.layout();
                area.moveTo(line, 0);
                area.showParagraphInViewport(line);
                area.requestFollowCaret();
            });
            pulses();
            sample("scroll-with-layout", start);
            fx(() -> {
                int target = last ? area.getParagraphs().size() - 1 : 0;
                check(
                        area.allParToVisibleParIndex(target).isPresent(),
                        "target paragraph visible: target=" + target + ", first=" + area.visibleParToAllParIndex(0)
                                + ", count=" + area.getVisibleParagraphs().size());
                check(!area.getVisibleParagraphs().isEmpty(), "Flowless visible cells");
                check(area.getParagraphGraphic(target) != null, "line graphic exists");
                check(Double.isFinite(area.estimatedScrollYProperty().getValue()), "finite scroll estimate");
                validSelection();
            });
        }
        fx(buffer::clearSearchMatches);
    }

    private void multiCaret() throws Exception {
        fx(() -> {
            // Large file editing above used the production large-file policy; this bounded fixture
            // independently verifies undo and multi-selection in the same buffer implementation.
            buffer.setLargeFile(false);
            buffer.setHeavyFile(false);
            buffer.setLanguageOverride("text");
            buffer.setContent("alpha beta alpha gamma alpha");
            area.getUndoManager().forgetHistory();
            check(
                    buffer.placeOccurrenceCarets(List.of(new int[] {0, 5}, new int[] {11, 16}, new int[] {23, 28}), 0)
                            == 3,
                    "three occurrence carets");
            type("X");
            check(buffer.getContent().equals("X beta X gamma X"), "multi-caret insertion");
            area.undo();
            check(buffer.getContent().equals("alpha beta alpha gamma alpha"), "atomic multi-caret undo");
            check(!area.isUndoAvailable(), "multi-caret one undo step");
            area.redo();
            check(buffer.getContent().equals("X beta X gamma X"), "multi-caret redo");
            buffer.collapseCarets();
            buffer.placeOccurrenceCarets(List.of(new int[] {0, 1}, new int[] {7, 8}, new int[] {15, 16}), 0);
            press(KeyCode.BACK_SPACE);
            check(buffer.getContent().equals(" beta  gamma "), "multi-caret deletion");
            area.undo();
            check(buffer.getContent().equals("X beta X gamma X"), "multi-caret delete undo");
            buffer.collapseCarets();
        });
        // Hundreds of match replacements via the real occurrence-caret integration.
        String matches = "match ".repeat(300);
        long start = System.nanoTime();
        fx(() -> {
            buffer.setContent(matches);
            area.getUndoManager().forgetHistory();
            List<int[]> ranges = new ArrayList<>();
            for (int i = 0; i < 300; i++) ranges.add(new int[] {i * 6, i * 6 + 5});
            buffer.placeOccurrenceCarets(ranges, 0);
            type("R");
            check(buffer.getContent().equals("R ".repeat(300)), "replace 300 matches");
            area.undo();
            check(buffer.getContent().equals(matches), "replace-many undo");
            buffer.collapseCarets();
        });
        sample("multi-caret-300-dispatch-and-undo", start);
    }

    private void decorations() throws Exception {
        fx(() -> {
            buffer.setLanguageOverride("java");
            buffer.setContent("public class Probe {\n    int value = 1;\n    void method() {}\n}\n");
            area.moveTo(0);
            buffer.setSemanticActive(true);
            buffer.setSemanticTokens(List.of(new SemanticToken(0, 13, 5, "sem-type")), buffer.semanticGen());
            buffer.setLspDiagnostics(List.of(
                    new LspDiagnostic(1, 8, 1, 13, LspDiagnostic.Severity.WARNING, "probe diagnostic", "P1", "probe")));
            buffer.setInlayHintsActive(true);
            buffer.setInlayHints(List.of(new EditorBuffer.InlayHint(1, 13, ": int")));
            buffer.setSearchMatches(List.of(new int[] {13, 18}, new int[] {29, 34}), 0);
            buffer.setChangeBars(Map.of(1, "diff-added"));
            buffer.toggleBookmark(1);
            buffer.getFoldManager().recompute();
            check(!buffer.getFoldManager().regions().isEmpty(), "fold regions detected");
            var region = buffer.getFoldManager().regions().getFirst();
            buffer.getFoldManager().fold(region);
            check(!buffer.getFoldManager().collapsedStartLines().isEmpty(), "fold collapsed");
            buffer.unfoldAll();
            check(buffer.getFoldManager().collapsedStartLines().isEmpty(), "fold restored");
        });
        awaitStyle(13, "sem-type");
        long oldGeneration = fxValue(buffer::semanticGen);
        fx(() -> {
            area.insertText(0, "// shifted\n");
            buffer.setSemanticTokens(List.of(new SemanticToken(0, 0, 2, "sem-probe-stale")), oldGeneration);
        });
        awaitStyle(0, "comment");
        fx(() -> check(!area.getStyleOfChar(0).contains("sem-probe-stale"), "stale semantic reply rejected"));
        pulses();
        fx(() -> {
            buffer.setSemanticActive(false);
            buffer.setInlayHints(List.of());
            buffer.setLspDiagnostics(List.of());
            buffer.clearSearchMatches();
        });
    }

    private void pathologicalLines() throws Exception {
        String text = "x".repeat(128 * 1024) + "\n" + "a\n".repeat(10000);
        fx(() -> {
            buffer.setLanguageOverride("text");
            buffer.setLargeFile(true); // same long-line load policy as FileWorkflowCoordinator
            buffer.setInitialContent(text, true);
        });
        pulses(); // lay out the new paragraph before asking RichTextFX for its caret geometry
        fx(() -> {
            area.moveTo(0, 100000);
            area.requestFollowCaret();
        });
        pulses();
        fx(() -> {
            check(area.estimatedScrollXProperty().getValue() > 0, "horizontal scroll on long line");
            check(area.getCaretBounds().isPresent(), "caret laid out on long line");
            area.showParagraphAtTop(10000);
        });
        pulses();
        assertText(text);
        fx(() -> {
            area.moveTo(0);
            area.showParagraphAtTop(0);
        });
        pulses();
    }

    private void awaitStyle(int offset, String style) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!fxValue(() -> area.getStyleOfChar(offset).contains(style))) {
            check(
                    System.nanoTime() < deadline,
                    "timed out waiting for style " + style + " at " + offset + ": "
                            + fxValue(() -> area.getStyleOfChar(offset)));
            pulses();
        }
    }

    private void validSelection() {
        check(area.getCaretPosition() >= 0 && area.getCaretPosition() <= area.getLength(), "caret range");
        check(
                area.getSelection().getStart() >= 0 && area.getSelection().getEnd() <= area.getLength(),
                "selection range");
    }

    private void assertText(String expected) throws Exception {
        String actual = fxValue(buffer::getContent);
        check(
                expected.equals(actual),
                "document oracle, expected length=" + expected.length() + ", actual=" + actual.length());
        event(
                "checksum",
                java.util.HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8))),
                0);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private void type(String text) {
        area.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, text, text, KeyCode.UNDEFINED, false, false, false, false));
    }

    private void press(KeyCode key) {
        area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
    }

    private void fx(Runnable action) throws Exception {
        fxValue(() -> {
            action.run();
            return null;
        });
    }

    private <T> T fxValue(Callable<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Exception | AssertionError error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(120, TimeUnit.SECONDS);
    }

    private void pulses() throws Exception {
        var done = new CompletableFuture<Void>();
        fx(() -> new AnimationTimer() {
            int count;

            @Override
            public void handle(long now) {
                if (++count >= 2) {
                    stop();
                    done.complete(null);
                }
            }
        }.start());
        done.get(120, TimeUnit.SECONDS);
    }

    private void sample(String name, long start) {
        event("sample", name, (System.nanoTime() - start) / 1e6);
    }

    private void memory(String name) {
        Runtime runtime = Runtime.getRuntime();
        event("heap-used-bytes", name, runtime.totalMemory() - runtime.freeMemory());
    }

    private void event(String kind, String name, double value) {
        System.out.printf(
                java.util.Locale.ROOT,
                "{\"kind\":\"%s\",\"name\":\"%s\",\"bytes\":%d,\"value\":%.6f}%n",
                kind,
                name,
                bytes,
                value);
        System.out.flush();
    }
}
