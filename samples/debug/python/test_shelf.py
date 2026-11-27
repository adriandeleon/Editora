"""Green unittest tests for the shelf, which pytest collects too: put the caret in one and use Debug Test at
Caret to debug that test alone (under pytest when it is installed, else under python -m unittest).
"""

import unittest

import pricing
import restock
from shelf import ITEMS, checkout, parse_quantity


class ShelfTest(unittest.TestCase):
    def test_checkout_totals(self) -> None:
        lines, total = checkout(ITEMS, 2)
        self.assertEqual(len(lines), 3)  # break here: `lines` and `total` in Variables
        self.assertAlmostEqual(total, 12.354)

    def test_stacked_discount(self) -> None:
        stacked = pricing.Discount(10, pricing.Discount(50))
        self.assertAlmostEqual(pricing.apply(stacked, 100), 45)

    def test_parse_quantity(self) -> None:
        self.assertEqual(parse_quantity("12"), 12)
        with self.assertRaises(ValueError):
            parse_quantity("many")

    def test_restock(self) -> None:
        self.assertEqual(restock.run(3), 480)

