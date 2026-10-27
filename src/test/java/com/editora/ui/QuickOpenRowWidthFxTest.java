package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.animation.AnimationTimer;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A row whose detail runs past the picker (Surround With's snippet bodies) must not make the list wider than
 * itself. It did: the list showed a horizontal scroll bar on one pulse and dropped it on the next, and the
 * picker flickered while the selection moved.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuickOpenRowWidthFxTest {

    private static final String LONG_DETAIL = "Indexed for loop, counting down  ·  for (int i = n - 1; i >= 0; i--) {"
            + " doSomethingWith(items[i]); andSomethingElse(); }";

    private FxWindowFixture fx;
    private OverlayHost overlay;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        overlay = FxTestSupport.field(fx.controller, "overlayHost");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void longDetailsFitTheListOnEveryFrameWhileTheSelectionMoves() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            rows.add("trigger" + i + " — Statement number " + i);
        }
        QuickOpen<String> picker = FxTestSupport.callOnFx(() -> {
            QuickOpen<String> p = new QuickOpen<>("Surround With", "", () -> rows, s -> s, s -> LONG_DETAIL, s -> {});
            p.setOverlayHost(overlay);
            p.show(null);
            return p;
        });
        try {
            ListView<String> list = FxTestSupport.field(picker, "list");
            List<String> problems = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> new AnimationTimer() {
                private int frame;

                @Override
                public void handle(long now) {
                    check(list, frame, problems);
                    if (frame % 4 == 3) { // move down the list, scrolling rows of every width into view
                        list.getSelectionModel().selectNext();
                        list.scrollTo(list.getSelectionModel().getSelectedIndex());
                    }
                    if (++frame == 80) {
                        stop();
                        done.countDown();
                    }
                }
            }.start());
            assertTrue(done.await(30, TimeUnit.SECONDS), "the frames ran");
            assertEquals(List.of(), problems);
        } finally {
            FxTestSupport.runOnFx(picker::hide);
        }
    }

    /** Records what is wrong with this frame: a horizontal scroll bar, or a row past the list's edge. */
    private static void check(ListView<String> list, int frame, List<String> problems) {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar bar && bar.getOrientation() == Orientation.HORIZONTAL && bar.isVisible()) {
                problems.add("frame " + frame + ": horizontal scroll bar");
            }
        }
        double width = list.getWidth();
        for (Node n : list.lookupAll(".list-cell")) {
            if (!(n instanceof ListCell<?> cell) || cell.isEmpty() || !cell.isVisible()) {
                continue;
            }
            if (cell.getWidth() > width) {
                problems.add(
                        "frame " + frame + ": row " + cell.getIndex() + " is " + cell.getWidth() + " wide in " + width);
            }
            if (cell.getGraphic() instanceof HBox box) {
                Node title = box.getChildren().getFirst();
                Label detail = (Label) box.getChildren().getLast();
                if (detail.getLayoutX()
                        < title.getLayoutX() + title.getLayoutBounds().getWidth()) {
                    problems.add("frame " + frame + ": row " + cell.getIndex() + " detail overlaps its title");
                }
            }
        }
    }
}
