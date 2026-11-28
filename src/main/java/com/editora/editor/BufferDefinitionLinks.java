package com.editora.editor;

import com.editora.editops.HtmlLinks;
import com.editora.editor.EditorBuffer.DefinitionProbe;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Ctrl/Cmd-hover links for one buffer: whether what is under the pointer goes somewhere when clicked (an HTML
 * page's {@code href} or {@code src}, or a symbol whose definition the language server knows), answered for
 * {@link DefinitionLinks}, and the injected probe that asks the server.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferDefinitionLinks {

    private final EditorBuffer b;

    BufferDefinitionLinks(EditorBuffer b) {
        this.b = b;
    }

    @Nullable
    private DefinitionProbe definitionProbe;
    /** TEST SEAM — the primary view's Ctrl/Cmd-hover links. */
    DefinitionLinks definitionLinksForTest() {
        return java.util.Objects.requireNonNull(b.definitionLinks);
    }
    /** Injects the definition probe behind Ctrl/Cmd-hover links ({@link DefinitionLinks}); null turns it off. */
    void setDefinitionProbe(@Nullable DefinitionProbe probe) {
        this.definitionProbe = probe;
    }
    /**
     * Whether a Ctrl/Cmd-click at {@code offset} of {@code a} goes somewhere, for {@link DefinitionLinks}: the same
     * cases the click handler above follows. An HTML page's {@code href}/{@code src} (worked out off the FX thread,
     * the page is parsed whole), then, with a live language server, the identifier there if the server knows its
     * definition. A Markdown link is styled as one already and is left alone.
     */
    void probeDefinitionLink(
            CodeArea a, int offset, java.util.function.Consumer<DefinitionLinks.@Nullable Span> answer) {
        if (b.isMarkdown() || b.largeFile) {
            answer.accept(null);
            return;
        }
        if (HtmlLinks.isHtml(b.language)) {
            long version = b.docVersion;
            DocumentText text = DocumentText.of(a); // O(1); the page's text is built off the FX thread (E11)
            com.editora.perf.OffFx.then(
                    EditorBuffer.HTML_LINK_HOVER_SITE, () -> HtmlLinks.at(text.text(), offset), found -> {
                        if (b.docVersion != version || b.isDisposed()) {
                            answer.accept(null);
                            return;
                        }
                        HtmlLinks.Link link = found.ok() ? found.value() : null;
                        if (link != null && link.end() > link.start()) {
                            answer.accept(new DefinitionLinks.Span(link.start(), link.end(), true));
                        } else {
                            probeSymbolLink(a, offset, answer);
                        }
                    });
            return;
        }
        probeSymbolLink(a, offset, answer);
    }

    private void probeSymbolLink(
            CodeArea a, int offset, java.util.function.Consumer<DefinitionLinks.@Nullable Span> answer) {
        DefinitionProbe probe = definitionProbe;
        if (probe == null || !b.isLspActive() || offset < 0 || offset >= a.getLength()) {
            answer.accept(null);
            return;
        }
        var pos = a.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
        int line = pos.getMajor();
        int column = pos.getMinor();
        int[] word = DefinitionLinks.wordAt(a.getParagraph(line).getText(), column, b.language);
        if (word == null) {
            answer.accept(null);
            return;
        }
        int start = offset - column + word[0];
        int end = offset - column + word[1];
        long version = b.docVersion;
        b.sendLspChange(); // the server must see what is under the mouse, not the text of a moment ago
        probe.probe(line, column, found -> {
            boolean current = b.docVersion == version && !b.isDisposed() && b.isLspActive();
            answer.accept(new DefinitionLinks.Span(start, end, current && Boolean.TRUE.equals(found)));
        });
    }
}
