"""A small Python sample for syntax highlighting and folding.

Covers docstrings, decorators, type hints, f-strings, a comprehension, a generator,
exception handling, and a ``match`` statement.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterator

MAX_ITEMS = 0x40  # hex literal
WORD = re.compile(r"[A-Za-z_]\w*")  # raw string


@dataclass(frozen=True)
class Item:
    """One thing on the shelf."""

    name: str
    weight_kg: float = 0.0
    tags: tuple[str, ...] = field(default_factory=tuple)

    @property
    def label(self) -> str:
        return f"{self.name!r} ({self.weight_kg:.1f} kg)"


class ShelfFull(Exception):
    pass


class Shelf:
    def __init__(self) -> None:
        self._items: list[Item] = []

    def add(self, item: Item) -> None:
        if len(self._items) >= MAX_ITEMS:
            raise ShelfFull(f"no room for {item.name}")
        self._items.append(item)

    def tagged(self, tag: str) -> Iterator[Item]:
        # A generator: yields lazily instead of building a list.
        for item in self._items:
            if tag in item.tags:
                yield item

    def total_weight(self) -> float:
        return sum(item.weight_kg for item in self._items)


def describe(value: object) -> str:
    match value:
        case Item(name=name, weight_kg=w) if w > 10:
            return f"heavy: {name}"
        case Item(name=name):
            return f"item: {name}"
        case [first, *rest]:
            return f"a list starting with {first} (+{len(rest)} more)"
        case _:
            return "something else"


if __name__ == "__main__":
    shelf = Shelf()
    try:
        shelf.add(Item("hammer", 0.6, ("tool",)))
        shelf.add(Item("anvil", 45.0, ("tool", "heavy")))
    except ShelfFull as err:
        print(f"error: {err}")
    print([describe(i) for i in shelf.tagged("tool")], shelf.total_weight())
