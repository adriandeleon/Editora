package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetalsStatusViewTest {

    private static LanguageServerSession.MetalsStatus status(String text, Boolean hide) {
        var s = new LanguageServerSession.MetalsStatus();
        s.text = text;
        s.hide = hide;
        return s;
    }

    @Test
    void aSpinningItemIsProgressAndItsIconIsDropped() {
        assertEquals(
                new MetalsStatusView("Progress", "Compiling root"),
                MetalsStatusView.of(status("$(sync~spin) Compiling root", null)));
    }

    @Test
    void anyOtherTextIsAMessage() {
        assertEquals(
                new MetalsStatusView("Message", "Build imported"),
                MetalsStatusView.of(status("$(check)  Build imported", false)));
        assertEquals(new MetalsStatusView("Message", "Metals"), MetalsStatusView.of(status("Metals", null)));
    }

    @Test
    void hidingEndsProgressAndEmptyTextSaysNothing() {
        assertEquals(new MetalsStatusView("ProgressEnd", null), MetalsStatusView.of(status("ignored", true)));
        assertNull(MetalsStatusView.of(status("$(sync~spin)", null)));
        assertNull(MetalsStatusView.of(status(null, null)));
        assertNull(MetalsStatusView.of(null));
    }
}
