package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editor.LspTextEdit;
import com.editora.editor.OccurrenceSpan;
import com.editora.editor.SemanticToken;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionDisabled;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensDelta;
import org.eclipse.lsp4j.SemanticTokensEdit;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensServerFull;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link LspManager} round-trips {@code LspManagerRequestsFxTest} does not reach: hierarchies, code
 * actions and their three ways of carrying a change (inline edit, deferred edit, command), the completion
 * follow-ups, semantic-token deltas, and the vendor {@code java/…} requests behind the jdtls generators and
 * refactorings.
 *
 * <p>Each of these maps an lsp4j answer to a neutral value the editor then acts on without looking at it
 * again, so what is asserted is the value delivered — and, for every one, what a refusing or failing server
 * leaves the user with.
 */
@Tag("fx")
class LspManagerFeatureRequestsFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private final List<List<Path>> blocked = new CopyOnWriteArrayList<>();
    private Path file;

    /** Capabilities the next session is attached with; a test sets this before opening. */
    private ServerCapabilities capabilities = new ServerCapabilities();

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.setRawSinkForTest(fake.rawSink());
            session.attachForTest(fake, capabilities);
        });
        manager.setOnEditBlocked(blocked::add);
        manager.configure(true, Map.of("java", "jdtls"));
        file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private FakeLanguageServer open() {
        manager.openDocument(file, root, "java", "class A {}\n");
        return fakes.get(0);
    }

    /** Runs a request and blocks for its FX-thread callback, returning what it delivered. */
    private <T> T await(Consumer<Consumer<T>> request) throws Exception {
        var result = new AtomicReference<T>();
        var latch = new CountDownLatch(1);
        request.accept(v -> {
            result.set(v);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the callback never fired");
        return result.get();
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    /**
     * An lsp4j value as it arrives off the wire. A server may leave out a field the specification requires,
     * and gson then leaves it null — a state lsp4j's own setters refuse to build, so it is parsed instead.
     */
    private static <T> T wire(String json, Class<T> type) {
        return LanguageServerSession.LSP_GSON.fromJson(json, type);
    }

    private static String rangeJson(int line, int from, int to) {
        return "{\"start\":{\"line\":" + line + ",\"character\":" + from + "},\"end\":{\"line\":" + line
                + ",\"character\":" + to + "}}";
    }

    private String uriJson() {
        return "\"" + file.toUri() + "\"";
    }

    private Path unopened() throws Exception {
        Path other = root.resolve("Nope.java");
        Files.writeString(other, "class Nope {}\n");
        return other;
    }

    // --- call and type hierarchy (#682) --------------------------------------------------------------

    private CallHierarchyItem callItem(String name, int line) {
        var item = new CallHierarchyItem();
        item.setName(name);
        item.setDetail("demo.A");
        item.setKind(SymbolKind.Method);
        item.setUri(file.toUri().toString());
        item.setRange(range(line, 0, 20));
        item.setSelectionRange(range(line, 9, 11));
        return item;
    }

    @Test
    void callHierarchyAnchorCarriesTheSelectionRangeAndTheRawItem() throws Exception {
        capabilities.setCallHierarchyProvider(Either.forLeft(true));
        var fake = open();
        CallHierarchyItem go = callItem("go", 3);
        fake.callHierarchyResponse = List.of(go);

        assertTrue(manager.supportsCallHierarchy(file));
        List<LspManager.HierarchyNode> nodes = await(cb -> manager.prepareCallHierarchy(file, 3, 10, cb));

        assertEquals(1, nodes.size());
        LspManager.HierarchyNode node = nodes.get(0);
        assertEquals("go", node.name());
        assertEquals("demo.A", node.detail());
        assertEquals("method", node.kindName());
        assertEquals(file, node.file());
        assertEquals(3, node.line());
        assertEquals(9, node.col(), "the caret lands on the name, not the start of the declaration");
        assertSame(go, node.raw(), "the server's item is what the children are asked about");
        assertEquals(
                new Position(3, 10),
                FakeLanguageServer.last(fake.callHierarchyPrepares).getPosition());
    }

    @Test
    void callHierarchyChildrenAskTheDirectionTheUserChose() throws Exception {
        capabilities.setCallHierarchyProvider(Either.forLeft(true));
        var fake = open();
        CallHierarchyItem go = callItem("go", 3);
        // No selection range: falls back to the declaration's range.
        var caller = wire(
                "{\"name\":\"caller\",\"kind\":6,\"uri\":" + uriJson() + ",\"range\":" + rangeJson(7, 0, 20) + "}",
                CallHierarchyItem.class);
        // Neither range, and no name, detail or kind: the top of the file, not an exception.
        var callee = wire("{\"uri\":" + uriJson() + "}", CallHierarchyItem.class);
        fake.incomingCallsResponse = new ArrayList<>(List.of(new CallHierarchyIncomingCall(caller, List.of())));
        fake.incomingCallsResponse.add(new CallHierarchyIncomingCall());
        fake.outgoingCallsResponse = new ArrayList<>(List.of(new CallHierarchyOutgoingCall(callee, List.of())));
        fake.outgoingCallsResponse.add(new CallHierarchyOutgoingCall());

        List<LspManager.HierarchyNode> incoming = await(cb -> manager.callHierarchyChildren(file, go, true, cb));
        List<LspManager.HierarchyNode> outgoing = await(cb -> manager.callHierarchyChildren(file, go, false, cb));

        assertEquals(
                List.of("caller"),
                incoming.stream().map(LspManager.HierarchyNode::name).toList());
        assertEquals(7, incoming.get(0).line());
        assertEquals(0, incoming.get(0).col());
        assertEquals(1, outgoing.size(), "a call with no target is dropped");
        assertEquals("", outgoing.get(0).name());
        assertEquals("", outgoing.get(0).detail());
        assertEquals("", outgoing.get(0).kindName());
        assertEquals(0, outgoing.get(0).line());
        assertEquals(List.of(go), fake.incomingCallRequests);
        assertEquals(List.of(go), fake.outgoingCallRequests);
    }

    @Test
    void typeHierarchyAnchorAndChildrenAreMapped() throws Exception {
        capabilities.setTypeHierarchyProvider(Either.forLeft(true));
        var fake = open();
        var type =
                new TypeHierarchyItem("A", SymbolKind.Class, file.toUri().toString(), range(0, 0, 10), range(0, 6, 7));
        var parent = wire(
                "{\"name\":\"Base\",\"kind\":11,\"uri\":\"jdt://contents/x/Base.class\"}", TypeHierarchyItem.class);
        var child = wire(
                "{\"name\":\"Sub\",\"kind\":5,\"uri\":" + uriJson() + ",\"range\":" + rangeJson(5, 0, 9) + "}",
                TypeHierarchyItem.class);
        fake.typeHierarchyResponse = List.of(type);
        fake.supertypesResponse = List.of(parent);
        fake.subtypesResponse = List.of(child);

        assertTrue(manager.supportsTypeHierarchy(file));
        List<LspManager.HierarchyNode> anchor = await(cb -> manager.prepareTypeHierarchy(file, 0, 6, cb));
        List<LspManager.HierarchyNode> supers = await(cb -> manager.typeHierarchyChildren(file, type, true, cb));
        List<LspManager.HierarchyNode> subs = await(cb -> manager.typeHierarchyChildren(file, type, false, cb));

        assertEquals("A", anchor.get(0).name());
        assertEquals("class", anchor.get(0).kindName());
        assertEquals(6, anchor.get(0).col());
        assertEquals("Base", supers.get(0).name());
        assertEquals("interface", supers.get(0).kindName());
        assertNull(supers.get(0).file(), "a type inside a library has no file to open");
        assertEquals(0, supers.get(0).line());
        assertEquals("Sub", subs.get(0).name());
        assertEquals(5, subs.get(0).line());
        assertEquals(List.of(type), fake.supertypeRequests);
        assertEquals(List.of(type), fake.subtypeRequests);
    }

    /** A node from one hierarchy handed to the other, a failing server, and an unmanaged file all read as
     *  "no children" — the tree shows an empty branch instead of throwing on expand. */
    @Test
    void hierarchiesDegradeToNoNodes() throws Exception {
        capabilities.setCallHierarchyProvider(Either.forLeft(true));
        capabilities.setTypeHierarchyProvider(Either.forLeft(true));
        var fake = open();
        Path other = unopened();
        CallHierarchyItem go = callItem("go", 3);
        var type =
                new TypeHierarchyItem("A", SymbolKind.Class, file.toUri().toString(), range(0, 0, 10), range(0, 6, 7));

        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.callHierarchyChildren(file, type, true, cb))
                .isEmpty());
        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.typeHierarchyChildren(file, go, true, cb))
                .isEmpty());
        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.prepareCallHierarchy(other, 0, 0, cb))
                .isEmpty());
        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.prepareTypeHierarchy(other, 0, 0, cb))
                .isEmpty());
        assertFalse(manager.supportsCallHierarchy(other));
        assertFalse(manager.supportsTypeHierarchy(other));

        fake.failEverything = true;
        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.prepareCallHierarchy(file, 0, 0, cb))
                .isEmpty());
        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.prepareTypeHierarchy(file, 0, 0, cb))
                .isEmpty());
        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.callHierarchyChildren(file, go, true, cb))
                .isEmpty());
        assertTrue(this.<List<LspManager.HierarchyNode>>await(cb -> manager.callHierarchyChildren(file, go, false, cb))
                .isEmpty());
        assertTrue(
                this.<List<LspManager.HierarchyNode>>await(cb -> manager.typeHierarchyChildren(file, type, false, cb))
                        .isEmpty());
    }

    @Test
    void hierarchyGatesReadBothCapabilityShapes() {
        var caps = new ServerCapabilities();
        assertFalse(LspManager.callHierarchyProvider(null));
        assertFalse(LspManager.typeHierarchyProvider(null));
        assertFalse(LspManager.callHierarchyProvider(caps));
        assertFalse(LspManager.typeHierarchyProvider(caps));
        caps.setCallHierarchyProvider(Either.forLeft(false));
        caps.setTypeHierarchyProvider(Either.forLeft(false));
        assertFalse(LspManager.callHierarchyProvider(caps), "advertised false");
        assertFalse(LspManager.typeHierarchyProvider(caps), "advertised false");
        caps.setCallHierarchyProvider(Either.forRight(new org.eclipse.lsp4j.CallHierarchyRegistrationOptions()));
        caps.setTypeHierarchyProvider(Either.forRight(new org.eclipse.lsp4j.TypeHierarchyRegistrationOptions()));
        assertTrue(LspManager.callHierarchyProvider(caps), "the options form means supported");
        assertTrue(LspManager.typeHierarchyProvider(caps), "the options form means supported");
    }

    // --- rename ----------------------------------------------------------------------------------------

    @Test
    void prepareRenameIsOnlyOfferedWhenTheServerAdvertisesIt() throws Exception {
        assertFalse(manager.supportsPrepareRename(unopened()), "no session");
        capabilities.setRenameProvider(Either.forLeft(true));
        open();
        assertTrue(manager.supportsRename(file));
        assertFalse(manager.supportsPrepareRename(file), "a bare true says nothing about prepare");

        capabilities.setRenameProvider(Either.forRight(new RenameOptions(false)));
        assertFalse(manager.supportsPrepareRename(file));
        capabilities.setRenameProvider(Either.forRight(new RenameOptions(true)));
        assertTrue(manager.supportsPrepareRename(file));
    }

    @Test
    void prepareRenameMapsTheRangeAndDefaultBehaviourShapes() throws Exception {
        var fake = open();
        fake.prepareRenameResponse = Either3.forFirst(new Range(new Position(2, 4), new Position(2, 9)));
        LspManager.RenamePrep ranged = await(cb -> manager.prepareRename(file, 2, 5, cb));
        assertEquals(new LspManager.RenamePrep(true, "", 2, 4, 2, 9), ranged);

        fake.prepareRenameResponse = Either3.forThird(new PrepareRenameDefaultBehavior(true));
        LspManager.RenamePrep byDefault = await(cb -> manager.prepareRename(file, 2, 5, cb));
        assertEquals(new LspManager.RenamePrep(true, "", 0, 0, 0, 0), byDefault);

        fake.prepareRenameResponse = Either3.forSecond(wire("{}", org.eclipse.lsp4j.PrepareRenameResult.class));
        LspManager.RenamePrep noRange = await(cb -> manager.prepareRename(file, 2, 5, cb));
        assertEquals(new LspManager.RenamePrep(true, "", 0, 0, 0, 0), noRange);

        Path other = unopened();
        LspManager.RenamePrep unmanaged = await(cb -> manager.prepareRename(other, 0, 0, cb));
        assertFalse(unmanaged.allowed(), "no server, no rename");
    }

    @Test
    void previewRenameDeliversTheEditWithoutApplyingIt() throws Exception {
        var fake = open();
        var applied = new AtomicReference<>(false);
        manager.setApplyEditHandler((mapped, done) -> {
            applied.set(true);
            done.accept(true);
        });
        fake.renameResponse =
                new WorkspaceEdit(Map.of(file.toUri().toString(), List.of(new TextEdit(range(0, 6, 7), "Renamed"))));

        WorkspaceEditMapper.Mapped mapped = await(cb -> manager.previewRename(file, 0, 6, "Renamed", cb));

        assertNotNull(mapped);
        assertEquals(1, mapped.edits().size());
        assertEquals(file, mapped.edits().get(0).file());
        assertEquals(
                List.of(new LspTextEdit(0, 6, 0, 7, "Renamed")),
                mapped.edits().get(0).edits());
        assertFalse(applied.get(), "a preview must not touch the workspace");
        assertEquals("Renamed", FakeLanguageServer.last(fake.renames).getNewName());
    }

    @Test
    void previewRenameDeliversNullWhenTheServerRefusesOrFails() throws Exception {
        var fake = open();
        fake.renameResponse = null;
        assertNull(this.<WorkspaceEditMapper.Mapped>await(cb -> manager.previewRename(file, 0, 6, "X", cb)));

        fake.failEverything = true;
        assertNull(this.<WorkspaceEditMapper.Mapped>await(cb -> manager.previewRename(file, 0, 6, "X", cb)));

        Path other = unopened();
        assertNull(this.<WorkspaceEditMapper.Mapped>await(cb -> manager.previewRename(other, 0, 6, "X", cb)));
    }

    /** The server edits a file that is no longer on disk: nothing shows what the edit was computed from, so
     *  the rename is refused and the user is told which file made it so. */
    @Test
    void previewRenameReportsTheFileThatBlockedIt() throws Exception {
        var fake = open();
        Path gone = root.resolve("Gone.java");
        fake.renameResponse =
                new WorkspaceEdit(Map.of(gone.toUri().toString(), List.of(new TextEdit(range(0, 15, 16), "B"))));

        WorkspaceEditMapper.Mapped mapped = await(cb -> manager.previewRename(file, 0, 6, "B", cb));

        assertNull(mapped);
        assertEquals(List.of(List.of(gone)), blocked, "the refusal names the file it is about");
    }

    // --- code actions (#670) ---------------------------------------------------------------------------

    @Test
    void codeActionsListPreferredFirstAndDropDisabledOnes() throws Exception {
        capabilities.setCodeActionProvider(true);
        var fake = open();
        var plain = new CodeAction("Add import");
        plain.setKind("quickfix");
        var preferred = new CodeAction("Fix typo");
        preferred.setIsPreferred(true);
        var disabled = new CodeAction("Extract method");
        disabled.setDisabled(new CodeActionDisabled("select an expression first"));
        var untitled = wire("{}", CodeAction.class);
        var bare = new Command("Organize imports", "java.edit.organizeImports", List.of("arg"));
        fake.codeActionResponse = new ArrayList<>();
        fake.codeActionResponse.add(Either.forRight(plain));
        fake.codeActionResponse.add(Either.forLeft(bare));
        fake.codeActionResponse.add(Either.forRight(disabled));
        fake.codeActionResponse.add(Either.forRight(preferred));
        fake.codeActionResponse.add(Either.forRight(untitled));
        fake.codeActionResponse.add(Either.forLeft(wire("{\"command\":\"java.noTitle\"}", Command.class)));

        assertTrue(manager.supportsCodeActions(file));
        List<LspManager.CodeActionItem> items = await(cb -> manager.codeActions(file, 0, 1, 0, 4, cb));

        assertEquals(
                List.of("Fix typo", "Add import", "Organize imports", "", ""),
                items.stream().map(LspManager.CodeActionItem::title).toList(),
                "the server's recommended fix leads; one it says does not apply here is not offered");
        assertTrue(items.get(0).preferred());
        assertEquals("quickfix", items.get(1).kind());
        assertEquals("", items.get(2).kind(), "a bare command has no kind");
        assertSame(bare, items.get(2).raw());
        assertEquals(Map.of(file, "class A {}\n"), items.get(0).expectedDocuments());
        assertEquals(range(0, 1, 4), FakeLanguageServer.last(fake.codeActions).getRange());
    }

    @Test
    void codeActionsDegradeToNoneOnFailureOrWithoutAServer() throws Exception {
        var fake = open();
        fake.failEverything = true;
        assertTrue(this.<List<LspManager.CodeActionItem>>await(cb -> manager.codeActions(file, 0, 0, 0, 0, cb))
                .isEmpty());
        Path other = unopened();
        assertTrue(this.<List<LspManager.CodeActionItem>>await(cb -> manager.codeActions(other, 0, 0, 0, 0, cb))
                .isEmpty());
        assertFalse(manager.supportsCodeActions(other));
    }

    @Test
    void commandPayloadAccessorsReadBothCarriers() {
        var command = new Command("Generate toString()", "java.action.generateToStringPrompt");
        command.setArguments(new ArrayList<>(java.util.Arrays.asList(Map.of("k", "v"), null, 7)));
        var action = new CodeAction("wrapped");
        action.setCommand(command);

        assertEquals("java.action.generateToStringPrompt", LspManager.commandIdOf(command));
        assertEquals("java.action.generateToStringPrompt", LspManager.commandIdOf(action));
        assertNull(LspManager.commandIdOf("not a payload"));
        assertNull(LspManager.commandIdOf(new CodeAction("no command")));

        assertEquals(Map.of("k", "v"), LspManager.commandArgument(action));
        assertNull(LspManager.commandArgument(new Command("no args", "x")));
        assertNull(LspManager.commandArgument(new Command("empty", "x", List.of())));
        assertNull(LspManager.commandArgument(42));

        List<JsonElement> args = LspManager.commandArguments(command);
        assertEquals(3, args.size());
        assertEquals("v", args.get(0).getAsJsonObject().get("k").getAsString());
        assertTrue(args.get(1).isJsonNull(), "a null argument keeps its position");
        assertEquals(7, args.get(2).getAsInt());
        assertEquals(List.of(), LspManager.commandArguments(new Command("no args", "x")));
        assertEquals(List.of(), LspManager.commandArguments("not a payload"));
    }

    private WorkspaceEdit editOfThisFile(String text) {
        return new WorkspaceEdit(Map.of(file.toUri().toString(), List.of(new TextEdit(range(0, 0, 0), text))));
    }

    private LspManager.CodeActionItem listed(FakeLanguageServer fake, Either<Command, CodeAction> offered)
            throws Exception {
        fake.codeActionResponse = List.of(offered);
        List<LspManager.CodeActionItem> items = await(cb -> manager.codeActions(file, 0, 0, 0, 0, cb));
        return items.get(0);
    }

    @Test
    void anActionWithAnInlineEditAppliesItThroughTheHandler() throws Exception {
        var fake = open();
        var seen = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            seen.set(mapped);
            done.accept(true);
        });
        var action = new CodeAction("insert header");
        action.setEdit(editOfThisFile("// header\n"));
        LspManager.CodeActionItem item = listed(fake, Either.forRight(action));

        Boolean ok = await(cb -> manager.applyCodeAction(file, item, cb));

        assertTrue(ok);
        assertEquals(
                List.of(new LspTextEdit(0, 0, 0, 0, "// header\n")),
                seen.get().edits().get(0).edits());
        assertTrue(fake.resolvedCodeActions.isEmpty(), "an action that already has its edit is not resolved");
        assertTrue(fake.executedCommands.isEmpty());
    }

    @Test
    void anEditLessActionIsResolvedFirst() throws Exception {
        var fake = open();
        var seen = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            seen.set(mapped);
            done.accept(true);
        });
        var lazy = new CodeAction("lazy fix");
        fake.codeActionResolver = unresolved -> {
            var resolved = new CodeAction(unresolved.getTitle());
            resolved.setEdit(editOfThisFile("// resolved\n"));
            return resolved;
        };
        LspManager.CodeActionItem item = listed(fake, Either.forRight(lazy));

        Boolean ok = await(cb -> manager.applyCodeAction(file, item, cb));

        assertTrue(ok);
        assertEquals(List.of(lazy), fake.resolvedCodeActions);
        assertEquals("// resolved\n", seen.get().edits().get(0).edits().get(0).newText());
    }

    /** Resolve fails, or resolves to nothing at all: the action did nothing, and that is reported as a
     *  failure rather than as a silent success. */
    @Test
    void anActionThatResolvesToNothingReportsFailure() throws Exception {
        var fake = open();
        manager.setApplyEditHandler((mapped, done) -> done.accept(true));
        LspManager.CodeActionItem item = listed(fake, Either.forRight(new CodeAction("empty")));

        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(file, item, cb)));

        fake.codeActionResolver = unresolved -> null;
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(file, item, cb)));

        fake.failEverything = true;
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(file, item, cb)));
    }

    @Test
    void aBareCommandIsExecutedOnTheServer() throws Exception {
        var fake = open();
        var command = new Command("Organize imports", "java.edit.organizeImports", List.of("u"));
        LspManager.CodeActionItem item = listed(fake, Either.forLeft(command));

        Boolean ok = await(cb -> manager.applyCodeAction(file, item, cb));

        assertTrue(ok);
        var sent = FakeLanguageServer.last(fake.executedCommands);
        assertEquals("java.edit.organizeImports", sent.getCommand());
        assertEquals(List.of("u"), sent.getArguments());
    }

    @Test
    void aFailingCommandReportsFailure() throws Exception {
        var fake = open();
        LspManager.CodeActionItem item = listed(fake, Either.forLeft(new Command("Boom", "java.boom")));
        fake.failEverything = true;

        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(file, item, cb)));
    }

    @Test
    void anActionWithAnEditAndACommandRunsTheCommandAfterTheEdit() throws Exception {
        var fake = open();
        List<String> order = new CopyOnWriteArrayList<>();
        manager.setApplyEditHandler((mapped, done) -> {
            order.add("edit");
            done.accept(true);
        });
        fake.executeCommandHandler = params -> {
            order.add("command");
            return null;
        };
        var action = new CodeAction("edit then command");
        action.setEdit(editOfThisFile("// first\n"));
        action.setCommand(new Command("follow up", "java.followUp"));
        LspManager.CodeActionItem item = listed(fake, Either.forRight(action));

        Boolean ok = await(cb -> manager.applyCodeAction(file, item, cb));

        assertTrue(ok);
        assertEquals(List.of("edit", "command"), order);
    }

    @Test
    void applyingNothingOrSomethingForeignReportsFailure() throws Exception {
        open();
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(file, (LspManager.CodeActionItem) null, cb)));
        var foreign = new LspManager.CodeActionItem("odd", "", false, "not an lsp4j payload");
        assertEquals(Map.of(), foreign.expectedDocuments());
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(file, foreign, cb)));
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(file, (Object) "not an lsp4j payload", cb)));

        Path other = unopened();
        var lazy = new LspManager.CodeActionItem("lazy", "", false, new CodeAction("lazy"));
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(other, lazy, cb)), "no server to resolve it");
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(other, (Object) new CodeAction("lazy"), cb)));
        var command = new LspManager.CodeActionItem("cmd", "", false, new Command("cmd", "x"));
        assertFalse(this.<Boolean>await(cb -> manager.applyCodeAction(other, command, cb)), "no server to run it");
    }

    /** The untyped entry point takes the same three routes as the listed-item one. */
    @Test
    void theRawPayloadEntryPointResolvesAndExecutesToo() throws Exception {
        var fake = open();
        manager.setApplyEditHandler((mapped, done) -> done.accept(true));
        fake.codeActionResolver = unresolved -> {
            var resolved = new CodeAction(unresolved.getTitle());
            resolved.setEdit(editOfThisFile("// resolved\n"));
            return resolved;
        };

        assertTrue(this.<Boolean>await(cb -> manager.applyCodeAction(file, (Object) new CodeAction("lazy"), cb)));
        assertEquals(1, fake.resolvedCodeActions.size());
        assertTrue(this.<Boolean>await(cb -> manager.applyCodeAction(file, (Object) new Command("c", "java.c"), cb)));
        assertEquals("java.c", FakeLanguageServer.last(fake.executedCommands).getCommand());
    }

    // --- completion follow-ups -------------------------------------------------------------------------

    @Test
    void resolvedCompletionYieldsItsAdditionalEdits() throws Exception {
        var options = new CompletionOptions();
        options.setResolveProvider(true);
        capabilities.setCompletionProvider(options);
        var fake = open();
        var item = new CompletionItem("List");
        fake.completionResolver = unresolved -> {
            var resolved = new CompletionItem(unresolved.getLabel());
            var edits = new ArrayList<TextEdit>();
            edits.add(new TextEdit(range(0, 0, 0), "import java.util.List;\n"));
            edits.add(null);
            edits.add(wire("{\"newText\":\"rangeless\"}", TextEdit.class));
            edits.add(wire("{\"range\":" + rangeJson(1, 0, 0) + "}", TextEdit.class));
            resolved.setAdditionalTextEdits(edits);
            return resolved;
        };

        List<LspTextEdit> edits = await(cb -> manager.resolveCompletion(file, item, cb));

        assertEquals(
                List.of(new LspTextEdit(0, 0, 0, 0, "import java.util.List;\n"), new LspTextEdit(1, 0, 1, 0, "")),
                edits);
        assertEquals(List.of(item), fake.resolvedCompletions);
    }

    @Test
    void anUnresolvableCompletionYieldsNoEdits() throws Exception {
        var options = new CompletionOptions();
        options.setResolveProvider(true);
        capabilities.setCompletionProvider(options);
        var fake = open();
        var item = new CompletionItem("List");

        assertTrue(this.<List<LspTextEdit>>await(cb -> manager.resolveCompletion(file, item, cb))
                .isEmpty());
        assertTrue(this.<List<LspTextEdit>>await(cb -> manager.resolveCompletion(file, null, cb))
                .isEmpty());
        Path other = unopened();
        assertTrue(this.<List<LspTextEdit>>await(cb -> manager.resolveCompletion(other, item, cb))
                .isEmpty());

        // A failed resolve falls back to the item as listed — and so to the edits it already carried.
        item.setAdditionalTextEdits(List.of(new TextEdit(range(0, 0, 0), "import x;\n")));
        fake.failEverything = true;
        assertEquals(
                List.of(new LspTextEdit(0, 0, 0, 0, "import x;\n")),
                this.<List<LspTextEdit>>await(cb -> manager.resolveCompletion(file, item, cb)));
    }

    @Test
    void completionDocumentationIsTakenEagerlyOrResolved() throws Exception {
        var options = new CompletionOptions();
        options.setResolveProvider(true);
        capabilities.setCompletionProvider(options);
        var fake = open();

        var eager = new CompletionItem("eager");
        eager.setDocumentation("Already here.");
        assertEquals("Already here.", this.<String>await(cb -> manager.resolveCompletionDoc(file, eager, cb)));
        assertTrue(fake.resolvedCompletions.isEmpty(), "documentation the item already carries is not re-fetched");

        var lazy = new CompletionItem("lazy");
        fake.completionResolver = unresolved -> {
            var resolved = new CompletionItem(unresolved.getLabel());
            resolved.setDocumentation(new MarkupContent("markdown", "**Resolved** doc"));
            return resolved;
        };
        assertEquals("**Resolved** doc", this.<String>await(cb -> manager.resolveCompletionDoc(file, lazy, cb)));

        fake.completionResolver = unresolved -> {
            var resolved = new CompletionItem(unresolved.getLabel());
            resolved.setDocumentation("   ");
            return resolved;
        };
        assertNull(this.<String>await(cb -> manager.resolveCompletionDoc(file, lazy, cb)), "blank is no documentation");

        fake.completionResolver = unresolved -> {
            var resolved = new CompletionItem(unresolved.getLabel());
            resolved.setDocumentation((MarkupContent) null);
            return resolved;
        };
        assertNull(this.<String>await(cb -> manager.resolveCompletionDoc(file, lazy, cb)));

        fake.failEverything = true;
        assertNull(this.<String>await(cb -> manager.resolveCompletionDoc(file, lazy, cb)));
        assertNull(this.<String>await(cb -> manager.resolveCompletionDoc(file, "not a completion item", cb)));
        Path other = unopened();
        assertNull(this.<String>await(cb -> manager.resolveCompletionDoc(other, lazy, cb)));
    }

    @Test
    void theServersCommitCharactersApplyToItemsWithoutTheirOwn() throws Exception {
        var options = new CompletionOptions();
        options.setAllCommitCharacters(List.of(".", ";"));
        capabilities.setCompletionProvider(options);
        var fake = open();
        var plain = new CompletionItem("plain");
        var own = new CompletionItem("own");
        own.setCommitCharacters(List.of("("));
        fake.completionFuture =
                java.util.concurrent.CompletableFuture.completedFuture(Either.forLeft(List.of(plain, own)));

        com.editora.completion.CompletionResult result =
                await(cb -> manager.completion(file, 0, 3, 1, null, it -> null, cb));

        assertEquals(2, result.items().size());
        assertFalse(result.incomplete(), "a plain list is complete");
        assertEquals(List.of(".", ";"), plain.getCommitCharacters());
        assertEquals(List.of("("), own.getCommitCharacters(), "an item's own commit characters win");
    }

    @Test
    void completionWithoutAServerAnswersEmptyAtOnce() throws Exception {
        var seen = new AtomicReference<com.editora.completion.CompletionResult>();
        Runnable cancel = manager.completion(unopened(), 0, 0, 1, null, null, seen::set);
        assertSame(com.editora.completion.CompletionResult.EMPTY, seen.get());
        cancel.run(); // nothing to cancel, and nothing to throw
    }

    @Test
    void aFailedCompletionDeliversAnEmptyResult() throws Exception {
        var fake = open();
        fake.completionFuture =
                java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("transport"));

        com.editora.completion.CompletionResult result =
                await(cb -> manager.completion(file, 0, 3, 1, null, it -> null, cb));

        assertTrue(result.items().isEmpty());
        assertFalse(result.incomplete());
    }

    // --- hover, highlights, definitions, symbols -------------------------------------------------------

    @Test
    void hoverDeliversTheTextAndDegradesToBlank() throws Exception {
        var fake = open();
        fake.hoverResponse = new Hover(new MarkupContent("plaintext", "int count"));
        assertEquals("int count", this.<String>await(cb -> manager.hover(file, 0, 2, cb)));
        assertEquals(new Position(0, 2), FakeLanguageServer.last(fake.hovers).getPosition());

        fake.failEverything = true;
        assertEquals("", this.<String>await(cb -> manager.hover(file, 0, 2, cb)));

        var direct = new AtomicReference<String>();
        manager.hover(unopened(), 0, 0, direct::set);
        assertEquals("", direct.get(), "no server answers at once, with nothing");
    }

    @Test
    void documentHighlightsFlagWrites() throws Exception {
        capabilities.setDocumentHighlightProvider(true);
        var fake = open();
        var highlights = new ArrayList<DocumentHighlight>();
        highlights.add(new DocumentHighlight(range(0, 6, 7), DocumentHighlightKind.Write));
        highlights.add(new DocumentHighlight(range(2, 1, 2), DocumentHighlightKind.Read));
        highlights.add(null);
        highlights.add(new DocumentHighlight());
        fake.highlightResponse = highlights;

        assertTrue(manager.supportsDocumentHighlight(file));
        List<OccurrenceSpan> spans = await(cb -> manager.documentHighlights(file, 0, 6, cb));

        assertEquals(List.of(new OccurrenceSpan(0, 6, 0, 7, true), new OccurrenceSpan(2, 1, 2, 2, false)), spans);

        fake.failEverything = true;
        assertTrue(this.<List<OccurrenceSpan>>await(cb -> manager.documentHighlights(file, 0, 6, cb))
                .isEmpty());
        Path other = unopened();
        assertTrue(this.<List<OccurrenceSpan>>await(cb -> manager.documentHighlights(other, 0, 6, cb))
                .isEmpty());
        assertFalse(manager.supportsDocumentHighlight(other));
    }

    @Test
    void definitionReadsTheLocationLinkShape() throws Exception {
        var fake = open();
        Path target = root.resolve("B.java");
        var selected = new LocationLink(target.toUri().toString(), range(4, 0, 30), range(4, 11, 12));
        String targetUri = "\"" + target.toUri() + "\"";
        var whole = wire(
                "{\"targetUri\":" + targetUri + ",\"targetRange\":" + rangeJson(8, 2, 30) + "}", LocationLink.class);
        var nowhere = wire("{\"targetUri\":" + targetUri + "}", LocationLink.class);
        fake.definitionLinksResponse = List.of(selected, whole, nowhere);

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 0, 6, cb));

        assertEquals(2, targets.size(), "a link with no range at all cannot be opened anywhere");
        assertEquals(target, targets.get(0).file());
        assertEquals(4, targets.get(0).line());
        assertEquals(11, targets.get(0).character(), "the selection range is where the name is");
        assertEquals(8, targets.get(1).line());
        assertEquals(2, targets.get(1).character());
    }

    /** From inside a library source the request is addressed with the library document's own URI. */
    @Test
    void definitionAtSendsTheGivenDocumentUri() throws Exception {
        var fake = open();
        String jdt = "jdt://contents/java.base/java.lang/String.class?=demo";
        Path target = root.resolve("B.java");
        fake.definitionResponse = List.of(new Location(target.toUri().toString(), range(1, 2, 3)));

        List<LspManager.Target> targets = await(cb -> manager.definitionAt(file, jdt, 10, 4, cb));

        assertEquals(target, targets.get(0).file());
        assertEquals(
                jdt, FakeLanguageServer.last(fake.definitions).getTextDocument().getUri());
        assertEquals(
                new Position(10, 4), FakeLanguageServer.last(fake.definitions).getPosition());
    }

    @Test
    void workspaceSymbolsReadTheOlderShapeAndUriOnlyLocations() throws Exception {
        capabilities.setWorkspaceSymbolProvider(true);
        var fake = open();
        Path target = root.resolve("B.java");
        var info = new SymbolInformation(
                "B", SymbolKind.Class, new Location(target.toUri().toString(), range(3, 6, 7)), "demo");
        fake.symbolInformationResponse = List.of(info);

        assertTrue(manager.supportsWorkspaceSymbols(file));
        List<LspManager.WorkspaceSymbolMatch> older = await(cb -> manager.workspaceSymbols(file, "B", cb));
        assertEquals(List.of(new LspManager.WorkspaceSymbolMatch("B", "demo", "class", target, 3, 6)), older);
        assertEquals("B", FakeLanguageServer.last(fake.workspaceSymbols).getQuery());

        fake.symbolInformationResponse = null;
        var uriOnly = new WorkspaceSymbol(
                "C",
                SymbolKind.Method,
                Either.forRight(new WorkspaceSymbolLocation(target.toUri().toString())));
        var nowhere = wire("{\"name\":\"D\",\"kind\":6}", WorkspaceSymbol.class);
        var foreign = new WorkspaceSymbol(
                "E", SymbolKind.Method, Either.forRight(new WorkspaceSymbolLocation("jdt://contents/x")));
        fake.workspaceSymbolResponse = List.of(uriOnly, nowhere, foreign);
        List<LspManager.WorkspaceSymbolMatch> modern = await(cb -> manager.workspaceSymbols(file, "", cb));
        assertEquals(
                List.of(new LspManager.WorkspaceSymbolMatch("C", null, "method", target, 0, 0)),
                modern,
                "a symbol with no file cannot be jumped to; one with only a file opens at its top");

        Path other = unopened();
        assertTrue(this.<List<LspManager.WorkspaceSymbolMatch>>await(cb -> manager.workspaceSymbols(other, "", cb))
                .isEmpty());
        assertFalse(manager.supportsWorkspaceSymbols(other));
    }

    // --- semantic token deltas (#679) ------------------------------------------------------------------

    private static ServerCapabilities deltaCaps() {
        var caps = new ServerCapabilities();
        var prov = new SemanticTokensWithRegistrationOptions();
        prov.setLegend(new SemanticTokensLegend(List.of("variable", "method"), List.of()));
        prov.setFull(Either.forRight(new SemanticTokensServerFull(true)));
        prov.setRange(Either.forLeft(false));
        caps.setSemanticTokensProvider(prov);
        return caps;
    }

    private List<SemanticToken> tokens() throws Exception {
        return await(cb -> manager.requestSemanticTokens(file, 0, 1, 2, 0, cb));
    }

    @Test
    void aSecondRequestAsksForADeltaAndSplicesIt() throws Exception {
        capabilities = deltaCaps();
        var fake = open();
        fake.semanticTokensResponse = new SemanticTokens("r1", List.of(0, 0, 5, 0, 0));

        List<SemanticToken> first = tokens();
        assertEquals(1, first.size());
        assertEquals(1, fake.semanticFulls.size());
        assertTrue(manager.wholeDocumentTokensCurrent(file), "nothing has changed since the server answered");

        // One more token appended after the five numbers the client already holds.
        fake.semanticDeltaResponse = Either.forRight(
                new SemanticTokensDelta(List.of(new SemanticTokensEdit(5, 0, List.of(0, 6, 1, 1, 0))), "r2"));
        List<SemanticToken> second = tokens();

        assertEquals(1, fake.semanticFulls.size(), "the second request must not transfer the whole set again");
        assertEquals("r1", FakeLanguageServer.last(fake.semanticDeltas).getPreviousResultId());
        assertEquals(2, second.size());
        assertEquals(new SemanticToken(0, 0, 5, second.get(0).cssClasses()), second.get(0));
        assertEquals(0, second.get(1).line());
        assertEquals(6, second.get(1).startChar());
        assertEquals(1, second.get(1).length());

        // The next delta is taken against r2, not r1.
        fake.semanticDeltaResponse = Either.forRight(new SemanticTokensDelta(List.of(), "r3"));
        assertEquals(2, tokens().size());
        assertEquals("r2", FakeLanguageServer.last(fake.semanticDeltas).getPreviousResultId());
    }

    @Test
    void aDeltaAnsweredWithAFullSetReplacesTheCache() throws Exception {
        capabilities = deltaCaps();
        var fake = open();
        fake.semanticTokensResponse = new SemanticTokens("r1", List.of(0, 0, 5, 0, 0));
        tokens();

        fake.semanticDeltaResponse = Either.forLeft(new SemanticTokens("r2", List.of(0, 2, 3, 1, 0, 1, 0, 4, 0, 0)));
        List<SemanticToken> second = tokens();

        assertEquals(2, second.size());
        assertEquals(2, second.get(0).startChar());
        assertEquals(1, second.get(1).line());
        assertEquals(1, fake.semanticFulls.size());
    }

    /** A delta the server cannot give (it failed, or it forgot the result id) is not an empty file: the
     *  whole set is asked for again. */
    @Test
    void aFailedDeltaFallsBackToAFullRequest() throws Exception {
        capabilities = deltaCaps();
        var fake = open();
        fake.semanticTokensResponse = new SemanticTokens("r1", List.of(0, 0, 5, 0, 0));
        tokens();

        fake.semanticDeltaResponse = Either.forRight(null);
        List<SemanticToken> afterEmptyDelta = tokens();
        assertEquals(1, afterEmptyDelta.size());
        assertEquals(2, fake.semanticFulls.size(), "the fallback is a plain full request");
    }

    @Test
    void aFullAnswerWithoutAResultIdIsNeverDeltaed() throws Exception {
        capabilities = deltaCaps();
        var fake = open();
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 5, 0, 0));

        tokens();
        tokens();

        assertEquals(2, fake.semanticFulls.size());
        assertTrue(fake.semanticDeltas.isEmpty(), "there is no result id to take a delta from");
    }

    @Test
    void aFailedFullRequestDeliversNoTokensAndForgetsTheDocument() throws Exception {
        capabilities = deltaCaps();
        var fake = open();
        fake.semanticTokensResponse = new SemanticTokens("r1", List.of(0, 0, 5, 0, 0));
        tokens();
        assertTrue(manager.wholeDocumentTokensCurrent(file));

        manager.changeDocument(file, "class A { int x; }\n");
        assertFalse(manager.wholeDocumentTokensCurrent(file), "the document moved on since the answer");

        fake.semanticTokensResponse = null;
        fake.semanticDeltaResponse = null;
        List<SemanticToken> none = tokens();
        assertTrue(none.isEmpty());
        assertFalse(manager.wholeDocumentTokensCurrent(file));
        assertFalse(manager.wholeDocumentTokensCurrent(unopened()));
    }

    // --- the jdtls vendor requests (#741, #746) --------------------------------------------------------

    private static final String TO_STRING_STATUS = """
            {"type":"Person",
             "fields":[
               {"bindingKey":"Ldemo/Person;.name)Ljava/lang/String;","name":"name","type":"String",
                "isField":true,"isSelected":true}],
             "exists":false}
            """;

    private JsonObject editJson(String text) {
        JsonObject edit = new JsonObject();
        JsonObject changes = new JsonObject();
        changes.add(
                file.toUri().toString(),
                JsonParser.parseString(
                        "[{\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":0}},"
                                + "\"newText\":\"" + text + "\"}]"));
        edit.add("changes", changes);
        return edit;
    }

    @Test
    void generateCandidatesComeFromTheCheckRequest() throws Exception {
        var fake = open();
        fake.rawResponse = JsonParser.parseString(TO_STRING_STATUS);
        Object params = Map.of("textDocument", Map.of("uri", file.toUri().toString()));

        JdtlsGenerate.Plan plan =
                await(cb -> manager.jdtlsGenerateCandidates(file, JdtlsGenerate.Kind.TO_STRING, params, cb));

        assertEquals(
                List.of("name : String"),
                plan.candidates().stream().map(JdtlsGenerate.Candidate::label).toList());
        assertNotNull(plan.status());
        assertEquals("java/checkToStringStatus", fake.rawRequests.get(0).method());
        assertSame(params, fake.rawRequests.get(0).params());
    }

    @Test
    void generateCandidatesAreEmptyWhenTheServerFailsOrIsAbsent() throws Exception {
        var fake = open();
        fake.failEverything = true;
        JdtlsGenerate.Plan failed =
                await(cb -> manager.jdtlsGenerateCandidates(file, JdtlsGenerate.Kind.TO_STRING, Map.of(), cb));
        assertTrue(failed.candidates().isEmpty());
        assertNull(failed.status());

        JdtlsGenerate.Plan noKind = await(cb -> manager.jdtlsGenerateCandidates(file, null, Map.of(), cb));
        assertTrue(noKind.candidates().isEmpty());
        Path other = unopened();
        JdtlsGenerate.Plan noServer =
                await(cb -> manager.jdtlsGenerateCandidates(other, JdtlsGenerate.Kind.TO_STRING, Map.of(), cb));
        assertTrue(noServer.candidates().isEmpty());
    }

    @Test
    void generateApplySendsTheChosenMembersAndAppliesTheAnsweredEdit() throws Exception {
        var fake = open();
        var seen = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            seen.set(mapped);
            done.accept(true);
        });
        JsonElement status = JsonParser.parseString(TO_STRING_STATUS);
        List<JdtlsGenerate.Candidate> chosen = JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, status);
        fake.rawResponse = editJson("// generated\\n");
        Object params = Map.of("textDocument", Map.of("uri", file.toUri().toString()));

        Boolean ok = await(cb ->
                manager.jdtlsGenerateApply(file, JdtlsGenerate.Kind.TO_STRING, params, status, Map.of(), chosen, cb));

        assertTrue(ok);
        assertEquals("java/generateToString", fake.rawRequests.get(0).method());
        assertEquals("// generated\n", seen.get().edits().get(0).edits().get(0).newText());
    }

    @Test
    void generateApplyReportsFailureWhenNothingComesBack() throws Exception {
        var fake = open();
        var applied = new AtomicReference<>(false);
        manager.setApplyEditHandler((mapped, done) -> {
            applied.set(true);
            done.accept(true);
        });
        JsonElement status = JsonParser.parseString(TO_STRING_STATUS);
        Object params = Map.of();

        fake.rawResponse = null;
        assertFalse(this.<Boolean>await(cb -> manager.jdtlsGenerateApply(
                file, JdtlsGenerate.Kind.TO_STRING, params, status, Map.of(file, "class A {}\n"), List.of(), cb)));
        fake.rawResponse = "not an edit";
        assertFalse(this.<Boolean>await(cb ->
                manager.jdtlsGenerateApply(file, JdtlsGenerate.Kind.TO_STRING, params, status, null, List.of(), cb)));
        fake.failEverything = true;
        assertFalse(this.<Boolean>await(cb ->
                manager.jdtlsGenerateApply(file, JdtlsGenerate.Kind.TO_STRING, params, status, null, List.of(), cb)));
        assertFalse(
                this.<Boolean>await(cb -> manager.jdtlsGenerateApply(file, null, params, status, null, List.of(), cb)));
        Path other = unopened();
        assertFalse(this.<Boolean>await(cb ->
                manager.jdtlsGenerateApply(other, JdtlsGenerate.Kind.TO_STRING, params, status, null, List.of(), cb)));
        assertFalse(applied.get(), "nothing was generated, so nothing may be applied");
    }

    @Test
    void aVendorRequestAnswersAsJsonOrNull() throws Exception {
        var fake = open();
        fake.rawResponse = Map.of("destinations", List.of());
        JsonElement answer = await(cb -> manager.jdtlsRequest(file, "java/getMoveDestinations", Map.of(), cb));
        assertTrue(answer.getAsJsonObject().has("destinations"));

        fake.failEverything = true;
        assertNull(this.<JsonElement>await(cb -> manager.jdtlsRequest(file, "java/getMoveDestinations", null, cb)));
        Path other = unopened();
        assertNull(this.<JsonElement>await(cb -> manager.jdtlsRequest(other, "java/getMoveDestinations", null, cb)));
    }

    private record EditOutcome(boolean ok, String refused) {}

    private EditOutcome applyEdit(Path target, Map<Path, String> expected) throws Exception {
        return await(cb -> manager.jdtlsApplyEdit(
                target, "java/move", Map.of(), expected, (ok, refused) -> cb.accept(new EditOutcome(ok, refused))));
    }

    @Test
    void aVendorEditIsAppliedAndTheServersRefusalIsKept() throws Exception {
        var fake = open();
        manager.setApplyEditHandler((mapped, done) -> done.accept(true));

        JsonObject wrapped = new JsonObject();
        wrapped.add("edit", editJson("// moved\\n"));
        fake.rawResponse = wrapped;
        assertEquals(new EditOutcome(true, null), applyEdit(file, Map.of()));
        assertEquals(new EditOutcome(true, null), applyEdit(file, Map.of(file, "class A {}\n")));

        JsonObject refusal = new JsonObject();
        refusal.addProperty("errorMessage", "Cannot move a local type");
        fake.rawResponse = refusal;
        assertEquals(
                new EditOutcome(false, "Cannot move a local type"),
                applyEdit(file, null),
                "the server's own reason is what the user is shown");

        fake.failEverything = true;
        assertEquals(new EditOutcome(false, null), applyEdit(file, null));
        assertEquals(new EditOutcome(false, null), applyEdit(unopened(), null));
    }

    @Test
    void smartSemicolonAsksOnlyAServerThatAdvertisesIt() throws Exception {
        var fake = open();
        assertNull(this.<int[]>await(cb -> manager.smartSemicolonPosition(file, 1, 2, cb)));
        assertTrue(fake.executedCommands.isEmpty(), "a server that does not advertise the command is not asked");

        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(JdtlsSmartSemicolon.COMMAND)));
        fake.executeCommandResponse = JsonParser.parseString("{\"position\":{\"line\":4.0,\"character\":29.0}}");
        int[] at = await(cb -> manager.smartSemicolonPosition(file, 4, 10, cb));
        assertEquals(List.of(4, 29), List.of(at[0], at[1]));
        var sent = FakeLanguageServer.last(fake.executedCommands);
        assertEquals(JdtlsSmartSemicolon.COMMAND, sent.getCommand());
        assertEquals(List.of(JdtlsSmartSemicolon.paramsJson(file.toUri().toString(), 4, 10)), sent.getArguments());

        fake.failEverything = true;
        assertNull(this.<int[]>await(cb -> manager.smartSemicolonPosition(file, 4, 10, cb)));
    }

    @Test
    void refreshingProjectDiagnosticsReportsWhetherTheBuildRan() throws Exception {
        var fake = open();
        assertTrue(this.<Boolean>await(cb -> manager.refreshProjectDiagnostics(file, cb)));
        assertEquals("java/buildWorkspace", fake.rawRequests.get(0).method());
        assertEquals(Boolean.TRUE, fake.rawRequests.get(0).params(), "a full build, not an incremental one");

        fake.failEverything = true;
        assertFalse(this.<Boolean>await(cb -> manager.refreshProjectDiagnostics(file, cb)));
        Path other = unopened();
        assertFalse(this.<Boolean>await(cb -> manager.refreshProjectDiagnostics(other, cb)));
        assertFalse(this.<Boolean>await(cb -> manager.refreshProjectDiagnostics(null, cb)));
    }

    @Test
    void classFileContentsDeliverTheSourceOrNull() throws Exception {
        var fake = open();
        String jdt = "jdt://contents/java.base/java.lang/String.class?=demo";
        fake.rawResponse = "package java.lang;\npublic final class String {}\n";
        assertEquals(
                "package java.lang;\npublic final class String {}\n",
                this.<String>await(cb -> manager.classFileContents(file, jdt, cb)));

        fake.rawResponse = "  ";
        assertNull(this.<String>await(cb -> manager.classFileContents(file, jdt, cb)), "blank source is no source");
        fake.failEverything = true;
        assertNull(this.<String>await(cb -> manager.classFileContents(file, jdt, cb)));
        assertNull(this.<String>await(cb -> manager.classFileContents(file, null, cb)));
        assertEquals("String.class", LspManager.classFileTitle(jdt));
    }

    @Test
    void javaDebugIsAvailableOnceARunningServerAdvertisesIt() throws Exception {
        assertFalse(manager.javaDebugCommandsAvailable(), "no server is running");
        open();
        assertFalse(manager.javaDebugCommandsAvailable(), "this one does not have the plugin");
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(List.of("vscode.java.startDebugSession")));
        assertTrue(manager.javaDebugCommandsAvailable());
    }

    @Test
    void executeCommandWithoutAServerFailsTheCallback() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        manager.executeCommand(unopened(), "java.x", List.of(), (result, error) -> {
            failure.set(error);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNotNull(failure.get(), "there is no server to run it");
    }

    @Test
    void documentVersionFollowsTheOpenDocument() throws Exception {
        assertNull(manager.documentVersion(null));
        assertNull(manager.documentVersion(unopened()));
        open();
        Integer opened = manager.documentVersion(file);
        assertNotNull(opened);
        manager.changeDocument(file, "class A { }\n");
        assertEquals(opened + 1, manager.documentVersion(file));
        Platform.runLater(() -> {});
    }
}
