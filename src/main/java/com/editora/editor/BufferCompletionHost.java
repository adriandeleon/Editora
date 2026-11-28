package com.editora.editor;

import javafx.scene.layout.AnchorPane;

import com.editora.editor.completion.BufferCompletion;
import com.editora.editor.completion.SnippetTyping;
import com.editora.editor.lsp.LspEditPlacement;
import com.editora.editor.undo.CompletionUndoManager;
import com.editora.snippet.Snippet;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * What the completion popup and its typing hooks ({@link BufferCompletion}) need to know about the buffer they serve:
 * its views, the language server's trigger characters, the language and font, and the snippet session.
 *
 * <p>Moved out of {@code EditorBuffer} for size, where it was an anonymous class; each method answers exactly what
 * that class did.
 */
final class BufferCompletionHost implements BufferCompletion.Host {

    private final EditorBuffer b;

    BufferCompletionHost(EditorBuffer b) {
        this.b = b;
    }

    @Override
    public CodeArea area() {
        return b.area;
    }

    @Override
    public AnchorPane root() {
        return b.root;
    }

    @Override
    public @Nullable CodeArea area2() {
        return b.area2;
    }

    @Override
    public @Nullable AnchorPane root2() {
        return b.root2;
    }

    @Override
    public boolean largeFile() {
        return b.largeFile;
    }

    @Override
    public long docVersion() {
        return b.docVersion;
    }

    @Override
    public boolean lspActive() {
        return b.lspActive;
    }

    @Override
    public java.util.Set<Character> lspTriggerChars() {
        return b.lspTriggerChars;
    }

    @Override
    public java.util.Set<Character> lspSignatureTriggerChars() {
        return b.lspSignatureTriggerChars;
    }

    @Override
    public java.util.function.@Nullable Consumer<Character> signatureHelpRequester() {
        return b.signatureHelpRequester;
    }

    @Override
    public String language() {
        return b.language;
    }

    @Override
    public String fontFamily() {
        return b.fontFamily;
    }

    @Override
    public int fontSize() {
        return b.fontSize;
    }

    @Override
    public CodeArea getFocusedArea() {
        return b.getFocusedArea();
    }

    @Override
    public boolean isDiagram() {
        return b.isDiagram();
    }

    @Override
    public void sendLspChange() {
        b.sendLspChange();
    }

    @Override
    public boolean multiCaretActiveOn(CodeArea a) {
        return b.caretOps.multiCaretActiveOn(a);
    }

    @Override
    public boolean isProse() {
        return b.isProse();
    }

    @Override
    public @Nullable String markdownFenceInfo(int line) {
        return b.markdownEditing
                .blockMap()
                .fenceInfoAt(line, b.area.getParagraphs().size());
    }

    @Override
    public String getSpellLanguage() {
        return b.getSpellLanguage();
    }

    @Override
    public boolean isEditable() {
        return b.isEditable();
    }

    @Override
    public boolean hasActiveSnippet() {
        return b.hasActiveSnippet();
    }

    @Override
    public Runnable beginCompletionUndo() {
        return CompletionUndoManager.begin(b.area, b.area2);
    }

    @Override
    public void finishSnippet() {
        b.snippetSession.finish();
    }

    @Override
    public int snippetDepth() {
        return b.snippetSession.depth();
    }

    @Override
    public void startSnippet(CodeArea a, Snippet snippet, int from, int to, boolean reindent) {
        b.snippetKeys.startSnippet(a, snippet, from, to, reindent);
    }

    @Override
    public com.editora.snippet.TabExpansion.@Nullable Match snippetTrigger(CodeArea a, boolean tabOnly) {
        if (tabOnly && !b.snippetKeys.snippetTabExpansion) {
            return null;
        }
        return SnippetTyping.triggerAtCaret(
                a,
                b.language,
                isProse(),
                tabOnly ? b.snippetKeys.snippetProvider : b.snippetKeys.snippetOfferProvider,
                !tabOnly);
    }

    @Override
    public com.editora.editops.Abbrev.@Nullable Edit abbrevAtCaret(CodeArea a) {
        if (!b.snippetKeys.abbrevMode
                || b.snippetKeys.applyingAbbrev
                || b.hugeFile
                || !isEditable()
                || b.snippetKeys.abbrevTable.isEmpty()) {
            return null;
        }
        if (hasActiveSnippet()
                || b.caretOps.multiCaretActiveOn(a)
                || a.getSelection().getLength() > 0) {
            return null;
        }
        return b.snippetKeys.abbrevBefore(a, a.getCaretPosition());
    }

    @Override
    public int lspOffset(CodeArea a, int line, int col) {
        return LspEditPlacement.offset(a, line, col);
    }

    @Override
    public int[] lspPosition(CodeArea a, int offset) {
        return LspEditPlacement.position(a, offset);
    }
}
