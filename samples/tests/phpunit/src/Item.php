<?php
// What is on the shelf: the class tests/ShelfTest.php checks, with Pricing.php.
declare(strict_types=1);

namespace Shelf;

final class Item
{
    public function __construct(
        public readonly string $sku,
        public readonly string $name,
        public readonly int $quantity,
        public readonly float $price,
    ) {
    }

    /** What a line of the receipt costs: quantity times price. */
    public function subtotal(): float
    {
        return $this->quantity * $this->price;
    }
}
