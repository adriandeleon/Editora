-- A small Lua sample for syntax highlighting and folding.
-- Covers tables, metatables, closures, varargs, a numeric and a generic for,
-- string patterns, long strings, and pcall.

local MAX_ITEMS = 0x40 -- hex literal

--[[
  A block comment.
  Shelf is a class built from a metatable.
]]
local Shelf = {}
Shelf.__index = Shelf

function Shelf.new()
  return setmetatable({ items = {} }, Shelf)
end

function Shelf:add(item)
  if #self.items >= MAX_ITEMS then
    error("the shelf is full", 2)
  end
  self.items[#self.items + 1] = item
  return self
end

function Shelf:each()
  local i = 0
  return function()
    i = i + 1
    return self.items[i]
  end
end

local function describe(item)
  if item.pages and item.pages > 500 then
    return string.format('a long book: "%s"', item.title)
  elseif item.pages then
    return "a book: " .. item.title
  end
  return ("a tool (%.1f kg): %s"):format(item.weight_kg, item.title)
end

local function count(...)
  return select("#", ...)
end

local banner = [[
Inventory
---------]]

local shelf = Shelf.new()
local ok, err = pcall(function()
  shelf:add({ title = "Dune", pages = 612 }):add({ title = "Hammer", weight_kg = 0.6 })
end)
if not ok then
  print("error: " .. tostring(err))
end

print(banner)
for item in shelf:each() do
  print("- " .. describe(item))
end
for i = 1, count("a", "b", "c") do
  io.write(i, i < 3 and ", " or "\n")
end
print(("Dune, Emma"):gsub("%a+", string.upper))
