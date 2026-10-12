package com.editora.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The toolkit-free half of {@link MarkdownPreview}: cutting a document into keyed blocks, and the line map. */
class MarkdownPreviewTest {

    private static final String DOC =
            "# Title\n\nFirst paragraph\nsecond line.\n\n- a\n- b\n\n```java\nint x;\n```\n\n## Title\n";

    private static List<MarkdownPreview.Unit> units(String md) {
        return MarkdownPreview.plan(md, null).units();
    }

    @Test
    void everyTopLevelBlockGetsItsSourceLinesAndAKey() {
        List<MarkdownPreview.Unit> u = units(DOC);
        assertEquals(5, u.size());
        assertArrayEquals(
                new int[] {0, 0}, new int[] {u.get(0).startLine(), u.get(0).endLine()});
        assertArrayEquals(
                new int[] {2, 3}, new int[] {u.get(1).startLine(), u.get(1).endLine()});
        assertArrayEquals(
                new int[] {5, 6}, new int[] {u.get(2).startLine(), u.get(2).endLine()});
        assertArrayEquals(
                new int[] {8, 10}, new int[] {u.get(3).startLine(), u.get(3).endLine()});
        assertArrayEquals(
                new int[] {12, 12}, new int[] {u.get(4).startLine(), u.get(4).endLine()});
        assertTrue(u.get(1).key().contains("First paragraph\nsecond line."));
    }

    @Test
    void anEditChangesOnlyTheKeyOfItsOwnBlock() {
        List<MarkdownPreview.Unit> before = units(DOC);
        List<MarkdownPreview.Unit> after = units(DOC.replace("second line.", "second line, edited."));
        for (int i = 0; i < before.size(); i++) {
            if (i == 1) {
                assertNotEquals(before.get(i).key(), after.get(i).key());
            } else {
                assertEquals(before.get(i).key(), after.get(i).key(), "block " + i + " is untouched");
            }
        }
        // Lines inserted above move the blocks below without changing what they are.
        List<MarkdownPreview.Unit> shifted = units("New first paragraph.\n\n" + DOC);
        assertEquals(before.get(3).key(), shifted.get(4).key());
        assertEquals(before.get(3).startLine() + 2, shifted.get(4).startLine());
    }

    @Test
    void aBlockThatUsesAReferenceFollowsItsDefinition() {
        String a = "See [the docs][d].\n\nPlain paragraph.\n\n[d]: https://a.example\n";
        String b = a.replace("a.example", "b.example");
        assertNotEquals(units(a).get(0).key(), units(b).get(0).key(), "the link's target changed");
        assertEquals(units(a).get(1).key(), units(b).get(1).key(), "a block without brackets cannot use it");
    }

    @Test
    void headingsGetGithubSlugsWithTheRepeatCounter() {
        List<MarkdownPreview.Unit> u = units("# Hello, World!\n\n## Set `x` up\n\n# Hello, World!\n\ntext\n");
        assertEquals("hello-world", u.get(0).slug());
        assertEquals("set-x-up", u.get(1).slug());
        assertEquals("hello-world-1", u.get(2).slug());
        assertNull(u.get(3).slug());
        Map<String, Integer> seen = new HashMap<>();
        assertEquals("a", MarkdownPreview.unique("a", seen));
        assertEquals("a-1", MarkdownPreview.unique("a", seen));
        assertEquals("a-2", MarkdownPreview.unique("a", seen));
    }

    @Test
    void detailsAndTheBlocksItFoldsAreOneUnit() {
        String md =
                "before\n\n<details>\n<summary>More</summary>\n\nHidden **text**.\n\n- item\n\n</details>\n\nafter\n";
        List<MarkdownPreview.Unit> u = units(md);
        assertEquals(3, u.size(), "before, the whole <details>, after");
        assertEquals(2, u.get(1).startLine());
        assertEquals(9, u.get(1).endLine());
        assertTrue(u.get(1).key().contains("Hidden **text**."), "an edit inside rebuilds the disclosure");
        // Never closed: nothing is folded.
        assertEquals(
                3, units("<details>\n<summary>More</summary>\n\ntext\n\nmore\n").size());
    }

    @Test
    void aLocalImageIsPartOfItsBlocksKey(@TempDir Path dir) throws Exception {
        Path img = dir.resolve("a.png");
        Files.write(img, new byte[] {1, 2, 3});
        String md = "![a](a.png)\n\n<img src=\"a.png\" width=\"10\">\n\nprose\n";
        List<MarkdownPreview.Unit> before = MarkdownPreview.plan(md, dir).units();
        Files.write(img, new byte[] {1, 2, 3, 4, 5});
        List<MarkdownPreview.Unit> after = MarkdownPreview.plan(md, dir).units();
        assertNotEquals(before.get(0).key(), after.get(0).key(), "the file was replaced: the block is rebuilt");
        assertNotEquals(before.get(1).key(), after.get(1).key(), "an HTML <img> too");
        assertEquals(before.get(2).key(), after.get(2).key());
        org.commonmark.node.Node ast = MarkdownRenderer.parseForRender(md, false);
        assertEquals(
                List.of("a.png", "a.png"), MarkdownPreview.imageDestinations(ast.getFirstChild(), ast.getLastChild()));
    }

    @Test
    void commonEndsFindTheUnchangedStartAndEnd() {
        List<String> before = List.of("a", "b", "c", "d");
        assertArrayEquals(new int[] {4, 0}, MarkdownPreview.commonEnds(before, before));
        assertArrayEquals(new int[] {1, 2}, MarkdownPreview.commonEnds(List.of("a", "X", "c", "d"), before));
        assertArrayEquals(new int[] {2, 2}, MarkdownPreview.commonEnds(List.of("a", "b", "N", "c", "d"), before));
        assertArrayEquals(
                new int[] {1, 2}, MarkdownPreview.commonEnds(List.of("a", "c", "d"), before), "a block removed");
        assertArrayEquals(new int[] {0, 0}, MarkdownPreview.commonEnds(List.of("x"), before));
        assertArrayEquals(new int[] {0, 0}, MarkdownPreview.commonEnds(List.of(), before));
        // Repeated blocks never make the two ends overlap.
        assertArrayEquals(new int[] {2, 0}, MarkdownPreview.commonEnds(List.of("a", "a"), List.of("a", "a", "a")));
        // A block without a key (no source span) matches nothing.
        assertArrayEquals(
                new int[] {1, 1},
                MarkdownPreview.commonEnds(Arrays.asList("a", null, "c"), Arrays.asList("a", null, "c")));
    }

    @Test
    void aSourceLineMapsToAnOffsetAndBack() {
        // Block 0: lines 0-0 at 40..80. Block 1: lines 2-3 at 100..2500 (a tall image). Block 2: lines 10-19 at
        // 2520..2720.
        double[] start = {0, 2, 10};
        double[] end = {0, 3, 19};
        double[] top = {40, 100, 2520};
        double[] bottom = {80, 2500, 2720};
        assertEquals(0, MarkdownPreview.yOfLine(0, start, end, top, bottom), "the very top shows the top padding");
        assertEquals(100, MarkdownPreview.yOfLine(2, start, end, top, bottom));
        assertEquals(1300, MarkdownPreview.yOfLine(3, start, end, top, bottom), "half way down the two-line block");
        assertEquals(2520, MarkdownPreview.yOfLine(10, start, end, top, bottom), "not a fraction of the document");
        assertEquals(2620, MarkdownPreview.yOfLine(15, start, end, top, bottom));
        assertEquals(2510, MarkdownPreview.yOfLine(7, start, end, top, bottom), "blank lines: across the gap");
        assertEquals(2720, MarkdownPreview.yOfLine(500, start, end, top, bottom));
        assertEquals(0, MarkdownPreview.yOfLine(5, new double[0], new double[0], new double[0], new double[0]));
        for (double line : new double[] {2, 3, 3.5, 7, 10, 15, 19.5}) {
            double y = MarkdownPreview.yOfLine(line, start, end, top, bottom);
            assertEquals(line, MarkdownPreview.lineAtY(y, start, end, top, bottom), 1e-6, "round trip of " + line);
        }
        assertEquals(20, MarkdownPreview.lineAtY(9999, start, end, top, bottom));
        assertEquals(0, MarkdownPreview.lineAtY(10, start, end, top, bottom));
        // A document that starts with blank lines.
        assertEquals(
                50,
                MarkdownPreview.yOfLine(2, new double[] {4}, new double[] {4}, new double[] {100}, new double[] {120}));
    }

    @Test
    void aWideBlockIsLaidOutAtItsOwnWidthNeverBelowIt() {
        assertEquals(800, SideScroll.contentWidth(500, 800));
        assertEquals(900, SideScroll.contentWidth(900, 800));
        assertEquals(800, SideScroll.contentWidth(-5, 800));
    }

    @Test
    void theThemeToggleGoesThroughThreeStatesAndCanLetGoAgain() {
        // Light app: follow → dark (a visible change) → light, pinned → follow.
        assertEquals("dark", PreviewButtons.nextTheme("", false));
        assertEquals("light", PreviewButtons.nextTheme("dark", false));
        assertEquals("", PreviewButtons.nextTheme("light", false));
        // Dark app: follow → light → dark, pinned → follow.
        assertEquals("light", PreviewButtons.nextTheme("", true));
        assertEquals("dark", PreviewButtons.nextTheme("light", true));
        assertEquals("", PreviewButtons.nextTheme("dark", true));
        assertEquals("dark", PreviewButtons.nextTheme(null, false));
        assertEquals("markdown.previewTheme.toFollow", PreviewButtons.themeActionKey(""));
        assertEquals("markdown.previewTheme.toLight", PreviewButtons.themeActionKey("light"));
        assertEquals("markdown.previewTheme.toDark", PreviewButtons.themeActionKey("dark"));
    }

    @Test
    void aPreviewLayoutIsStoredAsTextAndReadBackWithinBounds() {
        PreviewLayout layout = new PreviewLayout(1.21, 0.35);
        assertEquals("1.21;0.35", layout.text());
        assertEquals(layout, PreviewLayout.parse("1.21;0.35"));
        assertEquals(PreviewLayout.DEFAULT, PreviewLayout.parse(null));
        assertEquals(PreviewLayout.DEFAULT, PreviewLayout.parse("nonsense"));
        assertEquals(PreviewLayout.DEFAULT, PreviewLayout.parse("1.2;x"));
        assertEquals(new PreviewLayout(3.0, 0.9), PreviewLayout.parse("99;7"), "clamped to what the UI allows");
        assertEquals(new PreviewLayout(0.5, 0.1), PreviewLayout.parse("0;-1"));
        assertEquals(PreviewLayout.DEFAULT, new PreviewLayout(Double.NaN, Double.NaN));
        assertTrue(PreviewLayout.DEFAULT.isDefault());
        assertTrue(new PreviewLayout(1.001, 0.5).isDefault(), "a reset is the default again");
        org.junit.jupiter.api.Assertions.assertFalse(layout.isDefault());
    }
}
