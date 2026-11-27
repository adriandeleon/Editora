"""A tiny cart for test_cart.py to test (Go: Related File jumps between the two)."""

from dataclasses import dataclass, field


@dataclass
class Cart:
    lines: dict[str, tuple[int, float]] = field(default_factory=dict)

    def add(self, sku: str, quantity: int, price: float) -> None:
        if quantity <= 0:
            raise ValueError(f"quantity must be positive: {quantity}")
        held, _ = self.lines.get(sku, (0, price))
        self.lines[sku] = (held + quantity, price)

    def total(self) -> float:
        return round(sum(quantity * price for quantity, price in self.lines.values()), 2)


def line_total(quantity: int, price: float) -> float:
    """quantity x price, with 10% off from ten up."""
    total = quantity * price
    return round(total * 0.9 if quantity >= 10 else total, 2)
