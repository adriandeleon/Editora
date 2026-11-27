defmodule Shelf do
  @moduledoc """
  The shelf, in Elixir: `main/1` is what shelf.exs runs. Every suggested spot is a "break here" comment on
  the line above it, where `mix format` keeps comments.
  """

  alias Shelf.{Item, Pricing, Restock}

  def main(args) do
    # break here: a first plain breakpoint; `args` in Variables, then Step Into checkout
    limit = 6

    limit =
      case args do
        # a KeyError nobody rescues: the run ends with it, in the Debug console
        ["crash" | _] -> Map.fetch!(%{}, "boom")
        [text | _] -> parse_or(text, limit)
        [] -> limit
      end

    {lines, total} = checkout(Item.all(), limit)
    Enum.each(lines, &IO.puts/1)
    IO.puts("total " <> format(total, 0))
    IO.puts("restocked #{Restock.run(3)}")
  end

  @doc "Totals up to `limit` items: an Enum.reduce whose accumulator carries the lines, the totals and by_prefix."
  def checkout(shelf, limit) do
    {lines, by_prefix, total} =
      shelf
      |> Enum.take(limit)
      |> Enum.reduce({[], %{}, 0.0}, fn item, {lines, by_prefix, total} ->
        # break here: Step Into, then look at `discount` per item
        discount = Pricing.discount_for(item)
        price = Pricing.apply_discount(discount, item.price)
        subtotal = price * item.quantity

        line =
          "#{String.pad_trailing(item.sku, 4)} #{String.pad_trailing(item.name, 9)} " <>
            "#{String.pad_leading("#{item.quantity}", 3)} x #{format(price, 5)} = #{format(subtotal, 7)}"

        by_prefix =
          Map.update(by_prefix, String.first(item.sku), item.quantity, &(&1 + item.quantity))

        # break here: `total` and `by_prefix` grow on every pass
        {[line | lines], by_prefix, total + subtotal}
      end)

    # break here: where a condition, item.sku == "C-2", would go
    cheap = for item <- shelf, item.price < 1.0, do: item.name

    summary = "cheap: #{Enum.join(Enum.sort(cheap), ", ")}  by prefix: #{inspect(by_prefix)}"
    {Enum.reverse([summary | lines]), total}
  end

  @doc ~S'Turns "12" into 12; a word raises ArgumentError.'
  def parse_quantity(text) do
    case Integer.parse(text) do
      {n, ""} -> n
      # break here: `text` in Variables
      _ -> raise ArgumentError, "not a quantity: #{text}"
    end
  end

  defp parse_or(text, limit) do
    parse_quantity(text)
  rescue
    e in ArgumentError ->
      IO.puts(Exception.message(e))
      limit
  end

  defp format(number, width),
    do: :erlang.float_to_binary(number, decimals: 2) |> String.pad_leading(width)
end
