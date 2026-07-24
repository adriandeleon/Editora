package com.editora.lsp;

import java.util.Random;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextSyncDiff} (#678): the single-splice diff behind incremental document sync. A wrong splice
 * silently corrupts the server's copy of the document forever — hence the seeded randomized convergence
 * test on top of the edge cases.
 */
class TextSyncDiffTest {

    /** Applies a delta the way a server would — splice [start,end) of the old text with the replacement. */
    private static String apply(String oldText, TextSyncDiff.Delta d) {
        return oldText.substring(0, d.start()) + d.replacement() + oldText.substring(d.end());
    }

    private static void assertConverges(String oldText, String newText) {
        TextSyncDiff.Delta d = TextSyncDiff.diff(oldText, newText);
        if (d == null) {
            assertEquals(oldText, newText, "null delta claims identity");
            return;
        }
        assertEquals(newText, apply(oldText, d), "old=" + oldText + " new=" + newText);
    }

    @Test
    void insertDeleteReplaceAtAllPositions() {
        assertConverges("hello world", "hello brave world"); // insert middle
        assertConverges("hello", "Xhello"); // insert start
        assertConverges("hello", "helloX"); // insert end
        assertConverges("hello world", "world"); // delete start
        assertConverges("hello world", "hello"); // delete end
        assertConverges("hello world", "hello_world"); // replace middle
        assertConverges("", "abc"); // from empty
        assertConverges("abc", ""); // to empty
        assertConverges("aaaa", "aaaaa"); // repeated chars (prefix/suffix overlap trap)
        assertConverges("abab", "ababab");
    }

    @Test
    void identicalTextsDiffToNull() {
        assertNull(TextSyncDiff.diff("same", "same"));
        assertNull(TextSyncDiff.diff("", ""));
    }

    @Test
    void surrogatePairsAreNeverSplit() {
        // The emoji is two UTF-16 units; edits beside it must not put half a pair in the replacement.
        String smile = "😀";
        String rocket = "🚀";
        assertConverges("a" + smile + "b", "a" + rocket + "b"); // pairs share the high surrogate!
        assertConverges(smile, rocket);
        assertConverges("x" + smile, "x" + smile + smile);
        assertConverges(smile + "x", rocket + "x");
        // The replacement string itself must be well-formed (no lone surrogate at its edges).
        TextSyncDiff.Delta d = TextSyncDiff.diff("a" + smile + "b", "a" + rocket + "b");
        String rep = d.replacement();
        if (!rep.isEmpty()) {
            assertFalse(Character.isLowSurrogate(rep.charAt(0)), "replacement starts mid-pair");
            assertFalse(Character.isHighSurrogate(rep.charAt(rep.length() - 1)), "replacement ends mid-pair");
        }
    }

    /** 2,000 random edit steps (seeded — reproducible) must all converge byte-for-byte. */
    @Test
    void randomizedConvergence() {
        Random rnd = new Random(42);
        String alphabet = "ab\ncd 😀xyz{}();\t";
        String doc = "";
        for (int i = 0; i < 2000; i++) {
            String next = mutate(doc, rnd, alphabet);
            assertConverges(doc, next);
            doc = next;
        }
    }

    private static String mutate(String doc, Random rnd, String alphabet) {
        StringBuilder sb = new StringBuilder(doc);
        int op = rnd.nextInt(3);
        int at = doc.isEmpty() ? 0 : rnd.nextInt(doc.length() + 1);
        // Never split a surrogate pair in the *generated* document (the editor never does either).
        if (at > 0 && at < sb.length() && Character.isLowSurrogate(sb.charAt(at))) {
            at--;
        }
        if (op == 0 || doc.isEmpty()) { // insert 1..8 units from the alphabet (pairs kept together)
            int n = 1 + rnd.nextInt(4);
            StringBuilder ins = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int a = rnd.nextInt(alphabet.length());
                if (Character.isLowSurrogate(alphabet.charAt(a))) {
                    a--;
                }
                if (Character.isHighSurrogate(alphabet.charAt(a))) {
                    ins.append(alphabet, a, a + 2);
                } else {
                    ins.append(alphabet.charAt(a));
                }
            }
            sb.insert(at, ins);
        } else { // delete (op 1) or replace (op 2) a short run
            int len = Math.min(1 + rnd.nextInt(6), sb.length() - at);
            int end = at + len;
            if (end > 0 && end < sb.length() && Character.isLowSurrogate(sb.charAt(end))) {
                end--;
            }
            if (end > at) {
                sb.delete(at, end);
            }
            if (op == 2) {
                sb.insert(Math.min(at, sb.length()), "R");
            }
        }
        return sb.toString();
    }

    @Test
    void rangeOfComputesUtf16LineColumns() {
        String text = "one\ntwo\nthree";
        assertEquals(range(0, 0, 0, 3), TextSyncDiff.rangeOf(text, 0, 3)); // "one"
        assertEquals(range(1, 0, 1, 3), TextSyncDiff.rangeOf(text, 4, 7)); // "two"
        assertEquals(range(0, 3, 1, 0), TextSyncDiff.rangeOf(text, 3, 4)); // the newline itself
        assertEquals(range(2, 5, 2, 5), TextSyncDiff.rangeOf(text, 13, 13)); // empty at EOF
        // Columns are UTF-16 units: the emoji counts as 2.
        String emoji = "😀abc";
        assertEquals(range(0, 2, 0, 3), TextSyncDiff.rangeOf(emoji, 2, 3)); // "a" sits at unit column 2
    }

    private static Range range(int sl, int sc, int el, int ec) {
        return new Range(new Position(sl, sc), new Position(el, ec));
    }

    @Test
    void changeSyncKindReadsBothCapabilityForms() {
        assertNull(LanguageServerSession.changeSyncKind(null));
        var caps = new org.eclipse.lsp4j.ServerCapabilities();
        assertNull(LanguageServerSession.changeSyncKind(caps));
        caps.setTextDocumentSync(org.eclipse.lsp4j.TextDocumentSyncKind.Incremental);
        assertEquals(org.eclipse.lsp4j.TextDocumentSyncKind.Incremental, LanguageServerSession.changeSyncKind(caps));
        var opts = new org.eclipse.lsp4j.TextDocumentSyncOptions();
        opts.setChange(org.eclipse.lsp4j.TextDocumentSyncKind.Full);
        var caps2 = new org.eclipse.lsp4j.ServerCapabilities();
        caps2.setTextDocumentSync(opts);
        assertEquals(org.eclipse.lsp4j.TextDocumentSyncKind.Full, LanguageServerSession.changeSyncKind(caps2));
        assertTrue(true);
    }
}
