package com.editora.editor;

import org.fxmisc.richtext.CodeArea;

/**
 * The public face of a pending close-tag join (see {@link TagTyping}) for commands outside this package that read the
 * text before they edit it — the Emacs kill, case and transpose commands (XML/pom review 4, C32). Settling first lets
 * such a command compute its edit on the user's own {@code </} and partial name instead of on the joined closer,
 * whose name auto-rename would carry to the start tag.
 */
public final class PendingTagJoin {

    private PendingTagJoin() {}

    /**
     * Puts a join pending at {@code area}'s caret back as the user's own text ({@code </} and the name typed so far
     * before the closer, the caret after them). True when it did, so the caller re-reads the text.
     */
    public static boolean settle(CodeArea area) {
        return area instanceof TagRenameMirror.Area a && a.joins instanceof TagTyping typing && typing.settle(a);
    }
}
