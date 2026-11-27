# The shelf's entry script: open it and press F5 (Debug), which runs `mix run shelf.exs` under ElixirLS with the
# project's modules interpreted, so breakpoints stop in lib/. Arguments set by File: Run with Arguments… reach
# System.argv(): a number is the item limit, a word is a rescued error, and `crash` raises a KeyError.
Shelf.main(System.argv())
