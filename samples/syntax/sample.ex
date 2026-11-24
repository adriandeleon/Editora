#!/usr/bin/env elixir
# Elixir syntax sample: modules, protocols, structs, a GenServer, pattern matching,
# guards, pipes, sigils, heredocs, binaries and comprehensions.

defmodule Sample.Shape do
  @moduledoc """
  Shapes and their areas. A heredoc is folded on its own,
  and its `def area(…)` text is no declaration.
  """

  @enforce_keys [:kind]
  defstruct kind: :circle, size: 1.0, tags: []

  @type t :: %__MODULE__{kind: :circle | :square, size: float(), tags: [String.t()]}

  @doc "The area of a shape."
  @spec area(t()) :: float()
  def area(%__MODULE__{kind: :circle, size: r}), do: :math.pi() * r * r
  def area(%__MODULE__{kind: :square, size: s}), do: s * s

  defguard is_small(size) when is_number(size) and size < 1.0

  def describe(%__MODULE__{size: size} = shape) when is_small(size) do
    "a small #{shape.kind} (#{Float.round(area(shape), 2)})"
  end

  def describe(shape) do
    case shape do
      %{kind: :circle, tags: [first | _]} ->
        "a circle tagged #{first}"

      %{kind: kind} ->
        "a #{kind}"
    end
  end

  defp private_helper?(value), do: value in [:a, :b]

  defmacro twice(expr) do
    quote do
      unquote(expr)
      unquote(expr)
    end
  end
end

defprotocol Sample.Describable do
  @doc "A one-line description."
  def describe(value)
end

defimpl Sample.Describable, for: Sample.Shape do
  def describe(shape), do: Sample.Shape.describe(shape)
end

defmodule Sample.Counter do
  use GenServer

  # Client

  def start_link(initial \\ 0) do
    GenServer.start_link(__MODULE__, initial, name: __MODULE__)
  end

  def increment(by \\ 1), do: GenServer.cast(__MODULE__, {:increment, by})
  def value, do: GenServer.call(__MODULE__, :value)

  # Server

  @impl true
  def init(initial), do: {:ok, initial}

  @impl true
  def handle_call(:value, _from, count), do: {:reply, count, count}

  @impl true
  def handle_cast({:increment, by}, count) do
    {:noreply, count + by}
  end
end

defmodule Sample.Literals do
  @separator ?,
  @quote_char ?"

  def literals do
    atoms = [:ok, :error, :"quoted atom", true, false, nil]
    numbers = [42, 1_000_000, 0x1F, 0o17, 0b1010, 3.14, 1.0e-3]
    keyword = [do: 1, else: 2, end: 3]
    map = %{"key" => "value", atom: :yes, nested: %{list: [1, 2, 3]}}
    tuple = {:ok, "done", ?a}
    charlist = ~c"chars"
    words = ~w(alpha beta gamma)a
    regex = ~r/^\d{3}-(?<rest>\d+)$/i
    raw = ~S(no #{interpolation} and \n stays)
    date = ~D[2026-11-23]

    multi = """
    A heredoc keeps "quotes" and #{length(atoms)} interpolations.
    """

    binary = <<1, 2, 3::size(8), "abc"::binary>>
    <<head::binary-size(1), _rest::binary>> = "xyz"

    {atoms, numbers, keyword, map, tuple, charlist, words, regex, raw, date, multi, binary, head,
     @separator, @quote_char}
  end

  def control(list) do
    squares =
      for x <- list, rem(x, 2) == 0, into: [] do
        x * x
      end

    total =
      list
      |> Enum.filter(&(&1 > 0))
      |> Enum.map(fn x -> x * 2 end)
      |> Enum.reduce(0, &+/2)

    result =
      with {:ok, a} <- fetch(:a),
           {:ok, b} <- fetch(:b) do
        a + b
      else
        {:error, reason} -> {:error, reason}
      end

    cond do
      total > 100 -> :big
      result == 3 -> :exact
      true -> squares
    end
  end

  def safely(fun) do
    fun.()
  rescue
    e in ArgumentError -> {:error, Exception.message(e)}
  catch
    :exit, _ -> :exited
  after
    IO.puts("done")
  end

  def wait do
    receive do
      {:msg, text} when is_binary(text) -> text
    after
      1_000 -> :timeout
    end
  end

  defp fetch(key) when key in [:a, :b], do: {:ok, if(key == :a, do: 1, else: 2)}
  defp fetch(_), do: {:error, :unknown}
end
