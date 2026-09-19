package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignatureSelectionTest {
    private SignatureHelp help(String... labels) {
        return new SignatureHelp(
                java.util.Arrays.stream(labels).map(SignatureInformation::new).toList(), 0, 0);
    }

    @Test
    void explicitSelectionSurvivesReorderingAndReachesTheNextRequest() {
        var selection = new SignatureSelection();
        selection.update(help("foo(int)", "foo(String)"));
        selection.move(1);
        selection.update(help("foo(String)", "foo(int)"));
        assertEquals("foo(String)", selection.active().label());
        assertEquals(0, selection.context().getActiveSignature());
        selection.move(-1);
        assertEquals("foo(int)", selection.active().label());
    }

    @Test
    void serverControlsSelectionUntilTheUserChoosesAndAfterItDisappears() {
        var selection = new SignatureSelection();
        var help = help("foo(int)", "foo(String)");
        help.setActiveSignature(1);
        selection.update(help);
        assertEquals(1, selection.active().index());
        selection.move(1);
        selection.update(help("foo(double)"));
        assertEquals("foo(double)", selection.active().label());
        selection.update(new SignatureHelp(List.of(), 0, 0));
        assertNull(selection.active());
        assertNull(selection.context());
    }
}
