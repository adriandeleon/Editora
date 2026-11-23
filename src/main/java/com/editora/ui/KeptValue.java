package com.editora.ui;

/**
 * A value kept from an earlier stop (Debug History, the changed-value marks): its first {@link #MAX_KEPT} characters,
 * enough for every place an old value is shown, plus the whole value's length and hash, enough to tell whether a later
 * value differs. A 1 MB string local stepped over 200 times kept 200 copies of itself (perf review 2026-11-23, D6).
 *
 * @param prefix the value's first characters: all of it when it is at most {@link #MAX_KEPT} long
 * @param length the whole value's length
 * @param hash the whole value's {@link String#hashCode()}
 */
record KeptValue(String prefix, int length, int hash) {

    /** The most characters kept of one value. A Variables row shows {@link DebugValues#MAX_SHOWN}. */
    static final int MAX_KEPT = 4096;

    static KeptValue of(String value) {
        if (value.length() <= MAX_KEPT) {
            return new KeptValue(value, value.length(), value.hashCode());
        }
        int end = Character.isLowSurrogate(value.charAt(MAX_KEPT)) ? MAX_KEPT - 1 : MAX_KEPT;
        return new KeptValue(value.substring(0, end), value.length(), value.hashCode());
    }

    /** Whether only the first characters were kept. */
    boolean truncated() {
        return prefix.length() < length;
    }

    /** The value as it is shown: the whole of it, or the characters kept and "…". */
    String text() {
        return truncated() ? prefix + "…" : prefix;
    }

    /** Whether {@code value} is (as far as length, hash and the kept characters tell) the value kept. */
    boolean same(String value) {
        return value.length() == length
                && value.hashCode() == hash
                && (truncated() ? value.startsWith(prefix) : value.equals(prefix));
    }

    /** Whether {@code other} kept the same value. */
    boolean same(KeptValue other) {
        return other.length == length && other.hash == hash && other.prefix.equals(prefix);
    }
}
