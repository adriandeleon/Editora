<?php
declare(strict_types=1);

/** A percentage off, possibly stacked on another discount. */
final class Discount
{
    public function __construct(public readonly float $percent, public readonly ?Discount $then = null)
    {
    }
}

final class Pricing
{
    /** An item's discount: a stacked one for a big quantity. */
    public static function discountFor(Item $item): ?Discount
    {
        if ($item->quantity >= 10) {
            return new Discount(10, new Discount(5)); // break here: hit once per big item
        }
        return $item->price > 5 ? new Discount(15) : null;
    }

    /** Recurses through a stacked discount: the call stack shows two apply frames. */
    public static function apply(?Discount $discount, float $price): float
    {
        if ($discount === null) {
            return $price;
        }
        $price = $price * (100 - $discount->percent) / 100; // break here: Step Out, or look at the frames below
        return self::apply($discount->then, $price);
    }
}
