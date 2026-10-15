package com.editora.ui;

import com.editora.command.KeymapManager;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The .http ▶ tooltip names both chords of the active keymap: run this request, run them all (C9). */
class HttpRunTooltipTest {

    @Test
    void theTooltipNamesTheRunAndRunAllChords() {
        KeymapManager keymap = new KeymapManager();
        keymap.loadNamed("vscode");
        assertEquals(
                tr(
                        "editmenu.runHttpRequest.tip.chords",
                        keymap.displayChord("http.runRequest"),
                        keymap.displayChord("http.runFile")),
                MainController.httpRunTooltip(keymap));
        assertEquals(tr("editmenu.runHttpRequest.tip"), MainController.httpRunTooltip(null));
    }
}
