package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.Parent;

import com.editora.editor.MarkwhenCalendar;
import com.editora.editor.MarkwhenTimeline;
import com.editora.markwhen.MarkwhenParser;
import com.editora.markwhen.Timeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Smoke test: the Markwhen renderer builds a non-empty JavaFX node from a parsed timeline (needs the FX
 *  toolkit to construct the Pane/Label/Region/Circle nodes). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkwhenTimelineFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void buildsANonEmptyTimelineNode() throws Exception {
        String mw = "title: Demo\n#Travel: blue\n\n# Trips\n2023-01: Kickoff #Travel\n"
                + "2023-03 / 2023-06: Phase one\nMar 1 2024: Launch\n";
        Node node = FxTestSupport.callOnFx(() -> {
            Timeline model = MarkwhenParser.parse(mw);
            return MarkwhenTimeline.build(model, 1.0, 800);
        });
        assertNotNull(node);
        assertFalse(((Parent) node).getChildrenUnmodifiable().isEmpty(), "timeline node should have children");
    }

    @Test
    void buildsANonEmptyCalendarNode() throws Exception {
        String mw = "title: Demo\n#Travel: blue\n\n2023-01-15: Kickoff #Travel\n2023-02 / 2023-03: Sprint\n";
        Node node = FxTestSupport.callOnFx(() -> MarkwhenCalendar.build(MarkwhenParser.parse(mw), 1.0, 800));
        assertNotNull(node);
        assertFalse(((Parent) node).getChildrenUnmodifiable().isEmpty(), "calendar node should have month cards");
    }

    @Test
    void emptyTimelineRendersPlaceholderNotCrash() throws Exception {
        Node node = FxTestSupport.callOnFx(
                () -> MarkwhenTimeline.build(MarkwhenParser.parse("// just a comment\n"), 1.0, 800));
        assertNotNull(node);
    }
}
