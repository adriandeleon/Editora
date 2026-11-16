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
}
