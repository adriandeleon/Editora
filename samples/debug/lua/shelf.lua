-- The shelf, in Lua 5.4: open this file and press F5 (Debug). The script runs under Local Lua Debugger, which
-- stops at your breakpoints. Every suggested spot is a "break here" comment. `lua shelf.lua` runs it outside
-- Editora; the `crash` argument raises an error nobody catches, which stops the debugger where it was raised.
local pricing = require("pricing")
local restock = require("restock")

-- What is on the shelf; expand one in Variables to see its fields.
local function item(sku, name, quantity, price)
  return { sku = sku, name = name, quantity = quantity, price = price }
end

local items = {
  item("A-1", "Pencil", 12, 0.40),
  item("A-2", "Notebook", 3, 2.75),
  item("B-1", "Stapler", 1, 8.90),
  item("B-2", "Tape", 4, 1.20),
  item("C-1", "Marker", 10, 1.05),
  item("C-2", "Eraser", 25, 0.30),
  item("C-3", "Ruler", 2, 1.50),
  item("D-1", "Glue", 6, 2.10),
}

-- Totals up to limit items: total, lines and by_prefix change on every pass.
local function checkout(shelf, limit)
  local lines, by_prefix, total = {}, {}, 0.0
  for i = 1, math.min(limit, #shelf) do
    local it = shelf[i]
    local discount = pricing.discount_for(it) -- break here: Step Into, then look at `discount` per item
    local price = pricing.apply(discount, it.price)
    local subtotal = price * it.quantity
    lines[#lines + 1] = string.format("%-4s %-9s %3d x %5.2f = %7.2f", it.sku, it.name, it.quantity, price, subtotal)
    local prefix = it.sku:sub(1, 1)
    by_prefix[prefix] = (by_prefix[prefix] or 0) + it.quantity
    total = total + subtotal -- break here: a watch on `total`, or Set Value on it and Step Over
  end
  local cheap = {}
  for _, it in ipairs(shelf) do
    if it.price < 1.0 then -- break here: a conditional breakpoint, it.sku == "C-2"
      cheap[#cheap + 1] = it.name
    end
  end
  table.sort(cheap)
  lines[#lines + 1] = string.format("cheap: %s  by prefix: A=%d B=%d C=%d", table.concat(cheap, ", "),
    by_prefix.A or 0, by_prefix.B or 0, by_prefix.C or 0)
  return lines, total
end

-- Turns "12" into 12; a word raises an error, which the caller catches with pcall.
local function parse_quantity(text)
  local n = math.tointeger(tonumber(text))
  if n == nil then
    error("not a quantity: " .. text, 2) -- break here: the debugger stops on a word by itself, though the caller's pcall catches it
  end
  return n
end

local limit = 6 -- break here: a first plain breakpoint; Step Over, then Step Into checkout
if arg[1] == "crash" then
  local missing = nil
  print(missing.boom) -- indexing nil: an error nobody catches, so the debugger stops here
elseif arg[1] then
  local ok, result = pcall(parse_quantity, arg[1])
  if ok then limit = result else print(result) end
end
local lines, total = checkout(items, limit)
for _, line in ipairs(lines) do print(line) end
print(string.format("total %.2f", total))
print("restocked " .. restock.run(3))
