defmodule Shelf.Item do
  @moduledoc "What is on the shelf: a struct, shown in Variables with its fields."

  @enforce_keys [:sku, :name, :quantity, :price]
  defstruct @enforce_keys

  def all do
    for {sku, name, quantity, price} <- [
          {"A-1", "Pencil", 12, 0.40},
          {"A-2", "Notebook", 3, 2.75},
          {"B-1", "Stapler", 1, 8.90},
          {"B-2", "Tape", 4, 1.20},
          {"C-1", "Marker", 10, 1.05},
          {"C-2", "Eraser", 25, 0.30},
          {"C-3", "Ruler", 2, 1.50},
          {"D-1", "Glue", 6, 2.10}
        ],
        do: %__MODULE__{sku: sku, name: name, quantity: quantity, price: price}
  end
end
