package com.editora.office;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import com.editora.editor.MathImages;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Mermaid block or a display formula that cannot be drawn is written as its source. The {@code .docx}
 * writer used to create the picture's paragraph before it knew there was a picture, so every such block was
 * preceded by an empty paragraph — for a Mermaid block that is every export on a machine without mmdc.
 */
class DocxFallbackParagraphTest {

    @TempDir
    Path dir;

    private List<String> paragraphs(String md, List<String> mmdc) throws IOException {
        Path out = Files.createTempFile(dir, "doc", ".docx");
        DocxWriter.write(md, dir, mmdc, out);
        try (InputStream in = Files.newInputStream(out);
                XWPFDocument doc = new XWPFDocument(in)) {
            assertEquals(0, doc.getAllPictures().size());
            return doc.getParagraphs().stream().map(XWPFParagraph::getText).toList();
        }
    }

    private List<String> tool(String body) throws IOException {
        Path script = dir.resolve("fake-mmdc.sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return List.of(script.toString());
    }

    @Test
    void aMermaidBlockWithNoRendererIsOnlyItsSource() throws Exception {
        assertEquals(
                List.of("before", "graph TD; A-->B;", "after"),
                paragraphs("before\n\n```mermaid\ngraph TD; A-->B;\n```\n\nafter\n", null));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aMermaidBlockTheRendererRejectsIsOnlyItsSource() throws Exception {
        assertEquals(
                List.of("graph oops"),
                paragraphs("```mermaid\ngraph oops\n```\n", tool("echo 'Parse error' >&2; exit 1")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aRendererThatWritesSomethingThatIsNotAPictureLeavesOnlyTheSource() throws Exception {
        // "-i in -o out": the fourth argument is where the picture goes.
        assertEquals(
                List.of("graph TD; A-->B;"),
                paragraphs("```mermaid\ngraph TD; A-->B;\n```\n", tool("echo 'not a png' > \"$4\"")));
    }

    @Test
    void aDisplayFormulaThatIsNotDrawnIsOnlyItsSource() throws Exception {
        boolean was = MathImages.isEnabled();
        try {
            MathImages.configure(false, false);
            assertEquals(List.of("$$x^2$$"), paragraphs("$$x^2$$\n", null), "math rendering is off");
            MathImages.configure(true, false);
            assertEquals(
                    List.of("$$\\nosuchmacro{1}$$"), paragraphs("$$\\nosuchmacro{1}$$\n", null), "not valid LaTeX");
        } finally {
            MathImages.configure(was, false);
        }
    }
}
