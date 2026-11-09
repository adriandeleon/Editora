# A percentage off, possibly stacked on another discount.
Discount = Struct.new(:percent, :then_discount)

module Pricing
  # An item's discount: a stacked one for a big quantity.
  def self.discount_for(item)
    if item.quantity >= 10
      Discount.new(10, Discount.new(5, nil)) # break here: hit once per big item
    elsif item.price > 5
      Discount.new(15, nil)
    end
  end

  # Recurses through a stacked discount: the call stack shows two apply frames.
  def self.apply(discount, price)
    return price if discount.nil?

    price = price * (100 - discount.percent) / 100.0 # break here: Step Out, or look at the frames below
    apply(discount.then_discount, price)
  end
end
