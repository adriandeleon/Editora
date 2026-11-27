# The shelf logic the tests check: an item's subtotal, a percentage off, a quantity read from text.

# What is on the shelf.
Item = Struct.new(:sku, :name, :quantity, :price)

module Shelf
  # What a line of the receipt costs: quantity times price.
  def self.subtotal(item)
    item.quantity * item.price
  end

  # price with percent taken off.
  def self.discounted(price, percent)
    price * (100 - percent) / 100.0
  end

  # A quantity typed by hand; raises ArgumentError for anything that isn't a whole number >= 0.
  def self.parse_quantity(text)
    n = Integer(text, 10) # raises ArgumentError for "lots"
    raise ArgumentError, "not a quantity: #{text}" if n.negative?

    n
  end
end
