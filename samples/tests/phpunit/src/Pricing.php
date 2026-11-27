<?php
// A percentage off, and a quantity read from text: the functions tests/ShelfTest.php checks.
declare(strict_types=1);

namespace Shelf;

final class Pricing
{
    /** $price with $percent taken off. */
    public static function discounted(float $price, int $percent): float
    {
        return $price * (100 - $percent) / 100;
    }

    /** A quantity typed by hand; throws for anything that isn't a whole number >= 0. */
    public static function parseQuantity(string $text): int
    {
        if (!ctype_digit($text)) {
            throw new \InvalidArgumentException("not a quantity: $text");
        }
        return (int) $text;
    }
}
