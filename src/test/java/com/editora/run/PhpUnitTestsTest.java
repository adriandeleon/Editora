package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhpUnitTestsTest {

    private static final String TEST = """
            <?php
            use PHPUnit\\Framework\\TestCase;
            use PHPUnit\\Framework\\Attributes\\Test;

            final class CartTest extends TestCase
            {
                public function testAdds(): void
                {
                    $this->assertSame(4, 2 + 2);
                }

                /**
                 * @test
                 */
                public function removes(): void
                {
                    $this->assertTrue(true);
                }

                #[Test]
                #[DataProvider('pairs')]
                public function pairsMatch(int $a, int $b): void
                {
                    $this->assertSame($a, $b);
                }

                public static function pairs(): array
                {
                    return [[1, 1]];
                }
            }
            """;

    @Test
    void theTestWhoseSpanHoldsTheCaret() {
        assertEquals("testAdds", PhpUnitTests.at(TEST, 6));
        assertEquals("testAdds", PhpUnitTests.at(TEST, 8));
        assertEquals("testAdds", PhpUnitTests.at(TEST, 10), "the blank line after it");
        assertEquals("removes", PhpUnitTests.at(TEST, 12), "on its doc comment: @test");
        assertEquals("removes", PhpUnitTests.at(TEST, 16));
        assertEquals("pairsMatch", PhpUnitTests.at(TEST, 20), "on its #[Test] attribute");
        assertEquals("pairsMatch", PhpUnitTests.at(TEST, 24));
    }

    @Test
    void noneOnTheClassOrInAHelper() {
        assertNull(PhpUnitTests.at(TEST, 4), "the class's line: the whole file");
        assertNull(PhpUnitTests.at(TEST, 0));
        assertNull(PhpUnitTests.at(TEST, 29), "a data provider is not a test");
    }

    @Test
    void theFilterMatchesTheMethodAndItsDataSetsAlone() {
        String filter = PhpUnitTests.filter("testAdds");
        assertEquals("/::testAdds(?: .*)?$/", filter);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(filter.substring(1, filter.length() - 1));
        assertEquals(true, p.matcher("CartTest::testAdds").find());
        assertEquals(true, p.matcher("CartTest::testAdds with data set #0").find());
        assertEquals(false, p.matcher("CartTest::testAddsMore").find());
    }
}
