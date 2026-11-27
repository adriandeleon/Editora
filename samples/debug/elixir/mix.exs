# The shelf as a Mix project, for debugging Elixir on ElixirLS: open shelf.exs and press F5, or put the caret in a
# test of test/shelf_test.exs and use Debug Test at Caret. No dependencies; `mix run shelf.exs` runs it.
defmodule Shelf.MixProject do
  use Mix.Project

  def project do
    [app: :shelf, version: "0.1.0", elixir: "~> 1.15", deps: []]
  end

  # No `mod:` here, so `mix run` ends when the script does rather than keeping a supervision tree up.
  def application do
    [extra_applications: [:logger]]
  end
end
