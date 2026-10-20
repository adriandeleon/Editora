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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final String REACTOR_CHILD = """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>reactor-parent</artifactId>
                <version>2.0.0</version>
              </parent>
              <artifactId>core</artifactId>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>dangling</artifactId>
                  <version>${nowhere}</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static List<String> texts(Node n) {
        List<String> out = new ArrayList<>();
        walk(n, out);
        return out;
    }

    private static List<Node> links(Node root) {
        List<Node> out = new ArrayList<>();
        collect(root, out, n -> n.getStyleClass().contains("pom-link"));
        return out;
    }

    private static void collect(Node node, List<Node> out, java.util.function.Predicate<Node> test) {
        if (test.test(node)) {
            out.add(node);
        }
        if (node instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), out, test); // no skin without a scene: the content is not a child yet
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, out, test);
            }
        }
    }

    private static Node first(Node root, String styleClass) {
        List<Node> found = new ArrayList<>();
        collect(root, found, n -> n.getStyleClass().contains(styleClass));
        return found.isEmpty() ? null : found.get(0);
    }

    private static javafx.scene.input.MouseEvent click() {
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                javafx.scene.input.MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null);
    }

    @Test
    void labelsSayInheritedUnderAParentAndUnresolvedForADanglingProperty() throws Exception {
        List<String> texts =
                FxTestSupport.callOnFx(() -> texts(PomPreview.content(PomSummary.parse(REACTOR_CHILD), 700)));
        String all = String.join("\n", texts);
        assertTrue(texts.contains(com.editora.i18n.Messages.tr("pom.inherited")), all);
        assertTrue(texts.contains(com.editora.i18n.Messages.tr("pom.unresolved")), all);
        // A parentless pom with a BOM import names the BOM instead of claiming inheritance.
        String bom = POM.replace(
                        "<properties>",
                        "<dependencyManagement><dependencies><dependency><groupId>org.junit</groupId>"
                                + "<artifactId>junit-bom</artifactId><version>5.11.0</version><type>pom</type>"
                                + "<scope>import</scope></dependency></dependencies></dependencyManagement><properties>")
                .replace("<version>${junit.version}</version>", "");
        List<String> bomTexts = FxTestSupport.callOnFx(() -> texts(PomPreview.content(PomSummary.parse(bom), 700)));
        String bomAll = String.join("\n", bomTexts);
        assertFalse(bomTexts.contains(com.editora.i18n.Messages.tr("pom.inherited")), bomAll);
        assertTrue(
                bomTexts.contains(com.editora.i18n.Messages.tr("pom.probablyFrom", "junit-bom 5.11.0 (import)")),
                bomAll);
    }

    @Test
    void rowsNavigateToTheirLineAndTheFilterNarrowsThem() throws Exception {
        List<Integer> navigated = new ArrayList<>();
        Node preview = FxTestSupport.callOnFx(() -> PomPreview.build(PomSummary.parse(POM), navigated::add));
        FxTestSupport.runOnFx(() -> {
            List<Node> links = links(preview);
            assertFalse(links.isEmpty(), "name and version cells are links");
            // The junit-jupiter row: its <dependency> starts on line 9 of POM.
            Node junit = links.stream()
                    .filter(n -> texts(n).contains("junit-jupiter"))
                    .findFirst()
                    .orElseThrow();
            junit.getOnMouseClicked().handle(click());
        });
        assertEquals(List.of(9), navigated);

        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TextField filter = (javafx.scene.control.TextField) first(preview, "pom-filter");
            assertNotNull(filter, "the interactive preview has a filter field");
            filter.setText("surefire");
            List<Node> rows = new ArrayList<>();
            collect(preview, rows, n -> n.getStyleClass().contains("pom-name"));
            List<String> shown = rows.stream()
                    .filter(Node::isManaged)
                    .map(n -> String.join("", texts(n)))
                    .toList();
            assertEquals(List.of("org.apache.maven.plugins:maven-surefire-plugin"), shown);
            filter.setText("");
            assertTrue(rows.stream().allMatch(Node::isManaged), "an empty filter shows every row again");
        });
    }

    @Test
    void sectionsCollapseOnClickAndALongPropertyListStartsCollapsed() throws Exception {
        StringBuilder props = new StringBuilder();
        for (int i = 0; i < PomPreview.PROPERTIES_COLLAPSED_PAST + 1; i++) {
            props.append("<p").append(i).append(">v</p").append(i).append(">");
        }
        String many =
                POM.replace("<junit.version>5.10.2</junit.version>", "<junit.version>5.10.2</junit.version>" + props);
        Node preview = FxTestSupport.callOnFx(() -> PomPreview.build(PomSummary.parse(many), line -> {}));
        FxTestSupport.runOnFx(() -> {
            List<Node> sections = new ArrayList<>();
            collect(preview, sections, n -> n.getStyleClass().contains("pom-section"));
            Label properties = (Label) sections.stream()
                    .filter(n -> ((Label) n).getText().contains(com.editora.i18n.Messages.tr("pom.properties", 14)))
                    .findFirst()
                    .orElseThrow();
            assertTrue(properties.getText().startsWith("▸"), properties.getText());
            List<Node> names = new ArrayList<>();
            collect(preview, names, n -> n.getStyleClass().contains("pom-name") && texts(n).contains("p3"));
            assertEquals(1, names.size());
            assertFalse(names.get(0).isManaged(), "collapsed: the property rows are hidden");
            properties.getOnMouseClicked().handle(click());
            assertTrue(names.get(0).isManaged(), "a click on the title expands the section");
            assertTrue(properties.getText().startsWith("▾"), properties.getText());
            // Dependencies come before Properties now (the long list used to push them below the fold).
            List<String> order =
                    sections.stream().map(n -> ((Label) n).getText()).toList();
            assertTrue(
                    order.indexOf(properties.getText())
                            > order.stream()
                                    .filter(t -> t.contains(com.editora.i18n.Messages.tr("pom.dependencies", 1)))
                                    .map(order::indexOf)
                                    .findFirst()
                                    .orElseThrow(),
                    order.toString());
        });
    }

    @Test
    void aParseErrorNamesItsLineAndIsALinkToIt() throws Exception {
        List<Integer> navigated = new ArrayList<>();
        Node preview = FxTestSupport.callOnFx(() ->
                PomPreview.build(PomSummary.parse("<project>\n  <artifactId>oops\n</project>\n"), navigated::add));
        FxTestSupport.runOnFx(() -> {
            List<Node> links = links(preview);
            assertEquals(1, links.size());
            String text = ((Label) links.get(0)).getText();
            assertFalse(text.contains("Malformed pom.xml"), text);
            links.get(0).getOnMouseClicked().handle(click());
        });
        assertEquals(List.of(3), navigated);
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
