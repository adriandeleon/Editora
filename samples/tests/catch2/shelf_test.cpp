// Catch2 3 tests of shelf.hpp. Each TEST_CASE has a ▶ in the gutter (the first line runs them all), and
// the results fill Test Results: one test passes, one fails on purpose and one is skipped on purpose, so
// every status has an example; the GENERATE one runs its body once per value.
#include <catch2/catch_test_macros.hpp>
#include <catch2/generators/catch_generators.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>

#include "shelf.hpp"

using Catch::Matchers::WithinRel;

// Passes, with a SECTION per case: the test case runs once per section, from the top.
TEST_CASE("subtotal is quantity times price", "[shelf]") {
    SECTION("pencils") {
        CHECK_THAT(subtotal({"A-1", "Pencil", 12, 0.40}), WithinRel(4.80));
    }
    SECTION("a typed-in word isn't a quantity") {
        CHECK(parse_quantity("25") == 25);
        CHECK_THROWS_AS(parse_quantity("lots"), std::invalid_argument);
    }
}

// Fails on purpose: the red row in Test Results, with the expression and its values.
TEST_CASE("fails on purpose", "[shelf]") {
    Item stapler{"B-1", "Stapler", 1, 8.90};
    CHECK(subtotal(stapler) == 9.90);
}

// Skipped on purpose (SKIP needs Catch2 3.3 or later).
TEST_CASE("skipped on purpose", "[shelf]") {
    SKIP("skipped on purpose");
}

// Table-driven: GENERATE runs the body once per percentage.
TEST_CASE("a discount stays in range", "[pricing]") {
    int percent = GENERATE(0, 15, 100);
    double price = discounted(2.75, percent);
    CHECK(price >= 0.0);
    CHECK(price <= 2.75);
}
