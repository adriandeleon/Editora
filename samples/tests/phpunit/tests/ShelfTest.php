<?php
// PHPUnit tests of src/. Run File runs them all; Run Test at Caret runs the test the caret is in (--filter).
// The results fill Test Results: one test passes, one fails on purpose and one is skipped on purpose, so
// every status has an example; the data-provider test runs once per row.
declare(strict_types=1);

namespace Shelf\Tests;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\Attributes\Test;
use PHPUnit\Framework\TestCase;
use Shelf\Item;
use Shelf\Pricing;

require_once __DIR__ . '/../src/Item.php'; // works without composer's autoloader too
require_once __DIR__ . '/../src/Pricing.php';

final class ShelfTest extends TestCase
{
    // Passes.
    public function testSubtotalIsQuantityTimesPrice(): void
    {
        $pencils = new Item('A-1', 'Pencil', 12, 0.40);
        $this->assertEqualsWithDelta(4.80, $pencils->subtotal(), 0.001);
    }

    // Passes too, marked #[Test] instead of named test…: a typed-in word isn't a quantity.
    #[Test]
    public function parseQuantityRejectsAWord(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        Pricing::parseQuantity('lots');
    }

    // Fails on purpose: the red row in Test Results, with expected and actual.
    public function testFailsOnPurpose(): void
    {
        $stapler = new Item('B-1', 'Stapler', 1, 8.90);
        $this->assertSame(9.90, $stapler->subtotal(), "this failure is the sample's red test");
    }

    // Skipped on purpose.
    public function testSkippedOnPurpose(): void
    {
        $this->markTestSkipped('skipped on purpose');
    }

    /** The rows of testDiscountStaysInRange: a percentage each. */
    public static function percents(): array
    {
        return ['none' => [0], 'some' => [15], 'all' => [100]];
    }

    // A data provider: one row per percentage in Test Results.
    #[DataProvider('percents')]
    public function testDiscountStaysInRange(int $percent): void
    {
        $price = Pricing::discounted(2.75, $percent);
        $this->assertGreaterThanOrEqual(0.0, $price);
        $this->assertLessThanOrEqual(2.75, $price);
    }
}
