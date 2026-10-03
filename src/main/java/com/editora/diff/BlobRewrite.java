package com.editora.diff;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;

/**
 * Rebuilds a stored blob's <em>bytes</em> after a line-level edit of its decoded text, for hunk staging.
 *
 * <p>The diff viewer works on decoded text split into lines, which forgets two things a blob must keep:
 * each line's own terminator (a CRLF or mixed-ending file) and the charset the bytes were written in. A
 * text patch generated from that view never applied to a CRLF blob, and feeding it to Git as UTF-8 could
 * splice UTF-8 bytes into a Latin-1 blob. This class goes the other way: it takes the original bytes and
 * the desired text and produces the desired bytes, where
 *
 * <ul>
 *   <li>every line the edit did not touch keeps its original terminator,
 *   <li>new or changed lines take the blob's dominant terminator,
 *   <li>the result is encoded in the blob's charset, with its byte-order mark if it had one.
 * </ul>
 *
 * <p>It refuses (returns {@code null}) rather than guess whenever the bytes could not be reproduced exactly:
 * the displayed text is not what the blob decodes to, the blob does not round-trip through the charset
 * (malformed input), or the new text contains a character the charset cannot represent. Pure and
 * unit-tested.
 */
public final class BlobRewrite {

    private BlobRewrite() {}

    /** One line of a document with the exact terminator that followed it ({@code ""} for an unterminated last line). */
    private record Line(String text, String terminator) {}

    /**
     * The bytes of {@code original} after its decoded text is changed from {@code beforeText} to
     * {@code afterText}, or {@code null} when that cannot be done losslessly.
     *
     * @param original the stored blob (empty for a path not yet in the index)
     * @param charset the charset {@code original} is decoded with
     * @param bom the charset's byte-order mark; honoured only when {@code original} actually starts with it
     * @param beforeText the decoded text the edit was computed against (must equal the decoded blob)
     * @param afterText the desired decoded text, line terminators not significant except at end of file
     */
    public static byte[] rewrite(byte[] original, Charset charset, byte[] bom, String beforeText, String afterText) {
        byte[] source = original == null ? new byte[0] : original;
        byte[] mark = bom != null && bom.length > 0 && startsWith(source, bom) ? bom : new byte[0];
        String decoded = new String(source, mark.length, source.length - mark.length, charset);
        if (!decoded.equals(beforeText == null ? "" : beforeText)) {
            return null; // the view is not showing this blob
        }
        if (!Arrays.equals(source, encode(decoded, charset, mark))) {
            return null; // lossy decode: re-encoding would already change bytes the user never touched
        }
        List<Line> before = split(decoded);
        DiffText after = DiffText.parse(afterText == null ? "" : afterText);
        String dominant = DiffText.parse(decoded).lineSeparator();

        // Map each line of the desired text back to the original line it is an unchanged copy of (or -1).
        List<String> beforeLines = before.stream().map(Line::text).toList();
        int[] origin = new int[after.lines().size()];
        Arrays.fill(origin, -1);
        int beforeAt = 0;
        int afterAt = 0;
        for (AbstractDelta<String> delta :
                DiffUtils.diff(beforeLines, after.lines()).getDeltas()) {
            while (beforeAt < delta.getSource().getPosition()) {
                origin[afterAt++] = beforeAt++;
            }
            beforeAt += delta.getSource().size();
            afterAt += delta.getTarget().size();
        }
        while (afterAt < origin.length && beforeAt < before.size()) {
            origin[afterAt++] = beforeAt++;
        }

        StringBuilder out = new StringBuilder(decoded.length() + 64);
        for (int i = 0; i < origin.length; i++) {
            out.append(after.lines().get(i));
            boolean last = i == origin.length - 1;
            if (last && !after.finalNewline()) {
                break; // the desired text ends without a terminator
            }
            String kept = origin[i] >= 0 ? before.get(origin[i]).terminator() : "";
            out.append(kept.isEmpty() ? dominant : kept);
        }
        String rebuilt = out.toString();
        try {
            charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(rebuilt));
        } catch (CharacterCodingException unrepresentable) {
            return null; // String.getBytes would silently write '?' for it
        }
        return encode(rebuilt, charset, mark);
    }

    private static byte[] encode(String text, Charset charset, byte[] mark) {
        byte[] body = text.getBytes(charset);
        if (mark.length == 0) {
            return body;
        }
        byte[] out = Arrays.copyOf(mark, mark.length + body.length);
        System.arraycopy(body, 0, out, mark.length, body.length);
        return out;
    }

    /** Splits like {@link DiffText#parse} but keeps the terminator that followed each line. */
    private static List<Line> split(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                boolean crlf = i + 1 < text.length() && text.charAt(i + 1) == '\n';
                lines.add(new Line(text.substring(start, i), crlf ? "\r\n" : "\r"));
                if (crlf) {
                    i++;
                }
                start = i + 1;
            } else if (c == '\n') {
                lines.add(new Line(text.substring(start, i), "\n"));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(new Line(text.substring(start), ""));
        }
        return lines;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
