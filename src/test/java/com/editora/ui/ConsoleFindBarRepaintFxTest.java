package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An open find bar over a streaming console searches only the output that arrived (perf review P6), and what it
 * paints is still every match in the console as it is now: appended lines, a line still growing, the oldest
 * output cut away, a line overwritten after a carriage return, a cleared console.
 */
@Tag("fx")
@ExtendWith(ConsoleFindInPlace.class)
class ConsoleFindBarRepaintFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String pairs(List<int[]> matches) {
        StringBuilder out = new StringBuilder();
        for (int[] m : matches) {
            out.append(m[0]).append('-').append(m[1]).append(' ');
        }
        return out.toString().strip();
    }

    @Test
    void repaintsMatchAFullSearchThroughAppendsTrimsAndOverwrites() throws Exception {
        CodeArea[] area = new CodeArea[1];
        ConsoleFindBar[] bar = new ConsoleFindBar[1];
        Stage[] stage = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            area[0] = new CodeArea("warn one\nok\n");
            bar[0] = new ConsoleFindBar(() -> area[0]);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new VBox(bar[0], ConsoleFindBar.withMarks(area[0], area[0])), 400, 300));
            stage[0].show();
            bar[0].open(null);
            bar[0].field().setText("WARN");
        });
        Object marks = FxTestSupport.callOnFx(() -> ConsoleFindBar.marks(area[0]));
        List<Runnable> steps = List.of(
                () -> area[0].appendText("partial wa"),
                () -> area[0].appendText("rn and warn\n"),
                () -> area[0].appendText("x warn y\nwarn\n"),
                () -> area[0].deleteText(0, 5), // the oldest output cut, through the first match
                () -> area[0].appendText("50% warn"),
                () -> area[0].deleteText(area[0].getLength() - 8, area[0].getLength()), // "\r" overwrites the line
                () -> area[0].appendText("100% warnwarn\n"),
                () -> {
                    area[0].appendText("more warn\n");
                    area[0].deleteText(0, area[0].getText().indexOf('\n') + 1);
                },
                area[0]::clear,
                () -> area[0].appendText("after clear: warn\n"));
        for (Runnable step : steps) {
            FxTestSupport.runOnFx(step);
            FxTestSupport.runOnFx(() -> {}); // the repaint queued behind the change
            FxTestSupport.runOnFx(() -> {
                com.editora.text.SearchMatches painted = FxTestSupport.field(marks, "matches");
                String expected = pairs(ConsoleFindBar.matches(area[0].getText(), "WARN", false));
                assertEquals(expected, pairs(painted.toList()), "after a change, text: " + area[0].getText());
            });
        }
        FxTestSupport.runOnFx(() -> stage[0].hide());
    }

    @Test
    void anEmptyQueryDoesNotSearchAtEveryDrain() throws Exception {
        int[] reads = new int[1];
        CodeArea area = FxTestSupport.callOnFx(() -> new CodeArea("one\n"));
        ConsoleFindBar.Target counting = FxTestSupport.callOnFx(() -> {
            ConsoleFindBar.Target real = ConsoleFindBar.Target.of(area);
            return new ConsoleFindBar.Target() {
                @Override
                public String text() {
                    reads[0]++;
                    return real.text();
                }

                @Override
                public String textFrom(int from) {
                    reads[0]++;
                    return real.textFrom(from);
                }

                @Override
                public int lineStart(int offset) {
                    return real.lineStart(offset);
                }

                @Override
                public Object source() {
                    return area;
                }

                @Override
                public int length() {
                    return real.length();
                }

                @Override
                public int selectionStart() {
                    return real.selectionStart();
                }

                @Override
                public void select(int start, int end) {
                    real.select(start, end);
                }

                @Override
                public void focus() {}

                @Override
                public Runnable watch(java.util.function.Consumer<int @org.jspecify.annotations.Nullable []> changed) {
                    return real.watch(changed);
                }
            };
        });
        ConsoleFindBar bar = FxTestSupport.callOnFx(() -> ConsoleFindBar.overTarget(() -> counting));
        FxTestSupport.runOnFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(bar, area), 300, 200));
            stage.show();
            bar.open(null);
            reads[0] = 0;
            for (int i = 0; i < 20; i++) {
                area.appendText("line " + i + "\n");
            }
        });
        FxTestSupport.runOnFx(() -> {});
        assertEquals(0, reads[0], "an empty query copies nothing as output arrives");
        FxTestSupport.runOnFx(() -> bar.field().setText("line"));
        int full = reads[0];
        assertTrue(full >= 1);
        FxTestSupport.runOnFx(() -> area.appendText("line 20\n"));
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> {
            assertEquals(full + 1, reads[0], "one read of the appended text");
            ((Stage) area.getScene().getWindow()).hide();
        });
    }
}
