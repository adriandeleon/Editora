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

        // Several in files: the References window, titled as implementations (re-review BN9).
        fx.server().implementationResponse = List.of(at(impl, 1, 7), at(other, 0, 6));
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(1, fx.ops.referencesWindowOpened);
        assertEquals(1, fx.ops.jumps.size(), "several implementations do not jump on their own");
        Label summary = FxTestSupport.field(fx.coordinator.referencesPanel(), "summary");
        String text = FxTestSupport.callOnFx(summary::getText);
        assertTrue(text.startsWith("2 implementations of "), text);
        assertTrue(text.endsWith(" in 2 files"), text);

        // A library one among them: every one is listed in a picker, the library class by its name (BN4).
        fx.server().implementationResponse = List.of(at(impl, 1, 7), location(JDT_STRING, 5, 0), at(other, 0, 6));
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(3, FxTestSupport.callOnFx(fx::pickerRows).size());
        assertEquals(1, fx.ops.referencesWindowOpened, "not the References window, which would drop it");
        FxTestSupport.runOnFx(() -> fx.choose(1)); // rows by class: Impl, Other, String (fourth review BN4)
        fx.settle();
        assertEquals(new LspCoordinatorFixture.Jump(other, 0, 6), fx.ops.jumps.get(fx.ops.jumps.size() - 1));

        fx.server().failEverything = true; // an error is not "No implementations found" (B13)
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertTrue(
                fx.host
                        .lastError()
                        .startsWith(tr("status.lsp.navigationFailed", "").strip()),
                fx.host.lastError());
        fx.server().failEverything = false;

        fx.server().implementationResponse = List.of();
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(tr("status.lsp.noImplementations"), fx.host.lastStatus());
    }

    /** Every implementation lives inside a dependency: a picker names them all, and the chosen one opens as
     *  library source (re-review BN4: it opened the first with no word of the others). */
    @Test
    void implementationsOnlyInsideLibrariesAreListedAndTheChosenOneOpens() throws Exception {
        fx.capabilities.setImplementationProvider(Either.forLeft(true));
        fx.open("A.java", SOURCE);
        fx.server().rawResponse = "package java.lang;\nfinal class String {}\n";
        fx.server().implementationResponse =
                List.of(location(JDT_STRING, 1, 0), location("jdt://contents/x/Other.class", 0, 0));

        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(2, FxTestSupport.callOnFx(fx::pickerRows).size());
        assertEquals(
                "String",
                LspCoordinator.implementationLabel(new com.editora.lsp.LspManager.Target(null, 1, 0, JDT_STRING)));
        assertEquals(
                "java.lang  ·  " + tr("structure.line", 2),
                LspCoordinator.implementationDetail(
                        new com.editora.lsp.LspManager.Target(null, 1, 0, JDT_STRING), null),
                "the line tells apart the classes one file holds (third review BN4)");
        assertEquals(
                "IdentityArrayList",
                LspCoordinator.implementationLabel(new com.editora.lsp.LspManager.Target(
                        null, 1, 0, "jdt://contents/java.desktop/sun.awt.util/IdentityArrayList.java?=x")));
        FxTestSupport.runOnFx(() -> fx.choose(1)); // rows by class: Other, then String (fourth review BN4)
        fx.settle();

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
    void aLibraryClassFromGoToSymbolOpensOnItsDeclarationNotItsPackageLine() throws Exception {
        fx.open("A.java", SOURCE);
        fx.ops.readOnlyOpener = this::libraryBuffer;
        fx.server().rawResponse = "package java.lang;\n\n/**\n * Strings.\n */\npublic final class String {\n}\n";

        fx.run(() -> fx.coordinator.openLibraryType(new com.editora.lsp.LspManager.WorkspaceSymbolMatch(
                "String", "java.lang", "class", null, 0, 0, JDT_STRING)));

        EditorBuffer library = fx.ops.readOnlyDocs.get(0).buffer();
        assertEquals(
                5,
                (int) FxTestSupport.callOnFx(() -> library.getFocusedArea().getCurrentParagraph()),
                "jdtls places it at line 0 (re-review BN7)");
    }

    @Test
    void theRightClickMenusOwnCommandsRunTheirActions() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        java.util.function.Consumer<String> run = FxTestSupport.field(b, "lspCommand");
        fx.run(() -> run.accept("lsp.peekDefinition")); // what it shows is peekDefinition's own tests' business
        fx.run(() -> run.accept("lsp.refactorThis"));
        fx.run(() -> run.accept("lsp.generate"));
        fx.host.statuses.clear();
        fx.run(() -> run.accept("lsp.unknown"));
        assertTrue(fx.host.statuses.isEmpty() && fx.host.errors.isEmpty(), "an id it does not know does nothing");
    }

    @Test
    void aNewDefaultJdkWhileJdtlsRunsSaysToRestart() throws Exception {
        fx.open("A.java", SOURCE);
        fx.host.settings.setMavenJdkHome("/jdk/re-review");
        fx.run(() -> fx.coordinator.applyOnTypeFormatting());
        assertEquals(tr("status.lsp.javaJdkChanged"), fx.host.lastStatus(), "re-review CN7");
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.applyOnTypeFormatting());
        assertTrue(fx.host.statuses.isEmpty(), "unchanged: nothing to say");
    }

    @Test
    void aPreviewRowSaysWhereAMovedFileGoesRelativeToTheProject() throws Exception {
        fx.ops.projectRoot = fx.root;
        Path from = fx.root.resolve("src/a/Strings.java");
        Path moved = fx.root.resolve("src/b/Strings.java");
        String row = fx.coordinator.previewLabel(new com.editora.lsp.RenamePreview.FileChange(from, 1, moved));
        assertTrue(row.startsWith(Path.of("src/a/Strings.java").toString()), row);
        assertTrue(
                row.endsWith(
                        tr("lsp.rename.movesTo", Path.of("src/b/Strings.java").toString())),
                "BN8: " + row);
        String renamed = fx.coordinator.previewLabel(
                new com.editora.lsp.RenamePreview.FileChange(from, 0, fx.root.resolve("src/a/Text.java")));
        assertTrue(renamed.endsWith(tr("lsp.rename.movesTo", "Text.java")), renamed);

        Path impl = fx.root.resolve("src/a/Impl.java");
        var target = new com.editora.lsp.LspManager.Target(impl, 4, 0);
        assertEquals("Impl.java", LspCoordinator.implementationLabel(target));
        assertEquals(Path.of("src/a/Impl.java") + ":5", LspCoordinator.implementationDetail(target, fx.root));
        assertEquals(impl + ":5", LspCoordinator.implementationDetail(target, null));
    }

    @Test
    void foldingTheSaveActionsLeavesAHistoryItCannotReadAlone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("class A {}\n");
            b.getArea().appendText("// typed\n");
            LspCoordinator.foldIntoOneUndo(b, "something else entirely");
            assertEquals("class A {}\n// typed\n", b.getContent(), "steps that do not lead back are redone");
        });
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
    void peekReportsNoDefinitionAndPeeksALibraryClassInPlace() throws Exception {
        fx.open("A.java", SOURCE);
        fx.server().definitionResponse = List.of();
        fx.run(() -> fx.coordinator.peekDefinition());
        assertEquals(tr("status.lsp.noDefinition"), fx.host.lastStatus());

        // A class file's source is asked of the server and peeked like any file (Java review B7); no tab opens.
        fx.server().rawResponse = "package java.lang;\n\npublic final class String {}\n";
        fx.server().definitionResponse = List.of(location(JDT_STRING, 2, 0));
        fx.run(() -> fx.coordinator.peekDefinition());
        assertTrue(fx.ops.readOnlyDocs.isEmpty(), "peeked, not opened");
        assertTrue(fx.host.overlay.isShowing(), "the peek window is up");
        assertEquals(
                JDT_STRING,
                ((org.eclipse.lsp4j.TextDocumentIdentifier)
                                FakeLanguageServer.last(fx.server().rawRequests).params())
                        .getUri());

        fx.server().rawResponse = null; // the server has no source for it
        FxTestSupport.runOnFx(() -> fx.host.overlay.hide());
        fx.run(() -> fx.coordinator.peekDefinition());
        assertEquals(tr("status.lsp.libraryUnavailable"), fx.host.lastStatus());
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

    /** A link in hover documentation was drawn as a link and did nothing; it goes to the window's opener. */
    @Test
    void aLinkInHoverDocumentationOpensThroughTheWindow() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.awaitHighlighted(b);
        fx.caret(b, 1, 9);
        fx.server().hoverResponse =
                new Hover(new MarkupContent("markdown", "See [the docs](https://example.com/docs) for more."));
        fx.run(() -> fx.coordinator.showHover());
        Popup popup = hoverPopup();
        assertNotNull(popup);
        FxTestSupport.runOnFx(() -> {
            javafx.scene.Node link = popup.getScene().getRoot().lookup(".md-link");
            assertNotNull(link, "the link is rendered");
            link.getOnMouseClicked()
                    .handle(new javafx.scene.input.MouseEvent(
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
                            false,
                            false,
                            true,
                            null));
        });
        assertEquals(List.of("https://example.com/docs"), fx.host.urls);
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

    /**
     * A Java file in a folder with no build file: once the server is up, jdtls is asked for its source folders,
     * so a guessed one can be corrected (Java review C6). A Maven project is left to its build.
     */
    @Test
    void aPlainFoldersSourceRootIsCheckedWithTheServer() throws Exception {
        java.nio.file.Files.createDirectories(fx.root.resolve("src/demo"));
        fx.open("src/demo/A.java", "package demo;\n\nclass A {}\n");
        assertTrue(
                fx.server().executedCommands.stream()
                        .anyMatch(c -> c.getCommand().equals("java.project.listSourcePaths")),
                "asked which folders are source folders");
    }

    /** While Problems shows the whole project, a Java save is followed by an incremental build (C7). */
    @Test
    void aSaveRechecksTheProjectWhileProblemsShowsAllOfIt() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.notifyDocumentSaved(b, SOURCE));
        Thread.sleep(1500);
        fx.settle();
        assertFalse(
                fx.server().rawRequests.stream().anyMatch(r -> r.method().equals("java/buildWorkspace")),
                "only the open files are listed: nothing to recheck");

        FxTestSupport.runOnFx(() -> fx.coordinator.setProjectWideProblems(true));
        fx.run(() -> fx.coordinator.notifyDocumentSaved(b, SOURCE));
        long deadline = System.currentTimeMillis() + 5000;
        while (fx.server().rawRequests.stream().noneMatch(r -> r.method().equals("java/buildWorkspace"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            fx.settle();
        }
        var build = fx.server().rawRequests.stream()
                .filter(r -> r.method().equals("java/buildWorkspace"))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.FALSE, build.params(), "incremental, not a full build");
    }

    /**
     * With another Java file of the project open, a save rechecks too: a file calling a method just renamed elsewhere
     * showed no error until it was typed in (eighth review AQ1).
     */
    @Test
    void aSaveRechecksWhileAnotherJavaFileIsOpen() throws Exception {
        fx.open("B.java", "class B {}\n");
        EditorBuffer a = fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.notifyDocumentSaved(a, SOURCE));
        long deadline = System.currentTimeMillis() + 5000;
        while (fx.server().rawRequests.stream().noneMatch(r -> r.method().equals("java/buildWorkspace"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            fx.settle();
        }
        assertTrue(
                fx.server().rawRequests.stream().anyMatch(r -> r.method().equals("java/buildWorkspace")),
                "B.java is rechecked after A.java's save");
    }

    @Test
    void aMavenProjectsSourceRootIsLeftToItsBuild() throws Exception {
        java.nio.file.Files.writeString(fx.root.resolve("pom.xml"), "<project/>");
        java.nio.file.Files.createDirectories(fx.root.resolve("src/demo"));
        fx.open("src/demo/A.java", "package demo;\n\nclass A {}\n");
        assertTrue(fx.server().executedCommands.stream()
                .noneMatch(c -> c.getCommand().equals("java.project.listSourcePaths")));
    }

    @Test
    void buildWorkspaceSwitchesProblemsToTheProjectAndReportsTheResult() throws Exception {
        fx.open("A.java", SOURCE);
        assertFalse(fx.coordinator.isProjectWideProblems());
        fx.ops.loading.clear();

        fx.server().rawResponse = 1; // BuildWorkspaceStatus.SUCCEED
        fx.run(() -> fx.coordinator.buildWorkspace());
        assertTrue(fx.coordinator.isProjectWideProblems(), "or the results would be filtered straight out again");
        assertEquals(List.of(true, false), fx.ops.loading);
        assertTrue(fx.host.statuses.contains(tr("status.lsp.buildingWorkspace")));
        assertEquals(tr("status.lsp.buildWorkspaceDone"), fx.host.lastStatus());

        fx.server().rawResponse = 2; // WITH_ERROR: not "Project built" (Java review C15)
        fx.run(() -> fx.coordinator.buildWorkspace());
        assertEquals(
                tr("status.lsp.buildWorkspaceNotImported"),
                fx.host.lastError(),
                "errors and not one problem: the project did not import (CN2)");
        var file = fx.root.resolve("A.java");
        FxTestSupport.runOnFx(() -> fx.coordinator.onDiagnostics(
                file,
                List.of(new com.editora.text.LspDiagnostic(
                        0, 0, 0, 5, com.editora.text.LspDiagnostic.Severity.ERROR, "x", null, "jdtls"))));
        fx.run(() -> fx.coordinator.buildWorkspace());
        assertEquals(tr("status.lsp.buildWorkspaceErrors"), fx.host.lastError());

        fx.server().failEverything = true;
        fx.run(() -> fx.coordinator.buildWorkspace());
        assertEquals(tr("status.lsp.buildWorkspaceFailed"), fx.host.lastError());
        assertFalse(fx.ops.loading.get(fx.ops.loading.size() - 1), "the loading bar stops on failure too");
    }

    // --- server lifecycle as the user sees it ----------------------------------------------------------

    @Test
    void serverStatusDrivesTheEchoAreaAndTheLoadingBar() throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.coordinator.onServerStatus("Starting", "Init...");
            assertEquals(tr("status.lsp.server", "LSP", "Init..."), fx.host.lastStatus());
            assertTrue(fx.ops.loading.isEmpty(), "a plain status does not touch the loading bar");

            fx.coordinator.onServerStatus("Progress", "Indexing");
            fx.coordinator.onServerStatus("ProgressEnd", null);
            fx.coordinator.onServerStatus("ServiceReady", " ");
            fx.coordinator.onServerStatus("Error", "boom");
            assertEquals(List.of(true, false, false, false), fx.ops.loading);
            assertEquals(tr("status.lsp.server", "LSP", "boom"), fx.host.lastStatus());

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
    void startupEndsWithAReadyLineOnceTheServerHasSettled() throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.coordinator.onServerStatus("Starting", "Init...");
            fx.coordinator.onServerStatus("ServiceReady", "ServiceReady");
            fx.coordinator.onServerStatus("Progress", "Publish Diagnostics");
            fx.coordinator.onServerStatus("ProgressEnd", null);
            assertEquals(tr("status.lsp.server", "LSP", "Publish Diagnostics"), fx.host.lastStatus());
        });
        Thread.sleep(com.editora.lsp.ServerStatusNarrator.QUIET_MILLIS * 3);
        FxTestSupport.runOnFx(() -> assertEquals(tr("status.lsp.ready", "LSP"), fx.host.lastStatus()));
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

        assertTrue(fx.host.statuses.contains(tr("status.lsp.crashed", tr("install.lang.java"))));
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
        String loop = tr("status.lsp.crashLoop", tr("install.lang.java"));
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
        // Showing or re-syncing a tab of that root does not fork it again (Node review E5)…
        int afterLoop = fx.fakes.size();
        FxTestSupport.runOnFx(() -> fx.coordinator.syncBuffer(b));
        fx.settle();
        assertEquals(afterLoop, fx.fakes.size(), "a crash-looped server is not restarted by a tab");
        // …until the user asks for it.
        fx.run(() -> fx.coordinator.restartServers());
        assertTrue(fx.manager.isManaged(b.getPath()), "Restart Language Servers starts it again");
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
        // Created then modified inside one burst is still a creation: a server told only "changed" about a file
        // it never knew ignores it, and a new Python module stayed unresolved (Python review B6).
        assertEquals(
                org.eclipse.lsp4j.FileChangeType.Created, events.get(0).getType(), "a create is not lost to a modify");
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

    // --- problems at the caret (Java review A6) ------------------------------------------------------

    private static final String PROBLEMS = "class A {\n    int a = x;\n    int b = y;\n    int c = 1;\n}\n";

    private static com.editora.text.LspDiagnostic problem(
            int line, int from, int to, com.editora.text.LspDiagnostic.Severity severity, String message) {
        return new com.editora.text.LspDiagnostic(line, from, line, to, severity, message, null, "jdtls");
    }

    private int[] caretOf(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> new int[] {
            b.getFocusedArea().getCurrentParagraph(), b.getFocusedArea().getCaretColumn()
        });
    }

    @Test
    void nextAndPreviousProblemWalkTheErrorsWrappingAndSayEach() throws Exception {
        EditorBuffer b = fx.open("A.java", PROBLEMS);
        var x = problem(1, 12, 13, com.editora.text.LspDiagnostic.Severity.ERROR, "x cannot be resolved");
        var y = problem(2, 12, 13, com.editora.text.LspDiagnostic.Severity.ERROR, "y cannot be resolved");
        var unused = problem(3, 8, 9, com.editora.text.LspDiagnostic.Severity.WARNING, "c is never used");
        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of(unused, y, x)));
        fx.caret(b, 0, 0);

        fx.run(() -> fx.coordinator.gotoProblem(true));
        assertEquals(List.of(1, 12), List.of(caretOf(b)[0], caretOf(b)[1]));
        assertEquals(tr("status.lsp.problem", 2, "x cannot be resolved"), fx.host.lastError());
        fx.run(() -> fx.coordinator.gotoProblem(true));
        assertEquals(2, caretOf(b)[0]);
        fx.run(() -> fx.coordinator.gotoProblem(true));
        assertEquals(1, caretOf(b)[0], "errors only while there are any, wrapping past the warning");
        fx.run(() -> fx.coordinator.gotoProblem(false));
        assertEquals(2, caretOf(b)[0], "backwards wraps to the last");

        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of(unused)));
        fx.run(() -> fx.coordinator.gotoProblem(false));
        assertEquals(3, caretOf(b)[0], "with no errors the warnings are walked");
        assertEquals(tr("status.lsp.problem", 4, "c is never used"), fx.host.lastStatus());

        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of()));
        fx.run(() -> fx.coordinator.gotoProblem(true));
        assertEquals(tr("status.lsp.noProblems"), fx.host.lastStatus());
    }

    @Test
    void showProblemAtCaretSaysTheLinesProblem() throws Exception {
        EditorBuffer b = fx.open("A.java", PROBLEMS);
        var hint = problem(1, 4, 7, com.editora.text.LspDiagnostic.Severity.HINT, "could be final");
        var x = problem(1, 12, 13, com.editora.text.LspDiagnostic.Severity.ERROR, "x cannot be resolved");
        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of(hint, x)));

        fx.caret(b, 1, 12);
        fx.run(() -> fx.coordinator.problemAtCaret());
        assertEquals(tr("status.lsp.problem", 2, "x cannot be resolved"), fx.host.lastError(), "the one at the caret");
        fx.caret(b, 1, 0);
        fx.host.errors.clear();
        fx.run(() -> fx.coordinator.problemAtCaret());
        assertEquals(tr("status.lsp.problem", 2, "x cannot be resolved"), fx.host.lastError(), "the line's worst");
        fx.caret(b, 3, 0);
        fx.run(() -> fx.coordinator.problemAtCaret());
        assertEquals(tr("status.lsp.noProblemHere"), fx.host.lastStatus());
    }

    // --- Format Selection (Java review A13) ----------------------------------------------------------

    @Test
    void aSelectionIsFormattedAloneWholeLines() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.server().formattingResponse =
                List.of(new org.eclipse.lsp4j.TextEdit(new Range(new Position(1, 0), new Position(1, 4)), "  "));
        FxTestSupport.runOnFx(() -> b.getFocusedArea().selectRange(1, 6, 2, 0)); // ends at line 2's start

        fx.run(() -> fx.coordinator.formatDocument());

        assertTrue(fx.server().formattings.isEmpty(), "the whole file is not reformatted");
        var asked = FakeLanguageServer.last(fx.server().rangeFormattings).getRange();
        assertEquals(new Range(new Position(1, 0), new Position(1, "    void go() {}".length())), asked);
        assertEquals(tr("status.lsp.selectionFormatted"), fx.host.lastStatus());
        assertEquals("class A {\n  void go() {}\n}\n", FxTestSupport.callOnFx(b::getContent));
    }

    // --- references that fail (Java review B13) ------------------------------------------------------

    @Test
    void aFailedReferencesRequestIsReportedAsAFailure() throws Exception {
        fx.open("A.java", SOURCE);
        fx.server().failEverything = true;
        fx.run(() -> fx.coordinator.findReferences());
        assertTrue(
                fx.host
                        .lastError()
                        .startsWith(tr("status.lsp.referencesFailed", "").strip()),
                "not \"No references\": " + fx.host.lastError());
    }

    // --- inside library source (Java review B7) ------------------------------------------------------

    @Test
    void hoverAndReferencesInsideLibrarySourceAskAboutItsUri() throws Exception {
        Path a = fx.open("A.java", SOURCE).getPath();
        fx.ops.readOnlyOpener = this::libraryBuffer;
        fx.server().rawResponse =
                "package java.lang;\n\npublic final class String {\n    int length() { return 0; }\n}\n";
        fx.server().definitionResponse = List.of(location(JDT_STRING, 3, 8));
        fx.run(() -> fx.coordinator.gotoDefinition());
        EditorBuffer library = fx.ops.readOnlyDocs.get(0).buffer();
        FxTestSupport.runOnFx(() -> fx.host.active = library);

        fx.server().hoverResponse = new Hover(new MarkupContent("markdown", "int length()"));
        fx.run(() -> fx.coordinator.showHover());
        assertEquals(
                JDT_STRING,
                FakeLanguageServer.last(fx.server().hovers).getTextDocument().getUri());

        fx.server().referenceResponse = List.of(at(a, 1, 9), location(JDT_STRING, 9, 1), at(a, 0, 6));
        fx.run(() -> fx.coordinator.findReferences());
        assertEquals(
                JDT_STRING,
                FakeLanguageServer.last(fx.server().references)
                        .getTextDocument()
                        .getUri());
        assertFalse(
                FakeLanguageServer.last(fx.server().references).getContext().isIncludeDeclaration(),
                "the declaration is not a usage (B5)");

        fx.server().implementationResponse = List.of(at(a, 1, 9));
        fx.capabilities.setImplementationProvider(Either.forLeft(true));
        fx.run(() -> fx.coordinator.gotoImplementation());
        assertEquals(
                JDT_STRING,
                FakeLanguageServer.last(fx.server().implementations)
                        .getTextDocument()
                        .getUri());
        assertEquals(1, fx.ops.jumps.size(), "the project's implementation is opened");
    }

    // --- Search Everywhere's library classes (Java review E8) ----------------------------------------

    @Test
    void theServersLibraryClassesBecomeSearchRowsAndOpenAsLibrarySource() throws Exception {
        Path a = fx.open("A.java", SOURCE).getPath();
        fx.ops.readOnlyOpener = this::libraryBuffer;
        fx.server().workspaceSymbolResponse = List.of(
                new WorkspaceSymbol(
                        "String", SymbolKind.Class, Either.forLeft(location(JDT_STRING, 2, 19)), "java.lang"),
                new WorkspaceSymbol("Strings", SymbolKind.Class, Either.forLeft(at(a, 0, 6)), "demo"),
                new WorkspaceSymbol("strip", SymbolKind.Method, Either.forLeft(location(JDT_STRING, 9, 4)), "String"),
                new WorkspaceSymbol("Unrelated", SymbolKind.Class, Either.forLeft(location(JDT_STRING, 1, 0)), ""));
        List<com.editora.search.SearchEverywhere.Item> rows = new java.util.ArrayList<>();

        fx.run(() -> fx.coordinator.libraryTypeItems("Str", rows::addAll));

        assertEquals(
                List.of("String"),
                rows.stream()
                        .map(com.editora.search.SearchEverywhere.Item::label)
                        .toList(),
                "library types only: not the project's class, a method, or a name that does not match");
        assertEquals("java.lang", rows.get(0).detail());

        fx.server().rawResponse = "package java.lang;\n\npublic final class String {}\n";
        fx.run(() -> fx.coordinator.openLibraryType(
                (LspManager.WorkspaceSymbolMatch) rows.get(0).payload()));
        assertEquals(1, fx.ops.readOnlyDocs.size());
        assertEquals("String.class", fx.ops.readOnlyDocs.get(0).title());

        List<com.editora.search.SearchEverywhere.Item> none = new java.util.ArrayList<>(List.of(rows.get(0)));
        fx.run(() -> fx.coordinator.libraryTypeItems(" ", r -> {
            none.clear();
            none.addAll(r);
        }));
        assertTrue(none.isEmpty(), "a blank query asks nothing");
    }

    // --- Clean Java Workspace (Java review C14) ------------------------------------------------------

    @Test
    void cleanJavaWorkspaceNeedsAJavaFileAndSaysWhatHappened() throws Exception {
        EditorBuffer loose = FxTestSupport.callOnFx(EditorBuffer::new);
        FxTestSupport.runOnFx(() -> {
            fx.show(loose);
            fx.host.active = loose;
        });
        fx.run(() -> fx.coordinator.cleanJavaWorkspace());
        assertEquals(tr("status.lsp.unavailable"), fx.host.lastStatus());

        fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.cleanJavaWorkspace());
        assertTrue(fx.host.statuses.contains(tr("status.lsp.javaWorkspaceCleaning")));
        assertEquals(
                tr("status.lsp.javaWorkspaceKept"),
                fx.host.lastStatus(),
                "a session with no workspace of Editora's own keeps it");
    }

    // --- a server whose requests fail (Maven review C2) ----------------------------------------------

    @Test
    void aFailingRequestIsSaidOnceAndAServerThatKeepsFailingIsGivenUp() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);

        fx.run(() -> fx.coordinator.onRequestError("java", root, "textDocument/hover", "NPE in hover", true, false));
        assertEquals(
                tr(
                        "status.lsp.requestError",
                        tr("install.lang.java"),
                        LspCoordinator.methodLabel("textDocument/hover")),
                fx.host.lastStatus());
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.onRequestError("java", root, "textDocument/hover", "NPE in hover", false, false));
        assertTrue(fx.host.statuses.isEmpty(), "an unannounced repeat stays quiet");
        assertNull(FxTestSupport.callOnFx(() -> fx.coordinator.serverFailure("java")));

        fx.run(() -> fx.coordinator.onRequestError("java", root, "textDocument/hover", "NPE in hover", true, true));
        assertEquals("NPE in hover", FxTestSupport.callOnFx(() -> fx.coordinator.serverFailure("java")));
        assertTrue(
                fx.host.statuses.contains(tr("status.lsp.requestErrors", tr("install.lang.java"))),
                String.valueOf(fx.host.statuses));
        assertFalse(FxTestSupport.callOnFx(b::isLspActive), "its tabs are no longer served");
        assertEquals("unknownMethod", LspCoordinator.methodLabel("unknownMethod"));
    }

    // --- project-level problems after an import (Java review C19) ------------------------------------

    @Test
    void anImportIsBuiltOnceSoProjectProblemsArriveAndAreSaid() throws Exception {
        fx.open("A.java", SOURCE);
        fx.server().rawRequests.clear();
        fx.run(() -> fx.coordinator.buildAfterImport());
        assertTrue(fx.server().rawRequests.isEmpty(), "a folder without a build file is not built (Debug compiles it)");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        fx.run(() -> fx.coordinator.buildAfterImport());
        assertTrue(
                fx.server().rawRequests.isEmpty(), "nor a Gradle build unasked: it would write bin/ (re-review CN3)");
        Files.delete(root.resolve("build.gradle"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        fx.run(() -> fx.coordinator.onServerStatus("java", "ProjectStatus", "OK"));
        fx.run(() -> fx.coordinator.buildAfterImport()); // what the import timer runs
        fx.run(() -> fx.coordinator.buildAfterImport());
        assertEquals(
                List.of("java/buildWorkspace"),
                fx.server().rawRequests.stream()
                        .map(FakeLanguageServer.Raw::method)
                        .toList(),
                "one quiet build per root, however many projects report");

        var jdk = new com.editora.text.LspDiagnostic(
                0,
                0,
                0,
                1,
                com.editora.text.LspDiagnostic.Severity.WARNING,
                "Build path specifies execution environment JavaSE-1.8.",
                null,
                "jdtls");
        fx.run(() -> fx.coordinator.onDiagnostics(root, List.of(jdk)));
        assertEquals(
                tr("status.lsp.projectProblem", root.getFileName().toString(), jdk.message()),
                fx.host.lastStatus(),
                "a problem of the project, not a file, is said; a warning not as an error (third review CT8)");
        assertTrue(
                FxTestSupport.callOnFx(() -> fx.coordinator.problems()).containsKey(fx.ops.canonicalize(root)),
                "and listed although no tab shows the folder");
        fx.host.errors.clear();
        fx.run(() -> fx.coordinator.onDiagnostics(root, List.of(jdk)));
        assertTrue(fx.host.errors.isEmpty(), "once");
    }

    // --- Lombok on request (Java review gap) ---------------------------------------------------------

    @Test
    void aProjectMissingLombokIsOfferedItOnceAndItsServerRestartsWithIt() throws Exception {
        fx.open("A.java", SOURCE);
        Path config = java.nio.file.Files.createDirectories(root.resolve("config"));
        fx.ops.configManager = new com.editora.config.ConfigManager(config);
        List<Path> asked = new java.util.ArrayList<>();
        fx.coordinator.lombokAsker = r -> {
            asked.add(r);
            return java.util.concurrent.CompletableFuture.completedFuture(asked.size() == 1);
        };
        List<Path> installedInto = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx.coordinator.lombokInstaller = dir -> {
            installedInto.add(dir);
            return dir.resolve("lombok-1.18.48.jar");
        };
        int sessions = fx.fakes.size();

        fx.run(() -> fx.coordinator.offerLombok(root));
        for (int i = 0;
                i < 100 && !fx.host.statuses.contains(tr("status.lombok.installed", "lombok-1.18.48.jar"));
                i++) {
            Thread.sleep(20);
            fx.settle();
        }
        fx.quiesce();
        assertEquals(List.of(root), asked);
        assertEquals(List.of(config.resolve("plugins/lsp/lombok")), installedInto, "into Editora's own folder");
        assertTrue(fx.fakes.size() > sessions, "the Java server restarted to pick it up");

        fx.run(() -> fx.coordinator.offerLombok(root));
        assertEquals(1, asked.size(), "once per root");
    }

    @Test
    void aDeclinedOrFailedLombokDownloadChangesNothingOrSaysWhy() throws Exception {
        fx.open("A.java", SOURCE);
        fx.ops.configManager =
                new com.editora.config.ConfigManager(java.nio.file.Files.createDirectories(root.resolve("c")));
        fx.coordinator.lombokAsker = r -> java.util.concurrent.CompletableFuture.completedFuture(true);
        fx.coordinator.lombokInstaller = dir -> {
            throw new java.io.IOException("offline");
        };
        fx.run(() -> fx.coordinator.offerLombok(root.resolve("p")));
        for (int i = 0; i < 100 && fx.host.errors.isEmpty(); i++) {
            Thread.sleep(20);
            fx.settle();
        }
        assertEquals(tr("status.lombok.failed", "offline"), fx.host.lastError());
    }

    // --- Java actions on save -------------------------------------------------------------------------

    private static final String UNTIDY =
            "import java.util.List;\nimport java.util.Map;\n\nclass A {\n  List<String> l;\n}\n";

    @Test
    void organizeImportsAndFormatOnSaveTidyTheFileAndSaveItOnceMore() throws Exception {
        EditorBuffer b = fx.open("A.java", UNTIDY);
        fx.ops.saves = true;
        fx.host.settings.setJavaOrganizeImportsOnSave(true);
        fx.host.settings.setJavaFormatOnSave(true);
        String uri = b.getPath().toUri().toString();
        fx.server().rawHandler = raw -> "java/organizeImports".equals(raw.method())
                ? com.google.gson.JsonParser.parseString("{\"changes\":{\"" + uri + "\":[{\"range\":{\"start\":"
                        + "{\"line\":1,\"character\":0},\"end\":{\"line\":2,\"character\":0}},\"newText\":\"\"}]}}")
                : null;
        fx.server().formattingResponse =
                List.of(new org.eclipse.lsp4j.TextEdit(new Range(new Position(3, 0), new Position(3, 2)), "    "));

        fx.run(() -> fx.coordinator.afterUserSave(b));
        fx.settle();

        assertEquals(
                "import java.util.List;\n\nclass A {\n    List<String> l;\n}\n", FxTestSupport.callOnFx(b::getContent));
        assertEquals(List.of(b), fx.ops.saved, "saved again with the changes");
        assertTrue(FakeLanguageServer.last(fx.server().rawRequests).method().equals("java/organizeImports"));

        fx.run(() -> fx.coordinator.afterUserSave(b)); // the save it made reaches the hook again: no new round
        assertEquals(1, fx.ops.saved.size());

        // One Undo gives back what the user saved, not "organized but not formatted" (re-review AN7).
        FxTestSupport.runOnFx(() -> b.getArea().getUndoManager().undo());
        assertEquals(UNTIDY, FxTestSupport.callOnFx(b::getContent));
        FxTestSupport.runOnFx(() -> b.getArea().getUndoManager().redo());
        assertEquals(
                "import java.util.List;\n\nclass A {\n    List<String> l;\n}\n", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void actionsOnSaveAreOffByDefaultAndAnUnchangedFileIsNotSavedAgain() throws Exception {
        EditorBuffer b = fx.open("A.java", UNTIDY);
        fx.ops.saves = true;
        fx.run(() -> fx.coordinator.afterUserSave(b));
        assertTrue(fx.server().rawRequests.isEmpty() && fx.server().formattings.isEmpty(), "off: nothing asked");

        fx.host.settings.setJavaFormatOnSave(true);
        fx.server().formattingResponse = List.of();
        fx.run(() -> fx.coordinator.afterUserSave(b));
        fx.settle();
        assertEquals(1, fx.server().formattings.size(), "format only: no Organize Imports");
        assertTrue(fx.ops.saved.isEmpty(), "nothing changed: no second save");
    }

    /**
     * While an import question is open no on-save action runs: a format on save changed the file under the question
     * and its answer was dropped though the user typed nothing (eighth review AR1).
     */
    @Test
    void noActionOnSaveRunsWhileAnImportQuestionIsOpen() throws Exception {
        EditorBuffer b = fx.open("A.java", UNTIDY);
        fx.ops.saves = true;
        fx.host.settings.setJavaFormatOnSave(true);
        fx.host.settings.setJavaCleanupOnSave(true);
        fx.server().formattingResponse = List.of();
        var question = com.editora.lsp.LspTestHooks.openQuestion(fx.manager, b.getPath());
        fx.run(() -> fx.coordinator.afterUserSave(b));
        fx.settle();
        assertTrue(fx.server().formattings.isEmpty() && fx.server().rawRequests.isEmpty(), "nothing while it is open");

        question.complete(null);
        fx.run(() -> fx.coordinator.afterUserSave(b));
        fx.settle();
        assertEquals(1, fx.server().formattings.size(), "answered: the next save formats");
    }

    // --- Java clean-ups ------------------------------------------------------------------------------

    private static final String OVERRIDES =
            "class A {\n    public String toString() {\n        return \"a\";\n    }\n}\n";

    private Object addOverride(EditorBuffer b) {
        return com.google.gson.JsonParser.parseString(
                "{\"changes\":{\"" + b.getPath().toUri() + "\":[{\"range\":{"
                        + "\"start\":{\"line\":1,\"character\":0},\"end\":{\"line\":1,\"character\":0}},"
                        + "\"newText\":\"    @Override\\n\"}]}}");
    }

    @Test
    void cleanUpOnSaveRunsTheChosenCleanUpsAndSavesAgain() throws Exception {
        EditorBuffer b = fx.open("A.java", OVERRIDES);
        fx.ops.saves = true;
        fx.host.settings.setJavaCleanupOnSave(true);
        // What applySupport hands the manager; applySupport itself may restart the server mid-test.
        fx.run(() -> fx.manager.setJavaCleanups(fx.host.settings.getJavaCleanups()));
        fx.server().rawHandler = raw -> "java/cleanup".equals(raw.method()) ? addOverride(b) : null;

        fx.run(() -> fx.coordinator.afterUserSave(b));
        fx.settle();

        assertTrue(FxTestSupport.callOnFx(b::getContent).contains("    @Override\n    public String toString()"));
        assertEquals(List.of(b), fx.ops.saved);
        assertEquals(
                List.of("java/cleanup"),
                fx.server().rawRequests.stream()
                        .map(FakeLanguageServer.Raw::method)
                        .toList(),
                "clean-up only: no Organize Imports, no Format");
    }

    @Test
    void cleanUpFileSaysWhatItDid() throws Exception {
        EditorBuffer b = fx.open("A.java", OVERRIDES);
        fx.host.settings.setJavaCleanups(List.of());
        fx.run(() -> fx.coordinator.cleanUpFile());
        assertEquals(tr("status.lsp.cleanupNoneChosen"), fx.host.lastStatus());

        fx.host.settings.setJavaCleanups(List.of("addOverride"));
        fx.run(() -> fx.manager.setJavaCleanups(fx.host.settings.getJavaCleanups()));
        fx.server().rawHandler = raw -> null;
        fx.run(() -> fx.coordinator.cleanUpFile());
        fx.settle();
        assertEquals(tr("status.lsp.cleanupNothing"), fx.host.lastStatus());

        fx.server().rawHandler = raw -> "java/cleanup".equals(raw.method()) ? addOverride(b) : null;
        fx.run(() -> fx.coordinator.cleanUpFile());
        fx.settle();
        assertEquals(tr("status.lsp.cleanedUp"), fx.host.lastStatus());
        assertTrue(FxTestSupport.callOnFx(b::getContent).contains("@Override"));
    }

    // --- a Java server shared with another window (C12) ----------------------------------------------

    @Test
    void anotherWindowRestartingTheSharedJavaServerReopensThisWindowsFileQuietly() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        Path file = b.getPath();
        LspManager other = new LspManager((f, d) -> {}, (t, m) -> {});
        try {
            LspTestHooks.useFakeSessions(other);
            other.configure(true, java.util.Map.of("java", "jdtls"));
            LspTestHooks.shareServers(fx.manager, other);
            other.openDocument(file, LspTestHooks.sessionRoot(fx.manager, file), "java", SOURCE);
            FakeLanguageServer first = fx.server();
            assertEquals(1, first.opened.size(), "the other window's open of the same file is not a second open");

            fx.host.statuses.clear();
            fx.run(other::restartAll);
            fx.settle();

            assertTrue(fx.manager.isManaged(file), "reopened on the new server");
            assertTrue(fx.server() != first, "a new server");
            assertFalse(
                    fx.host.statuses.contains(tr("status.lsp.crashed", "Java")),
                    "no crash notice for another window's restart: " + fx.host.statuses);
        } finally {
            other.shutdownAll();
        }
    }

    // --- Smart Type completion ----------------------------------------------------------------------

    private static org.eclipse.lsp4j.CompletionItem typed(String label, String type, int relevance) {
        var item = new org.eclipse.lsp4j.CompletionItem(label);
        item.setKind(org.eclipse.lsp4j.CompletionItemKind.Field);
        var details = new org.eclipse.lsp4j.CompletionItemLabelDetails();
        details.setDescription(type);
        item.setLabelDetails(details);
        item.setSortText(String.valueOf(999_999_999L - relevance * 16L - 5));
        return item;
    }

    private void serverOffers(org.eclipse.lsp4j.CompletionItem... items) {
        fx.server().completionFuture =
                java.util.concurrent.CompletableFuture.completedFuture(Either.forLeft(List.of(items)));
    }

    /** Waits for the completion request to reach the server, which takes a worker hop. */
    private void awaitCompletions(int count) throws Exception {
        for (int i = 0; i < 200 && fx.server().completions.size() < count; i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertEquals(count, fx.server().completions.size(), "the list was asked for");
    }

    /** Waits for the status a completion's render sets from the worker it filters on. */
    private void awaitStatus(String expected) throws Exception {
        for (int i = 0; i < 200 && !expected.equals(fx.host.lastStatus()); i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertEquals(expected, fx.host.lastStatus());
    }

    @Test
    void smartTypeCompletionFiltersToTheDeclaredType() throws Exception {
        fx.capabilities.setCompletionProvider(new org.eclipse.lsp4j.CompletionOptions());
        EditorBuffer b = fx.open("A.java", "class A {\n    void f() {\n        String s = \n    }\n}\n");
        FxTestSupport.runOnFx(() -> fx.coordinator.wireBuffer(b)); // the completion source, as addBuffer does
        fx.caret(b, 2, 19);
        serverOffers(typed("name", "String", 82), typed("names", "List<String>", 52));
        int before = fx.server().completions.size();
        fx.run(() -> fx.coordinator.smartTypeCompletion());
        assertEquals(tr("status.lsp.smartType", "String"), fx.host.lastStatus());
        awaitCompletions(before + 1);

        serverOffers(typed("names", "List<String>", 52));
        fx.run(() -> fx.coordinator.smartTypeCompletion());
        awaitStatus(tr("status.lsp.smartTypeNone", "String"));
    }

    @Test
    void smartTypeCompletionAsksSignatureHelpForAnArgument() throws Exception {
        EditorBuffer b = fx.open("A.java", "class A {\n    void f() {\n        take(\n    }\n}\n");
        fx.caret(b, 2, 13);
        var signature = new org.eclipse.lsp4j.SignatureInformation("take(int n) : void");
        signature.setParameters(List.of(new org.eclipse.lsp4j.ParameterInformation("int n")));
        var help = new org.eclipse.lsp4j.SignatureHelp(List.of(signature), 0, 0);
        fx.server().signatureHelpResponse = help;
        fx.run(() -> fx.coordinator.smartTypeCompletion());
        assertEquals(tr("status.lsp.smartType", "int"), fx.host.lastStatus());

        fx.server().signatureHelpResponse = null;
        fx.run(() -> fx.coordinator.smartTypeCompletion());
        assertEquals(tr("status.lsp.smartTypeUnknown"), fx.host.lastStatus(), "no signature: the whole list");
    }

    @Test
    void smartTypeCompletionWithNothingExpectedOpensTheWholeList() throws Exception {
        fx.capabilities.setCompletionProvider(new org.eclipse.lsp4j.CompletionOptions());
        EditorBuffer b = fx.open("A.java", "class A {\n    void f() {\n        \n    }\n}\n");
        FxTestSupport.runOnFx(() -> fx.coordinator.wireBuffer(b)); // the completion source, as addBuffer does
        fx.caret(b, 2, 8);
        int before = fx.server().completions.size();
        fx.run(() -> fx.coordinator.smartTypeCompletion());
        assertEquals(tr("status.lsp.smartTypeUnknown"), fx.host.lastStatus());
        awaitCompletions(before + 1);

        EditorBuffer plain = FxTestSupport.callOnFx(EditorBuffer::new);
        FxTestSupport.runOnFx(() -> {
            fx.show(plain);
            fx.host.active = plain;
        });
        fx.run(() -> fx.coordinator.smartTypeCompletion());
        assertEquals(tr("status.lsp.smartTypeJavaOnly"), fx.host.lastStatus());
    }

    // --- a failed import says why (re-review CN2, CN9) -----------------------------------------------

    private void awaitError(String expected) throws Exception {
        for (int i = 0; i < 200 && !expected.equals(fx.host.lastError()); i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertEquals(expected, fx.host.lastError());
    }

    @Test
    void aFailedImportSaysWhyAndIsNotThenCalledReady() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        LspTestHooks.logError(
                fx.manager,
                b.getPath(),
                "Oct 25, 2026, 11:02:14 AM Could not find method thisIsNotAConfiguration() for arguments [x:y:1]\n\tat …");
        fx.run(() -> fx.coordinator.onServerStatus("java", "ProjectStatus", "WARNING"));
        awaitError(tr(
                "status.lsp.importProblemWhy",
                "jdtls",
                "Could not find method thisIsNotAConfiguration() for arguments [x:y:1]"));
        assertFalse(fx.host.lastError().contains("(WARNING)"), "jdtls's raw word is not shown");

        fx.run(() -> fx.coordinator.onServerStatus("java", "ServiceReady", "ServiceReady"));
        awaitError(tr("status.lsp.readyImportFailed", "jdtls"));
    }

    /**
     * A failed import is said in the project's own words when jdtls put an error on the project, belongs to that
     * project's folder only, and an OK import of that folder clears it (third review CT3, CT7).
     */
    @Test
    void aFailedImportIsTheFoldersOwnAndSaysTheProjectsReason() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        LspTestHooks.logError(
                fx.manager, b.getPath(), "Could not fetch model of type 'Map' using connection to Gradle");
        var cause = new com.editora.text.LspDiagnostic(
                0,
                0,
                0,
                1,
                com.editora.text.LspDiagnostic.Severity.ERROR,
                "The supplied phased action failed with an exception.\nBuild file 'build.gradle' line: 3\n"
                        + "Could not find method thisIsNotAConfiguration() for arguments [x]",
                null,
                "jdtls");
        fx.run(() -> fx.coordinator.onDiagnostics(root, List.of(cause)));
        Path other = java.nio.file.Files.createDirectories(root.resolve("other-project"));
        Path otherFile = java.nio.file.Files.writeString(other.resolve("B.java"), "class B {}");
        fx.run(() -> fx.manager.openDocument(otherFile, other, "java", "class B {}")); // a live server there
        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ProjectStatus", "WARNING"));
        awaitError(tr(
                "status.lsp.importProblemWhy",
                "jdtls",
                "Could not find method thisIsNotAConfiguration() for arguments [x]"));

        fx.run(() -> fx.coordinator.onServerStatus("java", other, "ServiceReady", "ServiceReady"));
        // another folder's import is not this one's failure, and with two folders each is named (fourth review CF2)
        awaitStatus(tr("status.lsp.ready", tr("status.lsp.serverInFolder", "jdtls", "other-project")));
        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ServiceReady", "ServiceReady"));
        awaitError(tr(
                "status.lsp.readyImportFailed",
                tr("status.lsp.serverInFolder", "jdtls", root.getFileName().toString())));

        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ProjectStatus", "OK"));
        fx.run(() -> fx.coordinator.reloadProject());
        assertFalse(
                tr("status.lsp.projectReimporting").equals(fx.host.lastStatus()),
                "it imported since: the failure no longer stands, so Reload is a plain reload");
    }

    @Test
    void theProjectsReasonIsSaidWhenItsErrorArrivesAfterTheFailure() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        LspTestHooks.logError(fx.manager, b.getPath(), "Could not fetch model of type 'Map'");
        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ProjectStatus", "WARNING"));
        awaitError(tr("status.lsp.importProblemWhy", "jdtls", "Could not fetch model of type 'Map'"));
        var cause = new com.editora.text.LspDiagnostic(
                0,
                0,
                0,
                1,
                com.editora.text.LspDiagnostic.Severity.ERROR,
                "The supplied phased action failed.\nCould not find method nope()",
                null,
                "jdtls");
        fx.run(() -> fx.coordinator.onDiagnostics(root, List.of(cause)));
        awaitError(tr("status.lsp.importProblemWhy", "jdtls", "Could not find method nope()"));
        int said = fx.host.errors.size();
        fx.run(() -> fx.coordinator.onDiagnostics(root, List.of(cause))); // published again
        assertEquals(said, fx.host.errors.size(), "said once");
    }

    @Test
    void anImportWithBuildProblemsIsLoadedNotFailed() throws Exception {
        fx.open("A.java", SOURCE);
        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ProjectStatus", "WARNING")); // a missing artifact
        fx.settle();
        assertFalse(
                fx.host.statuses.contains(tr("status.lsp.importProblem", "jdtls")),
                "said once, at ready: mid-import it came twice in two wordings (fourth review CF4)");
        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ServiceReady", "ServiceReady"));
        awaitStatus(tr("status.lsp.readyWithProblems", "jdtls")); // not "did not import" (third review CT2)

        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ProjectStatus", "OK")); // the pom was fixed
        assertEquals(tr("status.lsp.ready", "jdtls"), fx.host.lastStatus(), "the fix ends on ready (CF4)");

        fx.run(() -> fx.coordinator.onServerStatus("java", root, "ProjectStatus", "WARNING")); // broken again
        awaitStatus(tr("status.lsp.importProblem", "jdtls"));
    }

    /**
     * Reload run from the build.gradle tab, which the fix and the failure message point the user to, and saving that
     * file, both import a project that failed again (fourth review CT1): the build file has no server of its own, so
     * its folder's failure was missed and an update went to a project that does not exist.
     */
    @Test
    void reloadOrSaveFromTheBuildFileImportsAFailedProjectAgain() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        Path sessionRoot = LspTestHooks.sessionRoot(fx.manager, b.getPath());
        Path buildFile = java.nio.file.Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        EditorBuffer build = FxTestSupport.callOnFx(EditorBuffer::new); // no server edits it
        FxTestSupport.runOnFx(() -> {
            build.setPath(buildFile);
            build.setContent("plugins { id 'java' }\n");
            fx.show(build);
        });
        LspTestHooks.logError(fx.manager, b.getPath(), "Could not fetch model of type 'Map'");
        fx.run(() -> fx.coordinator.onServerStatus("java", sessionRoot, "ProjectStatus", "WARNING"));
        awaitError(tr("status.lsp.importProblemWhy", "jdtls", "Could not fetch model of type 'Map'"));
        FxTestSupport.runOnFx(() -> fx.host.active = build);
        int notified = fx.server().rawNotifications.size();

        fx.run(() -> fx.coordinator.reloadProject());

        assertEquals(tr("status.lsp.projectReimporting"), fx.host.lastStatus());
        assertTrue(
                fx.server().rawNotifications.stream()
                        .skip(notified)
                        .noneMatch(r -> r.method().equals("java/projectConfigurationUpdate")),
                "no update of a project that does not exist");

        LspTestHooks.logError(fx.manager, b.getPath(), "Could not fetch model again");
        Path again = LspTestHooks.sessionRoot(fx.manager, b.getPath());
        fx.run(() -> fx.coordinator.onServerStatus("java", again, "ProjectStatus", "WARNING"));
        awaitError(tr("status.lsp.importProblemWhy", "jdtls", "Could not fetch model again"));
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.notifyDocumentSaved(build, build.getContent()));
        assertEquals(tr("status.lsp.projectReimporting"), fx.host.lastStatus(), "saving the fixed build file");
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.notifyDocumentSaved(build, build.getContent()));
        assertFalse(
                fx.host.statuses.contains(tr("status.lsp.projectReimporting")),
                "an import that did not fail is not started over on every save");
    }

    /** Doctor's Update stops the server in every window; when it is done this window's files reopen (CF1). */
    @Test
    void afterAnInstallThisWindowsFilesReopenOnTheNewServer() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        assertTrue(fx.manager.isManaged(b.getPath()));
        try {
            FxTestSupport.runOnFx(() -> com.editora.lsp.LspManager.holdForInstall("java"));
            assertFalse(fx.manager.isManaged(b.getPath()));
            assertEquals(tr("status.lsp.heldForInstall", "jdtls"), fx.host.lastStatus());
        } finally {
            FxTestSupport.runOnFx(() -> com.editora.lsp.LspManager.releaseAfterInstall("java"));
        }
        for (int i = 0; i < 200 && !fx.manager.isManaged(b.getPath()); i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertTrue(fx.manager.isManaged(b.getPath()), "reopened without a Restart Language Servers");
    }

    @Test
    void reloadAfterAFailedImportStartsTheServerOverAndCleanForgetsTheFailure() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        LspTestHooks.logError(
                fx.manager, b.getPath(), "Oct 25, 2026, 3:13:16\u202FPM Could not fetch model of type 'Map'.");
        fx.run(() -> fx.coordinator.onServerStatus("java", "ProjectStatus", "WARNING"));
        awaitError(tr("status.lsp.importProblemWhy", "jdtls", "Could not fetch model of type 'Map'")); // CT7
        int notified = fx.server().rawNotifications.size();

        fx.run(() -> fx.coordinator.reloadProject());

        assertEquals(tr("status.lsp.projectReimporting"), fx.host.lastStatus(), "third review CT1");
        assertTrue(
                fx.server().rawNotifications.stream()
                        .skip(notified)
                        .noneMatch(r -> r.method().equals("java/projectConfigurationUpdate")),
                "no update of a project that does not exist");

        LspTestHooks.logError(fx.manager, b.getPath(), "Could not fetch model again");
        fx.run(() -> fx.coordinator.onServerStatus("java", "ProjectStatus", "WARNING"));
        fx.run(() -> fx.coordinator.cleanJavaWorkspace());
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.onServerStatus("java", "ServiceReady", "ServiceReady"));
        awaitStatus(tr("status.lsp.ready", "jdtls")); // the fresh import says its own outcome (CT3)
    }

    @Test
    void aFilesPackageLineIsReadFromDiskWhileItsBufferIsStillEmpty() throws Exception {
        Path hello =
                java.nio.file.Files.createDirectories(root.resolve("src/demo")).resolve("Hello.java");
        java.nio.file.Files.writeString(hello, "/* licence */\npackage demo;\n\nclass Hello {}\n");
        assertEquals(
                "demo",
                com.editora.dap.JavaSourceRootFix.declaredPackage(LspCoordinator.packageSource("", hello)),
                "the server is chosen before the text loads (third review CT4)");
        assertEquals("typed", LspCoordinator.packageSource("typed", hello), "a loaded buffer is read as it is");
        assertEquals("", LspCoordinator.packageSource("", root.resolve("missing.java")));
    }

    @Test
    void aLooseJavaFileIsRootedWhereItsPackageSays() throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("src/demo"));
        EditorBuffer b = fx.open("src/demo/Hello.java", "package demo;\n\nclass Hello {}\n");
        assertEquals(
                root.resolve("src").toAbsolutePath().normalize(),
                LspTestHooks.sessionRoot(fx.manager, b.getPath())
                        .toAbsolutePath()
                        .normalize(),
                "not src/demo, where jdtls expected the default package (re-review C6)");
    }

    /** Reload Project Configuration on a project that imported ends on "Loaded and ready" (fifth review CV3). */
    @Test
    void aReloadEndsOnReady() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        Path sessionRoot = LspTestHooks.sessionRoot(fx.manager, b.getPath());
        fx.run(() -> fx.coordinator.onServerStatus("java", sessionRoot, "ServiceReady", "ServiceReady"));
        awaitStatus(tr("status.lsp.ready", "jdtls"));
        fx.run(() -> fx.coordinator.reloadProject());
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.onServerStatus("java", sessionRoot, "ProjectStatus", "OK"));
        assertTrue(fx.host.statuses.contains(tr("status.lsp.ready", "jdtls")), fx.host.statuses.toString());
    }

    /** Reload in one project keeps another project's failed import: it was cleared for the window (sixth CS1). */
    @Test
    void aReloadKeepsAnotherProjectsFailedImport() throws Exception {
        fx.open("A.java", SOURCE);
        Path other = java.nio.file.Files.createDirectories(root.resolve("other-project"));
        Path otherFile = java.nio.file.Files.writeString(other.resolve("B.java"), "class B {}");
        fx.run(() -> fx.manager.openDocument(otherFile, other, "java", "class B {}"));
        LspTestHooks.logError(fx.manager, otherFile, "Could not fetch model of type 'Map'");
        fx.run(() -> fx.coordinator.onServerStatus("java", other, "ProjectStatus", "WARNING"));
        java.util.Set<String> failed = FxTestSupport.field(fx.coordinator, "importFailed");
        assertTrue(FxTestSupport.callOnFx(() -> failed.stream().anyMatch(k -> k.endsWith("other-project"))));

        fx.run(() -> fx.coordinator.reloadProject()); // from A.java's project
        assertTrue(
                FxTestSupport.callOnFx(() -> failed.stream().anyMatch(k -> k.endsWith("other-project"))),
                "the other project's failure stands");
    }

    /** Clean in one project leaves another project's build error in Problems (seventh review CR4). */
    @Test
    void aCleanKeepsAnotherProjectsProblems() throws Exception {
        fx.open("A.java", SOURCE);
        Path other = java.nio.file.Files.createDirectories(root.resolve("other-project"));
        Path otherFile = java.nio.file.Files.writeString(other.resolve("B.java"), "class B {}");
        fx.run(() -> fx.manager.openDocument(otherFile, other, "java", "class B {}"));
        fx.run(() -> fx.coordinator.onServerStatus("java", other, "ServiceReady", "ServiceReady")); // known here
        var broken = new com.editora.text.LspDiagnostic(
                0,
                0,
                0,
                1,
                com.editora.text.LspDiagnostic.Severity.ERROR,
                "The supplied phased action failed",
                null,
                "jdtls");
        fx.run(() -> fx.coordinator.onDiagnostics(other, List.of(broken)));
        assertTrue(FxTestSupport.callOnFx(
                () -> fx.coordinator.problems().keySet().stream().anyMatch(p -> p.endsWith("other-project"))));

        fx.run(() -> fx.coordinator.cleanJavaWorkspace()); // from A.java's project
        assertTrue(
                FxTestSupport.callOnFx(
                        () -> fx.coordinator.problems().keySet().stream().anyMatch(p -> p.endsWith("other-project"))),
                "the other project's build error stands");
    }
}
