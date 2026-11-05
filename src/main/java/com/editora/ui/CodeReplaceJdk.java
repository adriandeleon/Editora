package com.editora.ui;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Says why java-debug refused a hot code replace when it gives no reason (Java debug review 2, B102). The JVM
 * refuses classes compiled for a newer Java than its own, and java-debug passes that on as "Failed to redefine
 * classes: null". When the program's Java and the one the code was built for are known and differ, that is the
 * reason, and it is said so. Pure.
 */
final class CodeReplaceJdk {

    private CodeReplaceJdk() {}

    /** Whether {@code message} gives no reason: blank, {@code null}, or ending in {@code : null}. */
    static boolean reasonless(@Nullable String message) {
        String m = message == null ? "" : message.strip();
        return m.isEmpty() || m.equals("null") || m.endsWith(": null");
    }

    /**
     * {@code message} said with its reason — "the program runs Java {@code programMajor}, but the code was built
     * for Java {@code builtMajor}" — when it gives none and the two are known and differ; else null.
     */
    static @Nullable String explain(
            @Nullable String message, @Nullable Integer programMajor, @Nullable Integer builtMajor) {
        if (!reasonless(message) || programMajor == null || builtMajor == null || programMajor.equals(builtMajor)) {
            return null;
        }
        return tr("debug.hcr.jdkMismatch", programMajor, builtMajor);
    }

    /** The major version of a runtime chip's label ({@code Java 25.0.1 (Corretto)} → 25), or null. */
    static @Nullable Integer programMajor(@Nullable String chip) {
        if (chip == null) {
            return null;
        }
        java.util.regex.Matcher m = CHIP.matcher(chip);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static final java.util.regex.Pattern CHIP = java.util.regex.Pattern.compile("^Java (\\d{1,3})(?![\\d])");
}
