"""The shelf, in Python: open this file and press F5 (Debug). The script runs under debugpy, which stops
at your breakpoints. Every suggested spot is a "break here" comment. `python3 shelf.py` runs it outside
Editora, `python3 -m unittest` (or pytest) runs the tests; the `crash` argument raises a KeyError nobody
catches, which the debugger stops on by itself.
"""

import sys
from collections import Counter
from dataclasses import dataclass

import pricing
import restock


@dataclass(frozen=True)
class Item:
    """What is on the shelf; expand one in Variables to see its fields."""

    sku: str
    name: str
    quantity: int
    price: float


ITEMS = (
    Item("A-1", "Pencil", 12, 0.40),
    Item("A-2", "Notebook", 3, 2.75),
    Item("B-1", "Stapler", 1, 8.90),
    Item("B-2", "Tape", 4, 1.20),
    Item("C-1", "Marker", 10, 1.05),
    Item("C-2", "Eraser", 25, 0.30),
    Item("C-3", "Ruler", 2, 1.50),
    Item("D-1", "Glue", 6, 2.10),
)


def checkout(shelf: tuple[Item, ...], limit: int) -> tuple[list[str], float]:
    """Totals up to limit items: total, lines and by_prefix change on every pass."""
    lines: list[str] = []
    by_prefix: Counter[str] = Counter()
    total = 0.0
    for item in shelf[:limit]:
        discount = pricing.discount_for(item)  # break here: Step Into, then look at `discount` per item
        price = pricing.apply(discount, item.price)
        subtotal = price * item.quantity
        lines.append(f"{item.sku:<4} {item.name:<9} {item.quantity:3} x {price:5.2f} = {subtotal:7.2f}")
        by_prefix[item.sku[0]] += item.quantity
        total += subtotal  # break here: a watch on `total`, or a logpoint printing {item.name}
    cheap = sorted(item.name for item in shelf if item.price < 1.0)  # break here: a conditional breakpoint, item.sku == "C-2"
    lines.append(f"cheap: {', '.join(cheap)}  by prefix: {dict(by_prefix)}")
    return lines, total


def parse_quantity(text: str) -> int:
    """Turns "12" into 12; a word raises ValueError, which the caller catches."""
    try:
        return int(text)
    except ValueError as err:
        raise ValueError(f"not a quantity: {text} ({err})") from err  # break here: `err` in Variables


def main(args: list[str]) -> None:
    limit = 6  # break here: a first plain breakpoint; Step Over, Set Value on `limit`, then Step Into checkout
    if args and args[0] == "crash":
        missing: dict[str, int] = {}
        missing["boom"] += 1  # a KeyError nobody catches: the debugger stops here by itself
    elif args:
        try:
            limit = parse_quantity(args[0])
        except ValueError as err:  # turn on Raised Exceptions to stop where it was raised instead
            print(err)
    lines, total = checkout(ITEMS, limit)
    for line in lines:
        print(line)
    print(f"total {total:.2f}")
    print(f"restocked {restock.run(3)}")


if __name__ == "__main__":
    main(sys.argv[1:])
