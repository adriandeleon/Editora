package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LSP commands a user runs from the palette or a key, end to end against a scripted server: where each
 * one takes the caret, which window it raises, and — the half that had no test — what it says when the server
 * cannot do it, answers nothing, or answers for a document that has since changed.
 */
@Tag("fx")
class LspCoordinatorCommandsFxTest {

    private static final String SOURCE = "class A {\n    void go() {}\n}\n";
    private static final String JDT_STRING = "jdt://contents/java.base/java.lang/String.class?=demo";

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root);
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    private static Location location(String uri, int line, int ch) {
        return new Location(uri, new Range(new Position(line, ch), new Position(line, ch + 1)));
    }

    private Path file(String name, String content) throws Exception {
        Path f = root.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private Location at(Path file, int line, int ch) {
        return location(file.toUri().toString(), line, ch);
    }

    // --- implementation / type definition / declaration ----------------------------------------------

    @Test
    void theNavigationCommandsRefuseWhenTheServerDoesNotAdvertiseThem() throws Exception {
        fx.open("A.java", SOURCE);

        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(tr("status.lsp.implementationUnsupported"), fx.host.lastStatus());
        fx.run(() -> fx.coordinator.gotoTypeDefinition());
        assertEquals(tr("status.lsp.typeDefinitionUnsupported"), fx.host.lastStatus());
        fx.run(() -> fx.coordinator.gotoDeclaration());
        assertEquals(tr("status.lsp.declarationUnsupported"), fx.host.lastStatus());

        FakeLanguageServer server = fx.server();
        assertTrue(server.implementations.isEmpty() && server.typeDefinitions.isEmpty(), "nothing was asked");
        assertTrue(server.declarations.isEmpty());
        assertTrue(fx.ops.jumps.isEmpty());
    }

    @Test
    void theNavigationCommandsReportAnUnmanagedFile() throws Exception {
        EditorBuffer loose = FxTestSupport.callOnFx(EditorBuffer::new);
        FxTestSupport.runOnFx(() -> {
            fx.show(loose);
            fx.host.active = loose;
        });

        for (Runnable command : List.<Runnable>of(
                () -> fx.coordinator.gotoImplementation(),
                () -> fx.coordinator.peekDefinition(),
                () -> fx.coordinator.callHierarchy(),
                () -> fx.coordinator.gotoSymbolInWorkspace(),
                () -> fx.coordinator.showHover(),
                () -> fx.coordinator.organizeImports(),
                () -> fx.coordinator.copyQualifiedName(),
                () -> fx.coordinator.reloadProject(),
                () -> fx.coordinator.buildWorkspace(),
                () -> fx.coordinator.codeActions(),
                () -> fx.coordinator.rename())) {
            fx.host.statuses.clear();
            fx.run(command);
            assertEquals(List.of(tr("status.lsp.unavailable")), fx.host.statuses);
        }
        fx.run(() -> fx.coordinator.signatureHelp(true));
        assertEquals(tr("status.lsp.noSignatureHelp"), fx.host.lastStatus());
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.signatureHelp(false));
        assertTrue(fx.host.statuses.isEmpty(), "the automatic trigger stays silent");
    }

    @Test
    void aLoneImplementationIsOpenedAndSeveralFillTheReferencesWindow() throws Exception {
        fx.capabilities.setImplementationProvider(Either.forLeft(true));
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.caret(b, 1, 9);
        Path impl = file("Impl.java", "class Impl {\n  void go() {}\n}\n");
        Path other = file("Other.java", "class Other {}\n");

        fx.server().implementationResponse = List.of(at(impl, 1, 7));
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(List.of(new LspCoordinatorFixture.Jump(impl, 1, 7)), fx.ops.jumps);
        assertEquals(0, fx.ops.referencesWindowOpened);
        assertEquals(new Position(1, 9), fx.server().implementations.get(0).getPosition());

        // Several: the file-backed ones are listed, a library one (no path) is left out.
        fx.server().implementationResponse = List.of(at(impl, 1, 7), location(JDT_STRING, 5, 0), at(other, 0, 6));
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(1, fx.ops.referencesWindowOpened);
        assertEquals(1, fx.ops.jumps.size(), "several implementations do not jump on their own");
        Label summary = FxTestSupport.field(fx.coordinator.referencesPanel(), "summary");
        assertEquals(tr("references.summary", 2, 2), FxTestSupport.callOnFx(summary::getText));

        fx.server().implementationResponse = List.of();
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(tr("status.lsp.noImplementations"), fx.host.lastStatus());
    }

    /** Every implementation lives inside a dependency: there is no file list to show, so the first one is
     *  opened as library source. */
    @Test
    void implementationsOnlyInsideLibrariesOpenTheFirstAsLibrarySource() throws Exception {
        fx.capabilities.setImplementationProvider(Either.forLeft(true));
        fx.open("A.java", SOURCE);
        fx.server().rawResponse = "package java.lang;\nfinal class String {}\n";
        fx.server().implementationResponse =
                List.of(location(JDT_STRING, 1, 0), location("jdt://contents/x/Other.class", 0, 0));

        fx.run(() -> fx.coordinator.gotoImplementation());

        assertEquals(0, fx.ops.referencesWindowOpened);
        assertEquals(1, fx.ops.readOnlyDocs.size());
        assertEquals("String.class", fx.ops.readOnlyDocs.get(0).title());
        assertEquals("java", fx.ops.readOnlyDocs.get(0).language());
        assertEquals("java/classFileContents", fx.server().rawRequests.get(0).method());
    }

    @Test
    void typeDefinitionAndDeclarationOpenTheFirstTarget() throws Exception {
        fx.capabilities.setTypeDefinitionProvider(Either.forLeft(true));
        fx.capabilities.setDeclarationProvider(Either.forLeft(true));
        fx.open("A.java", SOURCE);
        Path type = file("T.java", "class T {}\n");
        Path decl = file("D.java", "interface D {}\n");

        fx.server().typeDefinitionResponse = List.of(at(type, 0, 6), at(decl, 0, 0));
        fx.run(() -> fx.coordinator.gotoTypeDefinition());
        fx.server().declarationResponse = List.of(at(decl, 0, 10));
        fx.run(() -> fx.coordinator.gotoDeclaration());
        assertEquals(
                List.of(new LspCoordinatorFixture.Jump(type, 0, 6), new LspCoordinatorFixture.Jump(decl, 0, 10)),
                fx.ops.jumps);

        fx.server().typeDefinitionResponse = List.of();
        fx.run(() -> fx.coordinator.gotoTypeDefinition());
        assertEquals(tr("status.lsp.noTypeDefinition"), fx.host.lastStatus());
        fx.server().declarationResponse = List.of();
        fx.run(() -> fx.coordinator.gotoDeclaration());
        assertEquals(tr("status.lsp.noDeclaration"), fx.host.lastStatus());
        assertEquals(2, fx.ops.jumps.size());
    }

    @Test
    void aNavigationAnswerForAnEditedDocumentIsDropped() throws Exception {
        fx.capabilities.setImplementationProvider(Either.forLeft(true));
        EditorBuffer b = fx.open("A.java", SOURCE);
        Path impl = file("Impl.java", "class Impl {}\n");
        fx.server().implementationResponse = List.of(at(impl, 0, 6));

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.gotoImplementation();
            b.replaceWholeDocument("class A { /* typed while the server was thinking */ }\n");
        });
        fx.settle();

        assertTrue(fx.ops.jumps.isEmpty(), "a target computed for older text must not be opened");
    }

    // --- library sources (#665, #684) ----------------------------------------------------------------

    private EditorBuffer libraryBuffer(LspCoordinatorFixture.ReadOnlyDoc doc) {
        EditorBuffer library = new EditorBuffer();
        library.setContent(doc.content());
        fx.show(library);
        return library;
    }

    @Test
    void aLibraryDefinitionOpensItsSourceReadOnlyAtTheLine() throws Exception {
        fx.open("A.java", SOURCE);
        fx.ops.readOnlyOpener = this::libraryBuffer;
        fx.server().rawResponse =
                "package java.lang;\n\npublic final class String {\n    int length() { return 0; }\n}\n";
        fx.server().definitionResponse = List.of(location(JDT_STRING, 3, 8));

        fx.run(() -> fx.coordinator.gotoDefinition());

        assertEquals(1, fx.ops.readOnlyDocs.size());
        EditorBuffer library = fx.ops.readOnlyDocs.get(0).buffer();
        assertEquals(
                3, (int) FxTestSupport.callOnFx(() -> library.getFocusedArea().getCurrentParagraph()));
        assertEquals(
                8, (int) FxTestSupport.callOnFx(() -> library.getFocusedArea().getCaretColumn()));
        assertTrue(fx.host.statuses.contains(tr("status.lsp.libraryLoading")));
        assertEquals(
                JDT_STRING,
                ((org.eclipse.lsp4j.TextDocumentIdentifier)
                                fx.server().rawRequests.get(0).params())
                        .getUri());

        // The same class again: its tab is re-selected, not fetched and opened a second time. A position
        // past the end of the source is clamped rather than thrown on.
        fx.server().definitionResponse = List.of(location(JDT_STRING, 400, 90));
        fx.run(() -> fx.coordinator.gotoDefinition());
        assertEquals(1, fx.ops.readOnlyDocs.size());
        assertEquals(1, fx.server().rawRequests.size());
        assertEquals(List.of(library), fx.ops.selected);
        assertEquals(
                5, (int) FxTestSupport.callOnFx(() -> library.getFocusedArea().getCurrentParagraph()));

        // The user closed that tab: fetch it again.
        fx.ops.tabsSelectable = false;
        fx.run(() -> fx.coordinator.gotoDefinition());
        assertEquals(2, fx.ops.readOnlyDocs.size());
    }

    @Test
    void anUnavailableLibrarySourceIsReported() throws Exception {
        fx.open("A.java", SOURCE);
        fx.server().rawResponse = null;
        fx.server().definitionResponse = List.of(location(JDT_STRING, 3, 8));

        fx.run(() -> fx.coordinator.gotoDefinition());

        assertEquals(tr("status.lsp.libraryUnavailable"), fx.host.lastStatus());
        assertTrue(fx.ops.readOnlyDocs.isEmpty());
    }

    @Test
    void definitionFromInsideALibrarySourceChainsOnTheAnchorsServer() throws Exception {
        EditorBuffer origin = fx.open("A.java", SOURCE);
        fx.ops.readOnlyOpener = this::libraryBuffer;
        fx.server().rawResponse = "package java.lang;\npublic final class String {}\n";
        fx.server().definitionResponse = List.of(location(JDT_STRING, 1, 19));
        fx.run(() -> fx.coordinator.gotoDefinition());
        EditorBuffer library = fx.ops.readOnlyDocs.get(0).buffer();
        FxTestSupport.runOnFx(() -> fx.host.active = library);

        // …to a workspace file.
        Path target = file("B.java", "class B {}\n");
        fx.server().definitionResponse = List.of(at(target, 0, 6));
        fx.run(() -> fx.coordinator.gotoDefinition());
        assertEquals(List.of(new LspCoordinatorFixture.Jump(target, 0, 6)), fx.ops.jumps);
        assertEquals(
                JDT_STRING,
                FakeLanguageServer.last(fx.server().definitions)
                        .getTextDocument()
                        .getUri(),
                "the request names the library document, on the session of the file it was opened from");

        // …to another library class.
        String object = "jdt://contents/java.base/java.lang/Object.class?=demo";
        fx.server().rawResponse = "package java.lang;\npublic class Object {}\n";
        fx.server().definitionResponse = List.of(location(object, 1, 13));
        fx.run(() -> fx.coordinator.gotoDefinition());
        assertEquals(2, fx.ops.readOnlyDocs.size());
        assertEquals("Object.class", fx.ops.readOnlyDocs.get(1).title());

        // …to nothing.
        fx.server().definitionResponse = List.of();
        fx.run(() -> fx.coordinator.gotoDefinition());
        assertEquals(tr("status.lsp.noDefinition"), fx.host.lastStatus());

        // …and an answer that arrives after the user went back to the other tab is dropped.
        fx.server().definitionResponse = List.of(at(target, 0, 6));
        FxTestSupport.runOnFx(() -> {
            fx.coordinator.gotoDefinition();
            fx.host.active = origin;
        });
        fx.settle();
        assertEquals(1, fx.ops.jumps.size());
    }

    @Test
    void aStackFrameInsideALibraryOpensItsSource() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.ops.readOnlyOpener = this::libraryBuffer;
        fx.server().rawResponse = "line0\nline1\nline2\n";

        fx.run(() -> fx.coordinator.openLibraryFrame(b.getPath(), JDT_STRING, -4));

        EditorBuffer library = fx.ops.readOnlyDocs.get(0).buffer();
        assertEquals(
                0,
                (int) FxTestSupport.callOnFx(() -> library.getFocusedArea().getCurrentParagraph()),
                "a negative line is the top of the file");
    }

    @Test
    void theAfterJumpHookRunsOnlyOnAJumpThatHappened() throws Exception {
        fx.open("A.java", SOURCE);
        Path target = file("B.java", "class B {}\n");
        int[] ran = {0};

        fx.server().definitionResponse = List.of();
        fx.run(() -> fx.coordinator.gotoDefinition(() -> ran[0]++));
        assertEquals(0, ran[0]);

        fx.server().definitionResponse = List.of(at(target, 0, 6));
        fx.run(() -> fx.coordinator.gotoDefinition(() -> ran[0]++));
        assertEquals(1, ran[0]);
        assertEquals(1, fx.ops.jumps.size());
    }

    // --- peek definition -------------------------------------------------------------------------------

    private static void awaitPeekRead() throws Exception {
        java.lang.reflect.Field f = LspCoordinator.class.getDeclaredField("PEEK_READ");
        f.setAccessible(true);
        ((ExecutorService) f.get(null)).submit(() -> {}).get(60, TimeUnit.SECONDS);
    }

    private String peekTitle() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Label title = (Label) fx.overlayCard().lookup(".palette-title");
            return title.getText();
        });
    }

    @Test
    void peekShowsAnOpenFilesUnsavedTextAndEnterJumps() throws Exception {
        fx.open("B.java", "class B {\n}\n");
        EditorBuffer target =
                fx.ops.open.get(root.resolve("B.java").toAbsolutePath().normalize());
        FxTestSupport.runOnFx(() -> target.setContent("class B {\n    int unsaved;\n}\n"));
        fx.open("A.java", SOURCE);
        fx.server().definitionResponse = List.of(at(root.resolve("B.java"), 1, 8));

        fx.run(() -> fx.coordinator.peekDefinition());

        assertEquals(tr("lsp.peek.title", "B.java", 2), peekTitle());
        String shown = FxTestSupport.callOnFx(() -> {
            StringBuilder all = new StringBuilder();
            for (javafx.scene.Node text : fx.overlayCard().lookupAll(".text")) {
                all.append(((javafx.scene.text.Text) text).getText());
            }
            return all.toString();
        });
        assertTrue(shown.contains("int unsaved;"), "an open buffer's text is what is peeked, not the file's");
        assertTrue(fx.ops.jumps.isEmpty(), "peeking does not move");

        FxTestSupport.runOnFx(() -> fx.overlayCard()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        assertEquals(List.of(new LspCoordinatorFixture.Jump(root.resolve("B.java"), 1, 8)), fx.ops.jumps);
    }

    @Test
    void peekReadsAClosedFileAndReportsOneItCannotRead() throws Exception {
        fx.open("A.java", SOURCE);
        Path closed = file("Closed.java", "class Closed {\n    void here() {}\n}\n");
        fx.server().definitionResponse = List.of(at(closed, 1, 9));

        FxTestSupport.runOnFx(() -> fx.coordinator.peekDefinition());
        fx.settle();
        awaitPeekRead();
        fx.settle();
        assertEquals(tr("lsp.peek.title", "Closed.java", 2), peekTitle());

        FxTestSupport.runOnFx(() -> fx.dismissOverlay());
        fx.server().definitionResponse = List.of(at(root.resolve("Missing.java"), 0, 0));
        FxTestSupport.runOnFx(() -> fx.coordinator.peekDefinition());
        fx.settle();
        awaitPeekRead();
        fx.settle();
        assertEquals(tr("status.lsp.peekUnreadable"), fx.host.lastStatus());
        assertFalse(fx.host.overlay.isShowing());
    }

    @Test
    void peekReportsNoDefinitionAndFallsBackToOpeningALibraryClass() throws Exception {
        fx.open("A.java", SOURCE);
        fx.server().definitionResponse = List.of();
        fx.run(() -> fx.coordinator.peekDefinition());
        assertEquals(tr("status.lsp.noDefinition"), fx.host.lastStatus());

        // A class file has no file to read a snippet out of: peek degrades to opening it.
        fx.server().rawResponse = "package java.lang;\n";
        fx.server().definitionResponse = List.of(location(JDT_STRING, 0, 0));
        fx.run(() -> fx.coordinator.peekDefinition());
        assertEquals(1, fx.ops.readOnlyDocs.size());
        assertFalse(fx.host.overlay.isShowing());
    }

    // --- hierarchy (#682) ------------------------------------------------------------------------------

    private CallHierarchyItem callItem(String name, Path file, int line) {
        var item = new CallHierarchyItem();
        item.setName(name);
        item.setKind(SymbolKind.Method);
        item.setUri(file.toUri().toString());
        item.setRange(new Range(new Position(line, 0), new Position(line, 10)));
        item.setSelectionRange(new Range(new Position(line, 4), new Position(line, 6)));
        return item;
    }

    @Test
    void callHierarchyFillsTheHierarchyWindowAndExpandsThroughTheServer() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.callHierarchy());
        assertEquals(tr("status.lsp.noHierarchy"), fx.host.lastStatus(), "the server does not advertise it");

        fx.capabilities.setCallHierarchyProvider(Either.forLeft(true));
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.callHierarchy());
        assertEquals(List.of(tr("status.lsp.noHierarchy")), fx.host.statuses, "nothing callable at the caret");
        assertEquals(0, fx.ops.hierarchyWindowOpened);

        CallHierarchyItem go = callItem("go", b.getPath(), 1);
        Path callerFile = file("Caller.java", "class Caller {}\n");
        fx.server().callHierarchyResponse = List.of(go);
        fx.server().incomingCallsResponse =
                List.of(new CallHierarchyIncomingCall(callItem("main", callerFile, 3), List.of()));
        fx.run(() -> fx.coordinator.callHierarchy());

        assertEquals(1, fx.ops.hierarchyWindowOpened);
        TreeView<LspManager.HierarchyNode> tree = FxTestSupport.field(fx.coordinator.hierarchyPanel(), "tree");
        TreeItem<LspManager.HierarchyNode> anchor =
                FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().get(0));
        assertEquals("go", anchor.getValue().name());
        assertEquals("main", anchor.getChildren().get(0).getValue().name(), "the callers came from the server");
        assertEquals(List.of(go), fx.server().incomingCallRequests);

        FxTestSupport.runOnFx(() -> {
            tree.getSelectionModel().select(anchor.getChildren().get(0));
            tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        assertEquals(List.of(new LspCoordinatorFixture.Jump(callerFile, 3, 4)), fx.ops.jumps);
    }

    @Test
    void typeHierarchyAsksForSupertypes() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.typeHierarchy());
        assertEquals(tr("status.lsp.noHierarchy"), fx.host.lastStatus());

        fx.capabilities.setTypeHierarchyProvider(Either.forLeft(true));
        var range = new Range(new Position(0, 6), new Position(0, 7));
        var type =
                new TypeHierarchyItem("A", SymbolKind.Class, b.getPath().toUri().toString(), range, range);
        var base = new TypeHierarchyItem(
                "Base", SymbolKind.Class, b.getPath().toUri().toString(), range, range);
        fx.server().typeHierarchyResponse = List.of(type);
        fx.server().supertypesResponse = List.of(base);
        fx.run(() -> fx.coordinator.typeHierarchy());

        assertEquals(1, fx.ops.hierarchyWindowOpened);
        TreeView<LspManager.HierarchyNode> tree = FxTestSupport.field(fx.coordinator.hierarchyPanel(), "tree");
        assertEquals(
                "Base",
                FxTestSupport.callOnFx(() -> tree.getRoot()
                        .getChildren()
                        .get(0)
                        .getChildren()
                        .get(0)
                        .getValue()
                        .name()));
        assertEquals(List.of(type), fx.server().supertypeRequests);
        assertTrue(fx.server().incomingCallRequests.isEmpty(), "a type hierarchy asks no call-hierarchy question");
    }

    @Test
    void aHierarchyAnswerForAnEditedDocumentIsDropped() throws Exception {
        fx.capabilities.setCallHierarchyProvider(Either.forLeft(true));
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.server().callHierarchyResponse = List.of(callItem("go", b.getPath(), 1));

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.callHierarchy();
            b.replaceWholeDocument("class A {}\n");
        });
        fx.settle();

        assertEquals(0, fx.ops.hierarchyWindowOpened);
    }

    // --- go to symbol in workspace -------------------------------------------------------------------

    @Test
    void workspaceSymbolSearchSeedsFromTheSelectionAndOpensTheChoice() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.gotoSymbolInWorkspace());
        assertEquals(tr("status.lsp.workspaceSymbolsUnsupported"), fx.host.lastStatus());
        assertFalse(fx.host.overlay.isShowing());

        fx.capabilities.setWorkspaceSymbolProvider(true);
        Path target = file("B.java", "class B {}\n");
        fx.server().workspaceSymbolResponse =
                List.of(new WorkspaceSymbol("go", SymbolKind.Method, Either.forLeft(at(target, 2, 9))));
        FxTestSupport.runOnFx(() -> b.getFocusedArea().selectRange(1, 9, 1, 11)); // "go"
        fx.run(() -> fx.coordinator.gotoSymbolInWorkspace());

        assertEquals("go", FakeLanguageServer.last(fx.server().workspaceSymbols).getQuery());
        FxTestSupport.runOnFx(() -> fx.overlayCard()
                .lookup(".text-field")
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        assertEquals(List.of(new LspCoordinatorFixture.Jump(target, 2, 9)), fx.ops.jumps);

        // A selection spanning lines is not a symbol name: the picker opens empty and asks nothing.
        int asked = fx.server().workspaceSymbols.size();
        FxTestSupport.runOnFx(() -> b.getFocusedArea().selectRange(0, 0, 1, 4));
        fx.run(() -> fx.coordinator.gotoSymbolInWorkspace());
        assertEquals(asked, fx.server().workspaceSymbols.size());
        assertTrue(fx.host.overlay.isShowing());
    }

    // --- hover -----------------------------------------------------------------------------------------

    private Popup hoverPopup() {
        return FxTestSupport.field(fx.coordinator, "hoverPopup");
    }

    @Test
    void hoverShowsAPopupThatEscapeAndCaretMovementDismiss() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.awaitHighlighted(b);
        fx.caret(b, 1, 9);
        fx.server().hoverResponse = new Hover(new MarkupContent("markdown", "`void go()` — runs it"));

        fx.run(() -> fx.coordinator.showHover());
        Popup first = hoverPopup();
        assertNotNull(first, "the documentation is shown");
        assertTrue(FxTestSupport.callOnFx(first::isShowing));
        assertEquals(new Position(1, 9), fx.server().hovers.get(0).getPosition());

        FxTestSupport.runOnFx(() -> b.getFocusedArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
        assertFalse(FxTestSupport.callOnFx(first::isShowing));
        assertNull(hoverPopup());

        fx.run(() -> fx.coordinator.showHover());
        Popup second = hoverPopup();
        fx.run(() -> fx.coordinator.showHover()); // another hover replaces the one on screen
        Popup third = hoverPopup();
        assertFalse(FxTestSupport.callOnFx(second::isShowing));
        assertTrue(FxTestSupport.callOnFx(third::isShowing));
        fx.caret(b, 0, 0);
        assertFalse(FxTestSupport.callOnFx(third::isShowing), "the popup belongs to the place the caret was");
    }

    @Test
    void hoverWithNothingToSaySaysSoAndAStaleAnswerShowsNothing() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.awaitHighlighted(b);
        fx.server().hoverResponse = new Hover(new MarkupContent("markdown", "  "));
        fx.run(() -> fx.coordinator.showHover());
        assertEquals(tr("status.lsp.noHover"), fx.host.lastStatus());
        assertNull(hoverPopup());

        fx.server().hoverResponse = new Hover(new MarkupContent("markdown", "doc"));
        FxTestSupport.runOnFx(() -> {
            fx.coordinator.showHover();
            b.getFocusedArea().moveTo(2, 0); // the caret left before the answer came
        });
        fx.settle();
        assertNull(hoverPopup(), "documentation for where the caret was is not shown where it is now");
    }

    // --- the jdtls project commands (#743, #746) -----------------------------------------------------

    @Test
    void organizeImportsSyncsTheTextAndReportsTheOutcome() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.ops.editable = false;
        fx.run(() -> fx.coordinator.organizeImports());
        assertEquals(tr("status.lsp.readOnly"), fx.host.lastStatus());
        assertTrue(fx.server().rawRequests.isEmpty(), "a read-only file is not sent for rewriting");

        fx.ops.editable = true;
        fx.server().rawResponse = null; // nothing to change
        fx.run(() -> fx.coordinator.organizeImports());
        assertEquals(tr("status.lsp.importsUnchanged"), fx.host.lastStatus());
        var params = (org.eclipse.lsp4j.CodeActionParams)
                fx.server().rawRequests.get(0).params();
        assertEquals(new Range(new Position(0, 0), new Position(3, 0)), params.getRange(), "the whole file");

        fx.server().rawResponse = JsonParser.parseString(
                "{\"changes\":{\"" + b.getPath().toUri()
                        + "\":[{\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":0}},"
                        + "\"newText\":\"import java.util.List;\\n\"}]}}");
        fx.run(() -> fx.coordinator.organizeImports());
        assertEquals(tr("status.lsp.importsOrganized"), fx.host.lastStatus());
        assertEquals("import java.util.List;\n" + SOURCE, FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void copyQualifiedNamePutsTheNameOnTheClipboard() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.caret(b, 1, 9);
        fx.server().executeCommandResponse = " ";
        fx.run(() -> fx.coordinator.copyQualifiedName());
        assertEquals(tr("status.lsp.noQualifiedName"), fx.host.lastStatus());

        fx.server().executeCommandResponse = "demo.A.go()";
        fx.run(() -> fx.coordinator.copyQualifiedName());
        assertEquals(tr("status.lsp.qualifiedNameCopied", "demo.A.go()"), fx.host.lastStatus());
        assertEquals(
                "demo.A.go()",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
    }

    @Test
    void reloadProjectNotifiesTheServer() throws Exception {
        fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.reloadProject());
        assertEquals(tr("status.lsp.projectReloading"), fx.host.lastStatus());
        assertEquals(
                "java/projectConfigurationUpdate",
                fx.server().rawNotifications.get(0).method());
    }

    @Test
    void buildWorkspaceSwitchesProblemsToTheProjectAndReportsTheResult() throws Exception {
        fx.open("A.java", SOURCE);
        assertFalse(fx.coordinator.isProjectWideProblems());
        fx.ops.loading.clear();

        fx.run(() -> fx.coordinator.buildWorkspace());
        assertTrue(fx.coordinator.isProjectWideProblems(), "or the results would be filtered straight out again");
        assertEquals(List.of(true, false), fx.ops.loading);
        assertTrue(fx.host.statuses.contains(tr("status.lsp.buildingWorkspace")));
        assertEquals(tr("status.lsp.buildWorkspaceDone"), fx.host.lastStatus());

        fx.server().failEverything = true;
        fx.run(() -> fx.coordinator.buildWorkspace());
        assertEquals(tr("status.lsp.buildWorkspaceFailed"), fx.host.lastStatus());
        assertFalse(fx.ops.loading.get(fx.ops.loading.size() - 1), "the loading bar stops on failure too");
    }

    // --- server lifecycle as the user sees it ----------------------------------------------------------

    @Test
    void serverStatusDrivesTheEchoAreaAndTheLoadingBar() throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.coordinator.onServerStatus("Starting", "Init...");
            assertEquals(tr("status.lsp.server", "Init..."), fx.host.lastStatus());
            assertTrue(fx.ops.loading.isEmpty(), "a plain status does not touch the loading bar");

            fx.coordinator.onServerStatus("Progress", "Indexing");
            fx.coordinator.onServerStatus("ProgressEnd", null);
            fx.coordinator.onServerStatus("ServiceReady", " ");
            fx.coordinator.onServerStatus("Error", "boom");
            assertEquals(List.of(true, false, false, false), fx.ops.loading);
            assertEquals(tr("status.lsp.server", "boom"), fx.host.lastStatus());

            int statuses = fx.host.statuses.size();
            fx.coordinator.onServerStatus(null, null);
            assertEquals(statuses, fx.host.statuses.size());
            assertEquals(4, fx.ops.loading.size());

            fx.ops.featureEnabled = false; // Simple mode: the server's chatter is not shown at all
            fx.coordinator.onServerStatus("Progress", "Indexing");
            assertEquals(statuses, fx.host.statuses.size());
            assertEquals(4, fx.ops.loading.size());
        });
    }

    @Test
    void restartingServersStartsAFreshSessionForTheActiveFile() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        assertEquals(1, fx.fakes.size());

        fx.run(() -> fx.coordinator.restartServers());

        assertEquals(tr("status.lsp.restarted"), fx.host.lastStatus());
        assertEquals(2, fx.fakes.size(), "the active file is opened on a new server");
        assertTrue(fx.manager.isManaged(b.getPath()));
        assertEquals(SOURCE, fx.server().opened.get(0).getTextDocument().getText());
    }

    @Test
    void aCrashedServerIsRestartedUntilItKeepsCrashing() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        EditorBuffer background = FxTestSupport.callOnFx(EditorBuffer::new);
        Path other = file("Background.java", "class Background {}\n");
        FxTestSupport.runOnFx(() -> {
            background.setPath(other);
            background.setContent("class Background {}\n");
            fx.show(background);
            fx.host.active = background;
            fx.coordinator.syncBuffer(background);
            fx.host.active = b;
        });
        assertTrue(fx.manager.isManaged(other));

        int sessions = fx.fakes.size();
        FxTestSupport.runOnFx(() -> LspTestHooks.simulateServerDeath(fx.manager, b.getPath()));
        fx.settle();

        assertTrue(fx.host.statuses.contains(tr("status.lsp.crashed", "jdtls")));
        assertEquals(sessions + 1, fx.fakes.size(), "the file on screen gets a fresh server at once");
        assertTrue(fx.manager.isManaged(b.getPath()));
        assertFalse(fx.manager.isManaged(other), "a background tab waits until it is shown");
        FxTestSupport.runOnFx(() -> {
            fx.host.active = background;
            fx.coordinator.onBufferShown(background);
        });
        assertTrue(fx.manager.isManaged(other));
        FxTestSupport.runOnFx(() -> fx.host.active = b);

        // Keep killing it: after the allowed restarts the editor stops re-forking and says so.
        String loop = tr("status.lsp.crashLoop", "jdtls");
        for (int i = 0; i < 12 && !fx.host.statuses.contains(loop); i++) {
            FxTestSupport.runOnFx(() -> {
                if (fx.manager.isManaged(b.getPath())) {
                    LspTestHooks.simulateServerDeath(fx.manager, b.getPath());
                }
            });
            fx.settle();
        }
        assertTrue(fx.host.statuses.contains(loop), "a crash loop is reported instead of restarted forever");
        assertFalse(fx.manager.isManaged(b.getPath()));
        assertFalse(FxTestSupport.callOnFx(b::isLspActive), "the dead server's marks are dropped");
    }

    @Test
    void aCrashIsIgnoredWhileTheFeatureIsOff() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.ops.featureEnabled = false;
        fx.host.statuses.clear();
        int sessions = fx.fakes.size();

        FxTestSupport.runOnFx(() -> LspTestHooks.simulateServerDeath(fx.manager, b.getPath()));
        fx.settle();

        assertTrue(fx.host.statuses.isEmpty());
        assertEquals(sessions, fx.fakes.size(), "nothing is restarted for a feature that is off");
    }

    // --- watched files (#677) --------------------------------------------------------------------------

    private void watchedFlushElapsed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.animation.PauseTransition flush = FxTestSupport.field(fx.coordinator, "watchedFlush");
            flush.stop();
            if (flush.getOnFinished() != null) {
                flush.getOnFinished().handle(null);
            }
        });
    }

    @Test
    void externalFileChangesReachTheServerAsOneBatchWithTheLatestKind() throws Exception {
        fx.open("A.java", SOURCE);
        Path changed = root.resolve("pom.xml");
        Path gone = root.resolve("Old.java");

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.watchedFilesChanged(null);
            fx.coordinator.watchedFilesChanged(List.of());
            fx.coordinator.watchedFilesReloaded(null);
            fx.coordinator.watchedFilesReloaded(List.of());
        });
        watchedFlushElapsed();
        assertTrue(fx.server().watchedFiles.isEmpty(), "nothing changed, nothing sent");

        FxTestSupport.runOnFx(() -> {
            var changes = new java.util.ArrayList<ProjectPanel.FsChange>();
            changes.add(new ProjectPanel.FsChange(changed, ProjectPanel.FsKind.CREATED));
            changes.add(null);
            changes.add(new ProjectPanel.FsChange(null, ProjectPanel.FsKind.CHANGED));
            changes.add(new ProjectPanel.FsChange(gone, ProjectPanel.FsKind.CHANGED));
            fx.coordinator.watchedFilesChanged(changes);
            fx.coordinator.watchedFilesChanged(List.of(new ProjectPanel.FsChange(gone, ProjectPanel.FsKind.DELETED)));
            fx.coordinator.watchedFilesReloaded(List.of(changed));
        });
        watchedFlushElapsed();

        assertEquals(1, fx.server().watchedFiles.size(), "a burst is one notification");
        var events = fx.server().watchedFiles.get(0).getChanges();
        assertEquals(2, events.size());
        assertEquals(changed.toUri().toString(), events.get(0).getUri());
        assertEquals(org.eclipse.lsp4j.FileChangeType.Changed, events.get(0).getType(), "the latest kind wins");
        assertEquals(org.eclipse.lsp4j.FileChangeType.Deleted, events.get(1).getType());

        watchedFlushElapsed();
        assertEquals(1, fx.server().watchedFiles.size(), "an empty queue sends nothing");

        fx.ops.featureEnabled = false;
        FxTestSupport.runOnFx(() -> fx.coordinator.watchedFilesChanged(
                List.of(new ProjectPanel.FsChange(changed, ProjectPanel.FsKind.CHANGED))));
        watchedFlushElapsed();
        assertEquals(1, fx.server().watchedFiles.size());
    }

    // --- saving ----------------------------------------------------------------------------------------

    @Test
    void savingAManagedFileTellsTheServerAfterSyncingItsText() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        FxTestSupport.runOnFx(() -> {
            b.setContent("class A { int typedDuringSave; }\n");
            fx.coordinator.notifyDocumentSaved(b, SOURCE);
            fx.coordinator.notifyDocumentSaved(null, SOURCE);
        });

        assertEquals(1, fx.server().saved.size());
        var change = FakeLanguageServer.last(fx.server().changed);
        assertNotNull(change, "the open document is brought up to date before didSave");
        assertSame(fx.server(), fx.fakes.get(0));
    }

    // --- the per-server settings pickers ---------------------------------------------------------------

    @Test
    void theTogglePickerFlipsOneServerAndSavesTheSetting() throws Exception {
        assertTrue(fx.host.settings.isPythonLspEnabled());
        int python = LspCoordinator.serverIds().indexOf("python");

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.chooseServerToggle();
            assertEquals(LspCoordinator.serverIds(), fx.pickerRows());
            fx.choose(python);
        });

        assertFalse(fx.host.settings.isPythonLspEnabled());
        assertEquals(1, fx.host.saveRequests);
        assertEquals(1, fx.host.settingsSyncs);
        assertEquals(tr("status.settingToggled", "python", tr("common.off")), fx.host.lastStatus());

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.chooseServerToggle();
            fx.choose(python);
        });
        assertTrue(fx.host.settings.isPythonLspEnabled());
        assertEquals(tr("status.settingToggled", "python", tr("common.on")), fx.host.lastStatus());

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.chooseServerToggle();
            fx.dismissOverlay();
        });
        assertEquals(2, fx.host.saveRequests, "a dismissed picker changes nothing");
    }

    @Test
    void theCommandPickerPromptsWithTheGlobalValueAndStoresTheAnswerTrimmed() throws Exception {
        fx.host.settings.setRustLspCommand("rust-analyzer --old");
        int rust = LspCoordinator.serverIds().indexOf("rust");

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.chooseServerCommand();
            fx.choose(rust);
        });
        LspCoordinatorFixture.Prompt prompt = fx.lastPrompt();
        assertEquals("rust", prompt.title());
        assertEquals("rust-analyzer --old", prompt.initial());

        FxTestSupport.runOnFx(() -> prompt.onAccept().accept("  /opt/ra/rust-analyzer  "));
        assertEquals("/opt/ra/rust-analyzer", fx.host.settings.getRustLspCommand());
        assertEquals(1, fx.host.saveRequests);
        assertEquals(tr("status.settingChanged", "rust", "/opt/ra/rust-analyzer"), fx.host.lastStatus());
    }

    private java.util.Map<String, Boolean> enabledStates(List<String> ids) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            for (String id : ids) {
                out.put(id, fx.coordinator.serverEnabled(id));
            }
            return out;
        });
    }

    /** Every server id has its own pair of settings: none may fall through to another server's. */
    @Test
    void everyServerIdTogglesAndStoresItsOwnSetting() throws Exception {
        List<String> ids = LspCoordinator.serverIds();
        for (int i = 0; i < ids.size(); i++) {
            int index = i;
            String id = ids.get(i);
            java.util.Map<String, Boolean> before = enabledStates(ids);
            FxTestSupport.runOnFx(() -> {
                fx.coordinator.chooseServerToggle();
                fx.choose(index);
            });
            java.util.Map<String, Boolean> flipped = new java.util.LinkedHashMap<>(before);
            flipped.put(id, !before.get(id));
            assertEquals(flipped, enabledStates(ids), "toggling " + id + " changes that server and no other");
            FxTestSupport.runOnFx(() -> {
                fx.coordinator.chooseServerToggle();
                fx.choose(index);
            });
            assertEquals(before, enabledStates(ids), id + " toggles back");

            FxTestSupport.runOnFx(() -> {
                fx.coordinator.chooseServerCommand();
                fx.choose(index);
            });
            FxTestSupport.runOnFx(() -> fx.lastPrompt().onAccept().accept("cmd-for-" + id));
            assertEquals(List.of("cmd-for-" + id), FxTestSupport.callOnFx(() -> fx.coordinator.serverArgv(id)), id);
        }
        // Each command landed in its own setting: no id overwrote another's.
        for (String id : ids) {
            assertEquals(List.of("cmd-for-" + id), FxTestSupport.callOnFx(() -> fx.coordinator.serverArgv(id)), id);
        }
    }
}
