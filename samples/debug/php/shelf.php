<?php
// The shelf, in PHP: open this file and press F5 (Debug). PHP runs it with Xdebug, which vscode-php-debug drives,
// and stops at your breakpoints. Every suggested spot is a "break here" comment.
// `php shelf.php` runs it outside Editora; the `crash` argument throws.
declare(strict_types=1);

require_once __DIR__ . '/Pricing.php';

/** What is on the shelf; expand one in Variables to see its properties. */
final class Item
{
    public function __construct(
        public readonly string $sku,
        public readonly string $name,
        public readonly int $quantity,
        public readonly float $price,
    ) {
    }
}

$items = [
    new Item('A-1', 'Pencil', 12, 0.40),
    new Item('A-2', 'Notebook', 3, 2.75),
    new Item('B-1', 'Stapler', 1, 8.90),
    new Item('B-2', 'Tape', 4, 1.20),
    new Item('C-1', 'Marker', 10, 1.05),
    new Item('C-2', 'Eraser', 25, 0.30),
    new Item('C-3', 'Ruler', 2, 1.50),
    new Item('D-1', 'Glue', 6, 2.10),
];

/** Totals up to $limit items: $total, $lines and $byPrefix change on every pass. */
function checkout(array $shelf, int $limit): array
{
    $lines = [];
    $byPrefix = [];
    $total = 0.0;
    foreach (array_slice($shelf, 0, $limit) as $item) {
        $discount = Pricing::discountFor($item); // break here: Step Into, then look at $discount per item
        $price = Pricing::apply($discount, $item->price);
        $subtotal = $price * $item->quantity;
        $lines[] = sprintf('%-4s %-9s %3d x %5.2f = %7.2f', $item->sku, $item->name, $item->quantity, $price, $subtotal);
        $prefix = $item->sku[0];
        $byPrefix[$prefix] = ($byPrefix[$prefix] ?? 0) + $item->quantity;
        $total += $subtotal; // break here: a watch on $total, or a logpoint printing {$item->name}
    }
    $cheap = [];
    foreach ($shelf as $item) {
        if ($item->price < 1.0) { // break here: a conditional breakpoint, $item->sku == "C-2"
            $cheap[] = $item->name;
        }
    }
    sort($cheap);
    $lines[] = 'cheap: ' . implode(', ', $cheap) . '  by prefix: ' . json_encode($byPrefix);
    return [$lines, $total];
}

/** Turns "12" into 12; a word throws an InvalidArgumentException, which the caller catches. */
function parseQuantity(string $text): int
{
    if (!ctype_digit($text)) {
        throw new InvalidArgumentException("not a quantity: $text"); // break here: Exceptions stops here first
    }
    return (int) $text;
}

$limit = 6; // break here: a first plain breakpoint; Step Over, then Step Into checkout
$arg = $argv[1] ?? null;
if ($arg === 'crash') {
    throw new RuntimeException('boom'); // an exception nobody catches
} elseif ($arg !== null) {
    try {
        $limit = parseQuantity($arg);
    } catch (InvalidArgumentException $e) {
        echo $e->getMessage(), "\n";
    }
}
[$lines, $total] = checkout($items, $limit);
foreach ($lines as $line) {
    echo $line, "\n";
}
printf("total %.2f\n", $total);
