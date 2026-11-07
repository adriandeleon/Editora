package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.collections.ObservableList;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The buffer switcher ({@code C-x b}) is the same size as the file finder ({@code C-x C-f}): the same card
 * width and height, and rows of the same height, however few buffers there are.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BufferSwitcherSizeFxTest {

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
    void theOpenFilesPickerUsesTheFileFindersListHeight() throws Exception {
        NavigationCoordinator navigation = FxTestSupport.field(fx.controller, "navigation");
        double height = FxTestSupport.field(navigation.openFilesPalette, "fixedListHeight");
        assertEquals(FileFinder.LIST_HEIGHT, height);
    }

    @Test
    void aFewBuffersGiveTheSameCardAsTheFileFinder(@TempDir Path dir) throws Exception {
        List<String> names = List.of("Alpha.java", "beta.md", "gamma.txt");
        for (String name : names) {
            Files.writeString(dir.resolve(name), "");
        }

        FileFinder finder = FxTestSupport.callOnFx(() -> {
            FileFinder f = new FileFinder(() -> dir, p -> {});
            f.setOverlayHost(overlay);
            f.show(null);
            return f;
        });
        ObservableList<Path> found = FxTestSupport.field(finder, "items");
        for (int i = 0; i < 200 && found.size() < names.size(); i++) {
            Thread.sleep(20);
            FxTestSupport.drainFx();
        }
        assertEquals(names.size(), found.size(), "the finder lists the folder");
        double[] finderSize = measure(FxTestSupport.field(finder, "content"), FxTestSupport.field(finder, "list"));
        FxTestSupport.runOnFx(finder::hide);

        QuickOpen<String> switcher = FxTestSupport.callOnFx(() -> {
            QuickOpen<String> q = new QuickOpen<>("Switch to Buffer", "", () -> names, s -> s, s -> "~/src", s -> {});
            q.setItemIcon(FileIcons::forFileName);
            q.setFixedListHeight(FileFinder.LIST_HEIGHT);
            q.setOverlayHost(overlay);
            q.show(null);
            return q;
        });
        double[] switcherSize =
                measure(FxTestSupport.field(switcher, "content"), FxTestSupport.field(switcher, "list"));
        FxTestSupport.runOnFx(switcher::hide);

        assertEquals(finderSize[0], switcherSize[0], 0.5, "card width");
        assertEquals(finderSize[1], switcherSize[1], 0.5, "card height");
        assertEquals(finderSize[2], switcherSize[2], 0.5, "row height");
    }

    /**
     * The card's width and height and its first row's height, once laid out: the list builds its cells on a
     * later pulse than the one that shows the card, so this waits for a filled row with a height.
     */
    private static double[] measure(VBox card, ListView<?> list) throws Exception {
        double[] size = {0, 0, 0};
        for (int i = 0; i < 100 && size[2] == 0; i++) {
            FxTestSupport.drainFx();
            size = FxTestSupport.callOnFx(() -> {
                card.applyCss();
                card.layout();
                double row = list.lookupAll(".list-cell").stream()
                        .map(n -> (ListCell<?>) n)
                        .filter(c -> !c.isEmpty())
                        .mapToDouble(ListCell::getHeight)
                        .filter(h -> h > 0)
                        .findFirst()
                        .orElse(0);
                return new double[] {card.getWidth(), card.getHeight(), row};
            });
            if (size[2] == 0) {
                Thread.sleep(20);
            }
        }
        assertFalse(size[2] == 0, "a row was laid out");
        return size;
    }
}
