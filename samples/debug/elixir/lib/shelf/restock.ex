defmodule Shelf.Restock do
  @moduledoc "The restock: three Task processes call one GenServer, which counts what they restock."
  # Each process is a row of the Threads view, among every other process of the VM.
  use GenServer

  @doc "Starts the counter, runs `workers` tasks that each restock 160 units, and returns the count."
  def run(workers) do
    {:ok, counter} = GenServer.start_link(__MODULE__, 0)

    1..workers
    |> Enum.map(fn w -> Task.async(fn -> restock(counter, "restock-#{w}", 160) end) end)
    |> Task.await_many()

    count = GenServer.call(counter, :count)
    GenServer.stop(counter)
    count
  end

  defp restock(counter, name, units) do
    # break here: stops in one of the three task processes; `name` says which
    for _ <- 1..units, do: GenServer.call(counter, {:add, name})
    :ok
  end

  @impl true
  def init(count), do: {:ok, count}

  @impl true
  def handle_call({:add, _name}, _from, count) do
    # break here: runs 480 times, in the counter's own process (where a hit-count breakpoint would go)
    {:reply, :ok, count + 1}
  end

  def handle_call(:count, _from, count), do: {:reply, count, count}
end
