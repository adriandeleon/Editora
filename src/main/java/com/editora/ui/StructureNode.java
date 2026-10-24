package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.fold.FoldRegions.Region;
import org.jspecify.annotations.Nullable;

/** A node in the structure tree: a foldable region, its symbol label/kind, the line to navigate to, an
 *  optional detail (the LSP signature, e.g. {@code (String x) : void}), syntax-styled label and return
 *  runs, an optional doc comment (the cleaned leading comment above the declaration), and children. */
final class StructureNode {
    /** A run of label text with the editor syntax classes it is painted with. */
    record SyntaxRun(String text, List<String> styles) {}

    private final @Nullable Region region;
    private final String label;
    private final @Nullable String kind;
    private final int line;
    private final String detail;
    private List<SyntaxRun> nameRuns = List.of();
    private List<SyntaxRun> returnRuns = List.of();
    private String doc = ""; // filled by attachDocs() from the buffer text; "" when none
    private final List<StructureNode> children = new ArrayList<>();

    StructureNode(@Nullable Region region, String label, @Nullable String kind, int line) {
        this(region, label, kind, line, "");
    }

    StructureNode(@Nullable Region region, String label, @Nullable String kind, int line, String detail) {
        this.region = region;
        this.label = label;
        this.kind = kind;
        this.line = line;
        this.detail = detail == null ? "" : detail;
    }

    @Nullable
    Region region() {
        return region;
    }

    String label() {
        return label;
    }

    @Nullable
    String kind() {
        return kind;
    }

    int line() {
        return line;
    }

    String detail() {
        return detail;
    }

    List<SyntaxRun> nameRuns() {
        return nameRuns;
    }

    void setNameRuns(List<SyntaxRun> runs) {
        nameRuns = runs == null ? List.of() : List.copyOf(runs);
    }

    List<SyntaxRun> returnRuns() {
        return returnRuns;
    }

    void setReturnRuns(List<SyntaxRun> runs) {
        returnRuns = runs == null ? List.of() : List.copyOf(runs);
    }

    String doc() {
        return doc;
    }

    void setDoc(String doc) {
        this.doc = doc == null ? "" : doc;
    }

    List<StructureNode> children() {
        return children;
    }
}
