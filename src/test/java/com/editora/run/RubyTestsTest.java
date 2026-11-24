package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RubyTestsTest {

    private static final String TEST = """
            class CalcTest < ActiveSupport::TestCase
              def test_adds
                assert_equal 4, 2 + 2
              end

              test "greets  by Name" do
                assert true
              end
            end
            """;

    @Test
    void theTestWhoseSpanHoldsTheCaretByMinitestsName() {
        assertNull(RubyTests.at(TEST, 0), "the class's line: the whole file");
        assertEquals("test_adds", RubyTests.at(TEST, 1));
        assertEquals("test_adds", RubyTests.at(TEST, 4));
        assertEquals("test_greets_by_Name", RubyTests.at(TEST, 5), "ActiveSupport's name for it");
        assertEquals("test_greets_by_Name", RubyTests.at(TEST, 7));
    }
}
