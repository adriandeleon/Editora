package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;

import com.editora.print.PrintService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printed excerpts of a file (Print Selection…): numbered as in the file, highlighted as in the file. */
@Tag("fx")
class PrintSelectionLayoutFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void texts(Node root, List<String> out) {
        if (root instanceof Text t) {
            out.add(t.getText());
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> texts(c, out));
        }
    }

    @Test
    void theGutterCountsFromTheExcerptsFirstLineAndIsWideEnoughForItsLastNumber() throws Exception {
        StringBuilder file = new StringBuilder();
        for (int i = 1; i <= 1200; i++) {
            file.append("line ").append(i).append('\n');
        }
        String text = file.toString();
        LineSelection lines = LineSelection.of(text, text.indexOf("line 998\n"), text.indexOf("line 1001\n") + 2);
        assertEquals(998, lines.firstLine());
        assertEquals(1001, lines.lastLine());

        PrintService service = new PrintService();
        try {
            PrintService.Prepared[] prepared = new PrintService.Prepared[1];
            CountDownLatch ready = new CountDownLatch(1);
            service.prepareCodeLines(
                    text, lines.start(), lines.end(), lines.firstLine(), "notes.txt", true, true, 4, p -> {
                        prepared[0] = p;
                        ready.countDown();
                    });
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            assertTrue(prepared[0].ok(), prepared[0].error());
            FxTestSupport.runOnFx(() -> {
                try {
                    PrintService.Pages pages = prepared[0].paginator().pages(PrintPreviewFxTest.letter());
                    assertEquals(1, pages.count());
                    Parent page = (Parent) pages.get(0);
                    List<String> gutter = new ArrayList<>();
                    List<String> code = new ArrayList<>();
                    double gutterWidth = 0;
                    for (Node row : page.getChildrenUnmodifiable()) {
                        HBox box = (HBox) row;
                        HBox numbers = (HBox) box.getChildren().get(0);
                        texts(numbers, gutter);
                        gutterWidth = numbers.getPrefWidth();
                        List<String> rest = new ArrayList<>();
                        texts(box.getChildren().get(1), rest);
                        code.add(String.join("", rest));
                    }
                    assertEquals(List.of("998", "999", "1000", "1001"), gutter);
                    assertEquals(List.of("line 998", "line 999", "line 1000", "line 1001"), code);
                    Text widest = new Text("1001");
                    widest.setFont(
                            javafx.scene.text.Font.font("JetBrains Mono", com.editora.print.CodePrintLayout.FONT_SIZE));
                    assertTrue(
                            gutterWidth >= widest.getLayoutBounds().getWidth(),
                            "four digits must fit a gutter sized for a four-line excerpt: " + gutterWidth);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            service.shutdown();
        }
    }

    /** A selection that starts inside a block comment is coloured as a comment, as it is in the editor. */
    @Test
    void anExcerptIsHighlightedFromTheWholeFile() {
        String text = "class A {\n    /*\n     * int notCode = 1;\n     */\n    int code = 2;\n}\n";
        int start = text.indexOf("     * int notCode");
        int end = text.indexOf('\n', start);
        var inComment = PrintService.excerptSpans(text, start, end, "A.java");
        assertNotNull(inComment, "Java has a bundled grammar");
        assertEquals(end - start, inComment.length());
        var alone = PrintService.excerptSpans(text.substring(start, end), 0, end - start, "A.java");
        String whole = String.valueOf(
                inComment.getStyleSpan(inComment.getSpanCount() - 1).getStyle());
        assertTrue(whole.contains("comment"), "the line is inside a block comment: " + whole);
        assertTrue(
                !String.valueOf(alone.getStyleSpan(alone.getSpanCount() - 1).getStyle())
                        .equals(whole),
                "highlighted on its own the same line reads as code — the excerpt must not be highlighted alone");
        assertEquals(null, PrintService.excerptSpans(text, start, end, null), "highlighting switched off");
    }
}
