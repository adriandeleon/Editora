package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.JdtlsGenerate;
import com.editora.lsp.JdtlsRefactor;
import com.editora.lsp.LspManager;
import com.editora.text.LspDiagnostic;
import com.editora.ui.LspCoordinator.WorkspaceFileOperations;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The refactorings {@link LspCoordinator} drives itself: Rename (prepare, prompt, the preview of the files it
 * changes, the check for conflicts it introduced) and the jdtls code actions the client has to carry out — the
 * source-generation prompts ({@link JdtlsGenerate}) and Move, Extract and Change Signature ({@link JdtlsRefactor}),
 * which ask the user what or where before the server's edit is requested. Their edits are applied and reported by
 * {@link LspWorkspaceEdits}. FX thread.
 */
final class LspRefactorings {

    /** What the refactorings need of {@link LspCoordinator}, resolved when called. */
    interface Host {
        /** The active buffer when its server manages it; otherwise says so and returns null. */
        @Nullable
        EditorBuffer activeLspBuffer();

        @Nullable
        Path lspPath(EditorBuffer buffer);

        /** The project's settings scope (indentation for a refactoring's edit). */
        ProjectScope projectScope();

        /** Diagnostics by canonical file, for the files a rename touched. */
        Map<Path, List<LspDiagnostic>> problems();
    }

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;
    private final Host lsp;
    private final LspWorkspaceEdits edits;
    private final LspRefactoringUndo undo;

    LspRefactorings(
            CoordinatorHost host,
            LspManager lspManager,
            LspCoordinator.Ops ops,
            Host lsp,
            LspWorkspaceEdits edits,
            LspRefactoringUndo undo) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.lsp = lsp;
        this.edits = edits;
        this.undo = undo;
    }

    /**
     * Runs a jdtls source-generation prompt (#741) if {@code item} is one, and reports whether it took over.
     *
     * <p>These actions must <b>not</b> reach {@code applyCodeAction}: their command is a client-side
     * {@code java.action.*Prompt}, so sending it as {@code workspace/executeCommand} fails and the user sees
     * "code action failed". Instead we run the server's {@code java/check…} request, show the member picker,
     * and run {@code java/generate…} with what was chosen.
     */
    boolean runGeneratePrompt(Path path, LspManager.CodeActionItem item) {
        Object params = LspManager.commandArgument(item.raw());
        JdtlsGenerate.Kind kind = JdtlsGenerate.forCommand(LspManager.commandIdOf(item.raw()));
        if (kind == null || params == null) {
            return false;
        }
        if (kind == JdtlsGenerate.Kind.DELEGATE_METHODS) {
            runDelegatePrompt(path, item, params);
            return true;
        }
        lspManager.jdtlsGenerateCandidates(path, kind, params, plan -> {
            List<JdtlsGenerate.Candidate> candidates = plan.candidates();
            List<JdtlsGenerate.Candidate> constructors = kind == JdtlsGenerate.Kind.CONSTRUCTORS
                    ? JdtlsGenerate.constructorCandidates(plan.status())
                    : List.of();
            if (candidates.isEmpty() && constructors.isEmpty()) {
                host.setStatus(tr("status.lsp.generateNothing", item.title()));
                return;
            }
            // Constructors are a two-step choice: which super constructors to call (each one becomes a
            // generated constructor), then which fields to initialise. A class with no fields — the usual
            // exception subclass — still has constructors to generate.
            java.util.function.Consumer<List<JdtlsGenerate.Candidate>> withConstructors = superChosen -> {
                java.util.function.Consumer<List<JdtlsGenerate.Candidate>> apply = chosen -> {
                    edits.beginReportedEdit();
                    com.google.gson.JsonElement status = plan.status(); // none: JsonNull, read as no constructors
                    lspManager.jdtlsGenerateApply(
                            path,
                            kind,
                            params,
                            status != null ? status : com.google.gson.JsonNull.INSTANCE,
                            item.expectedDocuments(),
                            chosen,
                            superChosen,
                            ok -> edits.reportEdit(
                                    ok,
                                    tr("status.lsp.codeActionApplied", LspCoordinator.bareTitle(item.title())),
                                    tr("status.lsp.codeActionFailed", item.title())));
                };
                if (candidates.isEmpty()) {
                    apply.accept(List.of());
                } else {
                    MultiSelectPicker.show(host.overlayHost(), item.title(), pickerRows(candidates), apply);
                }
            };
            if (constructors.size() > 1) {
                MultiSelectPicker.show(
                        host.overlayHost(),
                        tr("picker.generate.superConstructors", item.title()),
                        pickerRows(constructors),
                        withConstructors);
            } else {
                withConstructors.accept(null);
            }
        });
        return true;
    }

    /**
     * Generate Delegate Methods: a field first (only asked when several can be delegated to), then the
     * methods of that field to forward.
     */
    private void runDelegatePrompt(Path path, LspManager.CodeActionItem item, Object params) {
        JdtlsGenerate.Kind kind = JdtlsGenerate.Kind.DELEGATE_METHODS;
        lspManager.jdtlsRequest(path, kind.checkRequest(), params, status -> {
            List<JdtlsGenerate.DelegateField> fields = JdtlsGenerate.delegateFields(status);
            if (fields.isEmpty()) {
                host.setStatus(tr("status.lsp.generateNothing", item.title()));
                return;
            }
            java.util.function.Consumer<JdtlsGenerate.DelegateField> withField = field -> MultiSelectPicker.show(
                    host.overlayHost(),
                    item.title() + " — " + field.label(),
                    pickerRows(field.methods()),
                    chosen -> applyJdtlsEdit(
                            path,
                            item,
                            kind.generateRequest(),
                            JdtlsGenerate.delegateParams(LspManager.asJson(params), field, chosen)));
            if (fields.size() == 1) {
                withField.accept(fields.get(0));
            } else {
                LspCoordinator.pickOne(
                        host,
                        item.title(),
                        tr("picker.generate.delegateField"),
                        fields,
                        f -> f.label(),
                        f -> "",
                        withField);
            }
        });
    }

    /** Sends a {@code java/…} request that answers with an edit, applies it, and reports the outcome. */
    private void applyJdtlsEdit(Path path, LspManager.CodeActionItem item, String method, Object params) {
        applyJdtlsEdit(path, item, method, params, null);
    }

    /** As above; {@code onRename} gets the place of the new name the server suggests renaming. */
    private void applyJdtlsEdit(
            Path path,
            LspManager.CodeActionItem item,
            String method,
            Object params,
            java.util.function.@Nullable Consumer<JdtlsRefactor.RenameAt> onRename) {
        edits.beginReportedEdit();
        lspManager.jdtlsApplyEdit(path, method, params, item.expectedDocuments(), null, onRename, (ok, refused) -> {
            if (!ok && refused != null) {
                host.setError(refused); // the server's own reason beats "could not apply"
                return;
            }
            edits.reportEdit(
                    ok,
                    tr("status.lsp.codeActionApplied", LspCoordinator.bareTitle(item.title())),
                    tr("status.lsp.codeActionFailed", item.title()));
        });
    }

    /**
     * Carries out a jdtls refactoring the client has to drive — Move, Extract Interface, Change Signature
     * ({@link JdtlsRefactor}) — if {@code item} is one, and reports whether it took over.
     *
     * <p>Like the generate prompts these must not reach {@code applyCodeAction}: their command,
     * {@code java.action.applyRefactoringCommand}, is not one the server executes.
     */
    boolean runRefactorCommand(Path path, LspManager.CodeActionItem item) {
        JdtlsRefactor.Request request =
                JdtlsRefactor.parse(LspManager.commandIdOf(item.raw()), LspManager.commandArguments(item.raw()));
        if (request == null) {
            return false;
        }
        String uri = request.documentUri();
        switch (request.name()) {
            case JdtlsRefactor.MOVE_FILE -> {
                String source = request.info("uri") == null ? uri : request.info("uri");
                moveTo(
                        path,
                        item,
                        "moveResource",
                        source,
                        null,
                        tr("picker.refactor.package"),
                        r -> JdtlsRefactor.packages(r, false));
            }
            case JdtlsRefactor.MOVE_INSTANCE_METHOD ->
                moveTo(
                        path,
                        item,
                        "moveInstanceMethod",
                        uri,
                        request.params(),
                        tr("picker.refactor.moveTarget"),
                        JdtlsRefactor::instanceTargets);
            case JdtlsRefactor.MOVE_STATIC_MEMBER -> moveToType(path, item, "moveStaticMember", request);
            case JdtlsRefactor.MOVE_TYPE -> moveType(path, item, request);
            case JdtlsRefactor.EXTRACT_INTERFACE -> extractInterface(path, item, request);
            case JdtlsRefactor.CHANGE_SIGNATURE -> changeSignature(path, item, request);
            default -> extractRefactor(path, item, request);
        }
        return true;
    }

    /**
     * A refactoring whose edit is one {@code java/getRefactorEdit} away, after at most two questions: which
     * expression, when it was offered at a bare caret ({@code java/inferSelection}), and where an extracted
     * field is initialized. Most refactorings ask neither.
     */
    private void extractRefactor(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        java.util.function.BiConsumer<@Nullable String, JdtlsRefactor.@Nullable Selection> apply =
                (scope, selection) -> applyJdtlsEdit(
                        path,
                        item,
                        "java/getRefactorEdit",
                        refactorEdit(request, JdtlsRefactor.extractArguments(scope, selection)),
                        at -> renameExtracted(path, at));
        if (!JdtlsRefactor.needsInferredSelection(request)) {
            chooseInitializeScope(item, JdtlsRefactor.initializedScopes(request), scope -> apply.accept(scope, null));
            return;
        }
        lspManager.jdtlsRequest(
                path,
                "java/inferSelection",
                JdtlsRefactor.inferSelectionParams(request.name(), request.params()),
                answer -> {
                    List<JdtlsRefactor.Selection> found = JdtlsRefactor.selections(answer);
                    if (found.isEmpty()) {
                        host.setStatus(tr("status.lsp.refactorNoExpression", item.title()));
                        return;
                    }
                    java.util.function.Consumer<JdtlsRefactor.Selection> chosen = selection -> chooseInitializeScope(
                            item, selection.initializedScopes(), scope -> apply.accept(scope, selection));
                    if (found.size() == 1) {
                        chosen.accept(found.get(0));
                    } else {
                        LspCoordinator.pickOne(
                                host,
                                item.title(),
                                tr("picker.refactor.expression"),
                                found,
                                JdtlsRefactor.Selection::label,
                                selection -> "",
                                chosen);
                    }
                });
    }

    /**
     * After an extract, Rename on the name the server picked ({@code d}, {@code getTotal}, {@code string}), as
     * IntelliJ and vscode-java start an in-place rename there (Java review B12). Only in the file the
     * refactoring started from, while it is still the active tab; the selection the edit left is cleared.
     */
    private void renameExtracted(Path path, JdtlsRefactor.RenameAt at) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !path.equals(lsp.lspPath(b)) || !path.equals(lspManager.pathForServerUri(path, at.uri()))) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        if (at.offset() < 0 || at.offset() + at.length() > area.getLength()) {
            return;
        }
        area.moveTo(at.offset());
        area.requestFollowCaret();
        rename();
    }

    /** Asks where a field is initialized when the server offers more than one place; none or one is not asked. */
    private void chooseInitializeScope(
            LspManager.CodeActionItem item, List<String> scopes, java.util.function.Consumer<@Nullable String> then) {
        if (scopes.size() <= 1) {
            then.accept(scopes.isEmpty() ? null : scopes.get(0));
            return;
        }
        LspCoordinator.pickOne(
                host,
                item.title(),
                tr("picker.refactor.initializeIn"),
                scopes,
                LspRefactorings::initializeScopeLabel, // jdtls's scope names, translated (third review DN5)
                s -> "",
                then::accept);
    }

    /** jdtls's {@code InitializeScope} name in the UI language; the English value is what goes back (DN5). */
    static String initializeScopeLabel(String scope) {
        return switch (scope) {
            case "Field declaration" -> tr("picker.refactor.scope.field");
            case "Current method" -> tr("picker.refactor.scope.method");
            case "Class constructors" -> tr("picker.refactor.scope.constructors");
            default -> scope;
        };
    }

    /**
     * {@code java/move} with {@code params}. When jdtls finds problems it answers with them and a token instead
     * of an edit; the user sees them and the move is applied only if they continue.
     */
    private void applyJdtlsMove(Path path, LspManager.CodeActionItem item, com.google.gson.JsonObject params) {
        applyJdtlsMove(path, item, params, () -> {});
    }

    /** As above; {@code onApplied} runs once the move is applied. */
    private void applyJdtlsMove(
            Path path, LspManager.CodeActionItem item, com.google.gson.JsonObject params, Runnable onApplied) {
        edits.beginReportedEdit();
        lspManager.jdtlsApplyEdit(
                path,
                "java/move",
                params,
                item.expectedDocuments(),
                (problems, proceed) -> {
                    if (moveProblemsConfirmer.test(problems)) {
                        proceed.run();
                    } else {
                        host.setStatus(tr("status.lsp.refactorCancelled", item.title()));
                    }
                },
                null,
                refactorPreview(item.title()),
                (ok, refused) -> {
                    if (!ok && refused != null) {
                        host.setError(refused);
                        return;
                    }
                    if (ok) {
                        onApplied.run();
                    }
                    edits.reportEdit(
                            ok,
                            tr("status.lsp.codeActionApplied", LspCoordinator.bareTitle(item.title())),
                            tr("status.lsp.codeActionFailed", item.title()));
                });
    }

    /** Asks whether to move despite the problems jdtls found; a field so a test can answer. */
    java.util.function.Predicate<String> moveProblemsConfirmer = this::confirmMoveProblems;

    private boolean confirmMoveProblems(String problems) {
        javafx.scene.control.ButtonType proceed = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.moveProblems.continue"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancel = Dialogs.cancel();
        javafx.scene.control.Alert confirm = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING, problems, cancel, proceed));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.moveProblems.title"));
        confirm.setHeaderText(tr("dialog.lsp.moveProblems.header"));
        confirm.getDialogPane().setMinWidth(520);
        // The move may break code: Enter cancels.
        Dialogs.defaultTo(confirm, cancel);
        return confirm.showAndWait().orElse(cancel) == proceed;
    }

    private com.google.gson.JsonObject refactorEdit(
            JdtlsRefactor.Request request, com.google.gson.@Nullable JsonArray arguments) {
        EditorBuffer buffer = lsp.activeLspBuffer();
        com.editora.lsp.FormattingIndent indent = buffer == null
                ? new com.editora.lsp.FormattingIndent(lsp.projectScope().tabSize(host.settings()), true)
                : LspDocumentFacts.indent(buffer);
        return JdtlsRefactor.refactorEditParams(
                request.name(), request.params(), indent.tabSize(), indent.insertSpaces(), arguments);
    }

    /** Asks the server where {@code source} can move, lets the user choose, and moves it there. */
    private void moveTo(
            Path path,
            LspManager.CodeActionItem item,
            String moveKind,
            @Nullable String source,
            com.google.gson.@Nullable JsonElement params,
            String prompt,
            java.util.function.Function<com.google.gson.JsonElement, List<JdtlsRefactor.Destination>> read) {
        lspManager.jdtlsRequest(
                path, "java/getMoveDestinations", JdtlsRefactor.moveParams(moveKind, source, params, null), answer -> {
                    String refused = JdtlsRefactor.errorMessage(answer);
                    List<JdtlsRefactor.Destination> targets = read.apply(answer);
                    if (refused != null || targets.isEmpty()) {
                        host.setError(refused != null ? refused : tr("status.lsp.refactorNoTarget", item.title()));
                        return;
                    }
                    String sourceRoot =
                            "moveResource".equals(moveKind) ? JdtlsRefactor.currentSourceRoot(answer) : null;
                    List<JdtlsRefactor.Destination> rows = new java.util.ArrayList<>(targets);
                    if (!JdtlsRefactor.inTestRoot(path.toString())) {
                        // main code does not move into src/test: those packages are for tests (B20)
                        rows.removeIf(d -> JdtlsRefactor.inTestRoot(d.detail()));
                    }
                    JdtlsRefactor.Destination newPackage = new JdtlsRefactor.Destination(
                            tr("picker.refactor.newPackage"), "", com.google.gson.JsonNull.INSTANCE);
                    if (sourceRoot != null && !com.editora.vfs.Vfs.isRemote(path)) {
                        rows.add(newPackage); // a package that does not exist yet (Java review B20)
                    }
                    pickDestination(item, prompt, rows, target -> {
                        if (target == newPackage && sourceRoot != null) {
                            moveToNewPackage(path, item, source, Path.of(sourceRoot));
                        } else {
                            applyJdtlsMove(
                                    path, item, JdtlsRefactor.moveParams(moveKind, source, params, target.raw()));
                        }
                    });
                });
    }

    /**
     * Move into a package that does not exist yet, typed as IntelliJ's Move Class takes it: its folder is
     * created under the current source root and announced to the server, which moves the file there by URI.
     * Before, the package's folder had to be made in the Project tree first.
     */
    private void moveToNewPackage(Path path, LspManager.CodeActionItem item, @Nullable String source, Path root) {
        host.promptText(item.title(), tr("prompt.refactor.newPackage"), "", typed -> {
            String name = typed == null ? "" : typed.strip();
            if (!JdtlsRefactor.isPackageName(name)) {
                host.setError(tr("status.lsp.refactorBadPackage", name));
                return;
            }
            if (!java.nio.file.Files.isDirectory(root)) { // never create a folder tree somewhere else (BN2)
                host.setError(tr("status.lsp.refactorPackageNotCreated", name, root.toString()));
                return;
            }
            Path folder = root.resolve(name.replace('.', java.io.File.separatorChar));
            try {
                java.nio.file.Files.createDirectories(folder);
            } catch (java.io.IOException | RuntimeException e) {
                host.setError(tr("status.lsp.refactorPackageNotCreated", name, String.valueOf(e.getMessage())));
                return;
            }
            lspManager.notifyWatchedFiles(List.of(new LspManager.WatchedFile(folder, LspManager.WatchedKind.CREATED)));
            com.google.gson.JsonObject params = JdtlsRefactor.moveParams("moveResource", source, null, null);
            params.addProperty("destination", folder.toUri().toString());
            LspRefactoringUndo.AppliedEdit before = undo.lastAppliedEdit();
            applyJdtlsMove(path, item, params, () -> undo.newPackageFolder(before, folder, root));
        });
    }

    /** Moves a static member or a nested type into another type of the same project. */
    private void moveToType(Path path, LspManager.CodeActionItem item, String moveKind, JdtlsRefactor.Request request) {
        lspManager.jdtlsRequest(
                path, "java/searchSymbols", JdtlsRefactor.searchTypesParams(request.info("projectName")), answer -> {
                    List<JdtlsRefactor.Destination> types =
                            JdtlsRefactor.types(answer, request.info("enclosingTypeName"));
                    if (types.isEmpty()) {
                        host.setError(tr("status.lsp.refactorNoTarget", item.title()));
                        return;
                    }
                    pickDestination(
                            item,
                            tr("picker.refactor.class"),
                            types,
                            target -> applyJdtlsMove(
                                    path,
                                    item,
                                    JdtlsRefactor.moveParams(
                                            moveKind, request.documentUri(), request.params(), target.raw())));
                });
    }

    /** Move Type: into a file of its own, or into another class — asked only when both are possible. */
    private void moveType(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        Runnable newFile = () -> applyJdtlsMove(
                path,
                item,
                JdtlsRefactor.moveParams("moveTypeToNewFile", request.documentUri(), request.params(), null));
        Runnable otherClass = () -> moveToType(path, item, "moveTypeToClass", request);
        boolean toFile = request.supportsDestination("newFile");
        boolean toClass = request.supportsDestination("class");
        if (toFile && toClass) {
            String fileLabel = tr("picker.refactor.moveType.newFile");
            LspCoordinator.pickOne(
                    host,
                    item.title(),
                    tr("picker.refactor.moveTarget"),
                    List.of(fileLabel, tr("picker.refactor.moveType.class")),
                    s -> s,
                    s -> "",
                    chosen -> (chosen.equals(fileLabel) ? newFile : otherClass).run());
        } else if (toClass) {
            otherClass.run();
        } else {
            newFile.run();
        }
    }

    private void pickDestination(
            LspManager.CodeActionItem item,
            String prompt,
            List<JdtlsRefactor.Destination> targets,
            java.util.function.Consumer<JdtlsRefactor.Destination> onChoose) {
        LspCoordinator.pickOne(
                host,
                item.title(),
                prompt,
                targets,
                JdtlsRefactor.Destination::label,
                d -> d.detail() == null ? "" : destinationDetail(d.detail()),
                onChoose);
    }

    /** A destination's folder relative to the project when it is a path there (re-review BN8), else as given. */
    private String destinationDetail(String detail) {
        try {
            Path p = Path.of(detail);
            return p.isAbsolute() && java.nio.file.Files.exists(p) ? projectRelative(p) : detail;
        } catch (java.nio.file.InvalidPathException e) {
            return detail;
        }
    }

    /** Extract Interface: which methods, the interface's name, and the package it is created in. */
    private void extractInterface(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        lspManager.jdtlsRequest(path, "java/checkExtractInterfaceStatus", request.params(), status -> {
            List<JdtlsGenerate.Candidate> members = JdtlsRefactor.interfaceMembers(status);
            List<JdtlsRefactor.Destination> offered = JdtlsRefactor.interfacePackages(status);
            List<JdtlsRefactor.Destination> mainOnly = JdtlsRefactor.inTestRoot(path.toString())
                    ? offered
                    : offered.stream()
                            .filter(d -> !JdtlsRefactor.inTestRoot(d.detail()))
                            .toList();
            // main code's interface is not offered src/test/java, as Move already does (third review BT4)
            List<JdtlsRefactor.Destination> packages = mainOnly.isEmpty() ? offered : mainOnly;
            if (members.isEmpty() || packages.isEmpty()) {
                host.setStatus(tr("status.lsp.generateNothing", item.title()));
                return;
            }
            String subType = JdtlsRefactor.subTypeName(status);
            MultiSelectPicker.show(
                    host.overlayHost(),
                    item.title(),
                    pickerRows(members),
                    chosen -> host.promptText(
                            item.title(),
                            tr("prompt.refactor.interfaceName"),
                            subType == null ? "" : subType + "Interface",
                            typed -> {
                                String name = typed.strip();
                                if (!JdtlsRefactor.isTypeName(name)) {
                                    host.setError(tr("status.lsp.refactorBadName", name));
                                    return;
                                }
                                java.util.function.Consumer<JdtlsRefactor.Destination> create = pkg -> applyJdtlsEdit(
                                        path,
                                        item,
                                        "java/getRefactorEdit",
                                        refactorEdit(
                                                request, JdtlsRefactor.extractInterfaceArguments(chosen, name, pkg)));
                                if (packages.size() == 1) {
                                    create.accept(packages.get(0));
                                } else {
                                    pickDestination(item, tr("picker.refactor.package"), packages, create);
                                }
                            }));
        });
    }

    /** Change Signature: the method's signature is edited as one line of text. */
    private void changeSignature(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        lspManager.jdtlsRequest(path, "java/getChangeSignatureInfo", request.params(), info -> {
            String refused = JdtlsRefactor.errorMessage(info);
            String signature = JdtlsRefactor.signatureText(info);
            if (refused != null || signature.isEmpty()) {
                host.setError(refused != null ? refused : tr("status.lsp.codeActionFailed", item.title()));
                return;
            }
            host.promptText(item.title(), tr("prompt.refactor.signature"), signature, typed -> {
                if (typed.strip().equals(signature)) {
                    return; // nothing changed
                }
                com.google.gson.JsonArray arguments = JdtlsRefactor.changeSignatureArguments(info, typed);
                if (arguments == null) {
                    host.setError(tr("status.lsp.refactorBadSignature"));
                    return;
                }
                edits.beginReportedEdit();
                lspManager.jdtlsApplyEdit(
                        path,
                        "java/getRefactorEdit",
                        refactorEdit(request, arguments),
                        item.expectedDocuments(),
                        null,
                        null,
                        refactorPreview(item.title()),
                        (ok, why) -> {
                            if (!ok && why != null) {
                                host.setError(why);
                                return;
                            }
                            edits.reportEdit(
                                    ok,
                                    tr("status.lsp.codeActionApplied", LspCoordinator.bareTitle(item.title())),
                                    tr("status.lsp.codeActionFailed", item.title()));
                        });
            });
        });
    }

    /**
     * The preview step of a refactoring that changes files the user cannot see — Change Signature updating
     * every caller, Move rewriting imports — which before applied unseen (Java review B20). The rename
     * preview's card: each file ticked, untick to leave it out; an edit confined to this file applies at once.
     */
    private java.util.function.BiConsumer<
                    com.editora.lsp.WorkspaceEditMapper.Mapped,
                    java.util.function.Consumer<com.editora.lsp.WorkspaceEditMapper.Mapped>>
            refactorPreview(String title) {
        return (mapped, apply) -> edits.withDiskView(WorkspaceEditFiles.resourcePaths(mapped), files -> {
            if (!com.editora.lsp.RenamePreview.worthPreviewing(mapped, WorkspaceEditFiles.workspaceDisk(files))) {
                apply.accept(mapped);
                return;
            }
            java.util.List<MultiSelectPicker.Item<Path>> rows = new java.util.ArrayList<>();
            java.util.Set<Path> listed = new java.util.LinkedHashSet<>();
            for (com.editora.lsp.RenamePreview.FileChange change :
                    com.editora.lsp.RenamePreview.summarise(mapped, WorkspaceEditFiles.workspaceDisk(files))) {
                rows.add(new MultiSelectPicker.Item<>(previewLabel(change), true, change.file()));
                listed.add(change.file());
            }
            MultiSelectPicker.show(
                    host.overlayHost(),
                    tr(
                            "lsp.refactor.previewTitle",
                            title.replaceAll("\\.\\.\\.$|…$", ""),
                            com.editora.lsp.RenamePreview.totalEdits(mapped),
                            rows.size()),
                    rows,
                    tr("picker.hint.apply"),
                    keep -> {
                        var kept = new java.util.LinkedHashSet<>(keep);
                        com.editora.lsp.WorkspaceEditMapper.Mapped filtered = java.util.Objects.requireNonNull(
                                com.editora.lsp.RenamePreview.filter(mapped, kept, listed),
                                "filter is null only for a null edit");
                        List<Path> covered = com.editora.lsp.RenamePreview.excludedButDeleted(filtered, kept, listed);
                        if (!covered.isEmpty()) {
                            edits.editRefused(
                                    covered,
                                    tr(
                                            "status.lsp.renameDeleteCoversKept",
                                            LspWorkspaceEdits.blockedFileNames(covered)));
                            return;
                        }
                        apply.accept(filtered);
                    });
        });
    }

    private static List<MultiSelectPicker.Item<JdtlsGenerate.Candidate>> pickerRows(
            List<JdtlsGenerate.Candidate> candidates) {
        List<MultiSelectPicker.Item<JdtlsGenerate.Candidate>> rows = new java.util.ArrayList<>();
        for (JdtlsGenerate.Candidate c : candidates) {
            rows.add(new MultiSelectPicker.Item<>(c.label(), c.preselected(), c));
        }
        return rows;
    }

    /**
     * Rename the symbol under the caret (#676): validate via {@code prepareRename} when the server supports
     * it (jdtls does — it also supplies the placeholder), prompt pre-filled with the current name, then
     * {@code textDocument/rename} — whose workspace edit (including a class rename's {@code RenameFile})
     * applies through {@link #applyWorkspaceEdits}.
     */
    void rename() {
        EditorBuffer b = lsp.activeLspBuffer();
        if (b == null) {
            return;
        }
        Path path = lsp.lspPath(b);
        if (path == null) {
            return;
        }
        if (!lspManager.supportsRename(path) || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.noRename"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        int line = area.getCurrentParagraph();
        int col = area.getCaretColumn();
        if (lspManager.supportsPrepareRename(path)) {
            lspManager.prepareRename(path, line, col, prep -> {
                if (!prep.allowed()) {
                    host.setStatus(tr("status.lsp.cannotRename"));
                    return;
                }
                String placeholder = !prep.placeholder().isBlank()
                        ? prep.placeholder()
                        : textInRange(b, prep.startLine(), prep.startCol(), prep.endLine(), prep.endCol());
                String old = placeholder.isBlank() ? LspCoordinator.wordAtCaret(area) : placeholder;
                // a command word (echo, cd) is no symbol of the user's: bash-language-server renamed it (C1)
                BashLspUi.beforeRename(
                        lspManager,
                        b,
                        path,
                        line,
                        col,
                        old,
                        host::setStatus,
                        rule -> promptAndRename(b, path, line, col, old, rule));
            });
        } else {
            promptAndRename(b, path, line, col, LspCoordinator.wordAtCaret(area), name -> null);
        }
    }

    private void promptAndRename(
            EditorBuffer b,
            Path path,
            int line,
            int col,
            String placeholder,
            java.util.function.Function<String, @Nullable String> refusal) {
        host.promptText(tr("dialog.lsp.rename.title"), tr("dialog.lsp.rename.label"), placeholder, newName -> {
            String name = newName == null ? "" : newName.trim();
            if (name.isEmpty() || name.equals(placeholder)) {
                return; // nothing to do
            }
            if (refusal.apply(name) instanceof String bad) {
                host.setStatus(bad); // not a name the language takes (a shell name, Bash review B11)
                return;
            }
            host.setStatus(tr("status.lsp.renaming"));
            edits.beginReportedEdit();
            lspManager.previewRename(path, line, col, name, (mapped, why) -> {
                if (mapped == null) { // with the server's reason when it gave one (re-review BN9)
                    edits.reportEdit(
                            false,
                            "",
                            why == null || why.isBlank()
                                    ? tr("status.lsp.renameFailed", name)
                                    : tr("status.lsp.renameFailedWhy", name, why));
                    return;
                }
                edits.withDiskView(WorkspaceEditFiles.resourcePaths(mapped), files -> {
                    if (!com.editora.lsp.RenamePreview.worthPreviewing(
                            mapped, WorkspaceEditFiles.workspaceDisk(files))) {
                        // Confined to this file: visible on screen and one undo away, so a confirmation step
                        // here would be friction with nothing to confirm.
                        applyRename(mapped, name);
                        return;
                    }
                    previewThenApply(mapped, name, files);
                });
            });
        });
    }

    /**
     * Shows which files a rename would change and applies only the ones left ticked.
     *
     * <p>A rename edits files the user cannot see, and every desktop IDE treats showing them first as what
     * makes refactoring trustworthy. Reuses {@link MultiSelectPicker} — the same checkbox card the jdtls
     * generators use — rather than a bespoke preview pane; a per-file choice is what the filtering can
     * honour safely, and a per-hunk one would need the diff viewer and a much larger change.
     */
    private void previewThenApply(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, String name, WorkspaceFileOperations files) {
        java.util.List<MultiSelectPicker.Item<java.nio.file.Path>> rows = new java.util.ArrayList<>();
        java.util.Set<Path> listed = new java.util.LinkedHashSet<>();
        for (com.editora.lsp.RenamePreview.FileChange change :
                com.editora.lsp.RenamePreview.summarise(mapped, WorkspaceEditFiles.workspaceDisk(files))) {
            rows.add(new MultiSelectPicker.Item<>(previewLabel(change), true, change.file()));
            listed.add(change.file());
        }
        MultiSelectPicker.show(
                host.overlayHost(),
                tr("lsp.rename.previewTitle", name, com.editora.lsp.RenamePreview.totalEdits(mapped), rows.size()),
                rows,
                tr("picker.hint.apply"),
                keep -> applyPreviewed(mapped, new java.util.LinkedHashSet<>(keep), listed, name));
    }

    /** One preview row: the path, then what happens to it — deletes and replacements spelled out. */
    String previewLabel(com.editora.lsp.RenamePreview.FileChange change) {
        String label = projectRelative(change.file());
        if (change.edits() > 0) {
            label += "  ·  " + tr("lsp.rename.editCount", change.edits());
        }
        if (change.renamedTo() != null) {
            // where it goes, not just its new name: a move keeps the name (re-review BN8)
            Path to = change.renamedTo();
            boolean sameFolder =
                    java.util.Objects.equals(to.getParent(), change.file().getParent());
            label += "  ·  " + tr("lsp.rename.movesTo", sameFolder ? to.getFileName() : projectRelative(to));
        }
        if (change.overwrites()) {
            label += "  ·  " + tr("lsp.rename.overwrites");
        }
        if (change.deletion() != null) {
            label += "  ·  "
                    + tr(
                            change.deletion() == com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY
                                    ? "lsp.rename.deletesFolder"
                                    : "lsp.rename.deletesFile");
        }
        return label;
    }

    /** {@code file} relative to the project, else with {@code ~} for the home folder: rows were absolute (B10). */
    private String projectRelative(Path file) {
        Path root = ops.lspProjectRoot();
        return root != null && file.startsWith(root) && !file.equals(root)
                ? root.relativize(file).toString()
                : ops.homeCollapsed(file.toString());
    }

    /**
     * Applies what the user left ticked. Refused when a delete that is still ticked would remove a path
     * the user unticked — a file kept in place inside a folder the edit deletes.
     */
    void applyPreviewed(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.Set<Path> keep,
            java.util.Set<Path> listed,
            String name) {
        com.editora.lsp.WorkspaceEditMapper.Mapped filtered = java.util.Objects.requireNonNull(
                com.editora.lsp.RenamePreview.filter(mapped, keep, listed), "filter is null only for a null edit");
        List<Path> covered = com.editora.lsp.RenamePreview.excludedButDeleted(filtered, keep, listed);
        if (!covered.isEmpty()) {
            edits.beginReportedEdit();
            edits.editRefused(
                    covered, tr("status.lsp.renameDeleteCoversKept", LspWorkspaceEdits.blockedFileNames(covered)));
            edits.reportEdit(false, "", tr("status.lsp.renameFailed", name));
            return;
        }
        applyRename(filtered, name);
    }

    /** Applies a (possibly filtered) rename edit and reports the outcome. */
    private void applyRename(com.editora.lsp.WorkspaceEditMapper.Mapped mapped, String name) {
        edits.beginReportedEdit();
        java.util.Set<String> errorsBefore = errorsIn(mapped);
        edits.applyWorkspaceEditsAsync(mapped, ok -> {
            edits.reportEdit(ok, tr("status.lsp.renamed", name), tr("status.lsp.renameFailed", name));
            if (ok) {
                checkRenameConflicts(mapped, errorsBefore);
            }
        });
    }

    /** How long after a rename its files' diagnostics are compared with what they were (B17). */
    static final javafx.util.Duration RENAME_CHECK_DELAY = javafx.util.Duration.seconds(2);

    /**
     * jdtls applies a rename onto a name that is already taken (a second local {@code canvas}) without a word: the
     * conflicts JDT finds are not part of LSP's rename. So once its files have been re-checked, an error that
     * was not there before is reported, with the way back (Java review B17).
     */
    private void checkRenameConflicts(com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.Set<String> before) {
        var check = new javafx.animation.PauseTransition(RENAME_CHECK_DELAY);
        check.setOnFinished(e -> {
            List<String> added = new java.util.ArrayList<>(renamedFilesErrors(mapped));
            added.removeAll(before);
            if (!added.isEmpty()) {
                String first = added.get(0);
                host.setError(tr(
                        "status.lsp.renameIntroducedErrors",
                        added.size(),
                        first.substring(first.indexOf('\u0000') + 1),
                        com.editora.editor.MenuTitles.of(tr("command.lsp.undoRefactoring"))));
            }
        });
        check.play();
    }

    private java.util.Set<String> errorsIn(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        return new java.util.HashSet<>(renamedFilesErrors(mapped));
    }

    /** The error messages in {@code mapped}'s files (where its renames put them), as "file\0message". */
    private List<String> renamedFilesErrors(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        java.util.Set<Path> files = new java.util.LinkedHashSet<>();
        for (var edit : mapped.edits()) {
            files.add(ops.canonicalize(edit.file()));
        }
        for (var rename : mapped.renames()) {
            files.add(ops.canonicalize(rename.to()));
        }
        List<String> out = new java.util.ArrayList<>();
        for (Path file : files) {
            for (LspDiagnostic d : lsp.problems().getOrDefault(file, List.of())) {
                if (d.severity() == LspDiagnostic.Severity.ERROR) {
                    out.add(file.getFileName() + "\u0000" + d.message());
                }
            }
        }
        return out;
    }

    /** The document text inside a 0-based LSP range (single-line expected), or "" when out of bounds. */
    private static String textInRange(EditorBuffer b, int sl, int sc, int el, int ec) {
        try {
            CodeArea a = b.getFocusedArea();
            int from = a.getAbsolutePosition(sl, sc);
            int to = a.getAbsolutePosition(el, ec);
            return to > from ? a.getText(from, to) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
