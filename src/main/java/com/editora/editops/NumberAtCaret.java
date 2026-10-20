package com.editora.editops;

import java.math.BigInteger;

import org.jspecify.annotations.Nullable;

/**
 * Increment / decrement the number at a caret, as Vim's {@code Ctrl-A} / {@code Ctrl-X} do: the number the
 * caret is on, or else the first one after it on the same line. Decimal integers (a minus sign right before
 * the digits counts when it is not part of a word or expression like {@code a-1}) and hexadecimal
 * {@code 0x…} numbers, of any size. Zero padding keeps its width ({@code 007} → {@code 008}); a hex number
 * keeps its letter case and width. Pure.
 */
public final class NumberAtCaret {

    private NumberAtCaret() {}

    /**
     * The edit adding {@code delta} to the number at {@code [selStart, selEnd)} — with a selection, the first
     * number inside it — leaving the caret at the number's end; null when there is no number there.
     */
    public static Indenter.@Nullable TabEdit change(String text, int selStart, int selEnd, long delta) {
        int lo = Math.clamp(Math.min(selStart, selEnd), 0, text.length());
        int hi = Math.clamp(Math.max(selStart, selEnd), 0, text.length());
        int lineEnd = text.indexOf('\n', lo);
        int limit = hi > lo ? hi : lineEnd < 0 ? text.length() : lineEnd;
        int[] number = find(text, lo, limit, hi > lo);
        if (number == null) {
            return null;
        }
        String replacement = add(text.substring(number[0], number[1]), delta);
        int caret = number[0] + replacement.length();
        return new Indenter.TabEdit(number[0], number[1], replacement, caret, caret);
    }

    /** {@code [start, end)} of the number at or after {@code from}, starting before {@code limit}; null if none. */
    static int @Nullable [] find(String text, int from, int limit, boolean inside) {
        int start = from;
        if (!inside) {
            int[] hex = hexAround(text, from);
            if (hex != null) {
                return hex;
            }
            while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
                start--; // the caret is on a number: from its first digit
            }
        }
        for (int i = start; i < limit; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                continue;
            }
            if (text.charAt(i) == '0'
                    && i + 2 < text.length()
                    && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')
                    && Character.digit(text.charAt(i + 2), 16) >= 0) {
                return new int[] {i, hexEnd(text, i + 2)};
            }
            int end = i;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            int begin = i > 0 && text.charAt(i - 1) == '-' && !partOfExpression(text, i - 1) ? i - 1 : i;
            return new int[] {begin, end};
        }
        return null;
    }

    /** The {@code 0x…} literal {@code pos} is inside or right after, or null. */
    private static int @Nullable [] hexAround(String text, int pos) {
        int j = Math.min(pos, text.length());
        while (j > 0 && Character.digit(text.charAt(j - 1), 16) >= 0) {
            j--;
        }
        if (j < 2 || (text.charAt(j - 1) != 'x' && text.charAt(j - 1) != 'X') || text.charAt(j - 2) != '0') {
            return null;
        }
        int end = hexEnd(text, j);
        return end > j ? new int[] {j - 2, end} : null;
    }

    private static int hexEnd(String text, int from) {
        int end = from;
        while (end < text.length() && Character.digit(text.charAt(end), 16) >= 0) {
            end++;
        }
        return end;
    }

    /** A minus right after a word character or a closing bracket is subtraction, not a sign. */
    private static boolean partOfExpression(String text, int minus) {
        if (minus == 0) {
            return false;
        }
        char before = text.charAt(minus - 1);
        return Character.isLetterOrDigit(before) || before == '_' || before == ')' || before == ']';
    }

    /** {@code number} plus {@code delta}, written the way the number was. */
    static String add(String number, long delta) {
        if (number.length() > 2 && (number.charAt(1) == 'x' || number.charAt(1) == 'X')) {
            String digits = number.substring(2);
            BigInteger value = new BigInteger(digits, 16).add(BigInteger.valueOf(delta));
            if (value.signum() < 0) {
                value = BigInteger.ZERO; // a hex literal has no sign
            }
            String hex = value.toString(16);
            boolean upper = !digits.equals(digits.toLowerCase(java.util.Locale.ROOT));
            hex = upper ? hex.toUpperCase(java.util.Locale.ROOT) : hex;
            return number.substring(0, 2) + pad(hex, digits.length());
        }
        boolean negative = number.startsWith("-");
        String digits = negative ? number.substring(1) : number;
        BigInteger value = new BigInteger(digits);
        if (negative) {
            value = value.negate();
        }
        value = value.add(BigInteger.valueOf(delta));
        String body = value.abs().toString();
        boolean padded = digits.length() > 1 && digits.charAt(0) == '0';
        if (padded) {
            body = pad(body, digits.length());
        }
        return value.signum() < 0 ? "-" + body : body;
    }

    private static String pad(String digits, int width) {
        return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
    }
}
