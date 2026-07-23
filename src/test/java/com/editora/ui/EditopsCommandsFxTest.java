package com.editora.ui;

import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.search.LineMatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tabify/untabify/align/occur commands through the real {@link CommandRegistry} against a wired
 * window — the region resolution, the tab width coming from settings, and the prompt-past seams. The
 * transform arithmetic itself is the pure {@code TabConvertTest}/{@code AlignRegexpTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditopsCommandsFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setTabSize(4));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private EditorBuffer open(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    // --- tabify / untabify (immediate, no prompt) ------------------------------------------------

    @Test
    void untabifyExpandsTheWholeBufferWhenThereIsNoSelection() throws Exception {
        EditorBuffer b = open("\tx\n\t\ty");
        run("edit.untabify");
        assertEquals("    x\n        y", text(b), "tab width 4 from settings");
    }

    @Test
    void tabifyConvertsSpaceIndentationBack() throws Exception {
        EditorBuffer b = open("        deep\n    x");
        run("edit.tabify");
        assertEquals("\t\tdeep\n\tx", text(b));
    }

    @Test
    void untabifyActsOnlyOnTheSelectedLines() throws Exception {
        EditorBuffer b = open("\tkept\n\tchanged");
        FxTestSupport.runOnFx(() -> {
            // select within the second line; lineBounds extends it to the whole line
            int start = b.getArea().getText().indexOf("changed");
            b.getArea().selectRange(start, start + "changed".length());
        });
        run("edit.untabify");
        assertEquals("\tkept\n    changed", text(b), "the first line's tab is left alone");
    }

    // --- align (via the prompt-past seam) --------------------------------------------------------

    @Test
    void alignRegexpPadsTheWholeBufferToLineUpTheMatch() throws Exception {
        EditorBuffer b = open("a = 1\nbbb = 2");
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(fx.controller, "applyAlignRegexp", new Class[] {String.class}, "="));
        assertEquals("a   = 1\nbbb = 2", text(b));
    }

    @Test
    void alignRegexpWithABadPatternLeavesTheBufferAlone() throws Exception {
        EditorBuffer b = open("a=1\nbb=2");
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(fx.controller, "applyAlignRegexp", new Class[] {String.class}, "("));
        assertEquals("a=1\nbb=2", text(b));
    }

    // --- occur (via the match-list seam) ---------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void occurMatchesListsEveryMatchingLineWithItsNumber() throws Exception {
        EditorBuffer b = open("alpha\nbeta TODO\ngamma\ndelta TODO done");
        List<LineMatch> matches = (List<LineMatch>) FxTestSupport.callOnFx(() -> FxTestSupport.call(
                fx.controller, "occurMatches", new Class[] {String.class, String.class}, text(b), "todo"));
        assertEquals(2, matches.size(), "case-insensitive regex over the buffer");
        assertEquals(2, matches.get(0).line(), "1-based line numbers");
        assertEquals(4, matches.get(1).line());
    }
}
