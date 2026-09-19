package com.editora.editor;

import java.util.*;

import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.fxmisc.richtext.model.RichTextChange;
import org.fxmisc.richtext.model.SegmentOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImportUndoRebaseTest {
    private static String apply(String text, List<PlainTextChange> changes) {
        var result = new StringBuilder(text);
        for (var change : changes) {
            assertEquals(change.getRemoved(), result.substring(change.getPosition(), change.getRemovalEnd()));
            result.replace(change.getPosition(), change.getRemovalEnd(), change.getInserted());
        }
        return result.toString();
    }

    private static List<PlainTextChange> inverse(List<PlainTextChange> changes) {
        var result = new ArrayList<PlainTextChange>();
        for (int i = changes.size() - 1; i >= 0; i--) result.add(changes.get(i).invert());
        return result;
    }

    @Test
    void importsCommuteThroughTypingDeletionAndEditsAboveTheImport() {
        String original = "// hi\nclass A { Arr }";
        var completion = List.of(new PlainTextChange(original.indexOf("Arr"), "Arr", "ArrayList"));
        String accepted = apply(original, completion);
        var typing = List.of(new PlainTextChange(accepted.indexOf(" }"), "", " values"));
        String typed = apply(accepted, typing);
        var header = List.of(new PlainTextChange(3, "hi", "hello 😀"));
        String current = apply(typed, header);
        var imports = List.of(new PlainTextChange(current.indexOf("class"), "", "import java.util.ArrayList;\n"));
        var plan = ImportUndoRebase.plan(completion, List.of(typing, header), imports);
        assertNotNull(plan);
        String reordered = apply(original, plan.accepted());
        for (var change : plan.later()) reordered = apply(reordered, change);
        assertEquals(apply(current, imports), reordered);
        for (int i = plan.later().size() - 1; i >= 0; i--)
            reordered = apply(reordered, inverse(plan.later().get(i)));
        assertTrue(reordered.contains("import java.util.ArrayList;"));
        assertEquals(original, apply(reordered, inverse(plan.accepted())));
    }

    @Test
    void overlappingOrSamePointEditsAndExcessiveHistoryDeclineRebasing() {
        var accepted = List.of(new PlainTextChange(20, "Arr", "ArrayList"));
        assertNull(ImportUndoRebase.plan(
                accepted,
                List.of(List.of(new PlainTextChange(0, "", "// x\n"))),
                List.of(new PlainTextChange(2, "", "import x;\n"))));
        assertNull(ImportUndoRebase.plan(
                accepted, List.of(List.of(new PlainTextChange(0, "", "a"))), List.of(new PlainTextChange(0, "", "b"))));
        assertNull(ImportUndoRebase.plan(
                accepted,
                Collections.nCopies(256, List.of(new PlainTextChange(30, "", "x"))),
                List.of(new PlainTextChange(0, "", "import x;\n"))));
    }

    @Test
    void seededEditingSequencesConvergeAndUndoWithoutLosingImports() {
        var random = new Random(712034);
        for (int run = 0; run < 500; run++) {
            String original = "// heading\nclass A { Arr value; }";
            var completion = List.of(new PlainTextChange(original.indexOf("Arr"), "Arr", "ArrayList"));
            String current = apply(original, completion);
            List<List<PlainTextChange>> later = new ArrayList<>();
            for (int step = 0; step < 12; step++) {
                // Keep the name anchor while editing the suffix or its following punctuation.
                int position = current.indexOf("value") + 5;
                int remove = random.nextBoolean() && position < current.length()
                        ? Character.charCount(current.codePointAt(position))
                        : 0;
                String insertion = random.nextBoolean() ? "x" : "😀";
                var edit = List.of(
                        new PlainTextChange(position, current.substring(position, position + remove), insertion));
                later.add(edit);
                current = apply(current, edit);
            }
            var imports = List.of(new PlainTextChange(current.indexOf("class"), "", "import java.util.ArrayList;\n"));
            var plan = ImportUndoRebase.plan(completion, later, imports);
            assertNotNull(plan);
            String expected = apply(current, imports);
            String actual = apply(original, plan.accepted());
            for (var batch : plan.later()) actual = apply(actual, batch);
            assertEquals(expected, actual, "run " + run);
            for (int i = plan.later().size() - 1; i >= 0; i--)
                actual = apply(actual, inverse(plan.later().get(i)));
            assertTrue(actual.contains("import java.util.ArrayList;"));
            assertEquals(original, apply(actual, inverse(plan.accepted())));
        }
    }

    @Test
    void styledPayloadsSurviveOffsetTranslationExactly() {
        var empty = ReadOnlyStyledDocument.fromString("", "paragraph", "empty", SegmentOps.styledTextOps());
        var name = ReadOnlyStyledDocument.fromString("ArrayList", "paragraph", "type", SegmentOps.styledTextOps());
        var typed = ReadOnlyStyledDocument.fromString(" value😀", "paragraph", "variable", SegmentOps.styledTextOps());
        var imported = ReadOnlyStyledDocument.fromString(
                "import java.util.ArrayList;\n", "paragraph", "import", SegmentOps.styledTextOps());
        var completion = new RichTextChange<>(20, empty, name);
        var typing = new RichTextChange<>(29, empty, typed);
        var addition = new RichTextChange<>(0, empty, imported);
        var plan = ImportUndoRebase.plan(List.of(completion), List.of(List.of(typing)), List.of(addition));
        assertNotNull(plan);
        var rebased = plan.later().getFirst().getFirst();
        assertEquals(29 + imported.length(), rebased.getPosition());
        assertSame(typed, rebased.getInserted());
        assertSame(empty, rebased.getRemoved());
        assertSame(imported, plan.accepted().getLast().getInserted());
    }

    @Test
    void aBatchOfAdditionalEditsCommutesAcrossBothSidesOfLaterTyping() {
        String original = "// heading\nclass A { Arr value; }\n// footer";
        var completion = List.of(new PlainTextChange(original.indexOf("Arr"), "Arr", "ArrayList"));
        String current = apply(original, completion);
        var batch = List.of(
                new PlainTextChange(current.indexOf("value"), "value", "values"),
                new PlainTextChange(3, "heading", "updated heading😀"));
        current = apply(current, batch);
        // Additional edits arrive in descending offset order, just like the production edit applier.
        var additions = List.of(
                new PlainTextChange(current.indexOf("footer"), "footer", "tail"),
                new PlainTextChange(current.indexOf("class"), "", "import java.util.ArrayList;\n"));
        var plan = ImportUndoRebase.plan(completion, List.of(batch), additions);
        assertNotNull(plan);
        String end = apply(current, additions);
        assertEquals(end, apply(apply(original, plan.accepted()), plan.later().getFirst()));
        String beforeTyping = apply(end, inverse(plan.later().getFirst()));
        assertTrue(beforeTyping.contains("import java.util.ArrayList;"));
        assertTrue(beforeTyping.endsWith("// tail"));
        assertEquals(original, apply(beforeTyping, inverse(plan.accepted())));
    }
}
