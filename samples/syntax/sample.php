<?php

declare(strict_types=1);

/**
 * A small PHP sample for syntax highlighting and folding.
 *
 * Covers a docblock, an interface, an enum, constructor promotion, a match
 * expression, an arrow function, heredoc, and string interpolation.
 */

namespace Demo;

const MAX_ITEMS = 0x40; // hex literal

interface Item
{
    public function describe(): string;
}

enum Kind: string
{
    case Book = 'book';
    case Tool = 'tool';
}

final class Book implements Item
{
    public function __construct(
        private readonly string $title,
        private readonly int $pages,
    ) {}

    public function describe(): string
    {
        return match (true) {
            $this->pages > 500 => "a long book: \"{$this->title}\"",
            default => "a book: $this->title",
        };
    }
}

final class Shelf
{
    /** @var list<Item> */
    private array $items = [];

    public function add(Item $item): void
    {
        if (count($this->items) >= MAX_ITEMS) {
            throw new \OverflowException('The shelf is full.');
        }
        $this->items[] = $item;
    }

    /** @return list<string> */
    public function descriptions(): array
    {
        return array_map(fn(Item $item): string => $item->describe(), $this->items);
    }
}

# A hash comment, then a heredoc.
$banner = <<<TEXT
    Inventory
    ---------
    TEXT;

$shelf = new Shelf();
$shelf->add(new Book('Dune', 612));
$shelf->add(new Book('Emma', 474));

echo $banner, PHP_EOL;
foreach ($shelf->descriptions() as $index => $line) {
    printf("%d. %s (%s)\n", $index + 1, $line, Kind::Book->value);
}
