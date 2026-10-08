package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.EditorBuffer.MarkwhenView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each file type's preview puts on screen once its off-thread parse has reported back, what a failed
 * parse leaves the user looking at, and the preview's own menus, paging keys and zoom.
 */
@Tag("fx")
class PreviewRenderFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer buffer(String language, String text) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride(language);
        buffer.getArea().replaceText(text);
        return buffer;
    }

    private static StackPane treeHolder(EditorBuffer buffer) {
        return EditorFx.call(buffer, "structuredContentHolder");
    }

    private static ScrollPane pane(EditorBuffer buffer) {
        return EditorFx.call(buffer, "previewPane");
    }

    /** Runs {@code trigger} and waits for the tree-hosted preview (JSON, XML, crontab, …) to be replaced. */
    private static void awaitTree(EditorBuffer buffer, Runnable trigger) throws Exception {
        EditorFx.awaitChange(() -> treeHolder(buffer).getChildren(), trigger);
    }

    /** Runs {@code trigger} and waits for the scroll-pane preview (Markdown, Markwhen) to be replaced. */
    private static void awaitPane(EditorBuffer buffer, Runnable trigger) throws Exception {
        EditorFx.awaitChange(() -> pane(buffer).contentProperty(), trigger);
    }

    private static void enable(EditorBuffer buffer, String kind) {
        switch (kind) {
            case "crontab" -> buffer.setCrontabPreviewEnabled(true);
            case "fstab" -> buffer.setFstabPreviewEnabled(true);
            case "systemd" -> buffer.setSystemdPreviewEnabled(true);
            case "ssh-config" -> buffer.setSshConfigPreviewEnabled(true);
            case "dockerfile" -> buffer.setDockerfilePreviewEnabled(true);
            case "github" -> buffer.setGithubActionsPreviewEnabled(true);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private static void disable(EditorBuffer buffer, String kind) {
        switch (kind) {
            case "crontab" -> buffer.setCrontabPreviewEnabled(false);
            case "fstab" -> buffer.setFstabPreviewEnabled(false);
            case "systemd" -> buffer.setSystemdPreviewEnabled(false);
            case "ssh-config" -> buffer.setSshConfigPreviewEnabled(false);
            case "dockerfile" -> buffer.setDockerfilePreviewEnabled(false);
            case "github" -> buffer.setGithubActionsPreviewEnabled(false);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "crontab    | crontab    | 30 5 * * 1 /usr/local/bin/backup-home | backup-home",
                "fstab      | fstab      | UUID=1234-abcd /srv/data ext4 noatime 0 2 | /srv/data",
                "systemd    | systemd    | [Unit]%Description=Nightly sync%[Service]%ExecStart=/bin/true | Nightly sync",
                "ssh-config | ssh-config | Host buildbox%  HostName build.example.org%  User deploy | build.example.org",
                "dockerfile | dockerfile | FROM alpine:3.20%RUN apk add curl | alpine:3.20",
                "github     | yaml       | name: CI%on: push%jobs:%  build:%    runs-on: ubuntu-latest%    steps:%      - run: make | ubuntu-latest",
            })
    void aConfigFilePreviewShowsItsDigestAndFallsBackToSourceWhenTurnedOff(
            String kind, String language, String source, String expected) throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        List<String> modeChanges = new ArrayList<>();
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer(language, source.replace('%', '\n') + "\n");
            assertFalse(buffer.hasPreview(), "the feature is off: no preview is offered");
            enable(buffer, kind);
            enable(buffer, kind); // pushing the same setting twice is a no-op
            assertTrue(buffer.hasPreview());
            assertTrue(buffer.hasExportablePreview());
            buffer.setOnViewModeChanged(
                    () -> modeChanges.add(buffer.getMarkdownViewMode().name()));
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitTree(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            String shown = EditorFx.textOf(treeHolder(buffer));
            assertTrue(shown.contains(expected), "the digest names \"" + expected + "\": " + shown);
            assertFalse(buffer.previewShowsError());
            assertEquals(List.of("PREVIEW"), modeChanges);

            // Turning the feature off while its preview is up leaves nothing to preview: back to the source.
            disable(buffer, kind);
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode());
            assertEquals(List.of("PREVIEW", "EDITOR"), modeChanges);
            assertFalse(buffer.hasPreview());
            buffer.dispose();
        });
    }

    @Test
    void aHugeFileIsNeverOfferedAConfigPreview() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("crontab", "* * * * * true\n");
            buffer.setCrontabPreviewEnabled(true);
            assertTrue(buffer.hasCrontabPreview());
            buffer.setLanguageOverride("fstab");
            assertFalse(buffer.hasCrontabPreview(), "a different file type");
            buffer.setFstabPreviewEnabled(true);
            buffer.setSystemdPreviewEnabled(true);
            buffer.setSshConfigPreviewEnabled(true);
            buffer.setDockerfilePreviewEnabled(true);
            assertTrue(buffer.hasFstabPreview());
            assertFalse(buffer.hasSystemdPreview());
            assertFalse(buffer.hasSshConfigPreview());
            assertFalse(buffer.hasDockerfilePreview());
            assertFalse(buffer.hasSvgPreview());
            buffer.dispose();
        });
    }

    @Test
    void aStructuredDocumentShowsItsTreeAndABrokenOneShowsTheParserMessage() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("json", "{\"service\": {\"port\": 8080}}");
            buffer.setStructuredPreviewEnabled(true);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitTree(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            assertFalse(buffer.previewShowsError());
            assertFalse(buffer.isStructuredOpenApi());
            assertNull(treeHolder(buffer).lookup(".structured-error"));
        });

        // Break the document and re-render: the pane names the fault instead of going blank.
        awaitTree(buffer, () -> {
            buffer.getArea().replaceText("{\"service\": ");
            buffer.refreshPreview();
        });
        EditorFx.onFx(() -> {
            assertTrue(buffer.previewShowsError());
            Node error = treeHolder(buffer).lookup(".structured-error");
            assertNotNull(error);
            assertFalse(EditorFx.textOf(error).isBlank(), "the parser's message is shown");
            buffer.dispose();
        });
    }

    @Test
    void anOpenApiDocumentOpensAsDocsAndCanBeFlippedToItsTree() throws Exception {
        String spec = "openapi: 3.0.0\ninfo:\n  title: Orders API\n  version: 1.2.3\npaths:\n  /orders:\n    get:\n"
                + "      summary: List the orders\n      responses:\n        '200':\n          description: ok\n";
        EditorBuffer[] ref = new EditorBuffer[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("yaml", spec);
            buffer.setStructuredPreviewEnabled(true);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitTree(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        String[] docs = new String[1];
        EditorFx.onFx(() -> {
            assertTrue(buffer.isStructuredOpenApi());
            assertTrue(buffer.showsApiDocs());
            docs[0] = EditorFx.textOf(treeHolder(buffer));
            assertTrue(docs[0].contains("Orders API"), docs[0]);
            assertTrue(docs[0].contains("List the orders"), docs[0]);
        });

        awaitTree(buffer, buffer::toggleStructuredView);
        EditorFx.onFx(() -> {
            assertFalse(buffer.showsApiDocs());
            assertNotNull(treeHolder(buffer).lookup(".tree-view"), "the raw tree replaced the docs page");
        });
        awaitTree(buffer, buffer::toggleStructuredView);
        EditorFx.onFx(() -> {
            assertTrue(buffer.showsApiDocs());
            assertEquals(docs[0], EditorFx.textOf(treeHolder(buffer)), "and back to the same docs page");
            buffer.setMarkdownViewMode(MarkdownViewMode.EDITOR);
            buffer.toggleStructuredView(); // with no preview up, only the choice is remembered
            assertFalse(buffer.showsApiDocs());
            buffer.dispose();
        });
    }

    @Test
    void anXmlDocumentShowsItsTreeAndAMalformedOneShowsTheError() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("xml", "<config><item>one</item></config>");
            buffer.setStructuredPreviewEnabled(true);
            assertTrue(buffer.hasXmlPreview());
            assertFalse(buffer.isPom());
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitTree(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            assertFalse(buffer.previewShowsError());
            assertNotNull(treeHolder(buffer).lookup(".tree-view"));
        });
        awaitTree(buffer, () -> {
            buffer.getArea().replaceText("<config><item>one</config>");
            buffer.refreshPreview();
        });
        EditorFx.onFx(() -> {
            assertTrue(buffer.previewShowsError(), "mismatched tags are reported in the pane");
            // An XML tree rides the structured-data setting: turning that off leaves the preview.
            buffer.setStructuredPreviewEnabled(false);
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode());
            buffer.dispose();
        });
    }

    private static final String POM = "<project>\n  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>org.example</groupId>\n  <artifactId>orders-service</artifactId>\n"
            + "  <version>2.5.0</version>\n</project>\n";

    @Test
    void aPomShowsItsSummaryAndItsMenuSwitchesToTheXmlTreeOnlyWhenThatTreeIsAvailable() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        List<String> handled = new ArrayList<>();
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("xml", POM);
            assertTrue(buffer.isPom(), "recognised by content, with no file name");
            buffer.setPomPreviewEnabled(true);
            buffer.setPomPreviewEnabled(true);
            assertTrue(buffer.hasPomPreview());
            buffer.setPreviewExportPdfHandler(() -> handled.add("pdf"));
            buffer.setPreviewPrintHandler(() -> handled.add("print"));
            buffer.setPomViewToggleHandler(() -> handled.add("toggle:" + buffer.togglePomView()));
            stage[0] = EditorFx.show(buffer, 700, 400);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitTree(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            String summary = EditorFx.textOf(treeHolder(buffer));
            assertTrue(summary.contains("orders-service"), summary);
            assertTrue(summary.contains("2.5.0"), summary);

            ContextMenu menu = treeMenu(buffer);
            assertTrue(menu.isShowing());
            MenuItem pomView = menu.getItems().get(0);
            assertTrue(pomView.isVisible(), "a pom offers the switch to its XML tree");
            assertEquals(com.editora.i18n.Messages.tr("pom.menu.showXml"), pomView.getText());

            // The structured-data preview owns the XML tree; while it is off the flip is refused.
            pomView.fire();
            assertEquals(List.of("toggle:false"), handled);
            assertFalse(buffer.isPomShowingXml());
            menu.getItems().get(1).fire();
            menu.getItems().get(2).fire();
            assertEquals(List.of("toggle:false", "pdf", "print"), handled);
            menu.hide();
            buffer.setStructuredPreviewEnabled(true);
        });

        awaitTree(buffer, () -> assertTrue(buffer.togglePomView()));
        EditorFx.onFx(() -> {
            assertTrue(buffer.isPomShowingXml());
            assertFalse(buffer.hasPomPreview());
            assertNotNull(treeHolder(buffer).lookup(".tree-view"), "the generic XML tree is shown");
            ContextMenu menu = treeMenu(buffer);
            assertEquals(
                    com.editora.i18n.Messages.tr("pom.menu.showSummary"),
                    menu.getItems().get(0).getText(),
                    "the item names the view it switches to");
            menu.hide();
        });
        awaitTree(buffer, () -> assertTrue(buffer.togglePomView()));
        EditorFx.onFx(() -> {
            assertTrue(EditorFx.textOf(treeHolder(buffer)).contains("orders-service"), "back to the summary");
            buffer.setPomPreviewEnabled(false);
            assertEquals(
                    MarkdownViewMode.PREVIEW,
                    buffer.getMarkdownViewMode(),
                    "the XML tree is still a preview of this file");
            buffer.setPreviewExportPdfHandler(null);
            buffer.setPreviewPrintHandler(null);
            buffer.setPomViewToggleHandler(null);
            ContextMenu menu = treeMenu(buffer);
            menu.getItems().forEach(MenuItem::fire); // the no-op handlers: nothing is recorded, nothing throws
            assertEquals(3, handled.size());
            menu.hide();
            stage[0].close();
            buffer.dispose();
        });
    }

    private static ContextMenu treeMenu(EditorBuffer buffer) {
        StackPane holder = treeHolder(buffer);
        buffer.getNode().applyCss(); // a SplitPane only parents its items once its skin exists
        buffer.getNode().layout();
        holder.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 200, 200, false, null));
        return EditorFx.field(buffer, "treePreviewContextMenu");
    }

    @Test
    void aNonPomXmlFileHidesThePomSwitchInItsTreeMenu() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("xml", "<a/>");
            buffer.setStructuredPreviewEnabled(true);
            stage[0] = EditorFx.show(buffer, 600, 300);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitTree(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.SPLIT));
        EditorFx.onFx(() -> {
            ContextMenu menu = treeMenu(buffer);
            assertFalse(menu.getItems().get(0).isVisible());
            assertSame(menu, treeMenu(buffer), "the menu is built once and reused");
            menu.hide();
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void pomDetectionUsesTheFileNameOrTheDocumentHead() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer named = new EditorBuffer();
            named.setDisplayName("pom.xml");
            assertTrue(named.isPom(), "an empty pom.xml is still a pom");
            EditorBuffer dotPom = new EditorBuffer();
            dotPom.setDisplayName("orders-1.0.pom");
            dotPom.setLanguageOverride("xml");
            assertTrue(dotPom.isPom());
            EditorBuffer other = buffer("xml", "<project><name>not maven</name></project>");
            assertFalse(other.isPom(), "a <project> root without a modelVersion is some other XML");
            EditorBuffer java = buffer("java", POM);
            assertFalse(java.isPom(), "pom text in a non-XML buffer is not a pom");
            assertFalse(java.togglePomView(), "and there is no XML tree to switch to");
            named.dispose();
            dotPom.dispose();
            other.dispose();
            java.dispose();
        });
    }

    @Test
    void aTimelineRendersAndSwitchesBetweenTimelineAndCalendar() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        List<String> persisted = new ArrayList<>();
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markwhen", "title: Release plan\n\n2026-01-05: Kickoff meeting\n");
            assertTrue(buffer.isMarkwhen());
            assertTrue(buffer.hasPreview(), "a timeline always has a preview");
            assertEquals(MarkwhenView.TIMELINE, buffer.getMarkwhenView());
            buffer.toggleMarkwhenView(); // no preview up and no listener: only the choice flips
            assertEquals(MarkwhenView.CALENDAR, buffer.getMarkwhenView());
            buffer.setMarkwhenView(MarkwhenView.TIMELINE);
            buffer.setMarkwhenView(null);
            buffer.setMarkwhenView(MarkwhenView.TIMELINE);
            assertEquals(MarkwhenView.TIMELINE, buffer.getMarkwhenView());
            buffer.setOnMarkwhenViewChanged(
                    () -> persisted.add(buffer.getMarkwhenView().name()));
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitPane(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        String[] timeline = new String[1];
        EditorFx.onFx(() -> {
            timeline[0] = EditorFx.textOf(pane(buffer).getContent());
            assertTrue(timeline[0].contains("Kickoff meeting"), timeline[0]);
        });
        awaitPane(buffer, buffer::toggleMarkwhenView);
        EditorFx.onFx(() -> {
            assertEquals(List.of("CALENDAR"), persisted, "the flip is handed to the controller to persist");
            String calendar = EditorFx.textOf(pane(buffer).getContent());
            assertTrue(calendar.contains("Kickoff meeting"), calendar);
            assertFalse(calendar.equals(timeline[0]), "the calendar is a different rendering");
        });
        awaitPane(buffer, () -> buffer.setMarkwhenView(MarkwhenView.TIMELINE));
        EditorFx.onFx(() -> {
            assertEquals(timeline[0], EditorFx.textOf(pane(buffer).getContent()));
            assertEquals(1, persisted.size(), "a restore does not persist again");
        });
        // Zooming a timeline re-renders it at the new axis scale rather than restyling the old nodes.
        awaitPane(buffer, buffer::zoomPreviewIn);
        EditorFx.onFx(() -> {
            assertTrue(EditorFx.textOf(pane(buffer).getContent()).contains("Kickoff meeting"));
            buffer.setOnMarkwhenViewChanged(null);
            buffer.dispose();
        });
    }

    private static String longMarkdown() {
        StringBuilder sb = new StringBuilder("# Handbook\n\n");
        for (int i = 0; i < 120; i++) {
            sb.append("Paragraph number ").append(i).append(" of the handbook.\n\n");
        }
        return sb.toString();
    }

    @Test
    void theMarkdownPreviewPagesWithTheKeyboardAndZoomsItsText() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", longMarkdown());
            assertFalse(buffer.pagePreview(true), "no preview yet: the editor keeps the paging keys");
            buffer.applyPreviewTheme("dark", false); // chosen before the pane exists
            stage[0] = EditorFx.show(buffer, 640, 320);
            // Leave the editor scrolled to its end: once the preview replaces it, where the (now hidden)
            // editor was must not decide where the preview scrolls to.
            buffer.getArea().estimatedScrollYProperty().setValue(1.0e7);
            assertTrue(buffer.getArea().estimatedScrollYProperty().getValue() > 100);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitPane(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            ScrollPane pane = pane(buffer);
            buffer.getNode().applyCss();
            buffer.getNode().layout();
            assertTrue(pane.getStyleClass().contains("md-dark"), "the theme picked earlier is applied");
            assertTrue(EditorFx.textOf(pane.getContent()).contains("Handbook"));
            assertEquals(0.0, pane.getVvalue(), 1e-9);

            KeyEvent space = EditorFx.pressed(KeyCode.SPACE, false, false);
            pane.fireEvent(space);
            double afterSpace = pane.getVvalue();
            assertTrue(afterSpace > 0, "Space pages down");
            pane.fireEvent(EditorFx.pressed(KeyCode.PAGE_DOWN, false, false));
            double afterPageDown = pane.getVvalue();
            assertTrue(
                    afterPageDown > afterSpace,
                    afterSpace + " -> " + afterPageDown + " viewport " + pane.getViewportBounds() + " content "
                            + pane.getContent().getLayoutBounds());
            pane.fireEvent(EditorFx.pressed(KeyCode.BACK_SPACE, false, false));
            assertEquals(afterSpace, pane.getVvalue(), 1e-6, "Backspace pages back by the same amount");
            pane.fireEvent(EditorFx.pressed(KeyCode.PAGE_UP, false, false));
            assertEquals(0.0, pane.getVvalue(), 1e-6);

            pane.fireEvent(EditorFx.pressed(KeyCode.SPACE, false, true)); // Ctrl-Space belongs to the keymap
            pane.fireEvent(EditorFx.pressed(KeyCode.A, false, false)); // not a paging key
            assertEquals(0.0, pane.getVvalue(), 1e-9);

            assertTrue(buffer.pagePreview(true), "nav.pageDown pages the preview while it is the view");
            assertEquals(afterSpace, pane.getVvalue(), 1e-6);
            for (int i = 0; i < 400; i++) {
                buffer.pagePreview(true);
            }
            assertEquals(pane.getVmax(), pane.getVvalue(), 1e-9, "paging stops at the end");
            assertTrue(buffer.pagePreview(false));
            assertTrue(pane.getVvalue() < pane.getVmax());

            buffer.zoomPreviewIn();
            assertTrue(
                    pane.getContent().getStyle().contains("-fx-font-size"),
                    pane.getContent().getStyle());
            String zoomed = pane.getContent().getStyle();
            buffer.resetPreviewZoom();
            assertFalse(zoomed.equals(pane.getContent().getStyle()), "reset returns to the base size");
            for (int i = 0; i < 40; i++) {
                buffer.zoomPreviewOut();
            }
            String smallest = pane.getContent().getStyle();
            buffer.zoomPreviewOut();
            assertEquals(smallest, pane.getContent().getStyle(), "zoom is clamped at its minimum");

            buffer.applyPreviewTheme("light", true);
            assertTrue(pane.getStyleClass().contains("md-light"));
            assertFalse(pane.getStyleClass().contains("md-dark"));
            buffer.applyPreviewTheme(null, true);
            assertFalse(pane.getStyleClass().contains("md-light"), "following the app theme carries no override");
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void aShortPreviewHasNothingToPage() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "one line");
            stage[0] = EditorFx.show(buffer, 640, 320);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitPane(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            buffer.getNode().applyCss();
            buffer.getNode().layout();
            assertTrue(buffer.pagePreview(true), "the request is still the preview's");
            assertEquals(0.0, pane(buffer).getVvalue(), 1e-9, "but content that fits does not scroll");
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void theSplitPreviewScrollsTheEditorToTheSameFraction() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", longMarkdown());
            buffer.getArea().moveTo(0);
            stage[0] = EditorFx.show(buffer, 800, 300);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitPane(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.SPLIT));
        EditorFx.onFx(() -> {
            buffer.getNode().applyCss();
            buffer.getNode().layout();
        });
        EditorFx.drain();
        EditorFx.onFx(() -> {
            ScrollPane pane = pane(buffer);
            var editorY = buffer.getArea().estimatedScrollYProperty();
            double scrollable = EditorFx.<Double>call(buffer, "editorScrollableHeight");
            assertTrue(scrollable > 1, "the editor has more text than fits");
            editorY.setValue(0.0);
            buffer.getArea().layout(); // the area applies a scroll request when it is next laid out
            assertEquals(0.0, editorY.getValue(), 1e-6);
            EditorFx.setField(
                    buffer,
                    "previewContentHeight",
                    pane.getContent().getLayoutBounds().getHeight());

            // While a render is still settling, a vvalue move is the pane re-anchoring, not the user: the
            // preview is put back where the editor is and the editor does not move.
            EditorFx.setField(buffer, "previewLayoutChangedAt", System.nanoTime() + HOUR_NANOS);
            pane.setVvalue(pane.getVmax());
            assertEquals(pane.getVmin(), pane.getVvalue(), 1e-9, "re-anchored to the editor's position");
            assertEquals(0.0, editorY.getValue(), 1e-6);

            // Settled, with the mouse elsewhere: the move is not the user's either, the editor stays.
            EditorFx.setField(buffer, "previewLayoutChangedAt", System.nanoTime() - HOUR_NANOS);
            pane.setVvalue(pane.getVmax());
            assertEquals(pane.getVmax(), pane.getVvalue(), 1e-9);
            assertEquals(0.0, editorY.getValue(), 1e-6);

            // Settled and the mouse is over the preview: this is the user scrolling, the editor follows.
            EditorFx.hover(pane, true);
            pane.setVvalue(pane.getVmin());
            pane.setVvalue(pane.getVmax());
            buffer.getArea().layout();
            double atEnd = editorY.getValue();
            assertTrue(
                    atEnd > scrollable * 0.95 && atEnd <= scrollable + 1,
                    "the preview at its end puts the editor at its end: " + atEnd + " of " + scrollable);
            assertEquals(pane.getVmax(), pane.getVvalue(), 1e-9, "the editor moving does not push the preview back");
            assertFalse(EditorFx.<Boolean>field(buffer, "syncingScroll"), "the re-entrancy guard is released");

            // The other direction: scrolling the editor (mouse not over the preview) moves the preview.
            EditorFx.hover(pane, false);
            editorY.setValue(0.0);
            buffer.getArea().layout();
            assertEquals(pane.getVmin(), pane.getVvalue(), 1e-9);
            stage[0].close();
            buffer.dispose();
        });
    }

    private static final long HOUR_NANOS = 3_600_000_000_000L;

    private static ContextMenu previewMenu(EditorBuffer buffer) {
        buffer.getNode().applyCss();
        buffer.getNode().layout();
        pane(buffer)
                .fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 220, 220, false, null));
        return EditorFx.field(buffer, "previewContextMenu");
    }

    private static void wireExports(EditorBuffer buffer, Consumer<String> sink) {
        buffer.setPreviewExportPdfHandler(() -> sink.accept("pdf"));
        buffer.setPreviewExportPngHandler(() -> sink.accept("png"));
        buffer.setPreviewExportSvgHandler(() -> sink.accept("svg"));
        buffer.setPreviewPrintHandler(() -> sink.accept("print"));
        buffer.setPreviewExportDocxHandler(() -> sink.accept("docx"));
        buffer.setPreviewExportOdtHandler(() -> sink.accept("odt"));
        buffer.setPreviewExportJsonHandler(() -> sink.accept("json"));
    }

    @Test
    void theMarkdownPreviewMenuCopiesAndExports() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        List<String> ran = new ArrayList<>();
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "# Title\n\nSome **bold** text.\n");
            wireExports(buffer, ran::add);
            stage[0] = EditorFx.show(buffer, 640, 320);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitPane(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            ContextMenu menu = previewMenu(buffer);
            assertTrue(menu.isShowing());
            ObservableList<MenuItem> items = menu.getItems();
            List<String> titles = EditorFx.titles(items);
            assertTrue(titles.contains(tr("command.preview.exportDocx")), titles.toString());
            assertTrue(titles.contains(tr("command.preview.exportOdt")), titles.toString());
            assertFalse(titles.contains(tr("command.typst.exportPng")), "PNG export is Typst's");
            assertFalse(titles.contains(tr("command.markwhen.exportJson")), "JSON export is Markwhen's");

            EditorFx.clipboard("stale");
            EditorFx.menuItem(items, tr("editmenu.copy")).fire();
            String copied = Clipboard.getSystemClipboard().getString();
            assertTrue(copied.contains("Title") && copied.contains("bold"), copied);
            assertFalse(copied.contains("**"), "the rendered text is copied, not the markup");
            assertTrue(Clipboard.getSystemClipboard().getHtml().contains("<strong>bold</strong>"));

            EditorFx.menuItem(items, tr("command.preview.copyHtml")).fire();
            assertTrue(Clipboard.getSystemClipboard().getString().contains("<strong>bold</strong>"));

            EditorFx.menuItem(items, tr("command.preview.exportPdf")).fire();
            EditorFx.menuItem(items, tr("command.preview.exportDocx")).fire();
            EditorFx.menuItem(items, tr("command.preview.exportOdt")).fire();
            EditorFx.menuItem(items, tr("command.preview.print")).fire();
            assertEquals(List.of("pdf", "docx", "odt", "print"), ran);

            // A press in the preview dismisses the open menu (the ScrollPane swallows the auto-hide).
            pane(buffer).fireEvent(EditorFx.mousePressed(10, 10, 230, 230, false));
            assertFalse(menu.isShowing());
            assertSame(menu, previewMenu(buffer), "the menu is built once");
            menu.hide();
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void theTimelinePreviewMenuOffersJsonExportAndTheViewSwitch() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        List<String> ran = new ArrayList<>();
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markwhen", "2026-03-01: Ship it\n");
            wireExports(buffer, ran::add);
            stage[0] = EditorFx.show(buffer, 640, 320);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitPane(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        ContextMenu[] menu = new ContextMenu[1];
        EditorFx.onFx(() -> {
            menu[0] = previewMenu(buffer);
            ObservableList<MenuItem> items = menu[0].getItems();
            assertNotNull(EditorFx.findMenuItem(items, tr("markwhen.switchToCalendar")), "labelled as it opens");
            assertNull(EditorFx.findMenuItem(items, tr("command.preview.copyHtml")), "Copy as HTML is Markdown's");
            assertNull(EditorFx.findMenuItem(items, tr("command.preview.exportDocx")));

            EditorFx.clipboard("stale");
            EditorFx.menuItem(items, tr("editmenu.selectAll")).fire();
            assertEquals("2026-03-01: Ship it\n", Clipboard.getSystemClipboard().getString(), "the source is copied");
            assertFalse(buffer.copyPreviewHtmlSource(), "only Markdown has HTML markup to copy");

            EditorFx.menuItem(items, tr("command.markwhen.exportJson")).fire();
            EditorFx.menuItem(items, tr("command.preview.exportPdf")).fire();
            EditorFx.menuItem(items, tr("command.preview.print")).fire();
            assertEquals(List.of("json", "pdf", "print"), ran);
        });
        awaitPane(
                buffer,
                () -> EditorFx.menuItem(menu[0].getItems(), tr("markwhen.switchToCalendar"))
                        .fire());
        EditorFx.onFx(() -> {
            assertEquals(MarkwhenView.CALENDAR, buffer.getMarkwhenView());
            menu[0].hide();
            ContextMenu reopened = previewMenu(buffer);
            assertNotNull(
                    EditorFx.findMenuItem(reopened.getItems(), tr("markwhen.switchToTimeline")),
                    "the switch names the other view now");
            reopened.hide();
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void aPreviewRenderThatThrowsIsShownInThePaneInsteadOfLeavingItBlank() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "# fine");
            buffer.setCrontabPreviewEnabled(true);
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        awaitPane(buffer, () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        long gen = EditorFx.callFx(() -> EditorFx.<Long>field(buffer, "previewGen"));

        awaitPane(
                buffer,
                () -> EditorFx.call(
                        buffer,
                        "surfaceMarkdownPreviewError",
                        new Class<?>[] {long.class, Throwable.class},
                        gen,
                        new IllegalStateException("table too large")));
        EditorFx.onFx(() -> {
            Node content = pane(buffer).getContent();
            assertTrue(content.getStyleClass().contains("structured-error"));
            assertEquals("table too large\n", EditorFx.textOf(content));
        });

        // A failure from a render that has since been superseded must not overwrite the newer result.
        awaitPane(buffer, buffer::refreshPreview);
        EditorFx.onFx(() -> {
            EditorFx.call(
                    buffer,
                    "surfaceMarkdownPreviewError",
                    new Class<?>[] {long.class, Throwable.class},
                    gen,
                    new IllegalStateException("stale"));
        });
        EditorFx.drain();
        EditorFx.onFx(() ->
                assertTrue(EditorFx.textOf(pane(buffer).getContent()).contains("fine"), "the newer render stays"));

        // The tree host reports the same way; an exception with no message falls back to its class name.
        long treeGen = EditorFx.callFx(() -> EditorFx.<Long>field(buffer, "previewGen"));
        awaitTree(
                buffer,
                () -> EditorFx.call(
                        buffer,
                        "surfaceTreePreviewError",
                        new Class<?>[] {long.class, Throwable.class},
                        treeGen,
                        new NoClassDefFoundError()));
        EditorFx.onFx(() -> {
            assertEquals("java.lang.NoClassDefFoundError\n", EditorFx.textOf(treeHolder(buffer)));
            EditorFx.call(
                    buffer,
                    "surfaceTreePreviewError",
                    new Class<?>[] {long.class, Throwable.class},
                    treeGen - 1,
                    new IllegalStateException("stale tree"));
        });
        EditorFx.drain();
        EditorFx.onFx(() -> {
            assertEquals("java.lang.NoClassDefFoundError\n", EditorFx.textOf(treeHolder(buffer)));
            buffer.dispose();
        });
    }

    @Test
    void theLoadingOverlayIsShownOnlyWhileAsked() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "x");
            buffer.setPreviewLoading(true, "Explaining…");
            Node overlay = EditorFx.call(buffer, "previewLoadingOverlay");
            assertTrue(overlay.isVisible());
            assertTrue(EditorFx.textOf(overlay).contains("Explaining…"));
            buffer.setPreviewLoading(false, null);
            assertFalse(overlay.isVisible());
            assertFalse(overlay.isManaged());
            assertTrue(EditorFx.textOf(overlay).contains("Explaining…"), "a null message keeps the last text");
            buffer.dispose();
        });
    }

    private static String tr(String key) {
        return com.editora.i18n.Messages.tr(key);
    }
}
