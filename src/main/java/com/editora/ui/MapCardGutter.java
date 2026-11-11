package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;

import com.editora.editor.LazyTooltip;
import com.editora.editor.fold.FoldManager;
import com.editora.run.MainMethodScanner;
import com.editora.test.JavaTestScanner;
import com.editora.test.TestSourceLocator;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The gutter of a Project Map card (Map gaps round 3, item 1): beside each line's number, what the editor's gutter
 * shows there — a breakpoint (a click in the strip sets or removes one), a bookmark, a ▶ that runs a JUnit test or a
 * Java {@code main} method, and the Git change bar. Every line here is a 0-based line of the card's file; a
 * declaration card starts at its first line. Each column is there only when it has something to say for the file, so
 * a card of a file with none of them keeps a gutter of numbers.
 */
final class MapCardGutter {

    static final double BREAKPOINT_WIDTH = 14;
    static final double BOOKMARK_WIDTH = 12;
    static final double RUN_WIDTH = 13;
    static final double BAR_WIDTH = 3;
    /** A marked bar's width to the mouse; it paints its inner {@link #BAR_WIDTH}, as the editor's does. */
    static final double BAR_HIT_WIDTH = 8;

    /** A breakpoint's glyph on a line: its classes (as the editor's gutter has them; null: a plain one) and hover. */
    record Breakpoint(@Nullable String classes, String tooltip) {}

    /** What a line's ▶ runs: a JUnit test class or method, or a Java {@code main} method. */
    record Run(JavaTestScanner.@Nullable TestTarget test, MainMethodScanner.@Nullable MainMethod main) {
        /** The ▶'s hover text, as the editor's gutter words it. */
        String tooltip() {
            if (main != null) {
                return tr("editmenu.runMainTooltip", TestSourceLocator.simpleName(main.fqn()));
            }
            if (test == null) {
                return "";
            }
            return test.methodName() == null
                    ? tr("testrunner.gutter.runClass", TestSourceLocator.displayName(test.className()))
                    : tr("testrunner.gutter.runMethod", test.methodName());
        }
    }

    /**
     * A file's marks: whether a breakpoint may be set in it (the strip is there), its breakpoints and bookmarks by
     * line, and whether its JUnit tests and {@code main} methods can be run from here.
     */
    record Marks(
            boolean breakpointsOn,
            Map<Integer, Breakpoint> breakpoints,
            Set<Integer> bookmarks,
            boolean tests,
            boolean mains) {
        static final Marks NONE = new Marks(false, Map.of(), Set.of(), false, false);

        Marks {
            breakpoints = Map.copyOf(breakpoints);
            bookmarks = Set.copyOf(bookmarks);
        }
    }

    /** A file's Git change bars: line → the editor's bar class ({@code git-added} …), and each bar's hunk text. */
    record Changes(Map<Integer, String> bars, Map<Integer, String> tooltips) {
        Changes {
            bars = Map.copyOf(bars);
            tooltips = Map.copyOf(tooltips);
        }
    }

    /** Where a card's marks come from, and what its gutter's clicks do. */
    interface Source {
        /** The marks of {@code file} as they are now. */
        Marks marks(Path file);

        /** {@code file}'s change bars against HEAD, on the FX thread; null when Git does not track it. */
        default void changes(Path file, Consumer<@Nullable Changes> found) {
            found.accept(null);
        }

        /** Sets or removes a breakpoint on {@code line} of {@code file}, whose text is {@code text}. */
        default void toggleBreakpoint(Path file, int line, String text) {}

        /** Runs what a line's ▶ of {@code file} stands for. */
        default void run(Path file, Run run) {}
    }

    /** The ▶ lines of {@code text} (a Java file): its JUnit tests when {@code tests}, its mains when {@code mains}. */
    static Map<Integer, Run> runs(String text, boolean tests, boolean mains) {
        Map<Integer, Run> out = new HashMap<>();
        if (tests) {
            for (JavaTestScanner.TestTarget target : JavaTestScanner.scan(text)) {
                out.put(target.line(), new Run(target, null));
            }
        }
        if (mains) {
            for (MainMethodScanner.MainMethod main : MainMethodScanner.scan(text)) {
                out.put(main.line(), new Run(null, main));
            }
        }
        return out;
    }

    /**
     * A card's gutter: paragraph {@code p} is line {@code firstLine + p} of the file, numbered up to {@code lastLine +
     * 1}; {@code onBreakpoint} gets the file line of a click in the breakpoint strip, {@code onRun} a clicked ▶.
     */
    static IntFunction<Node> rows(
            int firstLine,
            int lastLine,
            Marks marks,
            @Nullable Changes changes,
            Map<Integer, Run> runs,
            IntConsumer onBreakpoint,
            Consumer<Run> onRun) {
        return rows(firstLine, lastLine, marks, changes, runs, -1, onBreakpoint, onRun);
    }

    /**
     * As above, with the debugger's arrow on the file's {@code executionLine} (-1: none; Map gaps round 3, item 2) in
     * the breakpoint strip, which is there for it even where breakpoints are not.
     */
    static IntFunction<Node> rows(
            int firstLine,
            int lastLine,
            Marks marks,
            @Nullable Changes changes,
            Map<Integer, Run> runs,
            int executionLine,
            IntConsumer onBreakpoint,
            Consumer<Run> onRun) {
        String format = "%" + String.valueOf(Math.max(1, lastLine + 1)).length() + "d";
        boolean bookmarks = !marks.bookmarks().isEmpty();
        boolean running = !runs.isEmpty();
        return paragraph -> {
            int line = firstLine + paragraph;
            HBox row = new HBox();
            row.getStyleClass().addAll("fold-gutter", "project-map-card-gutter"); // the editor gutter's look
            row.setAlignment(Pos.TOP_RIGHT);
            if (marks.breakpointsOn() || executionLine >= 0) {
                StackPane strip = breakpointSlot(line, marks.breakpoints().get(line), onBreakpoint);
                if (line == executionLine) {
                    strip.getChildren().add(FoldManager.executionArrow());
                }
                row.getChildren().add(strip);
            }
            if (bookmarks) {
                Region slot =
                        marks.bookmarks().contains(line) ? new StackPane(FoldManager.bookmarkMarker()) : new Region();
                fixWidth(slot, BOOKMARK_WIDTH);
                row.getChildren().add(slot);
            }
            if (running) {
                row.getChildren().add(runSlot(runs.get(line), onRun));
            }
            Label number = new Label(String.format(Locale.ROOT, format, line + 1));
            number.getStyleClass().add("lineno");
            number.setAlignment(Pos.TOP_RIGHT);
            number.setFont(Font.font("monospace", 12));
            row.getChildren().add(number);
            if (changes != null) {
                row.getChildren()
                        .add(bar(changes.bars().get(line), changes.tooltips().get(line)));
            }
            return row;
        };
    }

    /** The number a gutter row shows, stripped ("" for none). For tests. */
    static String numberOf(Node row) {
        if (row instanceof Label label) {
            return label.getText().strip();
        }
        if (row instanceof HBox box) {
            for (Node child : box.getChildren()) {
                if (child instanceof Label label && label.getStyleClass().contains("lineno")) {
                    return label.getText().strip();
                }
            }
        }
        return "";
    }

    private static StackPane breakpointSlot(int line, @Nullable Breakpoint breakpoint, IntConsumer onBreakpoint) {
        StackPane slot = new StackPane();
        slot.getStyleClass().add("breakpoint-slot");
        fixWidth(slot, BREAKPOINT_WIDTH);
        slot.setMaxHeight(Double.MAX_VALUE);
        slot.setCursor(Cursor.HAND);
        slot.setAccessibleRole(AccessibleRole.BUTTON);
        slot.setAccessibleText(tr("project.map.gutter.breakpoint", line + 1));
        if (breakpoint != null) {
            slot.getChildren().add(FoldManager.breakpointMarker(breakpoint.classes()));
            if (!breakpoint.tooltip().isBlank()) {
                LazyTooltip.install(slot, breakpoint::tooltip);
            }
        }
        ownPointer(slot);
        slot.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                onBreakpoint.accept(line);
                e.consume();
            }
        });
        return slot;
    }

    private static StackPane runSlot(@Nullable Run run, Consumer<Run> onRun) {
        StackPane slot = new StackPane();
        slot.getStyleClass().add("run-slot");
        slot.setAlignment(Pos.CENTER_RIGHT);
        fixWidth(slot, RUN_WIDTH);
        if (run != null) {
            Node glyph = FoldManager.runGlyph(run.test() != null ? "test-run-marker" : null);
            glyph.setCursor(Cursor.HAND);
            String tip = run.tooltip();
            LazyTooltip.install(glyph, () -> tip, null, 300);
            glyph.setAccessibleRole(AccessibleRole.BUTTON);
            glyph.setAccessibleText(tip);
            ownPointer(glyph);
            glyph.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    onRun.accept(run);
                    e.consume();
                }
            });
            slot.getChildren().add(glyph);
        }
        return slot;
    }

    private static StackPane bar(@Nullable String cls, @Nullable String tooltip) {
        StackPane bar = new StackPane();
        bar.getStyleClass().add("git-change-bar");
        fixWidth(bar, cls == null ? BAR_WIDTH : BAR_HIT_WIDTH);
        bar.setMaxHeight(Double.MAX_VALUE);
        if (cls != null) {
            // Wider than its slot, reaching back over the number's padding: the row is as wide with a bar as without.
            HBox.setMargin(bar, new Insets(0, 0, 0, BAR_WIDTH - BAR_HIT_WIDTH));
            bar.getStyleClass().add(cls);
            if (cls.equals("git-deleted")) {
                // Lines were removed above this one: a corner flag on the row's top edge, as in the editor.
                Polygon wedge = new Polygon(0, 0, BAR_HIT_WIDTH, 0, BAR_HIT_WIDTH, BAR_HIT_WIDTH);
                wedge.getStyleClass().add("git-deleted-wedge");
                StackPane.setAlignment(wedge, Pos.TOP_RIGHT);
                bar.getChildren().add(wedge);
            }
            if (tooltip != null) {
                LazyTooltip.install(bar, () -> tooltip, "git-diff-tooltip", 300);
            }
        }
        return bar;
    }

    private static void fixWidth(Region region, double width) {
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }

    /**
     * Keeps a press on a gutter control from also being a press on the card's text, which would move its caret and
     * drop its selection; the click still arrives. The card keeps focus, as a press on its text would give it.
     */
    private static final EventHandler<MouseEvent> OWN_POINTER = e -> {
        var type = e.getEventType();
        if (type == MouseEvent.MOUSE_PRESSED) {
            for (Node n = (Node) e.getSource(); n != null; n = n.getParent()) {
                if (n instanceof org.fxmisc.richtext.CodeArea view) {
                    view.requestFocus();
                    break;
                }
            }
        }
        if (type == MouseEvent.MOUSE_PRESSED
                || type == MouseEvent.MOUSE_DRAGGED
                || type == MouseEvent.MOUSE_RELEASED
                || type == MouseEvent.DRAG_DETECTED) {
            e.consume();
        }
    };

    private static void ownPointer(Node control) {
        control.addEventHandler(MouseEvent.ANY, OWN_POINTER);
    }

    private MapCardGutter() {}
}
