"""Fixtures shared by every test in this folder: a test names one as a parameter and pytest passes it in."""

import pytest

from cart import Cart


@pytest.fixture
def cart() -> Cart:
    """A fresh cart holding two lines, built again for each test that asks for it."""
    filled = Cart()
    filled.add("A-1", 2, 1.50)
    filled.add("B-1", 1, 4.00)
    return filled
