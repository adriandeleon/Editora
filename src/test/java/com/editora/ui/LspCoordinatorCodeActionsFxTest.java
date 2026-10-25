package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.CheckBox;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.JdtlsRefactor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code actions and rename from the command to the changed text, against a scripted server — including the
 * jdtls actions the client has to drive itself (#741): the source generators, which ask which members, and
 * the refactorings, which ask where to or what.
 *
 * <p>Each of those is a short conversation — a check request, one or two pickers, a prompt, the edit — and
 * every step of it can end the conversation. What is asserted is what was sent at each step, what the file
 * holds afterwards, and what the user is told when a step ends it.
 */
@Tag("fx")
class LspCoordinatorCodeActionsFxTest {

    private static final String SOURCE = "class A {\n    int count;\n}\n";

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;
    private LspCoordinatorFixture.PickingBuffer buffer;
    /** What each custom {@code java/…} request answers with, by method. */
    private final Map<String, Object> answers = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root);
        buffer = fx.openPicking("A.java", SOURCE);
        fx.server().rawHandler = raw -> answers.get(raw.method());
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    /** A workspace edit, as untyped JSON, inserting {@code text} at the top of the open file. */
    private JsonObject insertAtTop(String text) {
        JsonObject change = new JsonObject();
        change.add("range", json("{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":0}}"));
        change.addProperty("newText", text);
        com.google.gson.JsonArray edits = new com.google.gson.JsonArray();
        edits.add(change);
        JsonObject changes = new JsonObject();
        changes.add(buffer.getPath().toUri().toString(), edits);
        JsonObject edit = new JsonObject();
        edit.add("changes", changes);
        return edit;
    }

    private String content() throws Exception {
        return FxTestSupport.callOnFx(buffer::getContent);
    }

    private List<String> rawMethods() {
        return fx.server().rawRequests.stream()
                .map(FakeLanguageServer.Raw::method)
                .toList();
    }

    private JsonObject lastRawParams() {
        Object params = FakeLanguageServer.last(fx.server().rawRequests).params();
        return com.editora.lsp.LspManager.asJson(params).getAsJsonObject();
    }

    private void offer(Object... actions) {
        List<Either<Command, CodeAction>> offered = new ArrayList<>();
        for (Object action : actions) {
            offered.add(action instanceof Command c ? Either.forLeft(c) : Either.forRight((CodeAction) action));
        }
        fx.server().codeActionResponse = offered;
    }

    /** Lists the code actions at the caret and picks the one titled {@code title}. */
    private void runAction(String title) throws Exception {
        fx.run(() -> fx.coordinator.codeActions());
        fx.run(() -> buffer.pick(title));
    }

    private List<String> tickedLabels() throws Exception {
        return FxTestSupport.callOnFx(() -> fx.checkboxes().stream()
                .map(box -> (box.isSelected() ? "[x] " : "[ ] ") + box.getText())
                .toList());
    }

    // --- listing and applying ------------------------------------------------------------------------

    @Test
    void codeActionsAreRefusedWithoutAProviderOrOnAReadOnlyFile() throws Exception {
        fx.capabilities.setCodeActionProvider(false);
        fx.run(() -> fx.coordinator.codeActions());
        assertEquals(tr("status.lsp.noCodeActions"), fx.host.lastStatus());

        fx.capabilities.setCodeActionProvider(true);
        fx.ops.editable = false;
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.codeActions());
        assertEquals(List.of(tr("status.lsp.noCodeActions")), fx.host.statuses);
        assertTrue(fx.server().codeActions.isEmpty(), "the server is not asked for fixes that cannot be applied");
    }

    @Test
    void theServersActionsAreOfferedAtTheSelectionAndThePickIsApplied() throws Exception {
        fx.run(() -> fx.coordinator.codeActions());
        assertEquals(tr("status.lsp.noCodeActions"), fx.host.lastStatus(), "the server offered none");

        var plain = new CodeAction("Add header");
        plain.setKind("quickfix");
        plain.setEdit(new WorkspaceEdit(
                Map.of(buffer.getPath().toUri().toString(), List.of(new TextEdit(range(0, 0, 0), "// header\n")))));
        var preferred = new CodeAction("Remove field");
        preferred.setIsPreferred(true);
        offer(plain, preferred);
        FxTestSupport.runOnFx(() -> buffer.getFocusedArea().selectRange(1, 4, 1, 13)); // "int count"
        fx.run(() -> fx.coordinator.codeActions());

        assertEquals(
                List.of("Remove field", "Add header"),
                buffer.offered.stream()
                        .map(com.editora.editor.lsp.CodeAction::title)
                        .toList());
        assertTrue(buffer.offered.get(0).preferred());
        assertEquals(tr("lsp.codeAction.group.fix"), buffer.offered.get(1).kind(), "a word, not the LSP kind (A17)");
        assertEquals(
                range(1, 4, 13),
                FakeLanguageServer.last(fx.server().codeActions).getRange());

        fx.run(() -> buffer.dismiss());
        assertEquals(SOURCE, content(), "closing the popup applies nothing");

        fx.run(() -> buffer.pick("Add header"));
        assertEquals("// header\n" + SOURCE, content());
        assertEquals(tr("status.lsp.codeActionApplied", "Add header"), fx.host.lastStatus());
    }

    @Test
    void withoutASelectionTheRequestIsTheCaretPosition() throws Exception {
        offer(new CodeAction("Anything"));
        fx.caret(buffer, 1, 8);
        fx.run(() -> fx.coordinator.codeActions());
        assertEquals(
                range(1, 8, 8), FakeLanguageServer.last(fx.server().codeActions).getRange());
    }

    @Test
    void aRefusedActionSaysWhyTheServerRefused() throws Exception {
        offer(new CodeAction("Inline Method"));
        fx.server().codeActionResolver = a -> {
            throw new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            -32603, "Cannot inline abstract methods", null));
        };
        runAction("Inline Method");
        assertEquals(
                tr("status.lsp.codeActionFailedWhy", "Inline Method", "Cannot inline abstract methods"),
                fx.host.lastStatus(),
                "not just \"Could not apply\" (re-review BN9)");
    }

    @Test
    void anActionThatDoesNothingIsReportedAsFailed() throws Exception {
        offer(new CodeAction("Hollow"));
        runAction("Hollow");
        assertEquals(tr("status.lsp.codeActionNoChange", "Hollow"), fx.host.lastStatus()); // said as such (ET1)
        assertEquals(SOURCE, content());
    }

    @Test
    void actionsArrivingAfterATabSwitchAreNotOffered() throws Exception {
        offer(new CodeAction("Late"));
        EditorBuffer other = FxTestSupport.callOnFx(EditorBuffer::new);
        FxTestSupport.runOnFx(() -> {
            fx.show(other);
            fx.coordinator.codeActions();
            fx.host.active = other;
        });
        fx.settle();
        assertTrue(buffer.offered.isEmpty(), "a popup must not open over a tab the user has left");
    }

    /** The popup hands back a neutral action; one that is not among those listed is ignored. */
    @Test
    void aPickThatWasNotOfferedIsIgnored() throws Exception {
        offer(new CodeAction("Real"));
        fx.run(() -> fx.coordinator.codeActions());
        int statuses = fx.host.statuses.size();
        fx.run(() -> buffer.pickUnlisted(new com.editora.editor.lsp.CodeAction("Forged", "", false, new Object())));
        assertEquals(statuses, fx.host.statuses.size());
        assertEquals(SOURCE, content());
    }

    // --- the jdtls source generators (#741) ----------------------------------------------------------

    private static final String TO_STRING_STATUS = "{\"type\":\"A\",\"fields\":["
            + "{\"bindingKey\":\"k1\",\"name\":\"count\",\"type\":\"int\",\"isField\":true,\"isSelected\":true},"
            + "{\"bindingKey\":\"k2\",\"name\":\"hashCode\",\"type\":\"int\",\"isField\":false,\"isSelected\":false,"
            + "\"parameters\":[]}],\"exists\":false}";

    private CodeAction prompt(String title, String commandId) {
        var action = new CodeAction(title);
        action.setCommand(new Command(title, commandId, List.of(json("{\"textDocument\":{\"uri\":\"u\"}}"))));
        return action;
    }

    @Test
    void generateToStringAsksWhichMembersAndAppliesTheGeneratedEdit() throws Exception {
        offer(prompt("Generate toString()", "java.action.generateToStringPrompt"));
        answers.put("java/checkToStringStatus", json(TO_STRING_STATUS));
        answers.put("java/generateToString", insertAtTop("// toString\n"));

        runAction("Generate toString()");

        assertEquals(List.of("[x] count : int", "[ ] hashCode() : int"), tickedLabels(), "the server's preselection");
        assertTrue(fx.server().executedCommands.isEmpty(), "a client-side prompt command is never sent to the server");
        fx.run(() -> {
            fx.checkboxes().get(1).setSelected(true);
            fx.acceptTicked();
        });

        assertEquals(List.of("java/checkToStringStatus", "java/generateToString"), rawMethods());
        assertEquals(2, lastRawParams().getAsJsonArray("fields").size(), "both ticked members are sent");
        assertEquals("// toString\n" + SOURCE, content());
        assertEquals(tr("status.lsp.codeActionApplied", "Generate toString()"), fx.host.lastStatus());
    }

    @Test
    void aGeneratorWithNothingToOfferSaysSoAndAFailedGenerationIsReported() throws Exception {
        offer(prompt("Generate toString()", "java.action.generateToStringPrompt"));
        answers.put("java/checkToStringStatus", json("{\"fields\":[]}"));
        runAction("Generate toString()");
        assertEquals(tr("status.lsp.generateNothing", "Generate toString()"), fx.host.lastStatus());
        assertFalse(fx.host.overlay.isShowing());

        answers.put("java/checkToStringStatus", json(TO_STRING_STATUS));
        runAction("Generate toString()"); // …and the server then generates nothing
        fx.run(() -> fx.acceptTicked());
        assertEquals(tr("status.lsp.codeActionFailed", "Generate toString()"), fx.host.lastStatus());
        assertEquals(SOURCE, content());
    }

    private static final String THREE_CONSTRUCTORS = "\"constructors\":["
            + "{\"name\":\"Base\",\"parameters\":[],\"bindingKey\":\"c0\"},"
            + "{\"name\":\"Base\",\"parameters\":[\"String\"],\"bindingKey\":\"c1\"},"
            + "{\"name\":\"Base\",\"parameters\":[\"String\",\"Throwable\"],\"bindingKey\":\"c2\"}]";
    private static final String ONE_FIELD =
            "\"fields\":[{\"bindingKey\":\"f\",\"name\":\"count\",\"type\":\"int\",\"isField\":true,\"isSelected\":true}]";

    @Test
    void constructorsAskForTheSuperConstructorsThenTheFields() throws Exception {
        offer(prompt("Generate Constructors", "java.action.generateConstructorsPrompt"));
        answers.put("java/checkConstructorsStatus", json("{" + THREE_CONSTRUCTORS + "," + ONE_FIELD + "}"));
        answers.put("java/generateConstructors", insertAtTop("// ctor\n"));

        runAction("Generate Constructors");
        assertEquals(
                List.of("[x] Base()", "[ ] Base(String)", "[ ] Base(String, Throwable)"),
                tickedLabels(),
                "which super constructors first: each ticked one becomes a generated constructor");
        fx.run(() -> {
            fx.checkboxes().get(0).setSelected(false);
            fx.checkboxes().get(2).setSelected(true);
            fx.acceptTicked();
        });
        assertEquals(List.of("[x] count : int"), tickedLabels(), "then the fields to initialise");
        fx.run(() -> fx.acceptTicked());

        JsonObject sent = lastRawParams();
        assertEquals(1, sent.getAsJsonArray("constructors").size());
        assertEquals(
                "c2",
                sent.getAsJsonArray("constructors")
                        .get(0)
                        .getAsJsonObject()
                        .get("bindingKey")
                        .getAsString());
        assertEquals("// ctor\n" + SOURCE, content());
    }

    /** {@code class AppException extends RuntimeException {}}: constructors to choose, no fields to ask about. */
    @Test
    void aClassWithoutFieldsGeneratesStraightAfterTheConstructorChoice() throws Exception {
        offer(prompt("Generate Constructors", "java.action.generateConstructorsPrompt"));
        answers.put("java/checkConstructorsStatus", json("{" + THREE_CONSTRUCTORS + ",\"fields\":[]}"));
        answers.put("java/generateConstructors", insertAtTop("// ctor\n"));

        runAction("Generate Constructors");
        fx.run(() -> fx.acceptTicked());

        assertFalse(fx.host.overlay.isShowing(), "there are no fields to ask about");
        assertEquals("// ctor\n" + SOURCE, content());
    }

    @Test
    void aSingleSuperConstructorIsNotAsked() throws Exception {
        offer(prompt("Generate Constructors", "java.action.generateConstructorsPrompt"));
        answers.put(
                "java/checkConstructorsStatus",
                json("{\"constructors\":[{\"name\":\"Object\",\"parameters\":[],\"bindingKey\":\"c0\"}]," + ONE_FIELD
                        + "}"));
        answers.put("java/generateConstructors", insertAtTop("// ctor\n"));

        runAction("Generate Constructors");
        assertEquals(List.of("[x] count : int"), tickedLabels(), "straight to the fields");
        fx.run(() -> fx.dismissOverlay());
        assertEquals(List.of("java/checkConstructorsStatus"), rawMethods(), "a dismissed picker generates nothing");
    }

    private static final String TWO_DELEGATES = "{\"delegateFields\":["
            + "{\"field\":{\"bindingKey\":\"fi\",\"name\":\"items\",\"type\":\"List<String>\"},"
            + "\"delegateMethods\":[{\"bindingKey\":\"m1\",\"name\":\"add\",\"parameters\":[\"String\"]},"
            + "{\"bindingKey\":\"m2\",\"name\":\"clear\",\"parameters\":[]}]},"
            + "{\"field\":{\"bindingKey\":\"fn\",\"name\":\"name\",\"type\":\"String\"},"
            + "\"delegateMethods\":[{\"bindingKey\":\"m3\",\"name\":\"length\",\"parameters\":[]}]}]}";

    @Test
    void delegateMethodsAskForTheFieldThenItsMethods() throws Exception {
        offer(prompt("Generate Delegate Methods", "java.action.generateDelegateMethodsPrompt"));
        answers.put("java/checkDelegateMethodsStatus", json(TWO_DELEGATES));
        answers.put("java/generateDelegateMethods", insertAtTop("// delegates\n"));

        runAction("Generate Delegate Methods");
        fx.run(() -> fx.choose(0)); // the field "items"
        assertEquals(List.of("[ ] add(String)", "[ ] clear()"), tickedLabels());
        fx.run(() -> {
            fx.checkboxes().get(1).setSelected(true);
            fx.acceptTicked();
        });

        JsonObject sent = lastRawParams();
        assertEquals(1, sent.getAsJsonArray("delegateEntries").size());
        JsonObject entry = sent.getAsJsonArray("delegateEntries").get(0).getAsJsonObject();
        assertEquals("fi", entry.getAsJsonObject("field").get("bindingKey").getAsString());
        assertEquals(
                "m2", entry.getAsJsonObject("delegateMethod").get("bindingKey").getAsString());
        assertEquals("// delegates\n" + SOURCE, content());
        assertEquals(tr("status.lsp.codeActionApplied", "Generate Delegate Methods"), fx.host.lastStatus());
    }

    @Test
    void aLoneDelegateFieldIsNotAskedAndNoneAtAllIsReported() throws Exception {
        offer(prompt("Generate Delegate Methods", "java.action.generateDelegateMethodsPrompt"));
        answers.put(
                "java/checkDelegateMethodsStatus",
                json("{\"delegateFields\":[{\"field\":{\"bindingKey\":\"fn\",\"name\":\"name\",\"type\":\"String\"},"
                        + "\"delegateMethods\":[{\"bindingKey\":\"m3\",\"name\":\"length\",\"parameters\":[]}]}]}"));
        runAction("Generate Delegate Methods");
        assertEquals(List.of("[ ] length()"), tickedLabels(), "straight to the one field's methods");
        fx.run(() -> fx.dismissOverlay());

        answers.put("java/checkDelegateMethodsStatus", json("{\"delegateFields\":[]}"));
        runAction("Generate Delegate Methods");
        assertEquals(tr("status.lsp.generateNothing", "Generate Delegate Methods"), fx.host.lastStatus());

        // Several fields, and the user backs out of choosing one.
        answers.put("java/checkDelegateMethodsStatus", json(TWO_DELEGATES));
        runAction("Generate Delegate Methods");
        fx.run(() -> fx.dismissOverlay());
        assertFalse(rawMethods().contains("java/generateDelegateMethods"));
    }

    // --- the jdtls refactorings (#741) ---------------------------------------------------------------

    private static final String PARAMS = "{\"textDocument\":{\"uri\":\"file:///p/A.java\"},"
            + "\"range\":{\"start\":{\"line\":1,\"character\":4},\"end\":{\"line\":1,\"character\":4}},"
            + "\"context\":{\"diagnostics\":[]}}";

    private static final String PACKAGES = "{\"destinations\":["
            + "{\"displayName\":\"demo\",\"uri\":\"file:///p/demo\",\"path\":\"/p/demo\",\"project\":\"p\","
            + "\"isDefaultPackage\":false,\"isParentOfSelectedFile\":true},"
            + "{\"displayName\":\"demo.other\",\"uri\":\"file:///p/demo/other\",\"path\":\"/p/demo/other\","
            + "\"project\":\"p\",\"isDefaultPackage\":false,\"isParentOfSelectedFile\":false},"
            + "{\"displayName\":\"demo.third\",\"uri\":\"file:///p/demo/third\",\"path\":\"/p/demo/third\","
            + "\"project\":\"p\",\"isDefaultPackage\":false,\"isParentOfSelectedFile\":false}]}";

    private CodeAction refactoring(String title, String name, String info) {
        var action = new CodeAction(title);
        List<Object> args = new ArrayList<>(List.of(json("\"" + name + "\""), json(PARAMS)));
        if (info != null) {
            args.add(json(info));
        }
        action.setCommand(new Command(title, JdtlsRefactor.COMMAND, args));
        return action;
    }

    @Test
    void moveFileAsksForAPackageAndMovesThere() throws Exception {
        offer(refactoring("Move 'A.java'", JdtlsRefactor.MOVE_FILE, "{\"uri\":\"file:///p/demo/A.java\"}"));
        answers.put("java/getMoveDestinations", json(PACKAGES));
        JsonObject wrapped = new JsonObject();
        wrapped.add("edit", insertAtTop("package demo.other;\n"));
        answers.put("java/move", wrapped);

        runAction("Move 'A.java'");
        assertEquals(
                "[\"file:///p/demo/A.java\"]",
                lastRawParams().get("sourceUris").toString(),
                "the file named by the action, not the document it was invoked from");
        fx.run(() -> fx.choose(0)); // "demo.other": the package the file is already in is not offered

        JsonObject move = lastRawParams();
        assertEquals("moveResource", move.get("moveKind").getAsString());
        assertEquals(
                "demo.other",
                move.getAsJsonObject("destination").get("displayName").getAsString());
        assertEquals("package demo.other;\n" + SOURCE, content());
        assertEquals(tr("status.lsp.codeActionApplied", "Move 'A.java'"), fx.host.lastStatus());
        assertTrue(fx.server().executedCommands.isEmpty(), "the refactoring command is not one the server executes");
    }

    /** Move into a package that does not exist yet: typed, its folder created, named by URI (Java review B20). */
    @Test
    void moveFileOffersANewPackageAndCreatesItsFolder() throws Exception {
        Path current = java.nio.file.Files.createDirectories(root.resolve("src").resolve("demo"));
        // As jdtls answers: `uri` is the folder on disk, `path` the Eclipse workspace path (re-review BN2).
        String packages = "{\"destinations\":[{\"displayName\":\"demo\",\"uri\":\"" + current.toUri()
                + "\",\"path\":\"/p/src/demo\""
                + ",\"project\":\"p\",\"isDefaultPackage\":false,\"isParentOfSelectedFile\":true},"
                + "{\"displayName\":\"demo.other\",\"uri\":\"file:///p/demo/other\",\"path\":\"/p/demo/other\","
                + "\"project\":\"p\",\"isDefaultPackage\":false,\"isParentOfSelectedFile\":false}]}";
        offer(refactoring("Move 'A.java'", JdtlsRefactor.MOVE_FILE, null));
        answers.put("java/getMoveDestinations", json(packages));
        JsonObject wrapped = new JsonObject();
        wrapped.add("edit", insertAtTop("package demo.fresh;\n"));
        answers.put("java/move", wrapped);

        runAction("Move 'A.java'");
        List<String> rows = FxTestSupport.callOnFx(fx::pickerRows);
        assertEquals(2, rows.size(), String.valueOf(rows));
        assertTrue(rows.get(1).contains("label=" + tr("picker.refactor.newPackage")), "the last row types a package");
        fx.run(() -> fx.choose(1));
        fx.run(() -> fx.lastPrompt().onAccept().accept("not a package!"));
        assertEquals(tr("status.lsp.refactorBadPackage", "not a package!"), fx.host.lastError());

        fx.run(() -> fx.lastPrompt().onAccept().accept(" demo.fresh "));
        Path fresh = root.resolve("src").resolve("demo").resolve("fresh");
        assertTrue(java.nio.file.Files.isDirectory(fresh), "the package's folder is created under the source root");
        JsonObject move = lastRawParams();
        assertEquals("moveResource", move.get("moveKind").getAsString());
        assertEquals(fresh.toUri().toString(), move.get("destination").getAsString());
        assertEquals("package demo.fresh;\n" + SOURCE, content());
    }

    @Test
    void aMoveWithNowhereToGoOrRefusedByTheServerIsAnError() throws Exception {
        offer(refactoring("Move 'A.java'", JdtlsRefactor.MOVE_FILE, null));
        answers.put("java/getMoveDestinations", json("{\"destinations\":[]}"));
        runAction("Move 'A.java'");
        assertEquals(tr("status.lsp.refactorNoTarget", "Move 'A.java'"), fx.host.lastError());
        assertEquals(
                "[\"file:///p/A.java\"]",
                lastRawParams().get("sourceUris").toString(),
                "with no file named, the document's own");

        answers.put("java/getMoveDestinations", json("{\"errorMessage\":\"Cannot move a binary file\"}"));
        runAction("Move 'A.java'");
        assertEquals("Cannot move a binary file", fx.host.lastError(), "the server's own reason");
        assertFalse(fx.host.overlay.isShowing());

        // The destination is chosen, and then the move itself is refused.
        answers.put("java/getMoveDestinations", json(PACKAGES));
        answers.put("java/move", json("{\"errorMessage\":\"The target already has an A\"}"));
        runAction("Move 'A.java'");
        fx.run(() -> fx.choose(1));
        assertEquals("The target already has an A", fx.host.lastError());
        assertEquals(SOURCE, content());
    }

    @Test
    void moveInstanceMethodOffersTheMethodsOwnTargets() throws Exception {
        offer(refactoring("Move method", JdtlsRefactor.MOVE_INSTANCE_METHOD, null));
        answers.put(
                "java/getMoveDestinations",
                json("{\"destinations\":[{\"bindingKey\":\"k\",\"name\":\"helper\",\"type\":\"Helper\"}]}"));
        answers.put("java/move", insertAtTop("// moved\n"));

        runAction("Move method");
        assertTrue(lastRawParams().has("params"), "the position goes with the question");
        fx.run(() -> fx.choose(0));

        assertEquals("moveInstanceMethod", lastRawParams().get("moveKind").getAsString());
        assertEquals(
                "helper",
                lastRawParams().getAsJsonObject("destination").get("name").getAsString());
        assertEquals("// moved\n" + SOURCE, content());
    }

    private static final String TYPES = "[{\"name\":\"Helper\",\"kind\":5,\"containerName\":\"demo.other\"},"
            + "{\"name\":\"A\",\"kind\":5,\"containerName\":\"demo\"}]";

    @Test
    void moveStaticMemberOffersTheProjectsOtherTypes() throws Exception {
        offer(refactoring(
                "Move static member",
                JdtlsRefactor.MOVE_STATIC_MEMBER,
                "{\"enclosingTypeName\":\"demo.A\",\"projectName\":\"p\"}"));
        answers.put("java/searchSymbols", json(TYPES));
        answers.put("java/move", insertAtTop("// moved\n"));

        runAction("Move static member");
        fx.run(() -> fx.choose(0));

        assertEquals("moveStaticMember", lastRawParams().get("moveKind").getAsString());
        assertEquals(
                "Helper",
                lastRawParams().getAsJsonObject("destination").get("name").getAsString());
        assertEquals("// moved\n" + SOURCE, content());

        answers.put("java/searchSymbols", json("[]"));
        runAction("Move static member");
        assertEquals(tr("status.lsp.refactorNoTarget", "Move static member"), fx.host.lastError());
    }

    @Test
    void moveTypeAsksWhereOnlyWhenBothDestinationsArePossible() throws Exception {
        answers.put("java/searchSymbols", json(TYPES));
        answers.put("java/move", insertAtTop("// moved\n"));
        String both = "{\"supportedDestinationKinds\":[\"newFile\",\"class\"],\"enclosingTypeName\":\"demo.A\","
                + "\"projectName\":\"p\"}";

        offer(refactoring("Move type", JdtlsRefactor.MOVE_TYPE, both));
        runAction("Move type");
        assertEquals(
                List.of(tr("picker.refactor.moveType.newFile"), tr("picker.refactor.moveType.class")),
                FxTestSupport.callOnFx(() -> fx.pickerRows()));
        fx.run(() -> fx.choose(0));
        assertEquals("moveTypeToNewFile", lastRawParams().get("moveKind").getAsString());
        assertFalse(lastRawParams().has("destination"), "a new file needs no destination");

        runAction("Move type");
        fx.run(() -> fx.choose(1)); // into another class…
        fx.run(() -> fx.choose(0)); // …this one
        assertEquals("moveTypeToClass", lastRawParams().get("moveKind").getAsString());

        offer(refactoring(
                "Move type",
                JdtlsRefactor.MOVE_TYPE,
                "{\"supportedDestinationKinds\":[\"class\"],\"enclosingTypeName\":\"demo.A\"}"));
        runAction("Move type");
        assertEquals(
                "java/searchSymbols",
                FakeLanguageServer.last(fx.server().rawRequests).method());
        fx.run(() -> fx.dismissOverlay());

        offer(refactoring("Move type", JdtlsRefactor.MOVE_TYPE, "{\"supportedDestinationKinds\":[\"newFile\"]}"));
        int before = fx.server().rawRequests.size();
        runAction("Move type");
        assertEquals(before + 1, fx.server().rawRequests.size(), "straight to the move");
        assertEquals("moveTypeToNewFile", lastRawParams().get("moveKind").getAsString());
    }

    private static final String INTERFACE_STATUS_HEAD = "{\"members\":["
            + "{\"name\":\"greet\",\"typeName\":\"String\",\"parameters\":[\"int\"],\"handleIdentifier\":\"h1\"},"
            + "{\"name\":\"run\",\"typeName\":\"void\",\"parameters\":[],\"handleIdentifier\":\"h2\"}],"
            + "\"subTypeName\":\"A\",\"destinationResponse\":";

    @Test
    void extractInterfaceAsksForMembersANameAndAPackage() throws Exception {
        offer(refactoring("Extract interface", JdtlsRefactor.EXTRACT_INTERFACE, null));
        answers.put("java/checkExtractInterfaceStatus", json(INTERFACE_STATUS_HEAD + PACKAGES + "}"));
        answers.put("java/getRefactorEdit", insertAtTop("// interface\n"));

        runAction("Extract interface");
        assertEquals(List.of("[x] greet(int) : String", "[x] run() : void"), tickedLabels());
        fx.run(() -> {
            fx.checkboxes().get(0).setSelected(false);
            fx.acceptTicked();
        });
        LspCoordinatorFixture.Prompt name = fx.lastPrompt();
        assertEquals("AInterface", name.initial(), "a name is suggested from the class");

        fx.run(() -> name.onAccept().accept(" not a name "));
        assertEquals(tr("status.lsp.refactorBadName", "not a name"), fx.host.lastError());
        assertFalse(rawMethods().contains("java/getRefactorEdit"));

        fx.run(() -> name.onAccept().accept(" Runner "));
        fx.run(() -> fx.choose(1)); // several packages: asked

        JsonObject sent = lastRawParams();
        assertEquals("extractInterface", sent.get("command").getAsString());
        com.google.gson.JsonArray arguments = sent.getAsJsonArray("commandArguments");
        assertEquals("[\"h2\"]", arguments.get(0).toString(), "only the member left ticked");
        assertEquals("Runner", arguments.get(1).getAsString());
        assertEquals(
                "demo.other",
                arguments.get(2).getAsJsonObject().get("displayName").getAsString());
        assertEquals("// interface\n" + SOURCE, content());
    }

    @Test
    void extractInterfaceIntoTheOnlyPackageDoesNotAskAndNothingToExtractIsReported() throws Exception {
        offer(refactoring("Extract interface", JdtlsRefactor.EXTRACT_INTERFACE, null));
        String onePackage = "{\"destinations\":[{\"displayName\":\"demo\",\"uri\":\"file:///p/demo\","
                + "\"path\":\"/p/demo\",\"project\":\"p\",\"isParentOfSelectedFile\":true}]}";
        String noName = "{\"members\":[{\"name\":\"run\",\"typeName\":\"void\",\"parameters\":[],"
                + "\"handleIdentifier\":\"h2\"}],\"destinationResponse\":" + onePackage + "}";
        answers.put("java/checkExtractInterfaceStatus", json(noName));
        answers.put("java/getRefactorEdit", insertAtTop("// interface\n"));

        runAction("Extract interface");
        fx.run(() -> fx.acceptTicked());
        assertEquals("", fx.lastPrompt().initial(), "no class name to suggest one from");
        fx.run(() -> fx.lastPrompt().onAccept().accept("Runner"));
        assertFalse(fx.host.overlay.isShowing(), "one package: nothing to choose");
        assertEquals("// interface\n" + SOURCE, content());

        answers.put(
                "java/checkExtractInterfaceStatus",
                json("{\"members\":[],\"destinationResponse\":" + onePackage + "}"));
        runAction("Extract interface");
        assertEquals(tr("status.lsp.generateNothing", "Extract interface"), fx.host.lastStatus());
    }

    private static final String SIGNATURE = "{\"methodIdentifier\":\"id\",\"modifier\":\"public\","
            + "\"returnType\":\"String\",\"methodName\":\"greet\","
            + "\"parameters\":[{\"type\":\"int\",\"name\":\"n\",\"defaultValue\":\"\",\"originalIndex\":0}],"
            + "\"exceptions\":[]}";

    @Test
    void changeSignatureEditsTheSignatureAsOneLine() throws Exception {
        offer(refactoring("Change signature", JdtlsRefactor.CHANGE_SIGNATURE, null));
        answers.put("java/getChangeSignatureInfo", json(SIGNATURE));
        answers.put("java/getRefactorEdit", insertAtTop("// signature\n"));

        runAction("Change signature");
        LspCoordinatorFixture.Prompt prompt = fx.lastPrompt();
        assertEquals("public String greet(int n)", prompt.initial());

        fx.run(() -> prompt.onAccept().accept("  public String greet(int n) "));
        assertFalse(rawMethods().contains("java/getRefactorEdit"), "an unchanged signature changes nothing");

        fx.run(() -> prompt.onAccept().accept("this is not a signature"));
        assertEquals(tr("status.lsp.refactorBadSignature"), fx.host.lastError());

        fx.run(() -> prompt.onAccept().accept("public String hello(int n, String who)"));
        JsonObject sent = lastRawParams();
        assertEquals("changeSignature", sent.get("command").getAsString());
        assertEquals("hello", sent.getAsJsonArray("commandArguments").get(2).getAsString());
        assertEquals(
                fx.host.settings.getTabSize(),
                sent.getAsJsonObject("options").get("tabSize").getAsInt());
        assertEquals("// signature\n" + SOURCE, content());
    }

    @Test
    void aSignatureTheServerCannotChangeIsAnError() throws Exception {
        offer(refactoring("Change signature", JdtlsRefactor.CHANGE_SIGNATURE, null));
        answers.put("java/getChangeSignatureInfo", json("{\"errorMessage\":\"Not a method\"}"));
        runAction("Change signature");
        assertEquals("Not a method", fx.host.lastError());

        answers.remove("java/getChangeSignatureInfo"); // the server answers nothing at all
        runAction("Change signature");
        assertEquals(tr("status.lsp.codeActionFailed", "Change signature"), fx.host.lastError());
        assertTrue(fx.host.prompts.isEmpty(), "there is nothing to edit");
    }

    /** A refactoring that needs no choice from the user is one request away. */
    @Test
    void aRefactoringWithNothingToAskAppliesDirectly() throws Exception {
        offer(refactoring("Introduce Parameter...", "introduceParameter", null));
        answers.put("java/getRefactorEdit", insertAtTop("// introduced\n"));

        runAction("Introduce Parameter...");

        assertEquals(List.of("java/getRefactorEdit"), rawMethods());
        assertEquals("introduceParameter", lastRawParams().get("command").getAsString());
        assertEquals(0, lastRawParams().getAsJsonArray("commandArguments").size());
        assertEquals("// introduced\n" + SOURCE, content());

        answers.put("java/getRefactorEdit", null);
        runAction("Introduce Parameter...");
        assertEquals(tr("status.lsp.codeActionFailed", "Introduce Parameter..."), fx.host.lastStatus());
    }

    private static final String TWO_EXPRESSIONS =
            "[{\"name\":\"count\",\"offset\":14,\"length\":5},{\"name\":\"count + 1\",\"offset\":14,\"length\":9}]";

    /**
     * Extract at a bare caret (Java review D7): the server is asked which expressions are there, the user picks
     * one when there are several, and that expression goes back as the command's argument.
     */
    @Test
    void anExtractionAtACaretAsksWhichExpression() throws Exception {
        offer(refactoring("Extract to method", "extractMethod", null));
        answers.put("java/inferSelection", json(TWO_EXPRESSIONS));
        answers.put("java/getRefactorEdit", insertAtTop("// extracted\n"));

        runAction("Extract to method");
        assertEquals(List.of("java/inferSelection"), rawMethods(), "nothing is extracted before the choice");
        assertEquals("extractMethod", lastRawParams().get("command").getAsString());
        assertEquals(json(PARAMS), lastRawParams().get("context"));
        assertEquals(2, fx.pickerRows().size(), "both expressions are offered: " + fx.pickerRows());
        fx.run(() -> fx.choose(1));

        JsonObject sent = lastRawParams();
        assertEquals("extractMethod", sent.get("command").getAsString());
        assertEquals(
                "count + 1",
                sent.getAsJsonArray("commandArguments")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString());
        assertEquals("// extracted\n" + SOURCE, content());
    }

    @Test
    void aSingleInferredExpressionIsNotAskedAndNoneIsReported() throws Exception {
        offer(refactoring("Extract to local variable", "extractVariable", null));
        answers.put("java/inferSelection", json("[{\"name\":\"count\",\"offset\":14,\"length\":5}]"));
        answers.put("java/getRefactorEdit", insertAtTop("// extracted\n"));

        runAction("Extract to local variable");
        assertEquals(List.of("java/inferSelection", "java/getRefactorEdit"), rawMethods());
        assertEquals("// extracted\n" + SOURCE, content());

        fx.server().rawRequests.clear();
        answers.put("java/inferSelection", json("[]"));
        runAction("Extract to local variable");
        assertEquals(tr("status.lsp.refactorNoExpression", "Extract to local variable"), fx.host.lastStatus());
        assertEquals(List.of("java/inferSelection"), rawMethods());
    }

    /** Extract to field asks where the field is initialized when the server offers more than one place (D10). */
    @Test
    void anExtractedFieldAsksWhereItIsInitialized() throws Exception {
        offer(refactoring("Extract to field", "extractField", null));
        answers.put(
                "java/inferSelection",
                json("[{\"name\":\"count\",\"offset\":14,\"length\":5,"
                        + "\"params\":[\"Current method\",\"Field declaration\"]}]"));
        answers.put("java/getRefactorEdit", insertAtTop("// field\n"));

        runAction("Extract to field");
        assertEquals(List.of("Current method", "Field declaration"), fx.pickerRows());
        String shown = FxTestSupport.callOnFx(
                () -> { // each row as its cell draws it
                    @SuppressWarnings("unchecked")
                    javafx.scene.control.ListView<Object> list = (javafx.scene.control.ListView<Object>)
                            fx.overlayCard().lookup(".list-view");
                    StringBuilder all = new StringBuilder();
                    for (int i = 0; i < list.getItems().size(); i++) {
                        javafx.scene.control.ListCell<Object> cell =
                                list.getCellFactory().call(list);
                        cell.updateListView(list);
                        cell.updateIndex(i);
                        all.append(cell.getText()).append('|');
                        if (cell.getGraphic() instanceof javafx.scene.Parent g) {
                            for (javafx.scene.Node n : g.lookupAll("*")) {
                                if (n instanceof javafx.scene.control.Labeled l) {
                                    all.append(l.getText()).append('|');
                                } else if (n instanceof javafx.scene.text.Text t) {
                                    all.append(t.getText()).append('|');
                                }
                            }
                        }
                    }
                    return all.toString();
                });
        assertTrue(shown.contains(tr("picker.refactor.scope.method")), "the rows read translated: " + shown);
        assertFalse(shown.contains("settings.scope"), "never a raw key (third review DN5): " + shown);
        fx.run(() -> fx.choose(1));

        var arguments = lastRawParams().getAsJsonArray("commandArguments");
        assertEquals("Field declaration", arguments.get(0).getAsString(), "the scope first");
        assertEquals("count", arguments.get(1).getAsJsonObject().get("name").getAsString(), "then the expression");
        assertEquals("// field\n" + SOURCE, content());

        // Convert local to field: the scopes come with the action, nothing to infer.
        fx.server().rawRequests.clear();
        offer(refactoring(
                "Convert local variable to field",
                "convertVariableToField",
                "{\"initializedScopes\":[\"Current method\",\"Class constructors\"]}"));
        runAction("Convert local variable to field");
        assertEquals(List.of(), rawMethods(), "nothing is asked of the server before the choice");
        fx.run(() -> fx.choose(0));
        assertEquals(
                "Current method",
                lastRawParams().getAsJsonArray("commandArguments").get(0).getAsString());
    }

    /**
     * A move jdtls finds problems with comes back as those problems and a token (D11): nothing changes until the
     * user agrees, and then the move is asked for again with the token.
     */
    @Test
    void aMoveWithProblemsIsAppliedOnlyAfterTheUserAgrees() throws Exception {
        offer(refactoring("Move method", JdtlsRefactor.MOVE_INSTANCE_METHOD, null));
        answers.put(
                "java/getMoveDestinations",
                json("{\"destinations\":[{\"bindingKey\":\"k\",\"name\":\"helper\",\"type\":\"Helper\"}]}"));
        JsonObject moved = new JsonObject();
        moved.add("edit", insertAtTop("// moved\n"));
        fx.server().rawHandler = raw -> {
            if (!raw.method().equals("java/move")) {
                return answers.get(raw.method());
            }
            boolean confirmed = com.editora.lsp.LspManager.asJson(raw.params())
                    .getAsJsonObject()
                    .has("confirmationToken");
            return confirmed ? moved : json("{\"errorMessage\":\"helper may be null\",\"confirmationToken\":\"t-1\"}");
        };
        List<String> shown = new ArrayList<>();
        fx.coordinator.moveProblemsConfirmer = problems -> {
            shown.add(problems);
            return false;
        };

        runAction("Move method");
        fx.run(() -> fx.choose(0));
        assertEquals(List.of("helper may be null"), shown);
        assertEquals(SOURCE, content(), "declined: nothing moved");
        assertEquals(tr("status.lsp.refactorCancelled", "Move method"), fx.host.lastStatus());
        assertEquals(1, rawMethods().stream().filter("java/move"::equals).count());

        fx.coordinator.moveProblemsConfirmer = problems -> true;
        runAction("Move method");
        fx.run(() -> fx.choose(0));
        assertEquals("t-1", lastRawParams().get("confirmationToken").getAsString());
        assertEquals(
                "helper",
                lastRawParams().getAsJsonObject("destination").get("name").getAsString());
        assertEquals("// moved\n" + SOURCE, content());
    }

    // --- rename (#676, #768) -------------------------------------------------------------------------

    private WorkspaceEdit renameCountTo(String name) {
        return new WorkspaceEdit(
                Map.of(buffer.getPath().toUri().toString(), List.of(new TextEdit(range(1, 8, 13), name))));
    }

    @Test
    void renameIsRefusedWithoutAProviderOrOnAReadOnlyFile() throws Exception {
        fx.capabilities.setRenameProvider(Either.forLeft(false));
        fx.run(() -> fx.coordinator.rename());
        assertEquals(tr("status.lsp.noRename"), fx.host.lastStatus());

        fx.capabilities.setRenameProvider(Either.forLeft(true));
        fx.ops.editable = false;
        fx.host.statuses.clear();
        fx.run(() -> fx.coordinator.rename());
        assertEquals(List.of(tr("status.lsp.noRename")), fx.host.statuses);
        assertTrue(fx.host.prompts.isEmpty());
    }

    @Test
    void renameWithinOneFilePromptsWithTheWordAndAppliesWithoutAPreview() throws Exception {
        fx.caret(buffer, 1, 10); // inside "count"
        fx.server().renameResponse = renameCountTo("total");

        fx.run(() -> fx.coordinator.rename());
        LspCoordinatorFixture.Prompt prompt = fx.lastPrompt();
        assertEquals("count", prompt.initial());
        assertTrue(fx.server().prepareRenames.isEmpty(), "this server did not advertise prepareRename");

        fx.run(() -> prompt.onAccept().accept("  "));
        fx.run(() -> prompt.onAccept().accept("count"));
        fx.run(() -> prompt.onAccept().accept(null));
        assertTrue(fx.server().renames.isEmpty(), "a blank or unchanged name renames nothing");

        fx.run(() -> prompt.onAccept().accept(" total "));
        assertEquals("total", fx.server().renames.get(0).getNewName());
        assertEquals(new Position(1, 10), fx.server().renames.get(0).getPosition());
        assertEquals("class A {\n    int total;\n}\n", content());
        assertTrue(fx.host.statuses.contains(tr("status.lsp.renaming")));
        assertEquals(tr("status.lsp.renamed", "total"), fx.host.lastStatus());
        assertFalse(fx.host.overlay.isShowing(), "a change confined to the file on screen needs no confirmation");
    }

    @Test
    void aRefusedRenameIsReported() throws Exception {
        fx.caret(buffer, 1, 10);
        fx.server().renameResponse = null;
        fx.run(() -> fx.coordinator.rename());
        fx.run(() -> fx.lastPrompt().onAccept().accept("total"));
        assertEquals(tr("status.lsp.renameFailed", "total"), fx.host.lastStatus());
        assertEquals(SOURCE, content());
    }

    @Test
    void aRenameTheServerRefusesSaysWhy() throws Exception {
        fx.caret(buffer, 1, 10);
        fx.run(() -> fx.coordinator.rename());
        fx.server().failEverything = true;
        fx.run(() -> fx.lastPrompt().onAccept().accept("total"));
        assertEquals(
                tr("status.lsp.renameFailedWhy", "total", "simulated transport failure"),
                fx.host.lastStatus(),
                "re-review BN9");
    }

    @Test
    void inlineDoesNotOfferMakeStaticAndGenerateListsOnlyGenerators() throws Exception {
        CodeAction makeStatic = new CodeAction("Make Static");
        makeStatic.setKind("refactor.inline");
        CodeAction inline = new CodeAction("Inline Method");
        inline.setKind("refactor.inline");
        inline.setEdit(new WorkspaceEdit(
                Map.of(buffer.getPath().toUri().toString(), List.of(new TextEdit(range(0, 0, 0), "// inlined\n")))));
        offer(makeStatic, inline);
        fx.run(() -> fx.coordinator.refactoring("refactor.inline", "lsp.inline"));
        assertEquals(
                List.of("Inline Method"),
                buffer.offered.stream()
                        .map(com.editora.editor.lsp.CodeAction::title)
                        .toList(),
                "EN6: no Make Static; ET1: Inline Method is chosen from its list, not run by the chord alone");
        fx.run(() -> buffer.pick("Inline Method"));
        assertEquals(tr("status.lsp.codeActionApplied", "Inline Method"), fx.host.lastStatus());

        CodeAction refused = new CodeAction("Inline Method"); // jdtls resolves a failed final check to no edit
        refused.setKind("refactor.inline");
        offer(refused);
        runAction("Inline Method");
        assertEquals(tr("status.lsp.codeActionNoChange", "Inline Method"), fx.host.lastStatus(), "not a bare failure");

        CodeAction toString = new CodeAction("Generate toString()");
        toString.setKind("source.generate.toString");
        CodeAction organize = new CodeAction("Organize imports");
        organize.setKind("source.organizeImports");
        offer(toString, organize);
        fx.run(() -> fx.coordinator.generateActions());
        assertTrue(
                fx.host.statuses.stream()
                        .noneMatch(st -> st.equals(tr("status.lsp.codeActionApplied", "Organize imports"))),
                "BN6: Organize imports is not a generator");
    }

    @Test
    void prepareRenameSuppliesThePlaceholderOrRefuses() throws Exception {
        fx.capabilities.setRenameProvider(Either.forRight(new RenameOptions(true)));
        fx.caret(buffer, 1, 10);

        fx.server().prepareRenameResponse = null;
        fx.run(() -> fx.coordinator.rename());
        assertEquals(tr("status.lsp.cannotRename"), fx.host.lastStatus());
        assertTrue(fx.host.prompts.isEmpty());

        fx.server().prepareRenameResponse = Either3.forSecond(new PrepareRenameResult(range(1, 8, 13), "serverName"));
        fx.run(() -> fx.coordinator.rename());
        assertEquals("serverName", fx.lastPrompt().initial());

        // A bare range: the placeholder is the text it covers.
        fx.server().prepareRenameResponse = Either3.forFirst(range(1, 4, 7));
        fx.run(() -> fx.coordinator.rename());
        assertEquals("int", fx.lastPrompt().initial());

        // A range that is not in the document (or empty) falls back to the word at the caret.
        fx.server().prepareRenameResponse = Either3.forFirst(range(40, 0, 3));
        fx.run(() -> fx.coordinator.rename());
        assertEquals("count", fx.lastPrompt().initial());
        fx.server().prepareRenameResponse = Either3.forFirst(range(1, 9, 9));
        fx.run(() -> fx.coordinator.rename());
        assertEquals("count", fx.lastPrompt().initial());
    }

    @Test
    void aRenameReachingOtherFilesIsPreviewedAndOnlyTheTickedFilesChange() throws Exception {
        EditorBuffer user = fx.open("User.java", "class User {\n    int n = new A().count;\n}\n");
        FxTestSupport.runOnFx(() -> fx.host.active = buffer);
        fx.caret(buffer, 1, 10);
        var edit = new WorkspaceEdit(new java.util.LinkedHashMap<>());
        edit.getChanges().put(buffer.getPath().toUri().toString(), List.of(new TextEdit(range(1, 8, 13), "total")));
        edit.getChanges().put(user.getPath().toUri().toString(), List.of(new TextEdit(range(1, 20, 25), "total")));
        fx.server().renameResponse = edit;

        fx.run(() -> fx.coordinator.rename());
        fx.run(() -> fx.lastPrompt().onAccept().accept("total"));

        List<CheckBox> rows = FxTestSupport.callOnFx(() -> fx.checkboxes());
        assertEquals(2, rows.size(), "one row per file the rename touches");
        assertTrue(rows.stream().allMatch(CheckBox::isSelected), "everything is applied unless unticked");
        assertTrue(rows.get(0).getText().contains(tr("lsp.rename.editCount", 1)));
        assertEquals(SOURCE, content(), "nothing changes while the preview is up");

        int userRow = rows.get(0).getText().contains("User.java") ? 0 : 1;
        fx.run(() -> {
            fx.checkboxes().get(userRow).setSelected(false);
            fx.acceptTicked();
        });

        assertEquals("class A {\n    int total;\n}\n", content());
        assertEquals(
                "class User {\n    int n = new A().count;\n}\n",
                FxTestSupport.callOnFx(user::getContent),
                "the file the user unticked is left alone");
        assertEquals(tr("status.lsp.renamed", "total"), fx.host.lastStatus());
    }

    @Test
    void aDismissedRenamePreviewAppliesNothing() throws Exception {
        EditorBuffer user = fx.open("User.java", "class User {\n    int n = new A().count;\n}\n");
        FxTestSupport.runOnFx(() -> fx.host.active = buffer);
        fx.caret(buffer, 1, 10);
        var edit = new WorkspaceEdit(new java.util.LinkedHashMap<>());
        edit.getChanges().put(buffer.getPath().toUri().toString(), List.of(new TextEdit(range(1, 8, 13), "total")));
        edit.getChanges().put(user.getPath().toUri().toString(), List.of(new TextEdit(range(1, 20, 25), "total")));
        fx.server().renameResponse = edit;

        fx.run(() -> fx.coordinator.rename());
        fx.run(() -> fx.lastPrompt().onAccept().accept("total"));
        fx.run(() -> fx.dismissOverlay());

        assertEquals(SOURCE, content());
        assertNull(fx.host.statuses.stream()
                .filter(tr("status.lsp.renamed", "total")::equals)
                .findAny()
                .orElse(null));
        assertEquals("class User {\n    int n = new A().count;\n}\n", Files.readString(user.getPath()));
    }

    /**
     * After an extract, Rename opens on the name jdtls picked (Java review B12): the answer's
     * {@code java.action.rename} command names it by offset in the edited document.
     */
    @Test
    void anExtractionOpensRenameOnTheNameTheServerPicked() throws Exception {
        offer(refactoring("Extract to local variable", "extractVariable", null));
        answers.put("java/inferSelection", json("[{\"name\":\"count\",\"offset\":14,\"length\":5}]"));
        String edited = "// extracted\n" + SOURCE;
        int offset = edited.indexOf("count");
        JsonObject answer = new JsonObject();
        answer.add("edit", insertAtTop("// extracted\n"));
        answer.add(
                "command",
                json("{\"title\":\"Rename\",\"command\":\"java.action.rename\",\"arguments\":[{\"uri\":\""
                        + buffer.getPath().toUri() + "\",\"offset\":" + offset + ",\"length\":5}]}"));
        answers.put("java/getRefactorEdit", answer);

        runAction("Extract to local variable");

        assertEquals(edited, content());
        assertEquals(offset, (int)
                FxTestSupport.callOnFx(() -> buffer.getFocusedArea().getCaretPosition()));
        assertEquals("count", fx.lastPrompt().initial(), "Rename is open on the new name");
    }

    /** Change Signature lists the files it changes and applies only the ticked ones (Java review B20). */
    @Test
    void aChangeSignatureThatTouchesAnotherFileIsPreviewed() throws Exception {
        Path other = root.resolve("B.java");
        String otherSource = "class B {\n    void use() {}\n}\n";
        java.nio.file.Files.writeString(other, otherSource);
        offer(refactoring("Change signature", JdtlsRefactor.CHANGE_SIGNATURE, null));
        answers.put("java/getChangeSignatureInfo", json(SIGNATURE));
        JsonObject edit = insertAtTop("// signature\n");
        edit.getAsJsonObject("changes")
                .add(
                        other.toUri().toString(),
                        json("[{\"range\":{\"start\":{\"line\":0,\"character\":0},"
                                + "\"end\":{\"line\":0,\"character\":0}},\"newText\":\"// caller\\n\"}]"));
        answers.put("java/getRefactorEdit", edit);

        runAction("Change signature");
        fx.run(() -> fx.lastPrompt().onAccept().accept("public String hello(int n)"));

        // B.java has no tab: its text is read off the FX thread before the edit is planned and previewed.
        for (int i = 0; i < 100 && !FxTestSupport.callOnFx(fx.host.overlay::isShowing); i++) {
            Thread.sleep(20);
            fx.settle();
        }
        assertEquals(SOURCE, content(), "nothing is applied before the preview is answered");
        List<String> rows = tickedLabels();
        assertEquals(2, rows.size(), "both files are listed: " + rows);
        assertTrue(rows.stream().allMatch(r -> r.startsWith("[x] ")), "ticked to start with");
        FxTestSupport.runOnFx(() -> fx.checkboxes().stream()
                .filter(box -> box.getText().contains("B.java"))
                .forEach(box -> box.setSelected(false)));
        fx.run(fx::acceptTicked);

        assertEquals("// signature\n" + SOURCE, content());
        assertEquals(otherSource, java.nio.file.Files.readString(other), "the unticked file is left alone");
    }
}
