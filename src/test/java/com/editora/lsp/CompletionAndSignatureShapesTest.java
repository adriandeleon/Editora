package com.editora.lsp;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import com.editora.editor.LspTextEdit;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemDefaults;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertReplaceRange;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.InsertTextMode;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes of a completion or signature-help answer the existing mapper tests leave out: the list-level
 * defaults of LSP 3.17, the two forms of a text edit, every item kind, and a signature whose parameters are
 * given as offsets, are out of range, or are missing.
 */
class CompletionAndSignatureShapesTest {

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    /** An lsp4j value as it arrives off the wire, where a required field may simply be absent. */
    private static <T> T wire(String json, Class<T> type) {
        return LanguageServerSession.LSP_GSON.fromJson(json, type);
    }

    // --- completion ----------------------------------------------------------------------------------

    @Test
    void everyServerKindHasItsOwnIcon() {
        Set<CompletionIconKind> seen = EnumSet.noneOf(CompletionIconKind.class);
        for (CompletionItemKind kind : CompletionItemKind.values()) {
            CompletionIconKind icon = CompletionMapper.iconKindOf(kind);
            assertTrue(seen.add(icon), kind + " shares the icon " + icon + " with another kind");
            assertEquals(
                    kind.name().toLowerCase(Locale.ROOT),
                    icon.name().replace("_", "").toLowerCase(Locale.ROOT),
                    "the icon is the one named after the kind");
        }
        assertFalse(seen.contains(CompletionMapper.iconKindOf(null)), "no kind at all gets none of them");
    }

    @Test
    void theListsDefaultsFillWhatAnItemLeavesOut() {
        var defaults = new CompletionItemDefaults();
        defaults.setData("resolve-me");
        defaults.setCommitCharacters(List.of("."));
        defaults.setInsertTextFormat(InsertTextFormat.Snippet);
        defaults.setInsertTextMode(InsertTextMode.AdjustIndentation);
        defaults.setEditRange(Either.forLeft(range(2, 4, 7)));
        var bare = new CompletionItem("bare");
        var withInsert = new CompletionItem("label");
        withInsert.setInsertText("inserted");
        var withEditText = new CompletionItem("label");
        withEditText.setInsertText("ignored");
        withEditText.setTextEditText("edit text");
        var own = new CompletionItem("own");
        own.setData("mine");
        own.setCommitCharacters(List.of(";"));
        own.setInsertTextFormat(InsertTextFormat.PlainText);
        own.setInsertTextMode(InsertTextMode.AsIs);
        own.setTextEdit(Either.forLeft(new TextEdit(range(9, 0, 1), "own edit")));
        var items = new ArrayList<CompletionItem>(List.of(bare, withInsert, withEditText, own));
        items.add(null);
        var list = new CompletionList(false, items);
        list.setItemDefaults(defaults);

        assertSame(items, CompletionMapper.itemsOf(Either.forRight(list)));

        assertEquals("resolve-me", bare.getData());
        assertEquals(List.of("."), bare.getCommitCharacters());
        assertEquals(InsertTextFormat.Snippet, bare.getInsertTextFormat());
        assertEquals(InsertTextMode.AdjustIndentation, bare.getInsertTextMode());
        assertEquals(
                new TextEdit(range(2, 4, 7), "bare"), bare.getTextEdit().getLeft(), "the label, at the list's range");
        assertEquals("inserted", withInsert.getTextEdit().getLeft().getNewText());
        assertEquals(
                "edit text", withEditText.getTextEdit().getLeft().getNewText(), "textEditText wins over insertText");
        assertEquals("mine", own.getData(), "an item's own values are never overwritten");
        assertEquals(List.of(";"), own.getCommitCharacters());
        assertEquals(InsertTextFormat.PlainText, own.getInsertTextFormat());
        assertEquals(InsertTextMode.AsIs, own.getInsertTextMode());
        assertEquals("own edit", own.getTextEdit().getLeft().getNewText());
    }

    @Test
    void anInsertReplaceDefaultRangeBecomesAnInsertReplaceEdit() {
        var defaults = new CompletionItemDefaults();
        defaults.setEditRange(Either.forRight(new InsertReplaceRange(range(1, 2, 4), range(1, 2, 9))));
        var item = new CompletionItem("word");
        var list = new CompletionList(false, List.of(item));
        list.setItemDefaults(defaults);

        CompletionMapper.itemsOf(Either.forRight(list));
        Completion mapped = CompletionMapper.map(List.of(item), null).get(0);

        assertEquals(
                new InsertReplaceEdit("word", range(1, 2, 4), range(1, 2, 9)),
                item.getTextEdit().getRight());
        assertEquals(
                new Completion.ReplaceRange(1, 2, 1, 4),
                mapped.replaceRange(),
                "the insert range is what is replaced: the replace range reaches over text after the caret");
        assertEquals(new Completion.ReplaceRange(1, 2, 1, 9), mapped.protocol().replaceRange());
        assertEquals(0, mapped.protocol().insertTextMode());
        assertNull(mapped.onAccept(), "no accept hook was asked for");
    }

    @Test
    void anAnswerWithoutItemsIsAnEmptyList() {
        assertEquals(List.of(), CompletionMapper.itemsOf(null));
        assertEquals(List.of(), CompletionMapper.itemsOf(Either.forLeft(null)));
        assertEquals(List.of(), CompletionMapper.itemsOf(Either.forRight(null)));
        assertEquals(
                List.of(),
                CompletionMapper.itemsOf(Either.forRight(wire("{\"isIncomplete\":false}", CompletionList.class))));
        var plain = List.of(new CompletionItem("x"));
        assertSame(plain, CompletionMapper.itemsOf(Either.forLeft(plain)));
        var noDefaults = new CompletionList(false, plain);
        assertSame(plain, CompletionMapper.itemsOf(Either.forRight(noDefaults)));
        assertEquals(List.of(), CompletionMapper.map(null, null));
    }

    @Test
    void itemsWithoutALabelAreDroppedAndDetailsAreFlattened() {
        var detailed = new CompletionItem(" names ");
        var details = new CompletionItemLabelDetails();
        details.setDetail("(int)");
        details.setDescription(" ");
        detailed.setLabelDetails(details);
        detailed.setDetail("List<String>\n  from java.util");
        detailed.setInsertTextMode(InsertTextMode.AdjustIndentation);
        var described = new CompletionItem("other");
        var description = new CompletionItemLabelDetails();
        description.setDescription("demo.Other");
        described.setLabelDetails(description);
        described.setDetail("ignored when a description is given");
        var items = new ArrayList<CompletionItem>();
        items.add(null);
        items.add(wire("{}", CompletionItem.class));
        items.add(detailed);
        items.add(described);

        List<Completion> mapped = CompletionMapper.map(items, item -> () -> {});

        assertEquals(2, mapped.size());
        assertEquals("names(int)", mapped.get(0).label());
        assertEquals("List<String> from java.util", mapped.get(0).detail(), "one line, whatever the server sent");
        assertEquals(2, mapped.get(0).protocol().insertTextMode());
        assertEquals("demo.Other", mapped.get(1).detail());
        assertEquals("", CompletionMapper.detailText(new CompletionItem("nothing")));
    }

    @Test
    void deprecationIsReadFromTheTagOrTheLegacyFlag() {
        var tagged = new CompletionItem("old");
        tagged.setTags(List.of(CompletionItemTag.Deprecated));
        var flagged = new CompletionItem("older");
        flagged.setDeprecated(true);
        var untagged = new CompletionItem("fine");
        untagged.setTags(List.of());

        assertTrue(CompletionMapper.isDeprecated(tagged));
        assertTrue(CompletionMapper.isDeprecated(flagged));
        assertFalse(CompletionMapper.isDeprecated(untagged));
        assertFalse(CompletionMapper.isDeprecated(new CompletionItem("fine")));
    }

    @Test
    void additionalEditsAreKnownEagerlyOrPromisedByResolveData() {
        var item = new CompletionItem("List");
        assertFalse(CompletionMapper.mayHaveAdditionalEdits(null));
        assertFalse(CompletionMapper.mayHaveAdditionalEdits(item));
        assertEquals(List.of(), CompletionMapper.additionalEdits(null));
        assertEquals(List.of(), CompletionMapper.additionalEdits(item));

        item.setAdditionalTextEdits(List.of());
        assertFalse(CompletionMapper.mayHaveAdditionalEdits(item), "an empty list promises nothing");
        var edits = new ArrayList<TextEdit>();
        edits.add(new TextEdit(range(0, 0, 0), "import java.util.List;\n"));
        edits.add(null);
        edits.add(wire("{\"newText\":\"nowhere\"}", TextEdit.class));
        edits.add(wire(
                "{\"range\":{\"start\":{\"line\":3,\"character\":1},\"end\":{\"line\":3,\"character\":2}}}",
                TextEdit.class));
        item.setAdditionalTextEdits(edits);
        assertTrue(CompletionMapper.mayHaveAdditionalEdits(item));
        assertEquals(
                List.of(new LspTextEdit(0, 0, 0, 0, "import java.util.List;\n"), new LspTextEdit(3, 1, 3, 2, "")),
                CompletionMapper.additionalEdits(item),
                "an edit with no range cannot be placed; one with no text deletes");

        var lazy = new CompletionItem("lazy");
        lazy.setData(7);
        assertTrue(CompletionMapper.mayHaveAdditionalEdits(lazy));
    }

    @Test
    void theReplaceRangeToleratesAnEditWithPartsMissing() {
        var item = new CompletionItem("x");
        assertNull(CompletionMapper.replaceRangeOf(item), "no text edit: the editor works the overlap out itself");

        item.setTextEdit(Either.forLeft(wire("{\"newText\":\"x\"}", TextEdit.class)));
        assertNull(CompletionMapper.replaceRangeOf(item));
        item.setTextEdit(Either.forRight(null));
        assertNull(CompletionMapper.replaceRangeOf(item));
        assertEquals(1, CompletionMapper.map(List.of(item), null).size(), "the item is still offered");

        item.setTextEdit(Either.forLeft(
                wire("{\"newText\":\"x\",\"range\":{\"start\":{\"line\":4,\"character\":2}}}", TextEdit.class)));
        Completion.ReplaceRange open = CompletionMapper.replaceRangeOf(item);
        assertEquals(
                Completion.ReplaceRange.startingAt(4, 2),
                open,
                "a range with no end starts there and ends at the caret");

        item.setTextEdit(Either.forRight(wire(
                "{\"newText\":\"x\",\"insert\":{\"start\":{\"line\":1,\"character\":0},\"end\":{\"line\":1,\"character\":1}}}",
                InsertReplaceEdit.class)));
        assertNull(
                CompletionMapper.map(List.of(item), null).get(0).protocol().replaceRange(), "no replace range given");
    }

    // --- signature help ------------------------------------------------------------------------------

    private static SignatureInformation signature(String label, String... parameters) {
        var sig = new SignatureInformation(label);
        var params = new ArrayList<ParameterInformation>();
        for (String p : parameters) {
            params.add(new ParameterInformation(p));
        }
        sig.setParameters(params);
        return sig;
    }

    @Test
    void theActiveParameterIsFoundAsAWholeWord() {
        var sig = signature("void add(int index, E e)", "int index", "E e");
        var help = new SignatureHelp(List.of(sig), 0, 1);

        SignatureFormat.Active active = SignatureFormat.resolve(help);

        assertEquals("E e", active.label().substring(active.paramStart(), active.paramEnd()));
        assertEquals("", active.documentation());
        assertEquals(1, active.total());
    }

    /** {@code e} occurs inside {@code index} first; the parameter is the standalone one. */
    @Test
    void aParameterNameInsideAnotherWordIsNotTheParameter() {
        var sig = signature("void set(index, e)", "index", "e");
        SignatureFormat.Active active = SignatureFormat.resolve(new SignatureHelp(List.of(sig), 0, 1));
        assertEquals(16, active.paramStart());
        assertEquals(17, active.paramEnd());

        var missing = signature("void set(index)", "index", "zz");
        SignatureFormat.Active none = SignatureFormat.resolve(new SignatureHelp(List.of(missing), 0, 1));
        assertEquals(0, none.paramEnd(), "a parameter the label does not contain is not highlighted");
        var embedded = signature("void reindex()", "index");
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(embedded), 0, 0))
                        .paramEnd());
    }

    @Test
    void offsetsIntoTheLabelAreUsedAsGivenAndClamped() {
        var sig = new SignatureInformation("f(int a, int b)");
        var byOffset = new ParameterInformation();
        byOffset.setLabel(Either.forRight(new Tuple.Two<>(9, 14)));
        var pastTheEnd = new ParameterInformation();
        pastTheEnd.setLabel(Either.forRight(new Tuple.Two<>(9, 400)));
        var backwards = new ParameterInformation();
        backwards.setLabel(Either.forRight(new Tuple.Two<>(9, 3)));
        var halfGiven = new ParameterInformation();
        halfGiven.setLabel(Either.forRight(new Tuple.Two<>(null, null)));
        sig.setParameters(List.of(byOffset, pastTheEnd, backwards, halfGiven));

        assertEquals("int b", spanOf(sig, 0));
        assertEquals("int b)", spanOf(sig, 1), "an end past the label stops at its end");
        assertEquals("", spanOf(sig, 2), "an empty or inverted span highlights nothing");
        assertEquals("", spanOf(sig, 3));
    }

    private static String spanOf(SignatureInformation sig, int parameter) {
        SignatureFormat.Active active = SignatureFormat.resolve(new SignatureHelp(List.of(sig), 0, parameter));
        return active.label().substring(active.paramStart(), active.paramEnd());
    }

    @Test
    void whatCannotBeResolvedHighlightsNothingOrShowsNothing() {
        assertNull(SignatureFormat.resolve(null));
        assertNull(SignatureFormat.resolve(new SignatureHelp()));
        assertNull(SignatureFormat.resolve(new SignatureHelp(List.of(), 0, 0)));
        assertNull(SignatureFormat.resolve(new SignatureHelp(List.of(wire("{}", SignatureInformation.class)), 0, 0)));
        var holed = new ArrayList<SignatureInformation>();
        holed.add(null);
        assertNull(SignatureFormat.resolve(new SignatureHelp(holed, 0, 0)));

        var sig = signature("f(int a)", "int a");
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(sig), 0, 5)).paramEnd(),
                "past the last one");
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(sig), 0, -1)).paramEnd());
        var noParams = new SignatureInformation("f()");
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(noParams), 0, 0))
                        .paramEnd());
        noParams.setParameters(List.of());
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(noParams), 0, 0))
                        .paramEnd());
        var blank = signature("f(int a)", " ");
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(blank), 0, 0)).paramEnd());
        var unlabelled = new SignatureInformation("f(int a)");
        var holes = new ArrayList<ParameterInformation>();
        holes.add(null);
        holes.add(wire("{}", ParameterInformation.class));
        unlabelled.setParameters(holes);
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(unlabelled), 0, 0))
                        .paramEnd());
        assertEquals(
                0,
                SignatureFormat.resolve(new SignatureHelp(List.of(unlabelled), 0, 1))
                        .paramEnd());
    }

    @Test
    void theActiveOverloadAndItsOwnParameterWin() {
        var first = signature("f(int a)", "int a");
        var second = signature("f(int a, int b)", "int a", "int b");
        second.setActiveParameter(1); // LSP 3.16: per signature, over the help-level value
        second.setDocumentation(new MarkupContent("markdown", "**Adds** them"));
        var help = new SignatureHelp(List.of(first, second), 1, 0);

        SignatureFormat.Active active = SignatureFormat.resolve(help);

        assertEquals("f(int a, int b)", active.label());
        assertEquals("int b", active.label().substring(active.paramStart(), active.paramEnd()));
        assertEquals("**Adds** them", active.documentation());
        assertEquals(1, active.index());
        assertEquals(2, active.total());

        help.setActiveSignature(9); // out of range: the last one, not an exception
        assertEquals(1, SignatureFormat.resolve(help).index());
        help.setActiveSignature(null);
        help.setActiveParameter(null);
        SignatureFormat.Active defaulted = SignatureFormat.resolve(help);
        assertEquals(0, defaulted.index());
        assertEquals("int a", defaulted.label().substring(defaulted.paramStart(), defaulted.paramEnd()));

        first.setDocumentation("plain text");
        assertEquals("plain text", SignatureFormat.resolve(help).documentation());
        first.setDocumentation(Either.forLeft(null));
        assertEquals("", SignatureFormat.resolve(help).documentation());
        first.setDocumentation(Either.forRight(null));
        assertEquals("", SignatureFormat.resolve(help).documentation());
        first.setDocumentation(Either.forRight(wire("{\"kind\":\"markdown\"}", MarkupContent.class)));
        assertEquals("", SignatureFormat.resolve(help).documentation());
    }
}
