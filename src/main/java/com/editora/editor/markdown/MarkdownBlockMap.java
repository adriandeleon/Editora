package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.DocumentText;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownBlocks;
import com.editora.perf.OffFx;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;
import org.jspecify.annotations.Nullable;

/**
 * What each line of a Markdown buffer is (prose, a code block, front matter, raw HTML), known without scanning
 * the document on the JavaFX thread (PERF-01, Pauta 6.2.0). Whether a line is code depends on every line above
 * it, so the editing keys (Enter in a list, Backspace, Tab, {@code *} and smart punctuation, Enter in a table)
 * and the line commands used to scan the document from its start on every press. Now {@link MarkdownBlocks} is
 * scanned off the thread, and a key looks its line up.
 *
 * <p>The map is lazy (perf review 2026-11-23 E1): an edit only notes the first line it touched. A scan starts
 * when typing pauses ({@link #settled()}, on the buffer's first settle milestone), when a key or command finds
 * the map behind, or when someone waits for it ({@link #whenCurrent}); one is in flight at a time, and the
 * document is taken in O(1) and its text built on the scan's thread. A scan landing on a document that has moved
 * on starts another only when someone waits; otherwise the next pause asks. Continuous typing therefore copies
 * and scans nothing per keystroke.
 *
 * <p>Between an edit and the scan of its version the map is behind. A key
 * then decides from it without scanning: a line above every line edited since the map's version has not
 * changed, nor has anything above it, so its kind is exact; the first edited line keeps the kind it had; a
 * line inside the edited run carries the kind of the first edited line on (the line after an opening fence is
 * the fence's body, the line after a closing fence is prose); a line below the edited run is the old line the
 * same distance from the end. Before the first scan has landed (a buffer just opened or made Markdown) every
 * line counts as prose, as it did before a map existed. FX-confined.
 */
public final class MarkdownBlockMap {

    /** The name the scan's thread carries; {@link com.editora.perf.BlockingSites} hears it there. */
    public static final String SITE = "MarkdownBlockMap.scan";

    private final EditorBuffer buffer;
    private @Nullable MarkdownBlocks blocks;
    private long mapVersion = -1;
    /** The lowest line edited since {@link #mapVersion} ({@code Integer.MAX_VALUE}: none). */
    private int firstChanged = Integer.MAX_VALUE;
    /** The lowest line edited since the snapshot in flight was taken. */
    private int firstChangedSinceAsk = Integer.MAX_VALUE;

    private boolean asking;
    private final List<Runnable> waiters = new ArrayList<>();

    MarkdownBlockMap(EditorBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * An edit starting on {@code line} (of the document after the edit): the map is behind until it is scanned.
     * Records the line only; no copy and no scan per keystroke (see the class comment). The one exception is a
     * buffer with no map yet (its load): that scan starts at once, so the first key after opening decides from it.
     */
    public void changed(int line) {
        firstChanged = Math.min(firstChanged, line);
        firstChangedSinceAsk = Math.min(firstChangedSinceAsk, line);
        if (blocks == null && buffer.isMarkdown()) {
            ask();
        }
    }

    /** Typing paused: brings a map that is behind up to date, off the FX thread. */
    public void settled() {
        if (buffer.isMarkdown() && !current()) {
            ask();
        }
    }

    /** Whether a map was made (it must hear every edit then, Markdown or not, to know how far behind it is). */
    public boolean holdsMap() {
        return blocks != null || asking;
    }

    /** Whether the map answers for the document as it is now. */
    public boolean current() {
        return blocks != null && mapVersion == buffer.docVersion();
    }

    /** Runs {@code then} once the map answers for the document as it is (at once when it already does). */
    public void whenCurrent(Runnable then) {
        if (current()) {
            then.run();
            return;
        }
        waiters.add(then);
        ask();
    }

    private void ask() {
        if (asking) {
            return;
        }
        asking = true;
        long version = buffer.docVersion();
        DocumentText text = buffer.documentText(); // O(1); the String is built on the scan's thread
        firstChangedSinceAsk = Integer.MAX_VALUE;
        OffFx.then(SITE, () -> MarkdownBlocks.scan(text.text()), answer -> {
            asking = false;
            if (answer.ok() && answer.value() != null) {
                blocks = answer.value();
                mapVersion = version;
                firstChanged = firstChangedSinceAsk;
            }
            if (buffer.docVersion() != version && buffer.isMarkdown()) {
                if (!waiters.isEmpty()) {
                    ask(); // someone waits for the document as it is; otherwise the next pause asks
                }
            } else if (answer.ok()) {
                List<Runnable> now = new ArrayList<>(waiters);
                waiters.clear();
                now.forEach(Runnable::run);
            }
        });
    }

    /** The kind of the line that starts at offset {@code lineStart} of {@code area}'s text. */
    MarkdownBlocks.Kind kindAtOffset(CodeArea area, int lineStart) {
        int line = area.offsetToPosition(Math.min(lineStart, area.getLength()), TwoDimensional.Bias.Forward)
                .getMajor();
        return kindAtLine(line, area.getParagraphs().size());
    }

    /** The kind of {@code line} (a paragraph index), as the map knows it; see the class comment. */
    MarkdownBlocks.Kind kindAtLine(int line, int lineCount) {
        if (!current()) {
            ask(); // ask before reading: a scan that answers at once is the map this key decides from
        }
        MarkdownBlocks known = blocks;
        if (known == null) {
            return MarkdownBlocks.Kind.TEXT;
        }
        int old = oldLine(known, line, lineCount);
        if (old >= 0) {
            return known.kind(old);
        }
        MarkdownBlocks.Kind edited = known.kind(firstChanged);
        return switch (edited) { // inside the edited run: the first edited line's kind, carried on
            case FENCE_OPEN -> MarkdownBlocks.Kind.FENCE_BODY;
            case FENCE_CLOSE -> MarkdownBlocks.Kind.TEXT;
            default -> edited;
        };
    }

    /**
     * The line of the map that answers for {@code line} now: itself when the map is current or the line is above
     * every edit, the first edited line for that line, the old line the same distance from the end below the
     * edited run; -1 for a line inside the edited run (asks for a scan when the map is behind).
     */
    private int oldLine(MarkdownBlocks known, int line, int lineCount) {
        if (mapVersion == buffer.docVersion() || line < firstChanged) {
            return line;
        }
        ask();
        if (line == firstChanged) {
            return line;
        }
        int old = line - (lineCount - known.lineCount());
        return old > firstChanged ? old : -1;
    }

    /**
     * The info string of the fenced code block {@code line} is inside ({@code "mermaid"}…), or null when it is not
     * in its body, as the map knows it (see the class comment; a line inside the edited run counts as the first).
     */
    public @Nullable String fenceInfoAt(int line, int lineCount) {
        MarkdownBlocks known = blocks;
        if (known == null || kindAtLine(line, lineCount) != MarkdownBlocks.Kind.FENCE_BODY) {
            return null;
        }
        int old = oldLine(known, line, lineCount);
        MarkdownBlocks.Fence fence = known.fenceAt(old >= 0 ? old : firstChanged);
        return fence == null ? null : fence.info();
    }

    /** Whether {@code line} is Markdown prose (not code, front matter or raw HTML). */
    boolean isProse(CodeArea area, int line) {
        return kindAtLine(line, area.getParagraphs().size()) == MarkdownBlocks.Kind.TEXT;
    }
}
