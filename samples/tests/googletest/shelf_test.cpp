// GoogleTest tests of shelf.hpp. Each TEST has a ▶ in the gutter (the first line runs them all), and the
// results fill Test Results: one test passes, one fails on purpose and one is skipped on purpose, so every
// status has an example; the TEST_P runs once per value it is instantiated with.
#include <gtest/gtest.h>

#include "shelf.hpp"

// Passes.
TEST(Shelf, SubtotalIsQuantityTimesPrice) {
    Item pencils{"A-1", "Pencil", 12, 0.40};
    EXPECT_DOUBLE_EQ(subtotal(pencils), 4.80);
}

// Passes: a typed-in quantity that isn't a number throws.
TEST(Shelf, ParseQuantityRejectsWords) {
    EXPECT_EQ(parse_quantity("25"), 25);
    EXPECT_THROW(parse_quantity("lots"), std::invalid_argument);
}

// Fails on purpose: the red row in Test Results, with both values and the line it failed on.
TEST(Shelf, FailsOnPurpose) {
    Item stapler{"B-1", "Stapler", 1, 8.90};
    EXPECT_DOUBLE_EQ(subtotal(stapler), 9.90) << "this failure is the sample's red test";
}

// Skipped on purpose.
TEST(Shelf, SkippedOnPurpose) {
    GTEST_SKIP() << "skipped on purpose";
}

// Parameterized: one row per percentage, named Percents/DiscountTest.StaysInRange/0, /1, /2.
class DiscountTest : public testing::TestWithParam<int> {};

TEST_P(DiscountTest, StaysInRange) {
    double price = discounted(2.75, GetParam());
    EXPECT_GE(price, 0.0);
    EXPECT_LE(price, 2.75);
}

INSTANTIATE_TEST_SUITE_P(Percents, DiscountTest, testing::Values(0, 15, 100));
