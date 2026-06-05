package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure label generator for AceJump (avy-style): assigns short, unambiguous typed labels to N jump
 * targets from a key alphabet. One-character labels while they fit; otherwise uniform two-character
 * labels (so no label is a prefix of another). Capped at {@code alphabet²} targets. Unit-tested.
 */
public final class AceLabels {

    /** Home-row-first alphabet (fast to type, low finger travel). */
    public static final String DEFAULT_ALPHABET = "asdfghjklqwertyuiopzxcvbnm";

    private AceLabels() {
    }

    /** Up to {@code count} labels from {@code alphabet} (1-char while they fit, else uniform 2-char). */
    public static List<String> labels(int count, String alphabet) {
        List<String> out = new ArrayList<>();
        if (alphabet == null || alphabet.isEmpty() || count <= 0) {
            return out;
        }
        char[] keys = alphabet.toCharArray();
        int k = keys.length;
        if (count <= k) {
            for (int i = 0; i < count; i++) {
                out.add(String.valueOf(keys[i]));
            }
            return out;
        }
        int max = Math.min(count, k * k);
        for (int i = 0; i < max; i++) {
            out.add(new String(new char[] {keys[i / k], keys[i % k]}));
        }
        return out;
    }
}
