# Minitest tests of lib/shelf.rb (Minitest ships with Ruby). Run File (or the ▶ on the class line) runs
# them all as `ruby -Itest test/shelf_test.rb`; Run Test at Caret runs the test the caret is in (-n
# test_name). The results fill Test Results: one test passes, one fails on purpose and one is skipped on
# purpose, so every status has an example; the table test checks several rows in one test.
require "minitest/autorun"
require_relative "../lib/shelf"

class ShelfTest < Minitest::Test
  # Passes.
  def test_subtotal_is_quantity_times_price
    pencils = Item.new("A-1", "Pencil", 12, 0.40)
    assert_in_delta 4.80, Shelf.subtotal(pencils), 0.001
  end

  # Passes: a typed-in word isn't a quantity.
  def test_parse_quantity_rejects_a_word
    assert_raises(ArgumentError) { Shelf.parse_quantity("lots") }
  end

  # A table: every row is checked, and a failure names the row.
  def test_parse_quantity_table
    { "25" => 25, "0" => 0, "007" => 7 }.each do |text, want|
      assert_equal want, Shelf.parse_quantity(text), "parse_quantity(#{text.inspect})"
    end
  end

  # Fails on purpose: the red row in Test Results, with expected and actual.
  def test_fails_on_purpose
    stapler = Item.new("B-1", "Stapler", 1, 8.90)
    assert_equal 9.90, Shelf.subtotal(stapler), "this failure is the sample's red test"
  end

  # Skipped on purpose.
  def test_skipped_on_purpose
    skip "skipped on purpose"
  end
end
