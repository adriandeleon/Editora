# One test of every Test Results status: a red result here is the sample working, not a regression.
defmodule CalcTest do
  use ExUnit.Case, async: true

  # Passes.
  test "adds" do
    assert Calc.add(2, 3) == 5
  end

  # Fails on purpose: 2 + 2 is not 5.
  test "fails on purpose" do
    assert Calc.add(2, 2) == 5
  end

  # Skipped on purpose: @tag :skip leaves it out of the run, and Test Results shows it as skipped.
  @tag :skip
  test "skipped on purpose" do
    assert Calc.add(1, 1) == 3
  end

  # A table: one test per row, each a result of its own (ExUnit has no parameterized tests; a `for` makes them).
  describe "divide" do
    for {a, b, expected} <- [{6, 3, {:ok, 2.0}}, {1, 4, {:ok, 0.25}}, {5, 0, {:error, :zero}}] do
      test "#{a} / #{b}" do
        assert Calc.divide(unquote(a), unquote(b)) == unquote(Macro.escape(expected))
      end
    end
  end
end
