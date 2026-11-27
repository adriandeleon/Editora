// doctest tests of shelf.hpp. Each TEST_CASE has a ▶ in the gutter (the first line runs them all), and the
// results fill Test Results: one test passes, one fails on purpose and one is skipped on purpose, so every
// status has an example; the TEST_CASE_TEMPLATE runs once per type.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN // doctest writes main()
#include <doctest/doctest.h>

#include "shelf.hpp"

// Passes, with a SUBCASE per case: the test case runs once per subcase, from the top.
TEST_CASE("subtotal is quantity times price") {
    SUBCASE("pencils") {
        CHECK(subtotal({"A-1", "Pencil", 12, 0.40}) == doctest::Approx(4.80));
    }
    SUBCASE("a typed-in word isn't a quantity") {
        CHECK(parse_quantity("25") == 25);
        CHECK_THROWS_AS(parse_quantity("lots"), std::invalid_argument);
    }
}

// Fails on purpose: the red row in Test Results, with the expression and its values.
TEST_CASE("fails on purpose") {
    Item stapler{"B-1", "Stapler", 1, 8.90};
    CHECK(subtotal(stapler) == doctest::Approx(9.90));
}

// Skipped on purpose: the skip decorator leaves it out of the run.
TEST_CASE("skipped on purpose" * doctest::skip()) {
    CHECK(false);
}

// Templated: one row per price type, cents as int and dollars as double.
TEST_CASE_TEMPLATE("a discount stays in range", T, int, double) {
    T price = T(275);
    T off = discounted(price, 15);
    CHECK(off >= T(0));
    CHECK(off <= price);
}
