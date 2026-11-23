package com.editora.dap;

import java.math.BigInteger;

import com.editora.dap.NumberViews.Radix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Show Value As, on what each debugger really writes ({@code ValueFormatsProbeFxTest} saw these). */
class NumberViewsTest {

    private static String as(String value, String type, String language, Radix radix) {
        return NumberViews.format(value, type, language, radix);
    }

    @Test
    void aPlainNumberInEveryRadix() {
        assertEquals("0x2a", as("42", "int", "java", Radix.HEX));
        assertEquals("0b101010", as("42", "int", "java", Radix.BINARY));
        assertEquals("0o52", as("42", "int", "java", Radix.OCTAL));
        assertEquals("42", as("42", "int", "java", Radix.DECIMAL));
        assertEquals("'*'", as("42", "int", "java", Radix.CHARACTER));
        assertNull(as("42", "int", "java", Radix.DEFAULT), "the debugger's own");
    }

    @Test
    void aNegativeNumberIsItsTwosComplementInTheTypesWidth() {
        assertEquals("0xffffffd6", as("-42", "int", "java", Radix.HEX));
        assertEquals("0xff", as("-1", "byte", "java", Radix.HEX));
        assertEquals("0b11111111", as("-1", "byte", "java", Radix.BINARY));
        assertEquals("0xffffffffffffffff", as("-1", "long long", "cpp", Radix.HEX));
        // No width to go by: Python's int, JavaScript's number, a debugger that names no type (GDB, Delve).
        assertEquals("-0x2a", as("-42", "int", "python", Radix.HEX));
        assertEquals("-0x2a", as("-42", "number", "javascript", Radix.HEX));
        assertEquals("-0x2a", as("-42", "", "go", Radix.HEX));
    }

    @Test
    void hexadecimalFromShowNumbersAsHexadecimalIsReadBackInTheTypesWidth() {
        // java-debug sign-extends an int to 64 bits; debugpy keeps the sign; GDB pads nothing.
        assertEquals("-42", as("0xffffffffffffffd6", "int", "java", Radix.DECIMAL));
        assertEquals("-1", as("0xffffffffffffffff", "byte", "java", Radix.DECIMAL));
        assertEquals("-42", as("-0x2a", "int", "python", Radix.DECIMAL));
        assertEquals("42", as("0x2a", "", "cpp", Radix.DECIMAL));
        assertEquals("4294967254", as("0xffffffd6", "", "cpp", Radix.DECIMAL), "no type: read as it is written");
    }

    @Test
    void eachDebuggersOwnWayOfWritingANumber() {
        assertEquals("0xff", as("255 = 0xff", "", "go", Radix.HEX), "Delve");
        assertEquals("65", as("65 'A'", "", "cpp", Radix.DECIMAL), "GDB's char");
        assertEquals("-1", as("-1 '\\377'", "", "cpp", Radix.DECIMAL));
        assertEquals("0x10000000000", as("1099511627776n", "bigint", "javascript", Radix.HEX), "a BigInt");
        assertEquals("7", as("7", "Integer", "java", Radix.DECIMAL), "a boxed Integer");
    }

    @Test
    void aCharacterIsReadAndShown() {
        // CodeLLDB: 'A', '\xff' (signed char -1, unsigned char 255); java-debug: A.
        assertEquals("65", as("'A'", "char", "cpp", Radix.DECIMAL));
        assertEquals("-1", as("'\\xff'", "signed char", "cpp", Radix.DECIMAL));
        assertEquals("255", as("'\\xff'", "unsigned char", "cpp", Radix.DECIMAL));
        assertEquals("0xff", as("'\\xff'", "signed char", "cpp", Radix.HEX));
        assertEquals("10", as("'\\n'", "char", "cpp", Radix.DECIMAL));
        assertEquals("65", as("A", "char", "java", Radix.DECIMAL));
        assertEquals("0x41", as("A", "char", "java", Radix.HEX));
        assertEquals("55", as("7", "char", "java", Radix.DECIMAL), "the character 7, not the number");
        assertEquals("0x37", as("7", "char", "java", Radix.HEX));
        assertEquals("32", as(" ", "char", "java", Radix.DECIMAL), "a space");
        assertEquals("7", as("7", "int", "java", Radix.DECIMAL), "an int is its number");
        assertEquals("'A'", as("65", "", "go", Radix.CHARACTER), "a rune");
        assertEquals("'\\n'", as("10", "int", "java", Radix.CHARACTER));
        assertEquals("'\\x01'", as("1", "unsigned char", "cpp", Radix.CHARACTER));
        assertEquals("'\\xff'", as("-1", "signed char", "cpp", Radix.CHARACTER), "a C byte, not a Latin-1 letter");
        assertEquals("'é'", as("233", "char", "java", Radix.CHARACTER));
    }

    @Test
    void whatIsNotAWholeNumberIsLeftAlone() {
        for (String[] v : new String[][] {
            {"true", "boolean", "java"}, {"True", "bool", "python"}, {"1.5", "double", "java"},
            {"1.500000", "double", "java"}, {"'hello'", "str", "python"}, {"String[0]@8", "String[]", "java"},
            {"Integer@0xa \"7\"", "Integer", "java"}, {"0x1", "bool", "python"}, {"'A'", "str", "python"}
        }) {
            assertFalse(NumberViews.isNumber(v[0], v[1], v[2]), v[0] + " : " + v[1]);
            assertNull(as(v[0], v[1], v[2], Radix.HEX));
        }
        assertTrue(NumberViews.isNumber("42", null, "go"));
    }

    @Test
    void theWidthOfEachLanguagesTypes() {
        assertEquals(new NumberViews.Width(16, false), NumberViews.width("char", "java"));
        assertEquals(new NumberViews.Width(8, true), NumberViews.width("char", "cpp"));
        assertEquals(new NumberViews.Width(32, false), NumberViews.width("char", "rust"));
        assertEquals(new NumberViews.Width(8, false), NumberViews.width("byte", "go"));
        assertEquals(new NumberViews.Width(8, true), NumberViews.width("byte", "java"));
        assertEquals(new NumberViews.Width(8, false), NumberViews.width("const unsigned char &", "cpp"));
        assertEquals(new NumberViews.Width(64, false), NumberViews.width("u64", "rust"));
        assertEquals(new NumberViews.Width(32, true), NumberViews.width("java.lang.Integer", "java"));
        assertEquals(0, NumberViews.width("int", "python").bits(), "Python's int has no width");
        assertEquals(BigInteger.valueOf(-42), NumberViews.parse("-42", "int", "go"));
    }

    @Test
    void everyTypeNameReadsAsItsWidthAndSign() {
        String[][] cases = {
            {"signed char", "8", "true"}, {"uint8_t", "8", "false"}, {"short int", "16", "true"},
            {"unsigned short", "16", "false"}, {"char16_t", "16", "false"}, {"signed", "32", "true"},
            {"rune", "32", "true"}, {"unsigned", "32", "false"}, {"char32_t", "32", "false"},
            {"wchar_t", "32", "true"}, {"long long", "64", "true"}, {"isize", "64", "true"},
            {"ptrdiff_t", "64", "true"}, {"size_t", "64", "false"}, {"uintptr", "64", "false"},
            {"unsigned long long int", "64", "false"}, {"__int128", "128", "true"}, {"u128", "128", "false"},
            {"unsigned __int128", "128", "false"}, {"java.lang.Short", "16", "true"}, {"Byte", "8", "false"},
            {"Long", "64", "true"}, {"volatile int *", "32", "true"}, {"MyStruct", "0", "true"}
        };
        for (String[] c : cases) {
            NumberViews.Width w = NumberViews.width(c[0], "cpp");
            assertEquals(Integer.parseInt(c[1]), w.bits(), c[0]);
            assertEquals(Boolean.parseBoolean(c[2]), w.signed(), c[0]);
        }
        assertEquals(0, NumberViews.width(null, "cpp").bits());
        assertEquals(0, NumberViews.width("  ", "cpp").bits());
        assertEquals(32, NumberViews.width("int", null).bits(), "no language: a C-like width");
        for (String dynamic : new String[] {"javascript", "typescript", "ruby", "php"}) {
            assertEquals(0, NumberViews.width("int", dynamic).bits(), dynamic);
        }
    }

    @Test
    void aBaseTypeDropsQualifiersReferencesAndNamespaces() {
        assertEquals("", NumberViews.baseType(null));
        assertEquals("unsigned char", NumberViews.baseType("const volatile unsigned char &"));
        assertEquals("int", NumberViews.baseType("java.lang.Integer"));
        assertEquals("boolean", NumberViews.baseType("java.lang.Boolean"));
        assertEquals("char", NumberViews.baseType("Character"));
        assertEquals("unsigned long", NumberViews.baseType("static unsigned long**"));
        assertEquals("a.b c", NumberViews.baseType("a.b c"), "a dotted name with a space is no namespace");
    }

    @Test
    void booleansAndFloatsAreNoNumbersWhateverTheyLookLike() {
        assertNull(NumberViews.parse(null, "int", "cpp"));
        for (String t : new String[] {"bool", "boolean", "_Bool", "java.lang.Boolean"}) {
            assertNull(NumberViews.parse("1", t, "cpp"), t);
        }
        for (String t : new String[] {"float", "double", "long double", "float64", "f32", "f64"}) {
            assertNull(NumberViews.parse("3", t, "rust"), t);
            assertNull(NumberViews.parse("0x10", t, "rust"), t);
        }
        assertEquals(BigInteger.valueOf(-16), NumberViews.parse("-0x10", "int", "cpp"));
        assertEquals(BigInteger.valueOf(255), NumberViews.parse("0xff", "unknown_t", "cpp"));
        assertEquals(BigInteger.valueOf(42), NumberViews.parse("+42", "int", "cpp"));
    }

    @Test
    void aJvmCharIsItsCodeAndACCharItsLiteral() {
        assertEquals(BigInteger.valueOf(55), NumberViews.parse("7", "char", "java"), "java-debug shows '7' bare");
        assertEquals(BigInteger.valueOf(32), NumberViews.parse(" ", "char", "kotlin"));
        assertEquals(BigInteger.valueOf(65), NumberViews.parse("A", "java.lang.Character", "scala"));
        assertEquals(BigInteger.valueOf(-1), NumberViews.parse("'\\377'", "char", "cpp"), "octal, 8-bit signed");
        assertEquals(BigInteger.valueOf(0x263a), NumberViews.parse("'\\u263a'", "wchar_t", "cpp"));
        assertEquals(BigInteger.valueOf(0x1f600), NumberViews.parse("'\\U0001f600'", "char32_t", "cpp"));
        assertEquals(BigInteger.valueOf(66), NumberViews.parse("B", "char16_t", "cpp"), "a bare character");
        assertNull(NumberViews.parse("'ab'", "char", "cpp"), "two characters are no literal");
        assertNull(NumberViews.parse("hello", "char", "cpp"));
    }

    @Test
    void everyEscapeReadsAsItsCode() {
        String[][] cases = {
            {"'\\n'", "10"}, {"'\\t'", "9"}, {"'\\r'", "13"}, {"'\\a'", "7"}, {"'\\b'", "8"},
            {"'\\f'", "12"}, {"'\\v'", "11"}, {"'\\e'", "27"}, {"'\\\\'", "92"}, {"'\\''", "39"},
            {"'\\\"'", "34"}, {"'\\x41'", "65"}, {"'\\101'", "65"}, {"'z'", "122"}, {"Q", "81"}
        };
        for (String[] c : cases) {
            assertEquals(Integer.valueOf(c[1]), NumberViews.charCode(c[0]), c[0]);
        }
        assertNull(NumberViews.charCode("'\\q'"), "an unknown escape");
        assertNull(NumberViews.charCode("nope"));
    }

    @Test
    void aCodeShownAsACharacterUsesTheLiteralsEscapes() {
        String[][] cases = {
            {"0", "'\\0'"},
            {"7", "'\\a'"},
            {"8", "'\\b'"},
            {"9", "'\\t'"},
            {"10", "'\\n'"},
            {"11", "'\\v'"},
            {"12", "'\\f'"},
            {"13", "'\\r'"},
            {"39", "'\\''"},
            {"92", "'\\\\'"},
            {"1", "'\\x01'"},
            {"127", "'\\x7f'"},
            {"233", "'\u00e9'"},
            {"1114111", "'\\U0010ffff'"},
            {"55296", "'\\ud800'"},
            {"128512", "'\ud83d\ude00'"}
        };
        for (String[] c : cases) {
            assertEquals(c[1], as(c[0], "int", "java", Radix.CHARACTER), c[0]);
        }
        assertEquals("'\\xe9'", as("233", "unsigned char", "cpp", Radix.CHARACTER), "a C byte, not Latin-1");
        assertEquals("'\\xff'", as("-1", "char", "cpp", Radix.CHARACTER), "a negative char wraps to its byte");
        assertNull(as("1114112", "int", "java", Radix.CHARACTER), "past the last code point");
        assertNull(as("-5", "int", "python", Radix.CHARACTER), "a negative number of no width");
        assertNull(as("1.5", "double", "java", Radix.HEX));
    }
}
