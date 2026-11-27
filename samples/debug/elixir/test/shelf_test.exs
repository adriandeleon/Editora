# Green ExUnit tests: put the caret in one and use Debug Test at Caret, which runs that test alone.
defmodule ShelfTest do
  use ExUnit.Case, async: true

  alias Shelf.{Item, Pricing, Restock}

  describe "checkout" do
    test "totals the first two items" do
      {lines, total} = Shelf.checkout(Item.all(), 2)
      # break here: `lines` and `total` in Variables
      assert length(lines) == 3
      assert_in_delta total, 12.35, 0.01
    end

    test "groups quantities by prefix" do
      {lines, _total} = Shelf.checkout(Item.all(), 8)
      assert List.last(lines) =~ ~s(%{"A" => 15, "B" => 5, "C" => 37, "D" => 6})
    end
  end

  test "a stacked discount applies both" do
    assert Pricing.apply_discount({:stacked, {:percent, 10}, {:percent, 50}}, 100) == 45.0
  end

  test "a word is not a quantity" do
    assert Shelf.parse_quantity("12") == 12
    assert_raise ArgumentError, fn -> Shelf.parse_quantity("many") end
  end

  test "three tasks restock 480" do
    assert Restock.run(3) == 480
  end
end
