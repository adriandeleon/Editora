package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

import com.editora.editor.EditorBuffer;
import com.editora.editor.markdown.WikiLinkTargets;
import com.editora.markdown.MarkdownAnchors;
import com.editora.text.LanguageRegistry;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Opens a link clicked in a buffer through the {@link ExternalLinks} choke point, and follows the ones that
 * name a place inside a Markdown document — {@code #installation}, or {@code guide.md#installation} — by
 * moving the caret to the heading the fragment names ({@link MarkdownAnchors}). The entries of a generated
 * table of contents are all such links.
 *
 * <p>A buffer that shows its preview has the preview scrolled to the same heading
 * ({@code EditorBuffer.scrollPreviewToAnchor}), so a link clicked in the preview — a table of contents entry —
 * visibly goes somewhere whether or not the source is on screen.
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
        void to(@Nullable Path file, int line, int column);
    }

    private HeadingLinks() {}

    /**
     * @param buffer the buffer the link is in
     * @param openBuffer the open buffer of a file, or null when it has no tab
     */
    static void open(
            String link,
            EditorBuffer buffer,
            @Nullable Path projectRoot,
            javafx.application.@Nullable HostServices hostServices,
            FileWorkflowCoordinator workflows,
            Function<Path, @Nullable EditorBuffer> openBuffer,
            Jump jump,
            Consumer<String> status) {
        if (WikiLinks.isWikiForm(link)) {
            openWiki(WikiLinks.parse(link), buffer, workflows, openBuffer, jump, status);
            return;
        }
        if (WikiLinkTargets.isWikiAddress(link)) {
            followWiki(link, buffer, workflows::openPath, workflows::openThen, openBuffer, jump, status);
            return;
        }
        ExternalLinks.open(
                link,
                buffer.getPath(),
                projectRoot,
                hostServices,
                workflows::openPath,
                (file, fragment) -> follow(buffer, file, fragment, workflows::openThen, openBuffer, jump, status),
                status);
    }

    /** Follows a wiki link written in {@code buffer}: to its note, and to the heading when it names one. */
    private static void openWiki(
            WikiLinks.Parsed wiki,
            EditorBuffer buffer,
            FileWorkflowCoordinator workflows,
            Function<Path, @Nullable EditorBuffer> openBuffer,
            Jump jump,
            Consumer<String> status) {
        Path here = buffer.getPath() == null
                ? null
                : buffer.getPath().toAbsolutePath().normalize();
        Path file = wiki.target().isEmpty() ? here : WikiLinks.resolve(here, wiki.target());
        if (file == null && !wiki.target().isEmpty()) {
            status.accept(tr("status.wikilink.unresolved", wiki.target()));
            return;
        }
        boolean same = file == null || file.equals(here);
        if (wiki.heading().isEmpty()) {
            if (!same) {
                workflows.openPath(file);
            }
            return;
        }
        // A wiki link names a heading by its title; the anchor machinery goes by its slug.
        follow(
                buffer,
                same ? null : file,
                com.editora.markdown.MarkdownToc.slug(wiki.heading()),
                workflows::openThen,
                openBuffer,
                jump,
                status);
    }

    /**
     * Follows a wiki link ({@code wikilink:note#Heading}, {@link WikiLinkTargets#address}): the file the
     * resolver finds for it is opened — at the heading, when one is named — and a link nothing answers to is
     * said in the status bar, never guessed at.
     */
    static void followWiki(
            String address,
            EditorBuffer clicked,
            Consumer<Path> openFile,
            java.util.function.BiConsumer<Path, Runnable> openThen,
            Function<Path, @Nullable EditorBuffer> openBuffer,
            Jump jump,
            Consumer<String> status) {
        String target = WikiLinkTargets.targetOf(address);
        Path folder = clicked.getPath() == null ? null : clicked.getPath().getParent();
        Path file = WikiLinkTargets.resolve(folder, target);
        if (file == null) {
            status.accept(tr("status.wikilink.unresolved", target));
            return;
        }
        String fragment = WikiLinkTargets.fragmentOf(address);
        if (fragment == null) {
            openFile.accept(file);
        } else {
            follow(clicked, file, fragment, openThen, openBuffer, jump, status);
        }
    }

    /** Goes to {@code fragment} in {@code file}, or in {@code clicked} itself when {@code file} is null. */
    static void follow(
            EditorBuffer clicked,
            @Nullable Path file,
            String fragment,
            java.util.function.BiConsumer<Path, Runnable> openThen,
            Function<Path, @Nullable EditorBuffer> openBuffer,
            Jump jump,
            Consumer<String> status) {
        int[] place = linePlace(fragment);
        if (place != null) {
            // #L28,22 — a line (and column), as a language server's hover links name a place; a Markdown heading
            // that happens to answer to the same fragment still wins
            int heading = file == null
                    ? clicked.isMarkdown() ? MarkdownAnchors.lineOf(clicked.getContent(), fragment) : -1
                    : lineIn(file, openBuffer.apply(file), fragment);
            if (heading < 0) {
                if (file == null && clicked.getPath() == null) {
                    clicked.jumpToLine(place[0]);
                } else {
                    jump.to(file == null ? clicked.getPath() : file, place[0], place[1]);
                }
                return;
            }
        }
        if (file == null) {
            int line = clicked.isMarkdown() ? MarkdownAnchors.lineOf(clicked.getContent(), fragment) : -1;
            if (line >= 0 && clicked.getPath() == null) {
                clicked.jumpToLine(line); // an unsaved buffer has no place in the jump history
            } else if (line >= 0) {
                jump.to(clicked.getPath(), line, 0);
            }
            // The preview goes there too — it is all there is to see when only the preview is showing — and
            // knows anchors the source scan does not (a heading inside a quote or a list item).
            boolean shown = clicked.scrollPreviewToAnchor(fragment);
            if (line < 0 && !shown) {
                status.accept(tr("status.link.noAnchor", "#" + fragment));
            }
            return;
        }
        int line = lineIn(file, openBuffer.apply(file), fragment);
        if (line >= 0) {
            jump.to(file, line, 0);
        }
        // Once the file is showing: its preview is taken to the heading as well, and a fragment nothing
        // answered to is said then (the load reports "Opened …" and would replace an earlier message).
        openThen.accept(file, () -> {
            EditorBuffer opened = openBuffer.apply(file);
            boolean shown = opened != null && opened.scrollPreviewToAnchor(fragment);
            if (line < 0 && !shown) {
                status.accept(tr("status.link.noAnchor", "#" + fragment));
            }
        });
    }

    /** {@code #L<line>[,<column>]}, optionally followed by a range end ({@code -L28,30}), 1-based. */
    private static final java.util.regex.Pattern LINE_FRAGMENT =
            java.util.regex.Pattern.compile("L(\\d{1,9})(?:[,:C](\\d{1,9}))?(?:-L?\\d{1,9}(?:[,:C]\\d{1,9})?)?");

    /**
     * The 0-based {@code {line, column}} a {@code L<line>[,<column>]} fragment names (lemminx-maven's "managed in
     * …#L28,22-L28,22", GitHub's {@code #L28}), or null for any other fragment. The hover link opened the parent
     * pom at its top (review 2, E7). Pure.
     */
    static int @Nullable [] linePlace(@Nullable String fragment) {
        if (fragment == null) {
            return null;
        }
        java.util.regex.Matcher m = LINE_FRAGMENT.matcher(fragment.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            int line = Math.max(0, Integer.parseInt(m.group(1)) - 1);
            int column = m.group(2) == null ? 0 : Math.max(0, Integer.parseInt(m.group(2)) - 1);
            return new int[] {line, column};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The line {@code fragment} names in {@code file}: from its open buffer (unsaved edits count), else from disk. */
    private static int lineIn(Path file, @Nullable EditorBuffer open, String fragment) {
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
