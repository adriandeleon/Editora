# frozen_string_literal: true

# A small Ruby sample for syntax highlighting and folding.
# Covers a module, classes, symbols, blocks, string interpolation, a heredoc,
# a regular expression, pattern matching, and rescue.
module Demo
  MAX_ITEMS = 0x40 # hex literal

  class ShelfFull < StandardError; end

  Book = Struct.new(:title, :pages) do
    def describe
      pages > 500 ? "a long book: \"#{title}\"" : "a book: #{title}"
    end
  end

  class Shelf
    include Enumerable

    attr_reader :items

    def initialize
      @items = []
    end

    def add(item)
      raise ShelfFull, "no room for #{item.title}" if @items.size >= MAX_ITEMS

      @items << item
      self
    end

    def each(&block)
      @items.each(&block)
    end

    def titles_matching(pattern = /\A[A-D]/)
      select { |item| item.title =~ pattern }.map(&:title)
    end
  end

  def self.kind_of(value)
    case value
    in { title: String => title, pages: Integer => pages } if pages > 500
      "long: #{title}"
    in { title: String => title }
      "short: #{title}"
    else
      'unknown'
    end
  end
end

=begin
A block comment, then a heredoc.
=end
banner = <<~TEXT
  Inventory
  ---------
TEXT

shelf = Demo::Shelf.new
begin
  shelf.add(Demo::Book.new('Dune', 612)).add(Demo::Book.new('Emma', 474))
rescue Demo::ShelfFull => e
  warn "error: #{e.message}"
end

puts banner
shelf.each_with_index { |book, i| puts "#{i + 1}. #{book.describe}" }
puts shelf.titles_matching.inspect, Demo.kind_of({ title: 'Dune', pages: 612 })
