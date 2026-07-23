package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;

/**
 * Resolves an LSP {@link SignatureHelp} into what the popup renders (#674): the active signature's label,
 * the {@code [start,end)} span of the <b>active parameter</b> inside that label (to bold), the signature's
 * documentation, and a "n of m" overload count. Pure of JavaFX; unit-tested.
 *
 * <p>The active-parameter span prefers the server's label <i>offsets</i> (precise — that's why the client
 * capability declares {@code labelOffsetSupport}); a string-label parameter falls back to a whole-token
 * substring search so {@code foo(int a, int ab)} can't bold the {@code a} inside {@code ab}. No span ⇒
 * {@code start == end == 0} and the label renders unbolded.
 */
public final class SignatureFormat {

    private SignatureFormat() {}

    /** The resolved rendering: label + the active parameter's [start,end) within it + doc + overload count. */
    public record Active(String label, int paramStart, int paramEnd, String documentation, int index, int total) {}

    /** Resolves {@code help} to the active signature's rendering, or null when there is nothing to show. */
    public static Active resolve(SignatureHelp help) {
        if (help == null || help.getSignatures() == null || help.getSignatures().isEmpty()) {
            return null;
        }
        List<SignatureInformation> sigs = help.getSignatures();
        int index = clamp(help.getActiveSignature() == null ? 0 : help.getActiveSignature(), sigs.size());
        SignatureInformation sig = sigs.get(index);
        if (sig == null || sig.getLabel() == null) {
            return null;
        }
        String label = sig.getLabel();
        int[] span = activeParamSpan(sig, help.getActiveParameter(), label);
        String doc = documentationOf(sig);
        return new Active(label, span[0], span[1], doc, index, sigs.size());
    }

    /** The active parameter's [start,end) within {@code label}, or {0,0} when unknown. */
    private static int[] activeParamSpan(SignatureInformation sig, Integer helpActiveParam, String label) {
        List<ParameterInformation> params = sig.getParameters();
        if (params == null || params.isEmpty()) {
            return new int[] {0, 0};
        }
        // The per-signature activeParameter (LSP 3.16) wins over the help-level one.
        Integer active = sig.getActiveParameter() != null ? sig.getActiveParameter() : helpActiveParam;
        int idx = active == null ? 0 : active;
        if (idx < 0 || idx >= params.size()) {
            return new int[] {0, 0}; // e.g. the caret is past the last parameter of this overload
        }
        ParameterInformation p = params.get(idx);
        if (p == null || p.getLabel() == null) {
            return new int[] {0, 0};
        }
        if (p.getLabel().isRight()) { // precise [start,end) offsets into the signature label
            var two = p.getLabel().getRight();
            int start = clampOffset(two.getFirst() == null ? 0 : two.getFirst(), label.length());
            int end = clampOffset(two.getSecond() == null ? start : two.getSecond(), label.length());
            return end > start ? new int[] {start, end} : new int[] {0, 0};
        }
        String token = p.getLabel().getLeft();
        if (token == null || token.isBlank()) {
            return new int[] {0, 0};
        }
        int at = indexOfToken(label, token);
        return at < 0 ? new int[] {0, 0} : new int[] {at, at + token.length()};
    }

    /**
     * First whole-token occurrence of {@code token} in {@code label} — both neighbors must be
     * non-identifier chars, so the parameter {@code a} never matches inside {@code ab} or {@code param}.
     */
    static int indexOfToken(String label, String token) {
        int from = 0;
        while (from <= label.length() - token.length()) {
            int at = label.indexOf(token, from);
            if (at < 0) {
                return -1;
            }
            boolean leftOk = at == 0 || !Character.isJavaIdentifierPart(label.charAt(at - 1));
            int after = at + token.length();
            boolean rightOk = after >= label.length() || !Character.isJavaIdentifierPart(label.charAt(after));
            if (leftOk && rightOk) {
                return at;
            }
            from = at + 1;
        }
        return -1;
    }

    private static String documentationOf(SignatureInformation sig) {
        if (sig.getDocumentation() == null) {
            return "";
        }
        if (sig.getDocumentation().isLeft()) {
            return sig.getDocumentation().getLeft() == null
                    ? ""
                    : sig.getDocumentation().getLeft();
        }
        var mc = sig.getDocumentation().getRight();
        return mc == null || mc.getValue() == null ? "" : mc.getValue();
    }

    /** Clamps a list index to {@code [0, size-1]}. */
    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(v, size - 1));
    }

    /** Clamps a label offset to {@code [0, length]} — an <i>exclusive</i> end may equal the length. */
    private static int clampOffset(int v, int length) {
        return Math.max(0, Math.min(v, length));
    }
}
