# The shelf, in Ruby: open this file and press F5 (Debug). The script runs under rdbg (the debug gem, which
# Ruby 3.1 and later ship), which stops at your breakpoints. Every suggested spot is a "break here" comment.
# `ruby shelf.rb` runs it outside Editora; the `crash` argument raises.
require_relative "pricing"
require_relative "restock"

# What is on the shelf; expand one in Variables to see its members.
Item = Struct.new(:sku, :name, :quantity, :price)

ITEMS = [
  Item.new("A-1", "Pencil", 12, 0.40),
  Item.new("A-2", "Notebook", 3, 2.75),
  Item.new("B-1", "Stapler", 1, 8.90),
  Item.new("B-2", "Tape", 4, 1.20),
  Item.new("C-1", "Marker", 10, 1.05),
  Item.new("C-2", "Eraser", 25, 0.30),
  Item.new("C-3", "Ruler", 2, 1.50),
  Item.new("D-1", "Glue", 6, 2.10)
].freeze

# Totals up to limit items: total, lines and by_prefix change on every pass.
def checkout(shelf, limit)
  lines = []
  by_prefix = Hash.new(0)
  total = 0.0
  shelf.first(limit).each do |item|
    discount = Pricing.discount_for(item) # break here: Step Into, then look at `discount` per item
    price = Pricing.apply(discount, item.price)
    subtotal = price * item.quantity
    lines << format("%-4s %-9s %3d x %5.2f = %7.2f", item.sku, item.name, item.quantity, price, subtotal)
    by_prefix[item.sku[0]] += item.quantity
    total += subtotal # break here: a watch on `total`, or a logpoint printing {item.name}
  end
  cheap = shelf.select { |item| item.price < 1.0 }.map(&:name).sort # break here: a conditional breakpoint, item.sku == "C-2"
  lines << "cheap: #{cheap.join(', ')}  by prefix: #{by_prefix}"
  [lines, total]
end

# Turns "12" into 12; a word raises ArgumentError, which the caller rescues.
def parse_quantity(text)
  Integer(text)
rescue ArgumentError => e
  raise ArgumentError, "not a quantity: #{text} (#{e.message})" # break here: `e` in Variables
end

limit = 6 # break here: a first plain breakpoint; Step Over, then Step Into checkout
if ARGV.first == "crash"
  missing = {}
  missing.fetch("boom") # a KeyError nobody rescues
elsif ARGV.first
  begin
    limit = parse_quantity(ARGV.first)
  rescue ArgumentError => e
    puts e.message
  end
end
lines, total = checkout(ITEMS, limit)
lines.each { |line| puts line }
puts format("total %.2f", total)
puts "restocked #{Restock.run(3)}"
