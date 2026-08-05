package com.editora.lsp;

import java.util.List;

import com.editora.lsp.InlayHintFilter.Mode;
import com.editora.lsp.LspManager.InlayHintSpan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlayHintFilterTest {

    private static InlayHintSpan param(int col, String label) {
        return new InlayHintSpan(0, col, label, true);
    }

    private static InlayHintSpan type(int col, String label) {
        return new InlayHintSpan(0, col, label, false);
    }

    // --- Mode ids ---------------------------------------------------------------------------------

    @Test
    void modeParsesItsPersistedIdAndFallsBackToLiterals() {
        assertEquals(Mode.ALL, Mode.of("all"));
        assertEquals(Mode.LITERALS, Mode.of("literals"));
        assertEquals(Mode.ALL, Mode.of("ALL"), "id matching is case-insensitive");
        assertEquals(Mode.LITERALS, Mode.of(null));
        assertEquals(Mode.LITERALS, Mode.of("nonsense"), "an unknown id must not disable filtering");
        assertEquals("literals", Mode.LITERALS.id());
    }

    // --- Label parsing ----------------------------------------------------------------------------

    @Test
    void parameterNameStripsWhicheverSeparatorTheServerUsed() {
        assertEquals("count", InlayHintFilter.parameterName("count:"));
        assertEquals("count", InlayHintFilter.parameterName("count ="));
        assertEquals("count", InlayHintFilter.parameterName("  count:  "));
        assertEquals("", InlayHintFilter.parameterName(null));
        assertEquals("", InlayHintFilter.parameterName(":"));
    }

    @Test
    void uninformativeNamesAreSingleLettersAndPositionalPlaceholders() {
        assertTrue(InlayHintFilter.uninformativeName("x"));
        assertTrue(InlayHintFilter.uninformativeName("s"));
        assertTrue(InlayHintFilter.uninformativeName("arg0"));
        assertTrue(InlayHintFilter.uninformativeName("ARG12"));
        assertTrue(InlayHintFilter.uninformativeName("param1"));
        assertTrue(InlayHintFilter.uninformativeName("p2"));
    }

    @Test
    void shortButMeaningfulNamesSurvive() {
        // Two letters is the floor deliberately — these all carry information.
        assertFalse(InlayHintFilter.uninformativeName("id"));
        assertFalse(InlayHintFilter.uninformativeName("to"));
        assertFalse(InlayHintFilter.uninformativeName("on"));
        assertFalse(InlayHintFilter.uninformativeName("path"));
        // "param"/"arg"/"p" only count as placeholders when the rest is digits.
        assertFalse(InlayHintFilter.uninformativeName("params"));
        assertFalse(InlayHintFilter.uninformativeName("argument"));
        assertFalse(InlayHintFilter.uninformativeName("prefix"));
    }

    // --- Argument classification ------------------------------------------------------------------

    @Test
    void argumentAtSkipsWhitespaceAndClampsToTheLine() {
        assertEquals("\"hi\")", InlayHintFilter.argumentAt("f(  \"hi\")", 2));
        assertEquals("", InlayHintFilter.argumentAt("f()", 99), "a column past the line is unclassifiable");
        assertEquals("", InlayHintFilter.argumentAt(null, 0));
        assertEquals("", InlayHintFilter.argumentAt("f()", -1));
    }

    @Test
    void literalsAreStringsCharsNumbersAndKeywords() {
        assertTrue(InlayHintFilter.isLiteral("\"Hello\")"));
        assertTrue(InlayHintFilter.isLiteral("'c')"));
        assertTrue(InlayHintFilter.isLiteral("42)"));
        assertTrue(InlayHintFilter.isLiteral("-1)"));
        assertTrue(InlayHintFilter.isLiteral("+0.5f)"));
        assertTrue(InlayHintFilter.isLiteral(".5f)"));
        assertTrue(InlayHintFilter.isLiteral("true)"));
        assertTrue(InlayHintFilter.isLiteral("false,"));
        assertTrue(InlayHintFilter.isLiteral("null)"));
    }

    @Test
    void nonLiteralsIncludeKeywordPrefixesThatAreJustIdentifiers() {
        assertFalse(InlayHintFilter.isLiteral("value)"));
        assertFalse(InlayHintFilter.isLiteral("getName())"));
        assertFalse(InlayHintFilter.isLiteral(""));
        // The whole-word check: these merely start with a keyword.
        assertFalse(InlayHintFilter.isLiteral("nullable)"));
        assertFalse(InlayHintFilter.isLiteral("trueish)"));
        // A bare sign is not a number.
        assertFalse(InlayHintFilter.isLiteral("-"));
        assertFalse(InlayHintFilter.isLiteral("-x)"));
    }

    @Test
    void aHintThatMerelyRestatesItsArgumentIsRedundant() {
        assertTrue(InlayHintFilter.repeatsArgument("name", "name)"));
        assertTrue(InlayHintFilter.repeatsArgument("name", "this.name)"));
        assertTrue(InlayHintFilter.repeatsArgument("name", "user.profile.name,"));
        assertTrue(InlayHintFilter.repeatsArgument("color", "COLOR)"), "case-insensitive");
        assertFalse(InlayHintFilter.repeatsArgument("name", "title)"));
        assertFalse(InlayHintFilter.repeatsArgument("name", "getName())"));
        assertFalse(InlayHintFilter.repeatsArgument("name", ""));
    }

    // --- The rules together -----------------------------------------------------------------------

    @Test
    void theReportedCaseIsDroppedInEveryMode() {
        // System.out.println("Hello World!") → jdtls sends "x:", because PrintStream declares println(String x).
        String line = "        System.out.println(\"Hello World!\");";
        InlayHintSpan hint = param(line.indexOf('"'), "x:");
        assertFalse(InlayHintFilter.keep(hint, Mode.LITERALS, line));
        assertFalse(InlayHintFilter.keep(hint, Mode.ALL, line), "an uninformative name is dropped in ALL too");
    }

    @Test
    void literalsModeKeepsTheHintThatEarnsItsSpace() {
        String line = "        copy(source, target, true);";
        InlayHintSpan overwrite = param(line.indexOf("true"), "overwrite:");
        assertTrue(InlayHintFilter.keep(overwrite, Mode.LITERALS, line), "a bare true is what a reader cannot decode");
    }

    @Test
    void literalsModeDropsANamedNonLiteralThatAllKeeps() {
        String line = "        copy(source, target);";
        InlayHintSpan destination = param(line.indexOf("target"), "destination:");
        assertFalse(InlayHintFilter.keep(destination, Mode.LITERALS, line));
        assertTrue(InlayHintFilter.keep(destination, Mode.ALL, line));
    }

    @Test
    void typeHintsSurviveEveryModeAndEveryRule() {
        String line = "        var total = compute();";
        // Same label shape a filtered parameter hint would have, and an unclassifiable column: still kept.
        assertTrue(InlayHintFilter.keep(type(11, ": int"), Mode.LITERALS, line));
        assertTrue(InlayHintFilter.keep(type(999, "x:"), Mode.LITERALS, line));
    }

    @Test
    void anUnclassifiableParameterHintIsDroppedInLiteralsAndKeptInAll() {
        // A column past the line end: we cannot tell what it labels. LITERALS means "only when sure".
        assertFalse(InlayHintFilter.keep(param(999, "count:"), Mode.LITERALS, "f();"));
        assertTrue(InlayHintFilter.keep(param(999, "count:"), Mode.ALL, "f();"));
    }

    // --- The list-level entry point ---------------------------------------------------------------

    @Test
    void filterReadsEachSpansOwnLine() {
        List<String> lines = List.of("        println(\"hi\");", "        copy(src, true);");
        List<InlayHintSpan> spans = List.of(
                new InlayHintSpan(0, lines.get(0).indexOf('"'), "x:", true), // uninformative → dropped
                new InlayHintSpan(1, lines.get(1).indexOf("true"), "overwrite:", true), // literal → kept
                new InlayHintSpan(1, 8, ": void", false)); // type → kept
        List<InlayHintSpan> out = InlayHintFilter.filter(spans, Mode.LITERALS, lines::get);
        assertEquals(2, out.size());
        assertEquals("overwrite:", out.get(0).label());
        assertEquals(": void", out.get(1).label());
    }

    @Test
    void filterHandlesEmptyAndNullInput() {
        assertEquals(List.of(), InlayHintFilter.filter(List.of(), Mode.ALL, i -> ""));
        assertEquals(List.of(), InlayHintFilter.filter(null, Mode.ALL, i -> ""));
    }

    @Test
    void aSpanIsUnchangedByFiltering() {
        // The filter selects; it must never rewrite a label or move a position.
        InlayHintSpan kept = param(0, "overwrite:");
        List<InlayHintSpan> out = InlayHintFilter.filter(List.of(kept), Mode.LITERALS, i -> "true");
        assertEquals(List.of(kept), out);
    }

    @Test
    void aServerOmittingTheKindIsReadFromTheLabel() {
        // kind is optional in the protocol; without the fallback an unkinded ": String" would be filterable.
        assertTrue(LspManager.isParameterHint(null, "count:"));
        assertFalse(LspManager.isParameterHint(null, ": String"));
        assertTrue(LspManager.isParameterHint(org.eclipse.lsp4j.InlayHintKind.Parameter, ": odd"));
        assertFalse(LspManager.isParameterHint(org.eclipse.lsp4j.InlayHintKind.Type, "count:"));
    }

    @Test
    void theThreeArgSpanConstructorMeansParameter() {
        assertTrue(new InlayHintSpan(1, 2, "x:").parameter());
    }
}
