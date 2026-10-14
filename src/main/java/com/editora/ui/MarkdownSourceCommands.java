package com.editora.ui;

import java.lang.ref.WeakReference;
import java.util.function.Function;

import com.editora.command.Command;
import com.editora.editor.EditorBuffer;
import com.editora.editor.MarkdownFormatting;
import com.editora.editor.MarkdownFormatting.Outcome;
import com.editora.markdown.MarkdownLintOptions;

import static com.editora.i18n.Messages.tr;

/**
 * The Markdown commands that work on a document's structure rather than on the text at the caret: going to
 * a heading anywhere in the project, Format Document / Selection, heading numbers. One call from the
 * window's command registrar, like {@link MarkdownSettingCommands}.
 */
final class MarkdownSourceCommands {

    private MarkdownSourceCommands() {}

    static void register(WindowCommandRegistrar.Host host) {
        host.registry()
                .register(Command.of(
                        "project.gotoMarkdownHeading",
                        () -> host.indexCoordinator().headingPicker.show()));
        // The formatter follows the project's lint configuration. The lookup depends on the shared settings
        // and the file's folder, not on the window, so the last window to register serves every buffer — held
        // weakly, so a closed window is not kept alive by it.
        WeakReference<WindowCommandRegistrar.Host> window = new WeakReference<>(host);
        MarkdownFormatting.setOptionsSource(buffer -> {
            WindowCommandRegistrar.Host h = window.get();
            return h == null ? MarkdownLintOptions.DEFAULT : h.previews().lint().options(buffer);
        });
        host.registry()
                .register(Command.of(
                        "tool.backlinks",
                        () -> host.indexCoordinator().backlinks.toggle()));
        host.registry()
                .register(Command.of(
                        "markdown.showBacklinks",
                        () -> host.indexCoordinator().backlinks.show()));
        // Completion after "[[" lists the notes of the project that holds the document being edited.
        com.editora.editor.MarkdownCompletions.setWikiSource(
                document -> WikiLinks.enabled ? WikiLinks.targets(document) : com.editora.markdown.WikiTargets.EMPTY);
        edit(host, "markdown.formatDocument", MarkdownFormatting::formatDocument, "status.markdown.formatted");
        edit(host, "markdown.formatSelection", MarkdownFormatting::formatSelection, "status.markdown.formatted");
        edit(host, "markdown.numberHeadings", MarkdownFormatting::numberHeadings, "status.markdown.headingsNumbered");
        edit(
                host,
                "markdown.removeHeadingNumbers",
                MarkdownFormatting::removeHeadingNumbers,
                "status.markdown.headingNumbersRemoved");
    }

    /** A whole-document edit: Markdown and editable (said by {@code withMarkdown}), not preview-only, undoable. */
    private static void edit(
            WindowCommandRegistrar.Host host, String id, Function<EditorBuffer, Outcome> action, String doneKey) {
        host.registry()
                .register(Command.of(
                        id,
                        () -> host.previews().withMarkdown(buffer -> {
                            if (buffer.isPreviewOnly()) {
                                host.setStatus(tr("status.previewCannotEdit"));
                                return;
                            }
                            if (!NoUndoGuard.allow(buffer, tr("command." + id))) {
                                return;
                            }
                            Outcome outcome = action.apply(buffer);
                            if (outcome != Outcome.REFUSED) {
                                host.setStatus(
                                        tr(outcome == Outcome.CHANGED ? doneKey : "status.markdown.formatNothing"));
                            }
                        })));
    }
}
