"""Discounts: a percentage off, possibly stacked on another discount."""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from shelf import Item


@dataclass(frozen=True)
class Discount:
    percent: float
    then: Discount | None = None


def discount_for(item: Item) -> Discount | None:
    """An item's discount: a stacked one for a big quantity."""
    if item.quantity >= 10:
        return Discount(10, Discount(5))  # break here: hit once per big item; Step Out shows (return) in Variables
    if item.price > 5:
        return Discount(15)
    return None


def apply(discount: Discount | None, price: float) -> float:
    """Recurses through a stacked discount: the call stack shows two apply frames."""
    if discount is None:
        return price
    price = price * (100 - discount.percent) / 100  # break here: Step Out, or look at the frames below
    return apply(discount.then, price)
