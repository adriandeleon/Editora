package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.WorkspaceEditDrafts.EditBasis;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

/**
 * jdtls's own requests and commands, for {@link LspManager}: the generate prompts, client-driven refactorings
 * ({@code java/…} requests that answer with an edit), auto-import on paste, smart semicolon, test-file and
 * stack-trace questions, fully qualified names, class-file sources, and a project configuration reload. Answers
 * reach their callbacks on the FX thread.
 */
final class JdtlsCommands {

    private final LspManager m;

    JdtlsCommands(LspManager m) {
        this.m = m;
    }

    /**
     * Step 1 of a jdtls generate prompt (#741): runs the {@code java/check…} request and delivers the
     * choosable candidates on the FX thread. Empty when the server has no session, the request fails, or the
     * response isn't the expected shape — the caller then reports "nothing to generate" rather than opening
     * an empty picker.
     */
    public void jdtlsGenerateCandidates(
            Path file, JdtlsGenerate.Kind kind, Object actionParams, Consumer<JdtlsGenerate.Plan> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || kind == null) {
            Platform.runLater(() -> cb.accept(new JdtlsGenerate.Plan(List.of(), null)));
            return;
        }
        s.rawRequest(kind.checkRequest(), actionParams)
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    JdtlsGenerate.Plan plan = e != null
                            ? new JdtlsGenerate.Plan(List.of(), null)
                            : JdtlsGenerate.plan(kind, LspManager.asJson(r));
                    Platform.runLater(() -> cb.accept(plan));
                });
    }

    /**
     * Step 2 of a jdtls generate prompt (#741): runs the {@code java/generate…} request with the user's
     * choice and applies the {@code WorkspaceEdit} it answers with. {@code cb} reports whether anything was
     * applied.
     */
    public void jdtlsGenerateApply(
            Path file,
            JdtlsGenerate.Kind kind,
            Object actionParams,
            JsonElement checkResponse,
            Map<Path, String> expectedAtAction,
            List<JdtlsGenerate.Candidate> chosen,
            Consumer<Boolean> cb) {
        jdtlsGenerateApply(file, kind, actionParams, checkResponse, expectedAtAction, chosen, null, cb);
    }

    /** As above, with the super constructors the user chose (constructors only; null = all on offer). */
    public void jdtlsGenerateApply(
            Path file,
            JdtlsGenerate.Kind kind,
            Object actionParams,
            JsonElement checkResponse,
            Map<Path, String> expectedAtAction,
            List<JdtlsGenerate.Candidate> chosen,
            @Nullable List<JdtlsGenerate.Candidate> chosenConstructors,
            Consumer<Boolean> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || kind == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        EditBasis expected = expectedAtAction == null || expectedAtAction.isEmpty()
                ? WorkspaceEditDrafts.editBasis(s)
                : new EditBasis(expectedAtAction, System.currentTimeMillis(), s.uris());
        s.rawRequest(
                        kind.generateRequest(),
                        JdtlsGenerate.generateParams(
                                kind, LspManager.asJson(actionParams), chosen, checkResponse, chosenConstructors))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : asWorkspaceEdit(r);
                    Platform.runLater(() -> {
                        if (edit == null) {
                            cb.accept(false);
                        } else {
                            m.codeActions.applyWorkspaceEdit(edit, expected, cb);
                        }
                    });
                });
    }

    /**
     * Sends a custom {@code java/…} request and delivers its untyped JSON answer on the FX thread —
     * {@code null} when there is no session or the request fails. For the look-ups a client-driven
     * refactoring makes before it asks for an edit (destinations, candidates, a method's signature).
     */
    public void jdtlsRequest(Path file, String method, Object params, Consumer<JsonElement> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        s.rawRequest(method, params).whenComplete((r, e) -> {
            JsonElement json = e != null ? null : LspManager.asJson(r);
            Platform.runLater(() -> cb.accept(json));
        });
    }

    /**
     * Sends a custom {@code java/…} request whose answer is, or wraps, a {@code WorkspaceEdit}
     * ({@link JdtlsRefactor#editOf}) and applies it. {@code cb} gets whether an edit was applied, and the
     * server's own {@code errorMessage} when it refused the refactoring (else null).
     */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtlsApplyEdit(file, method, params, expectedAtAction, null, cb);
    }

    /**
     * As above, for a request jdtls may answer with problems and a {@code confirmationToken} instead of an edit
     * ({@code java/move} once {@code moveRefactoringConfirmationSupport} is declared). {@code confirm} gets the
     * problems on the FX thread and the action that asks again with the token; without it, or for any other
     * answer, this behaves exactly as above.
     */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtlsApplyEdit(file, method, params, expectedAtAction, confirm, null, cb);
    }

    /**
     * As above; {@code onRename} gets the new name's place ({@link JdtlsRefactor#renameAt}) on the FX thread
     * once an edit that carries one is applied.
     */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.@Nullable Consumer<JdtlsRefactor.RenameAt> onRename,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtlsApplyEdit(file, method, params, expectedAtAction, confirm, onRename, null, cb);
    }

    /** As above; {@code preview} sees the planned edit before it is applied (see {@link #applyWorkspaceEdit}). */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.@Nullable Consumer<JdtlsRefactor.RenameAt> onRename,
            java.util.function.@Nullable BiConsumer<WorkspaceEditMapper.Mapped, Consumer<WorkspaceEditMapper.Mapped>>
                    preview,
            java.util.function.BiConsumer<Boolean, String> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false, null));
            return;
        }
        EditBasis expected = expectedAtAction == null || expectedAtAction.isEmpty()
                ? WorkspaceEditDrafts.editBasis(s)
                : new EditBasis(expectedAtAction, System.currentTimeMillis(), s.uris());
        s.rawRequest(method, params).whenComplete((r, e) -> {
            JsonElement json = e != null ? null : LspManager.asJson(r);
            String refused = JdtlsRefactor.errorMessage(json);
            String token = JdtlsRefactor.confirmationToken(json);
            if (token != null && confirm != null && params instanceof JsonObject asked) {
                JsonObject again = JdtlsRefactor.withConfirmationToken(asked, token);
                Platform.runLater(() -> confirm.accept(
                        refused == null ? "" : refused,
                        () -> jdtlsApplyEdit(file, method, again, expectedAtAction, null, onRename, preview, cb)));
                return;
            }
            org.eclipse.lsp4j.WorkspaceEdit edit = asWorkspaceEdit(JdtlsRefactor.editOf(json));
            JdtlsRefactor.RenameAt renameAt = onRename == null ? null : JdtlsRefactor.renameAt(json);
            Platform.runLater(() -> {
                if (edit == null) {
                    cb.accept(false, refused);
                } else {
                    m.codeActions.applyWorkspaceEdit(edit, expected, preview, ok -> {
                        cb.accept(ok, refused);
                        if (ok && renameAt != null && onRename != null) {
                            onRename.accept(renameAt);
                        }
                    });
                }
            });
        });
    }

    /**
     * Auto-import for pasted code (#742): asks jdtls's {@code java.edit.handlePasteEvent} what the text
     * pasted at {@code [start..end]} (post-paste coordinates) needs, and applies the answered
     * {@code additionalEdit} — the import insertion — through the normal workspace-edit path. Gated on
     * the server actually advertising the command ({@link JdtlsPaste#supportsPasteEvent}), so a non-jdtls
     * java setup (or any other language's server) never even sends the request.
     *
     * <p>{@code stillValid} is consulted on the FX thread immediately before applying: the round trip is
     * asynchronous and the edit was computed against the post-paste document, so if the user typed in the
     * meantime the answer is dropped rather than applied against text it wasn't computed for (the
     * {@code docVersion} idiom — the caller supplies the check because only it can see the buffer).
     * {@code cb} reports whether an edit was applied.
     */
    public void handlePasteEvent(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            String pastedText,
            int tabSize,
            boolean insertSpaces,
            java.util.function.BooleanSupplier stillValid,
            Consumer<Boolean> cb) {
        handlePasteEvent(
                file,
                startLine,
                startChar,
                endLine,
                endChar,
                pastedText,
                tabSize,
                insertSpaces,
                null,
                stillValid,
                null,
                true,
                cb);
    }

    /**
     * As above, with the Java file the text was copied from ({@code copiedFrom}, or null). jdtls answers null when
     * asked a few milliseconds after the paste's change, before it has read it (most pastes): asked once more, after
     * the change has settled, it answers (eleventh review AM2). {@code current}, when given, is the document's text
     * now: an answer that inserts only above the pasted text applies to it though the user typed after the paste,
     * which {@code stillValid} allows (twelfth review AL2). A null or failed answer with {@code copiedFrom} is asked
     * again without it: jdtls answers nothing when that file is gone (AL3).
     */
    public void handlePasteEvent(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            String pastedText,
            int tabSize,
            boolean insertSpaces,
            @Nullable Path copiedFrom,
            java.util.function.BooleanSupplier stillValid,
            java.util.function.@Nullable Supplier<String> current,
            boolean retry,
            Consumer<Boolean> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || !JdtlsPaste.supportsPasteEvent(s.capabilities())) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        // The params go over the wire as a STRINGIFIED json — an object argument deserializes to null
        // server-side (established empirically; see JdtlsPaste).
        String params = JdtlsPaste.paramsJson(
                s.uris().toWire(LspManager.uri(file)),
                startLine,
                startChar,
                endLine,
                endChar,
                pastedText,
                tabSize,
                insertSpaces,
                copiedFrom == null ? null : s.uris().toWire(LspManager.uri(copiedFrom)));
        EditBasis expected = WorkspaceEditDrafts.editBasis(s);
        s.executeCommand(JdtlsPaste.COMMAND, List.of(params))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    org.eclipse.lsp4j.WorkspaceEdit edit = e != null
                            ? null
                            : asWorkspaceEdit(JdtlsPaste.keepPastedImports(
                                    JdtlsPaste.additionalEdit(LspManager.asJson(r)), pastedText));
                    com.google.gson.JsonElement json = e == null ? LspManager.asJson(r) : null;
                    boolean unanswered = e == null && (json == null || json.isJsonNull());
                    Platform.runLater(() -> {
                        if (copiedFrom != null && (e != null || (unanswered && !retry)) && stillValid.getAsBoolean()) {
                            // no answer even once settled, or a failure: perhaps the copied file is gone (AL3)
                            handlePasteEvent(
                                    file,
                                    startLine,
                                    startChar,
                                    endLine,
                                    endChar,
                                    pastedText,
                                    tabSize,
                                    insertSpaces,
                                    null,
                                    stillValid,
                                    current,
                                    retry,
                                    cb);
                        } else if (unanswered && retry && stillValid.getAsBoolean()) {
                            var later = new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
                            later.setOnFinished(ev -> {
                                if (stillValid.getAsBoolean()) {
                                    handlePasteEvent(
                                            file,
                                            startLine,
                                            startChar,
                                            endLine,
                                            endChar,
                                            pastedText,
                                            tabSize,
                                            insertSpaces,
                                            copiedFrom,
                                            stillValid,
                                            current,
                                            false,
                                            cb);
                                } else {
                                    cb.accept(false);
                                }
                            });
                            later.play();
                        } else if (edit == null || !stillValid.getAsBoolean()) {
                            cb.accept(false);
                        } else {
                            String now = current == null ? null : current.get();
                            m.codeActions.applyWorkspaceEdit(
                                    edit,
                                    now != null && JdtlsPaste.above(edit, startLine, startChar)
                                            ? WorkspaceEditDrafts.rebased(expected, file, now)
                                            : expected,
                                    cb);
                        }
                    });
                });
    }

    /**
     * Smart-semicolon detection (#746): asks jdtls where a {@code ;} typed at {@code (line, character)}
     * belongs — normally the end of the enclosing statement. Delivers the answered {@code {line, char}} on
     * the FX thread, or {@code null} when the server has nothing to say (already at the statement end, or
     * not a jdtls session). Gated on the command being advertised, so no other language pays a request.
     *
     * <p>The document must be in the state the caret refers to — i.e. <b>before</b> the semicolon is typed —
     * which is why the caller flushes its pending didChange first.
     */
    public void smartSemicolonPosition(Path file, int line, int character, Consumer<int[]> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || !JdtlsSmartSemicolon.supported(s.capabilities())) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String params = JdtlsSmartSemicolon.paramsJson(s.uris().toWire(LspManager.uri(file)), line, character);
        s.executeCommand(JdtlsSmartSemicolon.COMMAND, List.of(params))
                .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    int[] at = e != null ? null : JdtlsSmartSemicolon.answeredPosition(LspManager.asJson(r));
                    Platform.runLater(() -> cb.accept(at));
                });
    }

    /**
     * Decodes a {@code java/generate…} answer (an untyped {@code WorkspaceEdit}) into the typed form — with
     * LSP4J's own gson, whose adapters are what read the {@code Either}s inside {@code documentChanges}. A
     * plain gson cannot, so an answer in that shape was dropped as "nothing generated".
     */
    static org.eclipse.lsp4j.@Nullable WorkspaceEdit asWorkspaceEdit(@Nullable Object raw) {
        try {
            com.google.gson.JsonElement json = LspManager.asJson(raw);
            return json == null || !json.isJsonObject()
                    ? null
                    : LanguageServerSession.LSP_GSON.fromJson(json, org.eclipse.lsp4j.WorkspaceEdit.class);
        } catch (RuntimeException malformed) {
            return null; // a malformed answer degrades to "nothing generated", never an exception
        }
    }

    /**
     * Whether jdtls considers {@code file} a <em>test</em> source ({@code java.project.isTestFile}, #745).
     *
     * <p>Answered from the Eclipse project model — it knows which source folders are test folders — where
     * {@code test/JavaTestScanner} can only guess syntactically from annotations. Delivers {@code null}
     * (not {@code false}) when there's no session or the command fails, so the caller keeps its own answer
     * rather than treating "don't know" as "not a test".
     *
     * <p>Only a {@code .java} file is asked about. Of any other name jdtls has no compilation unit and answers
     * with an error whose stack trace lands in the Debug Log on every tab switch; a Java script is shown to it
     * as a stand-in ({@link JavaScriptShadow}), but one outside every project, where nothing is a test.
     */
    public void isTestFile(Path file, Consumer<Boolean> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || file == null || !isJavaSourceName(file)) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        m.executeCommand(file, "java.project.isTestFile", List.of(m.serverUri(file)), (result, error) -> {
            Boolean answer = error != null ? null : asBoolean(result);
            cb.accept(answer);
        });
    }

    /** Pure: whether jdtls can make a compilation unit of {@code file} — it must be named {@code *.java}. */
    static boolean isJavaSourceName(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().endsWith(".java");
    }

    /**
     * Resolves one stack-trace line to the URI it points at
     * ({@code java.project.resolveStackTraceLocation}, #744).
     *
     * <p>jdtls resolves it against the real classpath, so unlike the regex+filesystem walk in
     * {@code MainController.resolveRunLinkFile} it can name a frame inside a dependency or the JDK — which
     * comes back as a {@code jdt://} URI the class-file viewer (#665) already opens. Delivers null when
     * there's no session, the command fails, or the server can't place the frame.
     *
     * @param traceLine the console line verbatim, e.g. {@code "\tat demo.Person.of(Person.java:12)"}
     */
    public void resolveStackTraceLocation(Path anchorFile, String traceLine, Consumer<String> cb) {
        LanguageServerSession s = m.sessionFor(anchorFile);
        if (s == null || traceLine == null || traceLine.isBlank()) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        // The second argument is the project-name filter; an empty list means "search them all", which is
        // what we want — the console line doesn't say which project it came from.
        m.executeCommand(
                anchorFile,
                "java.project.resolveStackTraceLocation",
                List.of(traceLine, List.of()),
                (result, error) -> {
                    String uri = error != null ? null : rawStringResult(result);
                    cb.accept(uri == null || uri.isBlank() ? null : uri);
                });
    }

    /** A command result that is (or wraps) a boolean, else null. */
    private static @Nullable Boolean asBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof com.google.gson.JsonPrimitive p && p.isBoolean()) {
            return p.getAsBoolean();
        }
        return null;
    }

    /**
     * The fully qualified name of the symbol at a position ({@code java.getFullyQualifiedName}, #746) —
     * what "Copy Reference" copies in an IDE. Null when there's no session or nothing resolves there.
     */
    public void fullyQualifiedName(Path file, int line, int character, Consumer<String> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        var params = new org.eclipse.lsp4j.TextDocumentPositionParams(
                new org.eclipse.lsp4j.TextDocumentIdentifier(m.serverUri(file)), new Position(line, character));
        m.executeCommand(file, "java.getFullyQualifiedName", List.of(params), (result, error) -> {
            String name = error != null ? null : rawStringResult(result);
            cb.accept(name == null || name.isBlank() ? null : name);
        });
    }

    /**
     * Re-reads the project's build configuration ({@code java/projectConfigurationUpdate}, #746) — the
     * manual escape hatch when a {@code pom.xml} / {@code build.gradle} change hasn't been picked up. Asks the
     * Java server of {@code file}'s project; false when there is none.
     *
     * <p>A <b>notification</b>, not a request (jdtls declares it {@code void}), so there is nothing to
     * report back and nothing to wait for; the result shows up as fresh diagnostics.
     */
    public boolean reloadProjectConfiguration(Path file) {
        LanguageServerSession s = m.javaSessionFor(file);
        if (s != null && file != null) {
            // jdtls takes the build file itself as well as any source file of the project.
            s.rawNotify(
                    "java/projectConfigurationUpdate", new org.eclipse.lsp4j.TextDocumentIdentifier(m.serverUri(file)));
            return true;
        }
        return false;
    }

    /**
     * Fetches the source of a {@code jdt://} class-file URI via jdtls's {@code java/classFileContents}
     * request (the attached/decompiled source of a JDK or dependency class) and delivers it on the FX
     * thread — {@code null} when the session is gone, the request fails/times out, or the server returns
     * nothing. {@code anchorFile} is any file managed by the jdtls session that produced the URI.
     */
    public void classFileContents(Path anchorFile, @Nullable String jdtUri, Consumer<String> cb) {
        LanguageServerSession s = m.sessionFor(anchorFile);
        if (s == null || jdtUri == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        s.rawRequest("java/classFileContents", new org.eclipse.lsp4j.TextDocumentIdentifier(jdtUri))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    String text = e != null ? null : rawStringResult(r);
                    Platform.runLater(() -> cb.accept(text == null || text.isBlank() ? null : text));
                });
    }

    /** The string payload of a raw (untyped) JSON-RPC response — lsp4j decodes an unknown method's result
     *  as a gson element, so accept both. Null for anything else. */
    static @Nullable String rawStringResult(Object r) {
        if (r instanceof String s) {
            return s;
        }
        if (r instanceof com.google.gson.JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        return null;
    }
}
