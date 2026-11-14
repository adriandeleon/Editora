package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.editor.MenuChords;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A right-click or drop-down row measures as a menu bar row does (MENU-18, Pauta 6.0.1): 34 px high, a 22 px icon
 * column with 8 px to the title, and the chord at least 18 px after the title column; a row without a glyph keeps
 * the column, in a menu where other rows have one and in a menu where none has.
 */
@Tag("fx")
class MenuRowLayoutFxTest {

    private static Stage stage;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            stage = new Stage();
            Scene scene = new Scene(new StackPane(new Region()), 600, 400);
            scene.getStylesheets()
                    .add(MenuRowLayoutFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
        });
    }

    @AfterAll
    static void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stage.close();
            stage = null;
        });
    }

    @AfterEach
    void reset() throws Exception {
        FxTestSupport.runOnFx(() -> MenuChords.setChords(Map::of));
    }

    /** One laid-out row: the row itself, and its column, title and chord in the row's coordinates. */
    private record Row(String title, Region row, Bounds column, Bounds titleText, Bounds chord) {}

    @Test
    void aRowWithAGlyphAndARowWithoutOneMeasureAsTheMenuBarsRows() throws Exception {
        List<Row> rows = showAndMeasure(() -> {
            MenuChords.setChords(() -> Map.of("file.save", "C-x C-s"));
            MenuItem save = MenuChords.tag(new MenuItem("Save", Icons.save()), "file.save");
            MenuItem rename = new MenuItem("Rename a longer title…");
            return new ContextMenu(save, new SeparatorMenuItem(), rename);
        });
        assertEquals(2, rows.size());
        Row save = rows.get(0);
        Row rename = rows.get(1);
        for (Row r : rows) {
            assertEquals(34, r.row().getHeight(), 0.5, r.title() + ": the row is 34 px high");
            assertEquals(12 + 22 + 8, r.titleText().getMinX(), 0.5, r.title() + ": 12 px in, a 22 px column, 8 px");
        }
        assertEquals(12, save.column().getMinX(), 0.5, "the column starts at the row's padding");
        assertEquals(30, save.column().getWidth(), 0.5, "22 px for the glyph and 8 px to the title");
        assertNotNull(save.chord(), "Save shows its chord");
        double titleColumnEnd =
                Math.max(save.titleText().getMaxX(), rename.titleText().getMaxX());
        assertTrue(
                save.chord().getMinX() - titleColumnEnd >= 18 - 0.5,
                "the chord starts 18 px or more after the title column: "
                        + (save.chord().getMinX() - titleColumnEnd));
    }

    @Test
    void aMenuWhereNoRowHasAGlyphStillHasTheColumn() throws Exception {
        ContextMenu[] menu = new ContextMenu[1];
        List<Row> rows = showAndMeasure(() -> {
            menu[0] = new ContextMenu(new MenuItem("Copy"), new MenuItem("Select All"));
            return menu[0];
        });
        assertTrue(menu[0].getStyleClass().contains(MenuRowLayout.BLANK_COLUMN));
        for (Row r : rows) {
            assertEquals(34, r.row().getHeight(), 0.5, r.title() + ": the row is 34 px high");
            assertEquals(12 + 22 + 8, r.titleText().getMinX(), 0.5, r.title() + ": the title after the column");
        }
    }

    @Test
    void aTickSitsInTheSameColumn() throws Exception {
        List<Row> rows = showAndMeasure(() -> {
            CheckMenuItem on = new CheckMenuItem("Wrap");
            on.setSelected(true);
            return new ContextMenu(on, new CheckMenuItem("Follow"));
        });
        for (Row r : rows) {
            assertEquals(12, r.column().getMinX(), 0.5, r.title() + ": the tick's column starts at the padding");
            assertEquals(30, r.column().getWidth(), 0.5, r.title() + ": 22 px and 8 px to the title");
            assertEquals(12 + 22 + 8, r.titleText().getMinX(), 0.5, r.title());
        }
    }

    /** A menu with tick rows and glyph rows keeps one column: the tick is drawn where a glyph would be. */
    @Test
    void aTickAndAGlyphShareOneColumn() throws Exception {
        ContextMenu[] menu = new ContextMenu[1];
        List<Row> rows = showAndMeasure(() -> {
            CheckMenuItem on = new CheckMenuItem("Wrap");
            on.setSelected(true);
            menu[0] = new ContextMenu(on, new MenuItem("Save", Icons.save()));
            return menu[0];
        });
        assertTrue(menu[0].getStyleClass().contains(MenuRowLayout.SHARED_COLUMN));
        assertFalse(menu[0].getStyleClass().contains(MenuRowLayout.BLANK_COLUMN));
        for (Row r : rows) {
            assertEquals(34, r.row().getHeight(), 0.5, r.title() + ": the row is 34 px high");
            assertEquals(12 + 22 + 8, r.titleText().getMinX(), 0.5, r.title() + ": one column before the title");
        }
        Bounds tick = rows.get(0).column();
        assertEquals(12 + 11, (tick.getMinX() + tick.getMaxX()) / 2, 1.0, "the tick in the middle of the 22 px column");
        assertEquals(12, rows.get(1).column().getMinX(), 0.5, "the glyph's column starts at the row's padding");
    }

    /** Whether the column is decided for each showing: a row given a glyph later takes the padding away. */
    @Test
    void theBlankColumnIsDecidedOnEachShowing() {
        assertTrue(MenuRowLayout.needsBlankColumn(List.of(new MenuItem("a"), new SeparatorMenuItem())));
        MenuItem glyph = new MenuItem("b");
        glyph.setGraphic(new Region());
        assertFalse(MenuRowLayout.needsBlankColumn(List.of(new MenuItem("a"), glyph)));
        glyph.setVisible(false);
        assertTrue(MenuRowLayout.needsBlankColumn(List.of(new MenuItem("a"), glyph)), "a hidden glyph is no column");
        assertFalse(MenuRowLayout.needsBlankColumn(List.of(new CheckMenuItem("c"))));
        assertFalse(MenuRowLayout.needsBlankColumn(List.of()), "nothing to line up");
        CheckMenuItem tick = new CheckMenuItem("c");
        assertTrue(MenuRowLayout.needsSharedColumn(List.of(tick, withGlyph())), "a tick row and a glyph row");
        assertFalse(MenuRowLayout.needsSharedColumn(List.of(tick, new MenuItem("a"))), "ticks alone");
        assertFalse(MenuRowLayout.needsSharedColumn(List.of(withGlyph(), new MenuItem("a"))), "glyphs alone");
        tick.setGraphic(new Region());
        assertFalse(MenuRowLayout.needsSharedColumn(List.of(tick, withGlyph())), "a tick row with a glyph needs both");
    }

    private static MenuItem withGlyph() {
        MenuItem item = new MenuItem("g");
        item.setGraphic(new Region());
        return item;
    }

    private static List<Row> showAndMeasure(java.util.function.Supplier<ContextMenu> make) throws Exception {
        ContextMenu menu = FxTestSupport.callOnFx(() -> {
            ContextMenu made = make.get();
            made.show(stage.getScene().getRoot(), stage.getX() + 20, stage.getY() + 20);
            return made;
        });
        try {
            return FxTestSupport.callOnFx(() -> {
                Parent skin = (Parent) menu.getSkin().getNode();
                skin.applyCss();
                skin.layout();
                List<Row> rows = new ArrayList<>();
                for (Node node : skin.lookupAll(".menu-item")) {
                    if (!(node instanceof Region row) || !node.isVisible()) {
                        continue;
                    }
                    Label title = null;
                    Label chord = null;
                    Region column = null;
                    for (Node child : row.getChildrenUnmodifiable()) {
                        if (child instanceof Label label
                                && label.getStyleClass().contains("accelerator-text")) {
                            chord = label;
                        } else if (child instanceof Label label) {
                            title = label;
                        } else if (child instanceof Region region
                                && (region.getStyleClass().contains("graphic-container")
                                        || region.getStyleClass().contains("left-container"))) {
                            column = region;
                        }
                    }
                    if (title == null) {
                        continue; // a separator
                    }
                    Text text = (Text) title.lookup(".text");
                    rows.add(new Row(
                            title.getText(),
                            row,
                            column == null ? null : column.getBoundsInParent(),
                            row.sceneToLocal(text.localToScene(text.getBoundsInLocal())),
                            chord == null ? null : chord.getBoundsInParent()));
                }
                return rows;
            });
        } finally {
            FxTestSupport.runOnFx(menu::hide);
        }
    }
}
