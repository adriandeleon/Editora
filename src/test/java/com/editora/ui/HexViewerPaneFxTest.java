package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots the FX toolkit and checks the hex viewer renders a small binary as an offset/hex/ASCII dump. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HexViewerPaneFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void rendersABinaryFileAsAHexDump(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blob.bin");
        Files.write(file, new byte[] {0x00, 0x01, 0x41, 0x42, (byte) 0xFF});
        boolean[] builtInConstructor = new boolean[1];
        HexViewerPane pane = FxTestSupport.callOnFx(() -> {
            HexViewerPane p = new HexViewerPane(file);
            builtInConstructor[0] = p.isLoaded(); // the FX thread is held: only an inline load could be done
            return p;
        });
        assertFalse(builtInConstructor[0], "the read + dump run on a worker, not in the constructor");
        pane.loadedForTest().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(pane.node());
        assertEquals("blob.bin", pane.title());
        assertEquals(file, pane.getPath());
        assertTrue(pane.isLoaded(), "the dump built");
        String dumped = FxTestSupport.callOnFx(
                () -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(pane, "area")).getText());
        assertTrue(dumped.startsWith("00000000  00 01 41 42 FF "), dumped);
        assertTrue(dumped.endsWith("|..AB.|"), dumped); // 0x00/0x01/0xFF → dots, 0x41/0x42 → 'A'/'B'
    }

    @Test
    void anUnreadableFileShowsAMessageAndADisposedPaneStaysEmpty(@TempDir Path dir) throws Exception {
        HexViewerPane missing = FxTestSupport.callOnFx(() -> new HexViewerPane(dir.resolve("nope.bin")));
        missing.loadedForTest().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(missing.isLoaded());
        javafx.scene.Node center =
                FxTestSupport.callOnFx(() -> ((javafx.scene.layout.BorderPane) missing.node()).getCenter());
        javafx.scene.control.Label label = (javafx.scene.control.Label)
                ((javafx.scene.layout.StackPane) center).getChildren().get(0);
        assertEquals(com.editora.i18n.Messages.tr("hexviewer.loadFailed"), label.getText());

        Path file = dir.resolve("late.bin");
        Files.write(file, new byte[] {1, 2, 3});
        HexViewerPane closed = FxTestSupport.callOnFx(() -> {
            HexViewerPane p = new HexViewerPane(file);
            p.dispose();
            return p;
        });
        closed.loadedForTest().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(closed.isLoaded(), "a tab closed while loading gets no dump");
    }
}
