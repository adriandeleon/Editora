"""pytest tests for cart.py: one passes, one FAILS ON PURPOSE, one is SKIPPED ON PURPOSE, and a parametrized
one runs three cases, so every Test Results status has an example (a red result here is the sample working).
"""

import pytest

from cart import Cart, line_total


def test_total_of_two_lines(cart: Cart) -> None:
    """Passes: `cart` is the fixture from conftest.py."""
    assert cart.total() == 7.00


def test_adding_again_fails_on_purpose(cart: Cart) -> None:
    """FAILS ON PURPOSE: adding A-1 again makes 11.50, not 10.00."""
    cart.add("A-1", 3, 1.50)
    assert cart.total() == 10.00


@pytest.mark.skip(reason="skipped on purpose: shows the skipped status")
def test_skipped_on_purpose() -> None:
    assert line_total(1, 1.00) == 1.00


class TestLineTotal:
    """A class of tests: its ▶ runs both, and Test Results groups them under it."""

    @pytest.mark.parametrize(
        ("quantity", "price", "expected"),
        [(1, 2.50, 2.50), (4, 1.25, 5.00), (10, 1.00, 9.00)],
        ids=["one", "four", "ten-gets-discount"],
    )
    def test_line_total(self, quantity: int, price: float, expected: float) -> None:
        """Parametrized: one result row per case."""
        assert line_total(quantity, price) == expected

    def test_zero_quantity_is_rejected(self) -> None:
        with pytest.raises(ValueError, match="positive"):
            Cart().add("A-1", 0, 1.00)
