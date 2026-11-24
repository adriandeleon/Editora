package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Elixir's outline comes from the declaration scanner: the Elixir grammar scopes every name before {@code (} as a
 * function name, so its own symbols listed {@code IO.puts} and {@code Enum.map} beside the {@code def}s.
 */
class StructureOutlineElixirTest {

    @BeforeAll
    static void messages() {
        com.editora.i18n.Messages.init("en");
    }

    private static List<String> outline(String text) {
        List<String> out = new ArrayList<>();
        dump(
                StructureOutline.build(
                        StructureOutline.Source.file("counter.ex", text).analyzed()),
                "",
                out);
        return out;
    }

    private static void dump(List<StructureNode> nodes, String indent, List<String> out) {
        for (StructureNode n : nodes) {
            out.add(indent + n.kind() + " " + n.label() + " @" + (n.line() + 1));
            dump(n.children(), indent + "  ", out);
        }
    }

    @Test
    void modulesAndFunctionsNotCalls() {
        String src = """
                defmodule App.Counter do
                  use GenServer

                  def start_link(initial) do
                    GenServer.start_link(__MODULE__, initial, name: __MODULE__)
                  end

                  def value, do: GenServer.call(__MODULE__, :value)

                  defp log(message) do
                    IO.puts(message)
                    Enum.map([1, 2], fn x ->
                      x * 2
                    end)
                  end
                end
                """;
        assertEquals(
                List.of(
                        "namespace App.Counter @1",
                        "  function start_link @4",
                        "  function value @8",
                        "  function log @10"),
                outline(src));
    }
}
