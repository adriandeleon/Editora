package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * Repairs Java string values that java-debug sends with their emoji (any character outside the Basic
 * Multilingual Plane) garbled — follow-up 4 of the Java debugging review.
 *
 * <p>The garbling happens before Editora sees the value. java-debug reads strings through the Eclipse JDI inside
 * jdtls, whose {@code org.eclipse.jdi.internal.jdwp.JdwpString.read} decodes the VM's UTF-8 knowing only 1-, 2-
 * and 3-byte sequences. The VM sends a supplementary character as one 4-byte sequence ({@code F0 9F 98 80} for
 * 😀); the decoder takes its first three bytes as a 3-byte sequence and the fourth as a character of its own.
 * So 😀 arrives as two characters: {@code U+07D8} and {@code U+0080}. The debugged program's own string is fine.
 *
 * <p>The repair is exact and asks the program nothing it would run: from the two characters the four original
 * bytes, and so the code point, can be rebuilt. But a real text can hold such a pair too ({@code "о»"} — a
 * Cyrillic letter, then a closing guillemet — rebuilds into a valid code point), so a rebuilt value is used only
 * when it matches the string's own characters, read from its {@code value} array (the bytes of the string in
 * the JVM, which come over JDWP as numbers and are not garbled). A value that cannot be checked is left as
 * java-debug sent it.
 */
final class JdwpStringRepair {

    private JdwpStringRepair() {}

    /** A longer string is left alone: checking it would fetch twice its length in array elements. */
    static final int MAX_CHECKED_CHARS = 10_000;

    /** The code point the garbled pair {@code c1 c2} stands for, or -1 when they are not such a pair. */
    static int codePoint(char c1, char c2) {
        if (c1 < 0x400 || c1 > 0x4FFF || c2 < 0x80 || c2 > 0xBF) {
            return -1; // the decoder's char from a lead byte F0..F4 and a continuation byte, then the 4th byte
        }
        int lead = 0xF0 | (c1 >> 12);
        int b2 = 0x80 | ((c1 >> 6) & 0x3F);
        int b3 = 0x80 | (c1 & 0x3F);
        int cp = ((lead & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (c2 & 0x3F);
        return cp >= 0x10000 && cp <= 0x10FFFF ? cp : -1;
    }

    /** Whether {@code display} may hold a garbled supplementary character. */
    static boolean suspect(String display) {
        if (display == null) {
            return false;
        }
        for (int i = 0; i + 1 < display.length(); i++) {
            if (codePoint(display.charAt(i), display.charAt(i + 1)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code display} with every garbled pair rebuilt into the character it stands for. */
    static String rebuild(String display) {
        StringBuilder out = new StringBuilder(display.length());
        for (int i = 0; i < display.length(); i++) {
            int cp = i + 1 < display.length() ? codePoint(display.charAt(i), display.charAt(i + 1)) : -1;
            if (cp >= 0) {
                out.appendCodePoint(cp);
                i++;
            } else {
                out.append(display.charAt(i));
            }
        }
        return out.toString();
    }

    /** {@code display} without the quotes java-debug puts around a string value. */
    static String unquote(String display) {
        return display.length() >= 2 && display.startsWith("\"") && display.endsWith("\"")
                ? display.substring(1, display.length() - 1)
                : display;
    }

    /**
     * The characters of a UTF-16 ({@code coder} 1) string from its {@code value} array's elements, in the given
     * byte order — the JVM's own, which a caller cannot know, so both are tried; null when an element is not a
     * byte.
     */
    static @Nullable String utf16(List<DapModels.VariableInfo> bytes, boolean littleEndian) {
        if (bytes.size() % 2 != 0) {
            return null;
        }
        StringBuilder out = new StringBuilder(bytes.size() / 2);
        for (int i = 0; i + 1 < bytes.size(); i += 2) {
            Integer first = byteOf(bytes.get(i));
            Integer second = byteOf(bytes.get(i + 1));
            if (first == null || second == null) {
                return null;
            }
            int hi = littleEndian ? second : first;
            int lo = littleEndian ? first : second;
            out.append((char) (((hi & 0xFF) << 8) | (lo & 0xFF)));
        }
        return out.toString();
    }

    private static @Nullable Integer byteOf(DapModels.VariableInfo v) {
        try {
            return Integer.parseInt(v.value().strip());
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    /**
     * {@code display} — a value of type {@code type} whose children are {@code ref} — repaired when it is a
     * garbled string the JVM's own characters confirm; otherwise {@code display}. Never fails.
     */
    static CompletableFuture<String> repair(DapClient client, String display, @Nullable String type, int ref) {
        if (ref <= 0 || !"String".equals(type) || !suspect(display) || display.length() > MAX_CHECKED_CHARS + 2) {
            return CompletableFuture.completedFuture(display);
        }
        String rebuilt = rebuild(display);
        return client.variables(ref)
                .thenCompose(fields -> {
                    DapModels.VariableInfo coder = field(fields, "coder");
                    DapModels.VariableInfo value = field(fields, "value");
                    if (coder == null || !"1".equals(coder.value().strip()) || value == null) {
                        return CompletableFuture.completedFuture(display); // Latin-1 holds no such character
                    }
                    return client.variables(value.variablesReference()).thenApply(bytes -> {
                        String text = unquote(rebuilt);
                        return text.equals(utf16(bytes, true)) || text.equals(utf16(bytes, false)) ? rebuilt : display;
                    });
                })
                .exceptionally(e -> display);
    }

    private static DapModels.@Nullable VariableInfo field(List<DapModels.VariableInfo> fields, String name) {
        for (DapModels.VariableInfo f : fields) {
            if (name.equals(f.name())) {
                return f;
            }
        }
        return null;
    }

    /** {@code vars} with each garbled string value repaired (see {@link #repair}); the order is kept. */
    static CompletableFuture<List<DapModels.VariableInfo>> repairAll(
            DapClient client, List<DapModels.VariableInfo> vars) {
        if (vars == null || vars.stream().noneMatch(v -> suspect(v.value()))) {
            return CompletableFuture.completedFuture(vars);
        }
        List<CompletableFuture<DapModels.VariableInfo>> each = new ArrayList<>();
        for (DapModels.VariableInfo v : vars) {
            each.add(repair(client, v.value(), v.type(), v.variablesReference())
                    .thenApply(value -> value.equals(v.value())
                            ? v
                            : new DapModels.VariableInfo(
                                    v.name(),
                                    value,
                                    v.type(),
                                    v.variablesReference(),
                                    v.namedVariables(),
                                    v.indexedVariables())));
        }
        return CompletableFuture.allOf(each.toArray(new CompletableFuture<?>[0]))
                .thenApply(done -> each.stream().map(CompletableFuture::join).toList());
    }

    /** {@code result} with its value repaired (see {@link #repair}). */
    static CompletableFuture<DapModels.EvalResult> repair(DapClient client, DapModels.EvalResult result) {
        if (result == null || result.failed() || !suspect(result.result())) {
            return CompletableFuture.completedFuture(result);
        }
        return repair(client, result.result(), result.type(), result.variablesReference())
                .thenApply(value -> value.equals(result.result())
                        ? result
                        : new DapModels.EvalResult(
                                value,
                                result.variablesReference(),
                                result.type(),
                                result.namedVariables(),
                                result.indexedVariables(),
                                false));
    }
}
