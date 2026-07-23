package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link SignatureFormat}: active signature/parameter resolution for the popup (#674). */
class SignatureFormatTest {

    private static SignatureInformation sig(String label, String... paramLabels) {
        SignatureInformation si = new SignatureInformation(label);
        si.setParameters(new java.util.ArrayList<>());
        for (String p : paramLabels) {
            si.getParameters().add(new ParameterInformation(p));
        }
        return si;
    }

    @Test
    void resolvesActiveSignatureAndBoldsTheActiveParamBySubstring() {
        SignatureHelp help =
                new SignatureHelp(List.of(sig("format(String fmt, Object args)", "String fmt", "Object args")), 0, 1);
        var a = SignatureFormat.resolve(help);
        assertEquals("format(String fmt, Object args)", a.label());
        assertEquals("Object args", a.label().substring(a.paramStart(), a.paramEnd()));
        assertEquals(0, a.index());
        assertEquals(1, a.total());
    }

    @Test
    void labelOffsetsWinOverSubstringSearch() {
        SignatureInformation si = new SignatureInformation("f(int a, int ab)");
        ParameterInformation p = new ParameterInformation();
        p.setLabel(Either.forRight(Tuple.two(13, 15))); // "ab"
        si.setParameters(List.of(p));
        var a = SignatureFormat.resolve(new SignatureHelp(List.of(si), 0, 0));
        assertEquals("ab", a.label().substring(a.paramStart(), a.paramEnd()));
    }

    /** The parameter {@code a} must never bold the {@code a} inside {@code ab} — whole-token match only. */
    @Test
    void substringSearchMatchesWholeTokensOnly() {
        assertEquals(-1, SignatureFormat.indexOfToken("f(int ab)", "a"));
        assertEquals(6, SignatureFormat.indexOfToken("f(int a, int ab)", "a"));
        assertEquals(13, SignatureFormat.indexOfToken("f(int a, int ab)", "ab"));
    }

    @Test
    void perSignatureActiveParameterWinsOverHelpLevel() {
        SignatureInformation si = sig("f(int a, int b)", "int a", "int b");
        si.setActiveParameter(1);
        SignatureHelp help = new SignatureHelp(List.of(si), 0, 0); // help says param 0; signature says 1
        var a = SignatureFormat.resolve(help);
        assertEquals("int b", a.label().substring(a.paramStart(), a.paramEnd()));
    }

    @Test
    void outOfRangeActiveParameterRendersUnbolded() {
        SignatureHelp help = new SignatureHelp(List.of(sig("f(int a)", "int a")), 0, 5); // past the last param
        var a = SignatureFormat.resolve(help);
        assertEquals(0, a.paramStart());
        assertEquals(0, a.paramEnd());
    }

    @Test
    void emptyOrNullHelpResolvesToNull() {
        assertNull(SignatureFormat.resolve(null));
        assertNull(SignatureFormat.resolve(new SignatureHelp(List.of(), 0, 0)));
    }

    @Test
    void activeSignatureIndexIsClamped() {
        SignatureHelp help = new SignatureHelp(List.of(sig("f()"), sig("f(int a)", "int a")), 9, 0);
        var a = SignatureFormat.resolve(help);
        assertEquals("f(int a)", a.label()); // clamped to the last signature, not an exception
        assertEquals(2, a.total());
    }
}
