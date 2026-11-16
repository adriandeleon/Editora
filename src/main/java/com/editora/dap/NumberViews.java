package com.editora.dap;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Show Value As: one whole number in the radix the user chose for it (IntelliJ's and CLion's View as, Visual
 * Studio's format specifiers), read from the debugger's own text and its type. Pure.
 *
 * <p>Each debugger writes a whole number its own way, and every one of these is read (probed against the real
 * debuggers, {@code ValueFormatsProbeFxTest}): a plain number ({@code 42}, {@code -42}; js-debug's BigInt {@code 42n}),
 * hexadecimal from Show Numbers as Hexadecimal ({@code 0x2a}, debugpy's {@code -0x2a}, java-debug's {@code
 * 0xffffffffffffffd6} for an {@code int}), Delve's {@code 255 = 0xff}, GDB's character {@code 65 'A'} or {@code -1
 * '\377'}, CodeLLDB's {@code 'A'} or {@code '\xff'}, and java-debug's bare {@code A} for a {@code char}.
 *
 * <p>The type gives the number's width and sign ({@link #width}): a negative number is shown in hexadecimal, binary
 * and octal as its two's complement in that width, and a hexadecimal value read back is put in it ({@code
 * 0xffffffd6} is -42 for an {@code int}). With no type (GDB, Delve) or one of no width (Python's {@code int},
 * JavaScript's {@code number}) a negative number keeps its sign ({@code -0x2a}).
 */
public final class NumberViews {

    /** How a number is shown. */
    public enum Radix {
        /** As the debugger shows it. */
        DEFAULT,
        DECIMAL,
        HEX,
        BINARY,
        OCTAL,
        /** The character with that code. */
        CHARACTER
    }

    /** A number's width in bits and whether it is signed; width 0: unknown or unbounded. */
    public record Width(int bits, boolean signed) {
        static final Width UNKNOWN = new Width(0, true);
    }

    private static final Pattern DECIMAL = Pattern.compile("([+-]?)(\\d+)n?");
    private static final Pattern HEX = Pattern.compile("([+-]?)0[xX]([0-9a-fA-F]+)");
    /** Delve: {@code 255 = 0xff}; GDB: {@code 65 'A'}. */
    private static final Pattern LEADING = Pattern.compile("([+-]?\\d+)(?: = 0x[0-9a-fA-F]+| '.*')");

    private static final Pattern QUOTED_CHAR = Pattern.compile(
            "'(\\\\x[0-9a-fA-F]{1,2}|\\\\[0-7]{1,3}|\\\\u[0-9a-fA-F]{4}" + "|\\\\U[0-9a-fA-F]{8}|\\\\.|[^\\\\'])'");

    private NumberViews() {}

    /**
     * The whole number the debugger's {@code value} (of type {@code type}) stands for, or null when it is not one: a
     * boolean, a float, a string, an object.
     */
    public static @Nullable BigInteger parse(String value, @Nullable String type, String language) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        String t = baseType(type);
        if (t.equals("bool") || t.equals("boolean") || t.equals("_Bool")) {
            return null;
        }
        Width width = width(type, language);
        Matcher m = DECIMAL.matcher(v);
        if (m.matches() && !isFloatType(t)) {
            BigInteger n = new BigInteger(m.group(2));
            return "-".equals(m.group(1)) ? n.negate() : n;
        }
        m = HEX.matcher(v);
        if (m.matches() && !isFloatType(t)) {
            BigInteger n = new BigInteger(m.group(2), 16);
            if ("-".equals(m.group(1))) {
                return n.negate();
            }
            return width.bits() > 0 ? inWidth(n, width) : n;
        }
        m = LEADING.matcher(v);
        if (m.matches()) {
            return new BigInteger(m.group(1));
        }
        if (isCharType(t)) {
            Integer code = charCode(v);
            if (code != null) {
                BigInteger n = BigInteger.valueOf(code);
                return width.bits() > 0 ? inWidth(n, width) : n;
            }
        }
        return null;
    }

    /**
     * {@code value} (of {@code type}, in a {@code language} session) shown in {@code radix}, or null when it is not a
     * whole number or the radix is {@link Radix#DEFAULT}.
     */
    public static @Nullable String format(String value, @Nullable String type, String language, Radix radix) {
        if (radix == Radix.DEFAULT) {
            return null;
        }
        BigInteger n = parse(value, type, language);
        if (n == null) {
            return null;
        }
        Width width = width(type, language);
        return switch (radix) {
            case DECIMAL -> n.toString();
            case HEX -> withPrefix(n, width, 16, "0x");
            case BINARY -> withPrefix(n, width, 2, "0b");
            case OCTAL -> withPrefix(n, width, 8, "0o");
            case CHARACTER -> character(n, width);
            case DEFAULT -> null;
        };
    }

    /** Whether {@code value} is a whole number Show Value As can show another way. */
    public static boolean isNumber(String value, @Nullable String type, String language) {
        return parse(value, type, language) != null;
    }

    /**
     * The type's width and sign, from its name in {@code language}: a JVM language's {@code char} is 16 bits and
     * unsigned and its {@code byte} signed, Go's {@code byte} is {@code uint8} and its {@code rune} {@code int32},
     * Rust's {@code char} a 32-bit code point, and C's {@code char} 8 bits and signed (as on the platforms here).
     */
    public static Width width(@Nullable String type, String language) {
        String t = baseType(type);
        if (t.isEmpty()) {
            return Width.UNKNOWN;
        }
        switch (language == null ? "" : language) {
            case "python", "javascript", "typescript", "ruby", "php" -> {
                return Width.UNKNOWN; // a whole number of any size
            }
            default -> {}
        }
        boolean jvm = switch (language == null ? "" : language) {
            case "java", "kotlin", "scala", "groovy", "clojure" -> true;
            default -> false;
        };
        if (t.equals("char")) {
            return jvm ? new Width(16, false) : "rust".equals(language) ? new Width(32, false) : new Width(8, true);
        }
        if (t.equals("byte")) {
            return jvm ? new Width(8, true) : new Width(8, false);
        }
        return switch (t) {
            case "signed char", "int8", "i8", "int8_t" -> new Width(8, true);
            case "unsigned char", "uint8", "u8", "uint8_t" -> new Width(8, false);
            case "short", "short int", "signed short", "int16", "i16", "int16_t" -> new Width(16, true);
            case "unsigned short", "unsigned short int", "uint16", "u16", "uint16_t", "char16_t" ->
                new Width(16, false);
            case "int", "signed int", "signed", "int32", "i32", "int32_t", "rune" -> new Width(32, true);
            case "unsigned int", "unsigned", "uint32", "u32", "uint32_t", "char32_t", "wchar_t" ->
                new Width(32, "wchar_t".equals(t));
            case "long",
                    "long int",
                    "signed long",
                    "long long",
                    "long long int",
                    "signed long long",
                    "int64",
                    "i64",
                    "int64_t",
                    "isize",
                    "ssize_t",
                    "intptr_t",
                    "ptrdiff_t" -> new Width(64, true);
            case "unsigned long",
                    "unsigned long int",
                    "unsigned long long",
                    "unsigned long long int",
                    "uint64",
                    "u64",
                    "uint64_t",
                    "usize",
                    "size_t",
                    "uintptr",
                    "uintptr_t" -> new Width(64, false);
            case "i128", "__int128" -> new Width(128, true);
            case "u128", "unsigned __int128" -> new Width(128, false);
            default -> Width.UNKNOWN;
        };
    }

    /**
     * The type as named, without qualifiers, a reference or a namespace: {@code const unsigned char &} reads {@code
     * unsigned char}, {@code java.lang.Integer} reads {@code integer}'s simple name.
     */
    static String baseType(@Nullable String type) {
        if (type == null) {
            return "";
        }
        String t = type.strip()
                .replaceAll("\\b(const|volatile|static|register)\\b", "")
                .replaceAll("[&*]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
        int dot = t.lastIndexOf('.');
        if (dot >= 0 && !t.contains(" ")) {
            t = t.substring(dot + 1);
        }
        return switch (t) {
            case "Integer" -> "int";
            case "Long" -> "long";
            case "Short" -> "short";
            case "Byte" -> "byte";
            case "Boolean" -> "boolean";
            case "Character" -> "char";
            default -> t;
        };
    }

    private static boolean isFloatType(String t) {
        return t.equals("float")
                || t.equals("double")
                || t.equals("long double")
                || t.startsWith("float")
                || t.equals("f32")
                || t.equals("f64");
    }

    private static boolean isCharType(String t) {
        return t.equals("char")
                || t.equals("signed char")
                || t.equals("unsigned char")
                || t.equals("wchar_t")
                || t.equals("char16_t")
                || t.equals("char32_t");
    }

    /** The code of a character written {@code 'A'}, {@code '\xff'}, {@code '\n'} or bare {@code A}; null if none. */
    static @Nullable Integer charCode(String v) {
        if (v.codePointCount(0, v.length()) == 1) {
            return v.codePointAt(0); // java-debug: A
        }
        Matcher m = QUOTED_CHAR.matcher(v);
        if (!m.matches()) {
            return null;
        }
        String c = m.group(1);
        if (!c.startsWith("\\")) {
            return c.codePointAt(0);
        }
        char kind = c.charAt(1);
        return switch (kind) {
            case 'x', 'u', 'U' -> Integer.parseInt(c.substring(2), 16);
            case 'n' -> 10;
            case 't' -> 9;
            case 'r' -> 13;
            case 'a' -> 7;
            case 'b' -> 8;
            case 'f' -> 12;
            case 'v' -> 11;
            case 'e' -> 27;
            case '\\' -> 92;
            case '\'' -> 39;
            case '"' -> 34;
            default -> Character.isDigit(kind) ? Integer.parseInt(c.substring(1), 8) : null;
        };
    }

    /** {@code n} read as a number of {@code width}: its low bits, signed when the type is. */
    static BigInteger inWidth(BigInteger n, Width width) {
        BigInteger mod = BigInteger.ONE.shiftLeft(width.bits());
        BigInteger low = n.mod(mod);
        if (width.signed() && low.testBit(width.bits() - 1)) {
            return low.subtract(mod);
        }
        return low;
    }

    private static String withPrefix(BigInteger n, Width width, int radix, String prefix) {
        if (n.signum() < 0 && width.bits() > 0) {
            BigInteger twos = n.add(BigInteger.ONE.shiftLeft(width.bits()));
            return prefix + twos.toString(radix);
        }
        if (n.signum() < 0) {
            return "-" + prefix + n.negate().toString(radix);
        }
        return prefix + n.toString(radix);
    }

    /** The character with code {@code n}, as a literal: {@code 'A'}, {@code '\n'}, {@code '\x01'}. */
    private static @Nullable String character(BigInteger n, Width width) {
        BigInteger code = n.signum() < 0 && width.bits() > 0 ? n.add(BigInteger.ONE.shiftLeft(width.bits())) : n;
        if (code.signum() < 0 || code.compareTo(BigInteger.valueOf(Character.MAX_CODE_POINT)) > 0) {
            return null;
        }
        int c = code.intValue();
        String shown = switch (c) {
            case 0 -> "\\0";
            case 7 -> "\\a";
            case 8 -> "\\b";
            case 9 -> "\\t";
            case 10 -> "\\n";
            case 11 -> "\\v";
            case 12 -> "\\f";
            case 13 -> "\\r";
            case 39 -> "\\'";
            case 92 -> "\\\\";
            default -> {
                boolean aByte = width.bits() == 8 && c > 0x7f; // a C byte, not a Latin-1 letter
                if (aByte
                        || Character.isISOControl(c)
                        || !Character.isDefined(c)
                        || Character.getType(c) == Character.SURROGATE) {
                    yield c <= 0xff
                            ? String.format(Locale.ROOT, "\\x%02x", c)
                            : String.format(Locale.ROOT, c <= 0xffff ? "\\u%04x" : "\\U%08x", c);
                }
                yield new String(Character.toChars(c));
            }
        };
        return "'" + shown + "'";
    }
}
