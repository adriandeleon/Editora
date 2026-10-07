package com.editora.git;

import java.util.Locale;

/**
 * What became of a {@code git apply}. A plain apply either applies everything or nothing; a three-way apply
 * ({@code --3way}) can also apply <em>with conflicts</em>, which git reports with exit code 1 — the same as a
 * refusal — and the line "Applied patch to '…' with conflicts". Pure.
 */
public enum PatchOutcome {
    APPLIED,
    /** Three-way apply wrote the patch but left conflict markers in at least one file. */
    CONFLICTS,
    /** Nothing was applied; git's message says why. */
    REJECTED;

    public static PatchOutcome classify(boolean ok, String out, String err) {
        if (ok) {
            return APPLIED;
        }
        String text = ((out == null ? "" : out) + "\n" + (err == null ? "" : err)).toLowerCase(Locale.ROOT);
        return text.contains("with conflicts") ? CONFLICTS : REJECTED;
    }
}
