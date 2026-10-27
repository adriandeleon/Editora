package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import com.editora.text.LspTextEdit;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tab re-indents a line through the server's range formatting. lemminx states an indent as an edit of the line
 * break before the line, and answers a one-line range with nothing, so Tab on a non-blank XML line did nothing
 * while the server ran (XML review B4).
 */
@Tag("fx")
class LspTabReindentFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private record Asked(int startLine, int startChar, int endLine, int endChar) {}

    /** An XML buffer whose range formatter answers {@code answer} and records what it was asked. */
    private static EditorBuffer xml(String text, List<Asked> asked, List<LspTextEdit> answer) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride("xml");
        buffer.getArea().replaceText(text);
        buffer.setLspActive(true);
        buffer.setLspRangeFormatAvailable(true);
        buffer.setLspRangeFormatter((sl, sc, el, ec, cb) -> {
            asked.add(new Asked(sl, sc, el, ec));
            cb.accept(answer);
        });
        return buffer;
    }

    @Test
    void tabTakesTheIndentLemminxGivesFromThePreviousLinesEnd() throws Exception {
        EditorFx.onFx(() -> {
            List<Asked> asked = new ArrayList<>();
            // lemminx's answer for `<b>1</b>` at column 0 inside `  <a>`: the break and the indent, rewritten
            EditorBuffer buffer =
                    xml("<r>\n  <a>\n<b>1</b>\n  </a>\n</r>\n", asked, List.of(new LspTextEdit(1, 5, 2, 0, "\n    ")));
            Stage stage = EditorFx.show(buffer, 400, 200);
            CodeArea area = buffer.getArea();
            area.moveTo(2, 0);
            area.fireEvent(EditorFx.pressed(KeyCode.TAB, false, false));

            assertEquals(List.of(new Asked(1, 5, 2, 8)), asked, "asked from the line break, not the line alone");
            assertEquals("    <b>1</b>", area.getParagraph(2).getText());
            assertEquals(area.getAbsolutePosition(2, 4), area.getCaretPosition(), "the caret goes where typing starts");
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void anUnusableAnswerFallsBackToSmartTab() throws Exception {
        EditorFx.onFx(() -> {
            // An edit that joins the lines cannot be an indent: Tab does what it does without a server.
            EditorBuffer buffer =
                    xml("<r>\n<b>1</b>\n</r>\n", new ArrayList<>(), List.of(new LspTextEdit(0, 3, 1, 0, "")));
            Stage stage = EditorFx.show(buffer, 400, 200);
            CodeArea area = buffer.getArea();
            area.moveTo(1, 0);
            area.fireEvent(EditorFx.pressed(KeyCode.TAB, false, false));

            String line = area.getParagraph(1).getText();
            assertEquals("<b>1</b>", line.strip());
            assertEquals(true, line.startsWith(" ") || line.startsWith("\t"), "the line was indented: [" + line + "]");
            stage.close();
            buffer.dispose();
        });
    }
}
