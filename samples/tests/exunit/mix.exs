# An ExUnit test-runner sample: Run File on test/calc_test.exs fills Test Results with one test of every status.
# Its own Mix project, so the failure on purpose never reaches the green tests of samples/debug/elixir.
defmodule Calc.MixProject do
  use Mix.Project

  def project do
    [app: :calc, version: "0.1.0", elixir: "~> 1.15", deps: []]
  end
end
