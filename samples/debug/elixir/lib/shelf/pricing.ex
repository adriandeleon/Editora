defmodule Shelf.Pricing do
  @moduledoc "Discounts as tuples: apply_discount/2 matches on their shape and recurses through a stacked one."

  @doc "An item's discount: a stacked one for a big quantity, none for a cheap item."
  def discount_for(%{quantity: quantity}) when quantity >= 10,
    # break here: hit once per big item
    do: {:stacked, {:percent, 10}, {:percent, 5}}

  def discount_for(%{price: price}) when price > 5, do: {:percent, 15}
  def discount_for(_item), do: nil

  @doc "One clause per shape: the :stacked one calls apply_discount/2 for each part."
  def apply_discount(nil, price), do: price

  def apply_discount({:percent, percent}, price) do
    # break here: the first part of a stacked discount has the :stacked frame below it (the second is a tail call)
    price * (100 - percent) / 100
  end

  def apply_discount({:stacked, first, then}, price) do
    after_first = apply_discount(first, price)
    apply_discount(then, after_first)
  end
end
