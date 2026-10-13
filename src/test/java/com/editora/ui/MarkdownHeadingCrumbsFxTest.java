package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Hyperlink;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The breadcrumb shows where the caret is in a Markdown document's headings. */
@Tag("fx")
class MarkdownHeadingCrumbsFxTest {

    private static final String DOC =
            "intro\n\n# Guide\n\ntext\n\n## Install *now*\n\nsteps\n\n```\n# not a heading\n```\n\n### Linux\n\napt\n\n## Use\n\nrun\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(String name, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("/tmp/crumbs", name).toAbsolutePath());
            b.setContent(text);
            return b;
        });
    }

    private static List<String> at(FileBreadcrumb bar, EditorBuffer b, int line) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            b.getArea().moveTo(line, 0);
            bar.headingCrumbs().refresh();
            return bar.headingCrumbs().titles();
        });
    }

    @Test
    void theCaretsHeadingPathFollowsTheCaretAndTheText() throws Exception {
        EditorBuffer md = buffer("a.md", DOC);
        FileBreadcrumb bar = FxTestSupport.callOnFx(() -> {
            FileBreadcrumb b = new FileBreadcrumb(p -> {});
            b.setEnabled(true);
            b.setActiveBuffer(md);
            return b;
        });
        assertEquals(List.of(), at(bar, md, 0), "above the first heading");
        assertFalse(FxTestSupport.callOnFx(() -> bar.headingCrumbs().isManaged()));
        assertEquals(List.of("Guide"), at(bar, md, 4));
        assertEquals(List.of("Guide", "Install now"), at(bar, md, 11), "a # line inside a fence is not a heading");
        assertEquals(List.of("Guide", "Install now", "Linux"), at(bar, md, 16));
        assertEquals(List.of("Guide", "Use"), at(bar, md, 20));
        assertTrue(FxTestSupport.callOnFx(() -> bar.headingCrumbs().isManaged()));

        // An edit is picked up at the next refresh.
        FxTestSupport.runOnFx(() -> md.getArea().replaceText(0, 5, "# Top"));
        assertEquals(List.of("Guide", "Use"), at(bar, md, 20));
        assertEquals(List.of("Top"), at(bar, md, 0));

        // A crumb goes to its heading.
        at(bar, md, 16);
        FxTestSupport.runOnFx(() -> {
            Hyperlink install = (Hyperlink) bar.headingCrumbs().getChildren().get(3);
            assertEquals("Install now", install.getText());
            install.fire();
        });
        assertEquals(6, (int) FxTestSupport.callOnFx(() -> md.getArea().getCurrentParagraph()));
    }

    @Test
    void otherBuffersAndADisabledBarShowNoHeadings() throws Exception {
        EditorBuffer md = buffer("a.md", DOC);
        EditorBuffer java = buffer("A.java", "# Guide\nclass A {}\n");
        FileBreadcrumb bar = FxTestSupport.callOnFx(() -> {
            FileBreadcrumb b = new FileBreadcrumb(p -> {});
            b.setEnabled(true);
            b.setActiveBuffer(md);
            return b;
        });
        assertEquals(List.of("Guide"), at(bar, md, 4));
        FxTestSupport.runOnFx(() -> bar.setActiveBuffer(java));
        assertEquals(List.of(), at(bar, java, 1));
        FxTestSupport.runOnFx(() -> bar.setActiveBuffer(md));
        assertEquals(List.of("Guide"), at(bar, md, 4));
        FxTestSupport.runOnFx(() -> bar.setEnabled(false));
        assertEquals(List.of(), at(bar, md, 4), "a hidden bar follows nothing");
        FxTestSupport.runOnFx(() -> bar.setActiveBuffer(null));
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> bar.headingCrumbs().titles()));
    }
}
