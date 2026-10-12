package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LanguageRegistry;
import com.editora.markdown.MarkdownAnchors;

import static com.editora.i18n.Messages.tr;

/**
 * Opens a link clicked in a buffer through the {@link ExternalLinks} choke point, and follows the ones that
 * name a place inside a Markdown document — {@code #installation}, or {@code guide.md#installation} — by
 * moving the caret to the heading the fragment names ({@link MarkdownAnchors}). The entries of a generated
 * table of contents are all such links.
 *
 * <p>The move is an ordinary jump: it is recorded in the navigation history, so Back returns to the link.
 * A fragment nothing in the document answers to still opens the file (when the link names one) and says so
 * in the status bar; it never fails silently and never guesses a nearby heading.
 */
final class HeadingLinks {

    /** Largest file read from disk to find a heading in; a bigger target is opened at its top. */
    static final long MAX_SCAN_BYTES = 4L * 1024 * 1024;

    /** Opens a file (when it has no tab) and moves the caret to a 0-based line and column, as a jump. */
    @FunctionalInterface
    interface Jump {
        void to(Path file, int line, int column);
    }

    private HeadingLinks() {}

    /**
     * @param buffer the buffer the link is in
     * @param openBuffer the open buffer of a file, or null when it has no tab
     */
    static void open(
            String link,
            EditorBuffer buffer,
            Path projectRoot,
            javafx.application.HostServices hostServices,
            FileWorkflowCoordinator workflows,
            Function<Path, EditorBuffer> openBuffer,
            Jump jump,
            Consumer<String> status) {
        ExternalLinks.open(
                link,
                buffer.getPath(),
                projectRoot,
                hostServices,
                workflows::openPath,
                (file, fragment) -> follow(buffer, file, fragment, workflows::openThen, openBuffer, jump, status),
                status);
    }

    /** Goes to {@code fragment} in {@code file}, or in {@code clicked} itself when {@code file} is null. */
    static void follow(
            EditorBuffer clicked,
            Path file,
            String fragment,
            java.util.function.BiConsumer<Path, Runnable> openThen,
            Function<Path, EditorBuffer> openBuffer,
            Jump jump,
            Consumer<String> status) {
        if (file == null) {
            int line = clicked.isMarkdown() ? MarkdownAnchors.lineOf(clicked.getContent(), fragment) : -1;
            if (line < 0) {
                status.accept(tr("status.link.noAnchor", "#" + fragment));
            } else if (clicked.getPath() == null) {
                clicked.jumpToLine(line); // an unsaved buffer has no place in the jump history
            } else {
                jump.to(clicked.getPath(), line, 0);
            }
            return;
        }
        int line = lineIn(file, openBuffer.apply(file), fragment);
        if (line < 0) {
            // Said once the file is showing: the load reports "Opened …" and would replace it otherwise.
            openThen.accept(file, () -> status.accept(tr("status.link.noAnchor", "#" + fragment)));
        } else {
            jump.to(file, line, 0);
        }
    }

    /** The line {@code fragment} names in {@code file}: from its open buffer (unsaved edits count), else from disk. */
    private static int lineIn(Path file, EditorBuffer open, String fragment) {
        if (open != null && !open.isLoading()) {
            return open.isMarkdown() ? MarkdownAnchors.lineOf(open.getContent(), fragment) : -1;
        }
        if (!"markdown".equals(LanguageRegistry.forFileName(file.toString()))) {
            return -1;
        }
        try {
            return Files.size(file) > MAX_SCAN_BYTES ? -1 : MarkdownAnchors.lineOf(Files.readString(file), fragment);
        } catch (IOException | RuntimeException e) {
            return -1; // unreadable or not UTF-8: the file still opens, at its top
        }
    }
}
