package com.editora.dap;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoExpressionsTest {

    @Test
    void theVariablesAndMemberChainsOfBothLinesInOrder() {
        assertEquals(
                List.of("price", "qty", "total", "order.discount"),
                AutoExpressions.of("python", "price = qty * 2", "total = price - order.discount"));
    }

    @Test
    void aCallsNameIsLeftOutButItsReceiverAndArgumentsStay() {
        assertEquals(List.of("items", "x"), AutoExpressions.of("java", null, "items.add(x);"));
        assertEquals(List.of("y"), AutoExpressions.of("python", null, "print(len(y))"));
        assertEquals(List.of("v"), AutoExpressions.of("rust", null, "println!(\"{}\", v);"));
    }

    @Test
    void keywordsTypesStringsAndCommentsAreNotValues() {
        assertEquals(
                List.of("who", "n"),
                AutoExpressions.of("java", null, "String who = \"x\" + n; // not a comment value"));
        assertEquals(List.of("count"), AutoExpressions.of("python", null, "if count is None:  # count again"));
        assertEquals(List.of("self.count", "step"), AutoExpressions.of("python", null, "return self.count + step"));
        assertEquals(List.of("this.total"), AutoExpressions.of("java", null, "return this.total; /* x */"));
        assertEquals(List.of("i", "total"), AutoExpressions.of("lua", "for i = 1, 3 do", "  total = total + i -- n"));
    }

    @Test
    void pythonKeywordArgumentsAndAnnotationsAreNames() {
        assertEquals(List.of("a", "b"), AutoExpressions.of("python", null, "f(key=a, other=b)"));
        assertEquals(List.of("size"), AutoExpressions.of("python", null, "n: int = size"));
    }

    @Test
    void atMostTen() {
        assertEquals(
                AutoExpressions.MAX,
                AutoExpressions.of("python", null, "a+b+c+d+e+f+g+h+i+j+k+l").size());
    }

    @Test
    void theLineBeforeIsTheNearestWithCode() {
        assertArrayEquals(new String[] {"x = 1", "y = x"}, AutoExpressions.linesAround("x = 1\n\n\ny = x\n", 3));
        assertArrayEquals(new String[] {null, "first"}, AutoExpressions.linesAround("first\nsecond", 0));
        assertArrayEquals(new String[] {null, null}, AutoExpressions.linesAround("one", 5));
    }
}
