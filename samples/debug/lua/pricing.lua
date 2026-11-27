-- Discounts, required by shelf.lua: apply() recurses through a stacked one, so the call stack shows nested frames.
local pricing = {}

-- An item's discount: a stacked one for a big quantity (a percentage, then another on what is left).
function pricing.discount_for(item)
  if item.quantity >= 10 then
    return { percent = 10, next = { percent = 5 } } -- break here: hit once per big item
  elseif item.price > 5 then
    return { percent = 15 }
  end
  return nil
end

-- Recurses through a stacked discount: the call stack shows two apply frames.
function pricing.apply(discount, price)
  if discount == nil then
    return price
  end
  price = price * (100 - discount.percent) / 100 -- break here: Step Out, or look at the frames below
  return (pricing.apply(discount.next, price)) -- the parentheses keep this a real call, not a tail call
end

return pricing
