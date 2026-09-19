package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.completion.*;
import com.editora.editor.*;
import com.editora.lsp.CompletionMapper;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class JavaTypingCompletionFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static class Request {
        String text;
        int kind;
        String trigger;
        Consumer<CompletionResult> callback;
        boolean cancelled;

        Request(String text, int kind, String trigger, Consumer<CompletionResult> callback) {
            this.text = text;
            this.kind = kind;
            this.trigger = trigger;
            this.callback = callback;
        }
    }

    private AsyncTestScope scope;
    private EditorBuffer buffer;
    private Stage stage;
    private final List<Request> requests = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        scope = new AsyncTestScope();
        FxTestSupport.runOnFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("java");
            buffer.setContent("System.out.");
            buffer.setLspActive(true);
            buffer.setLspTriggerChars(java.util.Set.of('.'));
            buffer.setCompletionDocEnabled(false);
            buffer.setLspCompletionSource((line, col, kind, trigger, cb) -> {
                var request = new Request(buffer.text(), kind, trigger, cb);
                requests.add(request);
                return () -> request.cancelled = true;
            });
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 800, 600));
            stage.show();
            buffer.getArea().moveTo(buffer.text().length());
            buffer.getArea().requestFocus();
        });
        scope.onClose(() -> FxTestSupport.runOnFx(() -> {
            buffer.dispose();
            stage.close();
        }));
        scope.awaitFx();
    }

    @AfterEach
    void close() throws Exception {
        scope.close();
    }

    private CompletionPopup popup() {
        return (CompletionPopup) FxTestSupport.call(
                FxTestSupport.field(buffer, "completionActions"), "completionPopup", new Class<?>[] {});
    }

    private void invoke() throws Exception {
        FxTestSupport.runOnFx(buffer::triggerCompletion);
    }

    private void type(String value) throws Exception {
        FxTestSupport.runOnFx(() -> buffer.typeString(value));
        scope.awaitFx();
    }

    private void press(String key) throws Exception {
        FxTestSupport.runOnFx(() -> buffer.pressKey(key));
        scope.awaitFx();
    }

    private void respond(int n, boolean incomplete, Completion... items) throws Exception {
        FxTestSupport.runOnFx(() -> requests.get(n).callback.accept(new CompletionResult(List.of(items), incomplete)));
    }

    private void expectCompletion(String prefix) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            var p = popup();
            p.setOnSelect(c -> {
                if (c != null && c.label().startsWith(prefix)) ready.countDown();
            });
            if (p.isShowing() && p.selected() != null && p.selected().label().startsWith(prefix)) ready.countDown();
        });
        scope.await(ready, "completion " + prefix);
        scope.awaitFx();
    }

    private Completion method(String label, String insertion, int from, int to) {
        var item = new CompletionItem(label);
        item.setKind(CompletionItemKind.Method);
        item.setFilterText(label.substring(0, label.indexOf('(')));
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setTextEdit(
                Either.forLeft(new TextEdit(new Range(new Position(0, from), new Position(0, to)), insertion)));
        return CompletionMapper.map(List.of(item)).getFirst();
    }

    @Test
    void typeFilterBackspaceAndAcceptWithoutAnotherServerRoundTrip() throws Exception {
        invoke();
        respond(0, false, method("print(String)", "print(${1:s})", 11, 11), method("println()", "println()", 11, 11));
        expectCompletion("print");
        type("prin");
        press("BACK_SPACE");
        press("BACK_SPACE");
        type("intln");
        expectCompletion("println");
        press("ENTER");
        assertEquals("System.out.println()", FxTestSupport.callOnFx(buffer::text));
        assertEquals(20, FxTestSupport.callOnFx(() -> buffer.getArea().getCaretPosition()));
        assertEquals(1, FxTestSupport.callOnFx(requests::size));
    }

    @Test
    void responseForOlderRequestCannotReplaceNewerScope() throws Exception {
        invoke();
        type("getFoo().");
        assertEquals(2, FxTestSupport.callOnFx(requests::size));
        assertTrue(requests.getFirst().cancelled);
        respond(1, false, Completion.lsp("getBar", "getBar", ""));
        expectCompletion("getBar");
        respond(0, false, Completion.lsp("OLD", "OLD", ""));
        scope.awaitFx();
        assertEquals("getBar", FxTestSupport.callOnFx(() -> popup().selected().label()));
    }

    @Test
    void escapeCancelsPendingRequestAndPreventsResurrection() throws Exception {
        invoke();
        press("ESCAPE");
        assertTrue(requests.getFirst().cancelled);
        respond(0, false, Completion.lsp("println", "println", ""));
        scope.awaitFx();
        assertFalse(FxTestSupport.callOnFx(buffer::completionShowing));
    }

    @Test
    void compatibleRapidTypingCanUseACompleteResponseAlreadyInFlight() throws Exception {
        invoke();
        type("pr");
        respond(0, false, method("println()", "println()", 11, 11));
        expectCompletion("println");
        press("ENTER");
        assertEquals("System.out.println()", FxTestSupport.callOnFx(buffer::text));
        assertEquals(1, FxTestSupport.callOnFx(requests::size));
    }

    @Test
    void incompleteListRetriggersWithProtocolContext() throws Exception {
        invoke();
        respond(0, true, Completion.lsp("print", "print", ""));
        expectCompletion("print");
        type("pr");
        assertEquals(2, FxTestSupport.callOnFx(requests::size));
        assertEquals(3, requests.get(1).kind);
        respond(1, false, Completion.lsp("printf", "printf", ""));
        expectCompletion("printf");
    }

    @Test
    void staleVisibleRangeCannotBeAcceptedAfterUnrelatedEdit() throws Exception {
        invoke();
        respond(0, false, method("println()", "println()", 11, 11));
        expectCompletion("println");
        FxTestSupport.runOnFx(() -> {
            buffer.getArea().replaceText(0, 6, "Object");
            buffer.getArea().moveTo(11);
            buffer.pressKey("ENTER");
        });
        assertFalse(FxTestSupport.callOnFx(buffer::text).contains("println"));
    }

    @Test
    void zeroPrefixManualCompletionWorksInsideBrokenJava() throws Exception {
        for (String code : List.of("foo(", "if (", "new ", "List<", "return obj.", "SomeClass::")) {
            FxTestSupport.runOnFx(() -> {
                buffer.cancelCompletion();
                buffer.setContent(code);
                buffer.getArea().moveTo(code.length());
                buffer.triggerCompletion();
            });
            assertEquals(code, FxTestSupport.callOnFx(() -> requests.getLast().text));
        }
    }

    @Test
    void overloadsWithIdenticalInsertionRemainSelectable() throws Exception {
        invoke();
        respond(
                0,
                false,
                method("print(String)", "print(${1:s})", 11, 11),
                method("print(char[])", "print(${1:s})", 11, 11));
        expectCompletion("print(String)");
        press("DOWN");
        assertEquals(
                "print(char[])", FxTestSupport.callOnFx(() -> popup().selected().label()));
        type("pr");
        expectCompletion("print(char[])");
        assertEquals(
                "print(char[])", FxTestSupport.callOnFx(() -> popup().selected().label()));
    }

    @Test
    void tabUsesReplaceRangeAndEnterUsesInsertRange() throws Exception {
        for (String key : List.of("ENTER", "TAB")) {
            FxTestSupport.runOnFx(() -> {
                buffer.cancelCompletion();
                buffer.setContent("System.out.prXYZ");
                buffer.getArea().moveTo(13);
            });
            invoke();
            var item = new CompletionItem("print");
            item.setInsertTextFormat(InsertTextFormat.PlainText);
            item.setTextEdit(Either.forRight(new InsertReplaceEdit(
                    "print",
                    new Range(new Position(0, 11), new Position(0, 13)),
                    new Range(new Position(0, 11), new Position(0, 16)))));
            respond(
                    requests.size() - 1,
                    false,
                    CompletionMapper.map(List.of(item)).getFirst());
            expectCompletion("print");
            press(key);
            assertEquals("System.out.print" + (key.equals("ENTER") ? "XYZ" : ""), FxTestSupport.callOnFx(buffer::text));
        }
    }

    @Test
    void methodAcceptanceKeepsExistingArgumentsAndRequestsSignatureHelp() throws Exception {
        for (String args : List.of("", "existingText")) {
            var help = new java.util.concurrent.atomic.AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                buffer.cancelCompletion();
                buffer.setContent("System.out.pr(" + args + ")");
                buffer.getArea().moveTo(13);
                buffer.setSignatureHelpRequester(ch -> help.incrementAndGet());
            });
            invoke();
            respond(requests.size() - 1, false, method("println(String)", "println(${1:s})", 11, 13));
            expectCompletion("println");
            press("ENTER");
            scope.awaitFx();
            assertEquals("System.out.println(" + args + ")", FxTestSupport.callOnFx(buffer::text));
            assertEquals(19, FxTestSupport.callOnFx(() -> buffer.getArea().getCaretPosition()));
            assertEquals(1, help.get());
        }
    }

    @Test
    void completedNoArgumentCallDoesNotRequestParameterHelpAfterItsClosingParenthesis() throws Exception {
        var help = new java.util.concurrent.atomic.AtomicInteger();
        FxTestSupport.runOnFx(() -> buffer.setSignatureHelpRequester(ch -> help.incrementAndGet()));
        invoke();
        respond(0, false, method("println()", "println()", 11, 11));
        expectCompletion("println");
        press("ENTER");
        assertEquals("System.out.println()", FxTestSupport.callOnFx(buffer::text));
        assertEquals(0, help.get());
    }

    @Test
    void methodReferenceDoesNotRequestInvocationSignatureHelp() throws Exception {
        var help = new java.util.concurrent.atomic.AtomicInteger();
        FxTestSupport.runOnFx(() -> {
            buffer.cancelCompletion();
            buffer.setContent("String::tr");
            buffer.getArea().moveTo(10);
            buffer.setSignatureHelpRequester(ch -> help.incrementAndGet());
        });
        invoke();
        respond(requests.size() - 1, false, method("trim()", "trim()", 8, 10));
        expectCompletion("trim");
        press("ENTER");
        assertEquals("String::trim", FxTestSupport.callOnFx(buffer::text));
        assertEquals(0, help.get());
    }

    @Test
    void emptyArgumentPlaceholderStillRequestsSignatureHelp() throws Exception {
        var help = new java.util.concurrent.atomic.AtomicInteger();
        FxTestSupport.runOnFx(() -> buffer.setSignatureHelpRequester(ch -> help.incrementAndGet()));
        invoke();
        respond(0, false, method("println(String)", "println($0)", 11, 11));
        expectCompletion("println");
        press("ENTER");
        assertEquals("System.out.println()", FxTestSupport.callOnFx(buffer::text));
        assertEquals(19, FxTestSupport.callOnFx(() -> buffer.getArea().getCaretPosition()));
        assertEquals(1, help.get());
    }

    @Test
    void commitParenthesisDoesNotDuplicateTheSnippetParenthesis() throws Exception {
        invoke();
        Completion item = method("println(String)", "println(${1:s})", 11, 11)
                .withProtocol(new Completion.Protocol("println", null, List.of("(")));
        respond(0, false, item);
        expectCompletion("println");
        FxTestSupport.runOnFx(() -> buffer.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "(", "(", KeyCode.UNDEFINED, false, false, false, false)));
        assertEquals("System.out.println(s)", FxTestSupport.callOnFx(buffer::text));
        assertEquals("s", FxTestSupport.callOnFx(() -> buffer.getArea().getSelectedText()));
    }

    @Test
    void deferredImportSurvivesContinuousTypingAndPreservesCaret() throws Exception {
        var apply = new java.util.concurrent.atomic.AtomicReference<Consumer<List<LspTextEdit>>>();
        FxTestSupport.runOnFx(() -> {
            buffer.cancelCompletion();
            buffer.setContent("class A { Arr");
            buffer.getArea().moveTo(13);
        });
        invoke();
        Completion item = Completion.lsp(
                "ArrayList", "ArrayList", "java.util", () -> apply.set(buffer.trackCompletionAdditionalEdits()));
        respond(requests.size() - 1, false, item);
        expectCompletion("ArrayList");
        press("ENTER");
        type(" values");
        FxTestSupport.runOnFx(
                () -> apply.get().accept(List.of(new LspTextEdit(0, 0, 0, 0, "import java.util.ArrayList;\n"))));
        assertEquals("import java.util.ArrayList;\nclass A { ArrayList values", FxTestSupport.callOnFx(buffer::text));
        assertEquals(
                FxTestSupport.callOnFx(() -> buffer.text().length()),
                FxTestSupport.callOnFx(() -> buffer.getArea().getCaretPosition()));
    }

    private Consumer<List<LspTextEdit>> acceptArrayList(boolean eager) throws Exception {
        var apply = new java.util.concurrent.atomic.AtomicReference<Consumer<List<LspTextEdit>>>();
        FxTestSupport.runOnFx(() -> {
            buffer.cancelCompletion();
            buffer.setContent("class A { Arr");
            buffer.getArea().moveTo(13);
        });
        invoke();
        respond(requests.size() - 1, false, Completion.lsp("ArrayList", "ArrayList", "java.util", () -> {
            if (eager) buffer.applyCompletionAdditionalEdits(arrayListImport());
            else apply.set(buffer.trackCompletionAdditionalEdits());
        }));
        expectCompletion("ArrayList");
        press("ENTER");
        return apply.get();
    }

    private static List<LspTextEdit> arrayListImport() {
        return List.of(new LspTextEdit(0, 0, 0, 0, "import java.util.ArrayList;\n"));
    }

    @Test
    void eagerCompletionAndImportUndoAndRedoTogether() throws Exception {
        acceptArrayList(true);
        String accepted = FxTestSupport.callOnFx(buffer::text);
        FxTestSupport.runOnFx(() -> buffer.getArea().undo());
        assertEquals("class A { Arr", FxTestSupport.callOnFx(buffer::text));
        FxTestSupport.runOnFx(() -> buffer.getArea().redo());
        assertEquals(accepted, FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void delayedImportBeforeMoreTypingJoinsTheCompletionUndo() throws Exception {
        var apply = acceptArrayList(false);
        FxTestSupport.runOnFx(() -> apply.accept(arrayListImport()));
        String accepted = FxTestSupport.callOnFx(buffer::text);
        FxTestSupport.runOnFx(() -> buffer.getArea().undo());
        assertEquals("class A { Arr", FxTestSupport.callOnFx(buffer::text));
        FxTestSupport.runOnFx(() -> buffer.getArea().redo());
        assertEquals(accepted, FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void laterTypingRemainsSeparateFromCompletionAndImportUndo() throws Exception {
        var apply = acceptArrayList(false);
        type(" values");
        FxTestSupport.runOnFx(() -> apply.accept(arrayListImport()));
        FxTestSupport.runOnFx(() -> buffer.getArea().undo());
        assertEquals("import java.util.ArrayList;\nclass A { ArrayList ", FxTestSupport.callOnFx(buffer::text));
        FxTestSupport.runOnFx(() -> buffer.getArea().redo());
        assertEquals("import java.util.ArrayList;\nclass A { ArrayList values", FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void interleavedTypingUndoesBeforeItsCompletionAndImportThenRedoesExactly() throws Exception {
        var apply = acceptArrayList(false);
        type(" values");
        FxTestSupport.runOnFx(() -> apply.accept(arrayListImport()));
        String finalText = FxTestSupport.callOnFx(buffer::text);
        FxTestSupport.runOnFx(() -> buffer.getArea().undo()); // values
        assertTrue(FxTestSupport.callOnFx(buffer::text).contains("import java.util.ArrayList;"));
        FxTestSupport.runOnFx(() -> buffer.getArea().undo()); // space
        assertEquals("import java.util.ArrayList;\nclass A { ArrayList", FxTestSupport.callOnFx(buffer::text));
        FxTestSupport.runOnFx(() -> buffer.getArea().undo()); // completion + import
        assertEquals("class A { Arr", FxTestSupport.callOnFx(buffer::text));
        FxTestSupport.runOnFx(() -> {
            buffer.getArea().redo();
            buffer.getArea().redo();
            buffer.getArea().redo();
        });
        assertEquals(finalText, FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void completionImportGroupIsUndoableFromTheOtherSplitView() throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            buffer.getArea().requestFocus();
        });
        scope.awaitFx();
        var apply = acceptArrayList(false);
        FxTestSupport.runOnFx(() -> apply.accept(arrayListImport()));
        FxTestSupport.runOnFx(() -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(buffer, "area2")).undo());
        assertEquals("class A { Arr", FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void interleavedImportHistoryRebasesBothSplitViews() throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            buffer.getArea().requestFocus();
        });
        scope.awaitFx();
        var apply = acceptArrayList(false);
        type(" values");
        FxTestSupport.runOnFx(() -> apply.accept(arrayListImport()));
        String accepted = FxTestSupport.callOnFx(buffer::text);
        FxTestSupport.runOnFx(() -> {
            org.fxmisc.richtext.CodeArea second = FxTestSupport.field(buffer, "area2");
            second.undo(); // the passive view records this typing burst as one existing undo step
            assertEquals("import java.util.ArrayList;\nclass A { ArrayList", buffer.text());
            second.undo();
            assertEquals("class A { Arr", buffer.text());
            second.redo();
            second.redo();
            assertEquals(accepted, buffer.text());
        });
    }

    @Test
    void anEarlierDelayedImportPreservesTheLaterCompletionsUndoGroup() throws Exception {
        var firstImport = acceptArrayList(false);
        type(" values; HashM");
        invoke();
        var secondImport = new java.util.concurrent.atomic.AtomicReference<Consumer<List<LspTextEdit>>>();
        respond(
                requests.size() - 1,
                false,
                Completion.lsp(
                        "HashMap",
                        "HashMap",
                        "java.util",
                        () -> secondImport.set(buffer.trackCompletionAdditionalEdits())));
        expectCompletion("HashMap");
        press("ENTER");
        type(" items");
        FxTestSupport.runOnFx(() -> {
            firstImport.accept(arrayListImport());
            secondImport.get().accept(List.of(new LspTextEdit(0, 0, 0, 0, "import java.util.HashMap;\n")));
        });
        String accepted = FxTestSupport.callOnFx(buffer::text);
        FxTestSupport.runOnFx(() -> {
            buffer.getArea().undo(); // items
            buffer.getArea().undo(); // space
            buffer.getArea().undo(); // HashMap completion + its import
            assertEquals("import java.util.ArrayList;\nclass A { ArrayList values; HashM", buffer.text());
            buffer.getArea().redo();
            buffer.getArea().redo();
            buffer.getArea().redo();
            assertEquals(accepted, buffer.text());
        });
    }

    @Test
    void unicodeBeforeThePrefixUsesUtf16Coordinates() throws Exception {
        String before = "// 😀\nclass Å { Str";
        FxTestSupport.runOnFx(() -> {
            buffer.cancelCompletion();
            buffer.setContent(before);
            buffer.getArea().moveTo(before.length());
        });
        invoke();
        var item = new CompletionItem("String");
        item.setTextEdit(Either.forLeft(new TextEdit(new Range(new Position(1, 10), new Position(1, 13)), "String")));
        respond(requests.size() - 1, false, CompletionMapper.map(List.of(item)).getFirst());
        expectCompletion("String");
        press("ENTER");
        assertEquals("// 😀\nclass Å { String", FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void importInsertionPreservesTheSelectedArgumentPlaceholder() throws Exception {
        invoke();
        Completion item = method("println(String)", "println(${1:value})", 11, 11);
        respond(0, false, item);
        expectCompletion("println");
        press("ENTER");
        FxTestSupport.runOnFx(() ->
                buffer.applyCompletionAdditionalEdits(List.of(new LspTextEdit(0, 0, 0, 0, "import demo.Value;\n"))));
        assertEquals("value", FxTestSupport.callOnFx(() -> buffer.getArea().getSelectedText()));
        type("text");
        assertTrue(FxTestSupport.callOnFx(buffer::text).contains("println(text)"));
    }

    @Test
    void committingADotFinishesTheSnippetBeforeChainedCompletion() throws Exception {
        invoke();
        var item = method("getFoo(String)", "getFoo(${1:value})", 11, 11)
                .withProtocol(new Completion.Protocol("getFoo", null, List.of(".")));
        respond(0, false, item);
        expectCompletion("getFoo");
        FxTestSupport.runOnFx(() -> buffer.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ".", ".", KeyCode.UNDEFINED, false, false, false, false)));
        scope.awaitFx();
        assertEquals("System.out.getFoo(value).", FxTestSupport.callOnFx(buffer::text));
        assertEquals(".", FxTestSupport.callOnFx(() -> requests.getLast().trigger));
    }

    @Test
    void aMethodArgumentCanReceiveAnotherCompletion() throws Exception {
        invoke();
        respond(0, false, method("println(String)", "println(${1:value})", 11, 11));
        expectCompletion("println");
        press("ENTER");
        type("Str");
        invoke();
        respond(requests.size() - 1, false, Completion.lsp("String", "String", ""));
        expectCompletion("String");
        press("TAB");
        assertEquals("System.out.println(String)", FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void escapeDismissesArgumentCompletionBeforeCancellingItsSnippet() throws Exception {
        invoke();
        respond(0, false, method("println(String)", "println(${1:value})", 11, 11));
        expectCompletion("println");
        press("ENTER");
        type("Str");
        invoke();
        respond(requests.size() - 1, false, Completion.lsp("String", "String", ""));
        expectCompletion("String");
        press("ESCAPE");
        assertFalse(FxTestSupport.callOnFx(buffer::completionShowing));
        assertTrue(FxTestSupport.callOnFx(buffer::hasActiveSnippet));
        press("ESCAPE");
        assertFalse(FxTestSupport.callOnFx(buffer::hasActiveSnippet));
    }

    private void acceptNestedMethod() throws Exception {
        invoke();
        respond(0, false, method("make(String, String)", "make(${1:arg}, ${2:tail})", 11, 11));
        expectCompletion("make");
        press("ENTER");
        type("get");
        invoke();
        respond(requests.size() - 1, false, method("get(String)", "get(${1:value})", 16, 19));
        expectCompletion("get");
        press("ENTER");
        assertEquals("value", FxTestSupport.callOnFx(() -> buffer.getArea().getSelectedText()));
    }

    @Test
    void finishingNestedMethodResumesTheOuterArguments() throws Exception {
        acceptNestedMethod();
        type("x");
        press("TAB");
        assertTrue(FxTestSupport.callOnFx(buffer::hasActiveSnippet));
        assertEquals(
                "System.out.make(get(x)",
                FxTestSupport.callOnFx(
                        () -> buffer.text().substring(0, buffer.getArea().getCaretPosition())));
        press("TAB");
        assertEquals("tail", FxTestSupport.callOnFx(() -> buffer.getArea().getSelectedText()));
        type("y");
        press("TAB");
        assertEquals("System.out.make(get(x), y)", FxTestSupport.callOnFx(buffer::text));
        assertFalse(FxTestSupport.callOnFx(buffer::hasActiveSnippet));
    }

    @Test
    void importDuringNestedMethodKeepsBothArgumentSessions() throws Exception {
        acceptNestedMethod();
        FxTestSupport.runOnFx(() ->
                buffer.applyCompletionAdditionalEdits(List.of(new LspTextEdit(0, 0, 0, 0, "import demo.Value;\n"))));
        assertEquals("value", FxTestSupport.callOnFx(() -> buffer.getArea().getSelectedText()));
        type("x");
        press("TAB");
        press("TAB");
        assertEquals("tail", FxTestSupport.callOnFx(() -> buffer.getArea().getSelectedText()));
        type("y");
        press("TAB");
        assertEquals("import demo.Value;\nSystem.out.make(get(x), y)", FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void escapeAndUndoAbandonAllNestedArguments() throws Exception {
        acceptNestedMethod();
        press("ESCAPE");
        assertFalse(FxTestSupport.callOnFx(buffer::hasActiveSnippet));
        FxTestSupport.runOnFx(() -> buffer.applyTemplate(
                com.editora.snippet.SnippetParser.parse("make(${1:arg}, ${2:tail})", name -> null)));
        // Undo must never restore a suspended parent's listeners or apply mirrors during undo.
        FxTestSupport.runOnFx(() -> buffer.getArea().undo());
        assertFalse(FxTestSupport.callOnFx(buffer::hasActiveSnippet));
    }

    @Test
    void inactiveSplitCaretCannotDismissTheFocusedCompletion() throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
            var other = (org.fxmisc.richtext.CodeArea) FxTestSupport.field(buffer, "area2");
            other.moveTo(0);
            buffer.getArea().requestFocus();
        });
        scope.awaitFx();
        FxTestSupport.runOnFx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            buffer.getArea().moveTo(buffer.text().length());
            buffer.getArea().requestFocus();
        });
        invoke();
        respond(requests.size() - 1, false, method("println()", "println()", 11, 11));
        expectCompletion("println");
        FxTestSupport.runOnFx(() -> {
            var other = (org.fxmisc.richtext.CodeArea) FxTestSupport.field(buffer, "area2");
            other.moveTo(1);
        });
        scope.awaitFx();
        assertTrue(FxTestSupport.callOnFx(buffer::completionShowing));
        type("pr");
        expectCompletion("println");
        press("ENTER");
        assertEquals("System.out.println()", FxTestSupport.callOnFx(buffer::text));
    }
}
