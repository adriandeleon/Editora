package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Turning a preview feature off must drop a buffer back to the editor exactly when it has no preview left —
 * no more, no less. Each setter used to ask "is this file MY format?" instead, which is wrong in both
 * directions: several formats share one setting (XML rides {@code structuredPreview}), and one file can have
 * two previews (a workflow is also YAML). A buffer stranded in PREVIEW with no preview falls through every
 * branch of {@code scheduleRenderPreview} to its unconditional Markdown tail — i.e. it renders its own source
 * as Markdown — and {@code hasPreview()} being false also takes away the toggle needed to escape.
 */
@Tag("fx")
class PreviewModeReconcileFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(Path path, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(path); // derives the language from the file name
            b.getArea().replaceText(text);
            return b;
        });
    }

    @Test
    void turningOffStructuredDropsAnXmlBufferBackToTheEditor() throws Exception {
        // XML has no preview of its own setting — its DOM tree rides Settings.structuredPreview.
        EditorBuffer b = buffer(Path.of("/tmp/pom.xml"), "<project><name>x</name></project>");
        FxTestSupport.runOnFx(() -> {
            b.setStructuredPreviewEnabled(true);
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
        });
        assertEquals(
                EditorBuffer.MarkdownViewMode.PREVIEW,
                FxTestSupport.callOnFx(b::getMarkdownViewMode),
                "precondition: the XML tree preview is showing");

        FxTestSupport.runOnFx(() -> b.setStructuredPreviewEnabled(false)); // the user unticks "Structured data"

        assertEquals(
                EditorBuffer.MarkdownViewMode.EDITOR,
                FxTestSupport.callOnFx(b::getMarkdownViewMode),
                "the XML preview is gone, so the buffer must return to the editor rather than be stranded");
    }

    @Test
    void turningOffStructuredKeepsAWorkflowInItsOwnPreview() throws Exception {
        // A workflow is YAML, so it is "structured" — but its digest is a different feature, still enabled.
        String workflow = """
                name: CI
                on:
                  push:
                    branches: [main]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - run: npm test
                """;
        EditorBuffer b = buffer(Path.of("/tmp/ci.yml"), workflow);
        FxTestSupport.runOnFx(() -> {
            b.setStructuredPreviewEnabled(true);
            b.setGithubActionsPreviewEnabled(true);
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
        });

        FxTestSupport.runOnFx(() -> b.setStructuredPreviewEnabled(false)); // unrelated feature turned off

        assertEquals(
                EditorBuffer.MarkdownViewMode.PREVIEW,
                FxTestSupport.callOnFx(b::getMarkdownViewMode),
                "the workflow digest is still enabled, so its persisted preview mode must not be clobbered");
    }

    @Test
    void turningOffTheLastPreviewOfAYamlFileDropsToTheEditor() throws Exception {
        // Plain YAML (not a workflow): structured is its only preview, so off means back to the editor.
        EditorBuffer b = buffer(Path.of("/tmp/conf.yml"), "a: 1\nb: two\n");
        FxTestSupport.runOnFx(() -> {
            b.setStructuredPreviewEnabled(true);
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
        });

        FxTestSupport.runOnFx(() -> b.setStructuredPreviewEnabled(false));

        assertEquals(
                EditorBuffer.MarkdownViewMode.EDITOR,
                FxTestSupport.callOnFx(b::getMarkdownViewMode),
                "no preview left — back to the editor");
    }
}
