package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        HexViewerPane pane = FxTestSupport.callOnFx(() -> new HexViewerPane(file));
        assertNotNull(pane.node());
        assertEquals("blob.bin", pane.title());
        assertEquals(file, pane.getPath());
        assertTrue(pane.isLoaded(), "the dump built");
        String dumped = FxTestSupport.callOnFx(
                () -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(pane, "area")).getText());
        assertTrue(dumped.startsWith("00000000  00 01 41 42 FF "), dumped);
        assertTrue(dumped.endsWith("|..AB.|"), dumped); // 0x00/0x01/0xFF → dots, 0x41/0x42 → 'A'/'B'
    }
}
