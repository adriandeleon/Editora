package com.editora.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.stage.FileChooser;

import com.editora.config.PathDisplay;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.XmlGrammarBinding;
import com.editora.lsp.XmlServer;
import com.editora.lsp.XmlServerSettings;
import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The XML server's own commands and client commands (XML review B15, D6): revalidate the current file or every
 * open one, bind a document to an XSD/DTD/RELAX NG grammar (lemminx's "Bind to existing grammar/schema" quick fix,
 * its code lens, and a palette command), generate a grammar from a document and bind it (XML review 2 C8), and the
 * client commands lemminx asks the editor for —
 * {@code xml.show.references}, {@code xml.open.uri}, {@code xml.open.binding.wizard}. Owned by
 * {@link LspCoordinator}; every method runs on the FX thread.
 */
final class XmlLspActions {

    /** Where the user picks a grammar and how it is bound; a test answers without a dialog. */
    interface Prompts {
        /** The grammar file to bind {@code xmlFile} to, or null when cancelled. */
        @Nullable
        Path chooseGrammar(Path xmlFile);

        /** Which of {@code types} ({@link XmlServer#bindingTypes}) to bind {@code grammar} by; null when cancelled. */
        void chooseBinding(Path grammar, List<String> types, Consumer<@Nullable String> chosen);

        /** Which of lemminx's generate-a-grammar fixes to apply to {@code xmlFile}; nothing when cancelled. */
        void chooseGenerated(
                Path xmlFile, List<LspManager.CodeActionItem> fixes, Consumer<LspManager.CodeActionItem> chosen);
    }

    /** Opens a file at a 0-based line and column. */
    interface Opener {
        void openAndGoto(@Nullable Path file, int line, int column);
    }

    private final CoordinatorHost host;
    private final LspManager lsp;
    private final Opener opener;
    private final Supplier<@Nullable EditorBuffer> activeLspBuffer;
    private final Function<EditorBuffer, @Nullable Path> lspPath;
    private final Runnable findReferences;
    private final BiConsumer<Path, LspManager.CodeActionItem> applyAction;
    private Prompts prompts = new DialogPrompts();

    XmlLspActions(
            CoordinatorHost host,
            LspManager lsp,
            Opener opener,
            Supplier<@Nullable EditorBuffer> activeLspBuffer,
            Function<EditorBuffer, @Nullable Path> lspPath,
            Runnable findReferences,
            BiConsumer<Path, LspManager.CodeActionItem> applyAction) {
        this.host = host;
        this.applyAction = applyAction;
        this.lsp = lsp;
        this.opener = opener;
        this.activeLspBuffer = activeLspBuffer;
        this.lspPath = lspPath;
        this.findReferences = findReferences;
    }

    /** TEST SEAM: answers the grammar and binding questions without dialogs. */
    void setPromptsForTest(Prompts prompts) {
        this.prompts = prompts;
    }

    /** The active XML file and its text. */
    private record Active(Path path, String text) {}

    /** The active buffer's path when the XML server serves it; says why not and answers null otherwise. */
    private @Nullable Path activeXmlPath() {
        Active active = activeXml();
        return active == null ? null : active.path();
    }

    private @Nullable Active activeXml() {
        EditorBuffer b = activeLspBuffer.get(); // says "No language server for this file" itself
        Path path = b == null ? null : lspPath.apply(b);
        if (b == null || path == null) {
            return null;
        }
        if (!XmlServerSettings.isLemminx(lsp.managedServerId(path))) { // a pom on the Maven server too (D15)
            host.setStatus(tr("status.xml.notXmlServer"));
            return null;
        }
        String text = b.text();
        lsp.changeDocument(path, text); // what the user sees is what is validated or bound
        return new Active(path, text);
    }

    /**
     * Says so and answers true when {@code text} is already bound to a grammar (XML review 2 C7): lemminx would add
     * a second binding — duplicate {@code xsi} attributes, or a second DOCTYPE — and report success.
     */
    private boolean refuseBound(Path path, String text) {
        XmlGrammarBinding.Binding bound = XmlGrammarBinding.existing(text);
        if (bound == null) {
            return false;
        }
        host.setStatus(tr("status.xml.alreadyBound", PathDisplay.fileName(path), bound.grammar(), bound.how()));
        return true;
    }

    /** {@code xml.validateFile}: lemminx revalidates the active document (after its schema changed, say). */
    void validateCurrent() {
        Path path = activeXmlPath();
        if (path != null) {
            run(path, XmlServer.VALIDATE_CURRENT, List.of(Map.of("uri", lsp.serverUri(path))), "status.xml.validated");
        }
    }

    /** {@code xml.validateAllFiles}: lemminx forgets cached grammars and revalidates every open document. */
    void validateAll() {
        Path path = activeXmlPath();
        if (path != null) {
            run(path, XmlServer.VALIDATE_ALL, List.of(), "status.xml.validatedAll");
        }
    }

    private void run(Path path, String command, List<Object> args, String doneKey) {
        if (!lsp.supportsCommand(path, command)) {
            host.setStatus(tr("status.xml.commandUnavailable", command));
            return;
        }
        lsp.executeCommand(path, command, args, (result, error) -> {
            if (error != null) {
                host.setError(tr("status.xml.commandFailed", command, reason(error)));
            } else {
                host.setStatus(tr(doneKey, PathDisplay.fileName(path)));
            }
        });
    }

    /**
     * What Go to Definition says when it found nothing: "missing.xsl does not exist" when the include href at the
     * caret names a file that is not there ({@code missing}, XML/pom review 3, C26), else "No definition found".
     */
    static String noDefinition(@Nullable String missing) {
        return missing == null ? tr("status.lsp.noDefinition") : tr("status.xml.includeMissing", missing);
    }

    /** {@code xml.bindGrammar}: binds the active XML document to a grammar the user picks, when it has none yet. */
    void bindGrammar() {
        Active active = activeXml();
        if (active != null && !refuseBound(active.path(), active.text())) {
            bindingWizard(active.path(), XmlGrammarBinding.hasDoctype(active.text()));
        }
    }

    /**
     * XML: Generate Grammar… (XML review 2 C8): lemminx's "Generate … and bind" fixes for the active document, which
     * it offers only on its no-grammar diagnostic — never reported while "Files without a grammar" is Nothing, the
     * default. Asks for them with that diagnostic at the root element, lists them and applies the one picked.
     */
    void generateGrammar() {
        Active active = activeXml();
        if (active == null) {
            return;
        }
        Path path = active.path();
        if (com.editora.vfs.Vfs.isRemote(path)) {
            host.setStatus(tr("status.xml.generateRemote"));
            return;
        }
        if (refuseBound(path, active.text())) {
            return;
        }
        int[] root = XmlGrammarBinding.rootName(active.text());
        if (root == null) {
            host.setStatus(tr("status.xml.generateNoRoot"));
            return;
        }
        int[] start = XmlGrammarBinding.lineColumn(active.text(), root[0]);
        int[] end = XmlGrammarBinding.lineColumn(active.text(), root[1]);
        var range = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(start[0], start[1]), new org.eclipse.lsp4j.Position(end[0], end[1]));
        // A DOCTYPE with only an internal subset is no binding, but a second DOCTYPE is not well-formed (C24)
        boolean doctype = XmlGrammarBinding.hasDoctype(active.text());
        lsp.codeActionsFor(path, XmlServer.noGrammarDiagnostic(range), items -> {
            List<LspManager.CodeActionItem> fixes = items.stream()
                    .filter(item -> XmlServer.generatesGrammar(item.raw()))
                    .filter(item -> !doctype || !XmlServer.bindsWithDoctype(item.raw()))
                    .toList();
            if (fixes.isEmpty()) {
                host.setStatus(tr("status.xml.generateNone"));
            } else {
                prompts.chooseGenerated(path, fixes, item -> applyAction.accept(path, item));
            }
        });
    }

    /**
     * lemminx's binding wizard: pick an XSD, DTD or RELAX NG file, then how to bind it (the document's own syntax or
     * an {@code xml-model} instruction), and apply the edit lemminx computes for it.
     */
    void bindingWizard(Path xmlFile) {
        bindingWizard(xmlFile, false);
    }

    /**
     * The wizard for a document that has a DOCTYPE already ({@code doctype}): a DTD is then bound only by
     * {@code xml-model}, since a second DOCTYPE is not well-formed.
     */
    private void bindingWizard(Path xmlFile, boolean doctype) {
        if (com.editora.vfs.Vfs.isRemote(xmlFile)) {
            host.setStatus(tr("status.xml.bindRemote"));
            return;
        }
        Path grammar = prompts.chooseGrammar(xmlFile);
        if (grammar == null) {
            return;
        }
        String grammarName = String.valueOf(grammar.getFileName());
        List<String> types =
                doctype && grammarName.toLowerCase(java.util.Locale.ROOT).endsWith(".dtd")
                        ? List.of(XmlServer.BINDING_XML_MODEL)
                        : XmlServer.bindingTypes(grammarName);
        Consumer<@Nullable String> bind = type -> {
            if (type == null) {
                return;
            }
            lsp.xmlAssociateGrammar(xmlFile, grammar, type, ok -> {
                String why = lsp.lastCodeActionFailure();
                if (ok) {
                    host.setStatus(tr("status.xml.bound", PathDisplay.fileName(xmlFile), grammar.getFileName()));
                } else {
                    host.setError(tr(
                            "status.xml.bindFailed",
                            grammar.getFileName(),
                            why == null ? tr("status.xml.bindNoEdit") : why));
                }
            });
        };
        if (types.size() == 1) {
            bind.accept(types.get(0));
        } else {
            prompts.chooseBinding(grammar, types, bind);
        }
    }

    /** Takes over a picked code action whose command is a client command ("Bind to existing grammar/schema"). */
    boolean runsCodeAction(Path path, LspManager.CodeActionItem item) {
        String command = LspManager.commandIdOf(item.raw());
        if (!XmlServer.BINDING_WIZARD.equals(command) || !XmlServerSettings.isLemminx(lsp.managedServerId(path))) {
            return false;
        }
        bindingWizard(path);
        return true;
    }

    /**
     * A client command lemminx asks for ({@code xml/executeClientCommand}, or an actionable notification's button);
     * answers null, which is all lemminx expects of these.
     */
    CompletableFuture<Object> clientCommand(String serverId, String command, List<Object> arguments) {
        if (!XmlServerSettings.isLemminx(serverId)) {
            return CompletableFuture.completedFuture(null);
        }
        List<JsonElement> args = arguments.stream().map(LspManager::asJson).toList();
        String uri = args.isEmpty() ? null : string(args.get(0));
        switch (command) {
            case XmlServer.OPEN_URI -> {
                Path file = uri == null ? null : fileOf(uri);
                if (file != null) {
                    opener.openAndGoto(file, 0, 0);
                } else if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://"))) {
                    host.openExternalUrl(uri);
                }
            }
            case XmlServer.BINDING_WIZARD -> {
                Path file = uri == null ? null : fileOf(uri);
                if (file != null) {
                    bindingWizard(file);
                }
            }
            case XmlServer.SHOW_REFERENCES -> {
                Path file = uri == null ? null : fileOf(uri);
                JsonElement at = args.size() > 1 ? args.get(1) : null;
                if (file != null && at != null && at.isJsonObject()) {
                    int line = intOf(at, "line");
                    int character = intOf(at, "character");
                    opener.openAndGoto(file, line, character);
                    Platform.runLater(findReferences); // after the open has moved the caret (it runs later too)
                }
            }
            default -> {
                // not one Editora knows: answered null, as the protocol allows
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /** A failed request's reason: the server's message, or "timeout". */
    private static String reason(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return cause instanceof java.util.concurrent.TimeoutException ? "timeout" : String.valueOf(cause.getMessage());
    }

    private static @Nullable String string(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int intOf(JsonElement object, String member) {
        JsonElement v = object.getAsJsonObject().get(member);
        return v != null && v.isJsonPrimitive() ? Math.max(0, v.getAsInt()) : 0;
    }

    /** The local file a {@code file:} URI names, or null. */
    private static @Nullable Path fileOf(String uri) {
        if (!uri.startsWith("file:")) {
            return null;
        }
        try {
            return Path.of(java.net.URI.create(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The real questions: a file chooser, then a picker of binding kinds. */
    private final class DialogPrompts implements Prompts {

        @Override
        public @Nullable Path chooseGrammar(Path xmlFile) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(tr("dialog.xml.bindTitle", PathDisplay.fileName(xmlFile)));
            Path dir = xmlFile.getParent();
            if (dir != null && dir.toFile().isDirectory()) {
                chooser.setInitialDirectory(dir.toFile());
            }
            chooser.getExtensionFilters()
                    .addAll(
                            new FileChooser.ExtensionFilter(tr("dialog.xml.grammars"), "*.xsd", "*.dtd", "*.rng"),
                            new FileChooser.ExtensionFilter("XML Schema (*.xsd)", "*.xsd"),
                            new FileChooser.ExtensionFilter("DTD (*.dtd)", "*.dtd"),
                            new FileChooser.ExtensionFilter("RELAX NG (*.rng)", "*.rng"));
            File f = chooser.showOpenDialog(host.window());
            return f == null ? null : f.toPath();
        }

        @Override
        public void chooseBinding(Path grammar, List<String> types, Consumer<@Nullable String> chosen) {
            boolean dtd = String.valueOf(grammar.getFileName())
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".dtd");
            QuickOpen<String> picker = new QuickOpen<>(
                    tr("picker.xml.bindingType", grammar.getFileName()),
                    tr("palette.setting.pick"),
                    () -> types,
                    type -> XmlServer.BINDING_STANDARD.equals(type)
                            ? tr(dtd ? "picker.xml.binding.doctype" : "picker.xml.binding.schemaLocation")
                            : tr("picker.xml.binding.xmlModel"),
                    type -> XmlServer.BINDING_STANDARD.equals(type)
                            ? (dtd ? "<!DOCTYPE … SYSTEM \"…\">" : "xsi:noNamespaceSchemaLocation / xsi:schemaLocation")
                            : "<?xml-model href=\"…\"?>",
                    chosen::accept);
            picker.setPreview(type -> {}, () -> {});
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        }

        @Override
        public void chooseGenerated(
                Path xmlFile, List<LspManager.CodeActionItem> fixes, Consumer<LspManager.CodeActionItem> chosen) {
            QuickOpen<LspManager.CodeActionItem> picker = new QuickOpen<>(
                    tr("picker.xml.generateGrammar", PathDisplay.fileName(xmlFile)),
                    tr("palette.setting.pick"),
                    () -> fixes,
                    LspManager.CodeActionItem::title,
                    item -> "",
                    item -> {
                        if (item != null) {
                            chosen.accept(item);
                        }
                    });
            picker.setPreview(item -> {}, () -> {});
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        }
    }
}
