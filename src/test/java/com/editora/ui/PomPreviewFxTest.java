package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import com.editora.editor.PomPreview;
import com.editora.maven.PomSummary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pom.xml summary preview: what it renders, that it is the <em>default</em> view for a pom (winning over
 * the generic XML tree), and that the switch back to that tree works in both directions — plus the one case
 * it must refuse, where the XML tree's own feature is off and flipping would strand the buffer.
 */
@Tag("fx")
class PomPreviewFxTest {

    private static final String POM = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <properties>
                <junit.version>5.10.2</junit.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>${junit.version}</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("/tmp/pom.xml")); // derives the language (xml) from the file name
            b.getArea().replaceText(text);
            return b;
        });
    }

    @Test
    void listsPropertiesDependenciesAndPluginsWithTheirVersions() throws Exception {
        List<String> texts = FxTestSupport.callOnFx(() -> {
            VBox n = PomPreview.content(PomSummary.parse(POM), 700);
            n.applyCss();
            List<String> out = new ArrayList<>();
            walk(n, out);
            return out;
        });
        String all = String.join("\n", texts);
        assertTrue(all.contains("com.example:demo:1.0.0"), all);
        assertTrue(texts.contains("junit.version"), all);
        assertTrue(texts.contains("junit-jupiter"), all);
        // The version is shown resolved, with the property reference kept beside it as the source.
        assertTrue(texts.contains("5.10.2"), all);
        assertTrue(all.contains("${junit.version}"), all);
        assertTrue(all.contains("test"), all); // the scope tag
        assertTrue(texts.contains("maven-surefire-plugin"), all);
        assertTrue(texts.contains("3.2.5"), all);
    }

    @Test
    void reportsAMalformedPomInsteadOfRenderingNothing() throws Exception {
        List<String> texts = FxTestSupport.callOnFx(() -> {
            VBox n = PomPreview.content(PomSummary.parse("<project><artifactId>oops</project>"), 700);
            List<String> out = new ArrayList<>();
            walk(n, out);
            return out;
        });
        assertFalse(texts.isEmpty(), "a broken pom must still say why");
    }

    @Test
    void theSummaryIsThePomDefaultAndTheXmlTreeIsOneToggleAway() throws Exception {
        EditorBuffer b = buffer(POM);
        FxTestSupport.runOnFx(() -> {
            b.setStructuredPreviewEnabled(true); // the XML tree's feature
            b.setPomPreviewEnabled(true);
        });

        assertTrue(FxTestSupport.callOnFx(b::isPom));
        assertTrue(FxTestSupport.callOnFx(b::hasPomPreview), "a pom shows its summary, not the XML tree");
        assertFalse(FxTestSupport.callOnFx(b::isPomShowingXml));

        assertTrue(FxTestSupport.callOnFx(b::togglePomView));
        assertTrue(FxTestSupport.callOnFx(b::isPomShowingXml));
        assertFalse(FxTestSupport.callOnFx(b::hasPomPreview), "switched to the standard XML rendering");
        assertTrue(FxTestSupport.callOnFx(b::hasXmlPreview));
        assertTrue(FxTestSupport.callOnFx(b::hasPreview), "still previewable — as the XML tree");

        assertTrue(FxTestSupport.callOnFx(b::togglePomView)); // and back
        assertTrue(FxTestSupport.callOnFx(b::hasPomPreview));
    }

    @Test
    void aPomKeepsItsSummaryWhenTheStructuredPreviewIsOff() throws Exception {
        // The pom summary is its own feature: turning off the XML tree must not take it away.
        EditorBuffer b = buffer(POM);
        FxTestSupport.runOnFx(() -> {
            b.setPomPreviewEnabled(true);
            b.setStructuredPreviewEnabled(false);
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
        });
        assertTrue(FxTestSupport.callOnFx(b::hasPomPreview));
        assertFalse(FxTestSupport.callOnFx(b::hasXmlPreview));

        // …and with no XML tree to switch to, the switch refuses rather than stranding the buffer in a view
        // that isn't there (which would fall through to the Markdown tail and render the pom as Markdown).
        assertFalse(FxTestSupport.callOnFx(b::togglePomView));
        assertFalse(FxTestSupport.callOnFx(b::isPomShowingXml));
        assertTrue(FxTestSupport.callOnFx(b::hasPomPreview));
    }

    @Test
    void turningTheFeatureOffLeavesTheXmlTreeAsThePreview() throws Exception {
        EditorBuffer b = buffer(POM);
        FxTestSupport.runOnFx(() -> {
            b.setStructuredPreviewEnabled(true);
            b.setPomPreviewEnabled(true);
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
        });
        FxTestSupport.runOnFx(() -> b.setPomPreviewEnabled(false));

        assertFalse(FxTestSupport.callOnFx(b::hasPomPreview));
        assertTrue(FxTestSupport.callOnFx(b::hasXmlPreview));
        // The buffer still has a preview, so it must stay in it rather than drop back to the editor.
        assertTrue(FxTestSupport.callOnFx(() -> b.getMarkdownViewMode() == EditorBuffer.MarkdownViewMode.PREVIEW));
    }

    @Test
    void anOrdinaryXmlFileIsNotTreatedAsAPom() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(Path.of("/tmp/beans.xml"));
            x.getArea().replaceText("<beans><bean id=\"a\"/></beans>");
            return x;
        });
        FxTestSupport.runOnFx(() -> {
            b.setStructuredPreviewEnabled(true);
            b.setPomPreviewEnabled(true);
        });
        assertFalse(FxTestSupport.callOnFx(b::isPom));
        assertFalse(FxTestSupport.callOnFx(b::hasPomPreview));
        assertTrue(FxTestSupport.callOnFx(b::hasXmlPreview));
    }

    @Test
    void aDifferentlyNamedPomIsRecognizedByItsContent() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(Path.of("/tmp/effective-pom.xml"));
            x.getArea()
                    .replaceText("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                            + "<modelVersion>4.0.0</modelVersion><artifactId>demo</artifactId></project>");
            return x;
        });
        FxTestSupport.runOnFx(() -> b.setPomPreviewEnabled(true));
        assertTrue(FxTestSupport.callOnFx(b::isPom));
    }

    private static void walk(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof Text t && t.getText() != null && !t.getText().isBlank()) {
            out.add(t.getText());
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, out);
            }
        }
    }
}
