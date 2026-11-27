defmodule Calc do
  @moduledoc "What test/calc_test.exs tests (Go: Related File jumps between them)."

  def add(a, b), do: a + b

  def divide(_a, 0), do: {:error, :zero}
  def divide(a, b), do: {:ok, a / b}
end
