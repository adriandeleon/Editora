package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;

/**
 * What tests in other packages may ask of a {@link MarkdownPreview}, whose column holds only the blocks on
 * screen (PERF-08): the whole document's blocks, built on demand.
 */
public final class PreviewBlocks {

    private PreviewBlocks() {}

    /** How many blocks the document has, built or not. */
    public static int count(MarkdownPreview preview) {
        return preview.blockCount();
    }

    /** Every block's node, in document order, built now where it was not (FX thread); a block that shows
     *  nothing is left out. */
    public static List<Node> all(MarkdownPreview preview) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < preview.blockCount(); i++) {
            Node n = preview.blockNode(i);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    /** The blocks built so far, in document order (on screen or kept). */
    public static List<Node> built(MarkdownPreview preview) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < preview.blockCount(); i++) {
            Node n = preview.builtNode(i);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }
}
