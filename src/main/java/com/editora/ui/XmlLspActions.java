package com.editora.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.stage.FileChooser;

import com.editora.config.PathDisplay;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.XmlServer;
import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The XML server's own commands and client commands (XML review B15, D6): revalidate the current file or every
 * open one, bind a document to an XSD/DTD/RELAX NG grammar (lemminx's "Bind to existing grammar/schema" quick fix,
 * its code lens, and a palette command), and the client commands lemminx asks the editor for —
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
    private Prompts prompts = new DialogPrompts();

    XmlLspActions(
            CoordinatorHost host,
            LspManager lsp,
            Opener opener,
            Supplier<@Nullable EditorBuffer> activeLspBuffer,
            Function<EditorBuffer, @Nullable Path> lspPath,
            Runnable findReferences) {
        this.host = host;
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

    /** The active buffer's path when the XML server serves it; says why not and answers null otherwise. */
    private @Nullable Path activeXmlPath() {
        EditorBuffer b = activeLspBuffer.get(); // says "No language server for this file" itself
        Path path = b == null ? null : lspPath.apply(b);
        if (b == null || path == null) {
            return null;
        }
        if (!XmlServer.isXml(lsp.managedServerId(path))) {
            host.setStatus(tr("status.xml.notXmlServer"));
            return null;
        }
        lsp.changeDocument(path, b.text()); // what the user sees is what is validated or bound
        return path;
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

    /** {@code xml.bindGrammar}: binds the active XML document to a grammar the user picks. */
    void bindGrammar() {
        Path path = activeXmlPath();
        if (path != null) {
            bindingWizard(path);
        }
    }

    /**
     * lemminx's binding wizard: pick an XSD, DTD or RELAX NG file, then how to bind it (the document's own syntax or
     * an {@code xml-model} instruction), and apply the edit lemminx computes for it.
     */
    void bindingWizard(Path xmlFile) {
        if (com.editora.vfs.Vfs.isRemote(xmlFile)) {
            host.setStatus(tr("status.xml.bindRemote"));
            return;
        }
        Path grammar = prompts.chooseGrammar(xmlFile);
        if (grammar == null) {
            return;
        }
        List<String> types = XmlServer.bindingTypes(String.valueOf(grammar.getFileName()));
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
        if (!XmlServer.BINDING_WIZARD.equals(command) || !XmlServer.isXml(lsp.managedServerId(path))) {
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
        if (!XmlServer.isXml(serverId)) {
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
    }
}
