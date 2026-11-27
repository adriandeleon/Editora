# RSpec examples of lib/shelf.rb. Run File (or the ▶ on the describe line) runs them all; Run Test at Caret
# runs the example or group on the caret's line (rspec spec/shelf_spec.rb:LINE). The results fill Test
# Results: one example passes, one fails on purpose and one is skipped on purpose (and one is pending), so
# every status has an example; the shared examples and the `each` table make several more.
require_relative "../lib/shelf"

RSpec.shared_examples "a price in range" do |percent|
  it "keeps #{percent}% off between 0 and the price" do
    expect(Shelf.discounted(2.75, percent)).to be_between(0, 2.75)
  end
end

RSpec.describe Shelf do
  let(:pencils) { Item.new("A-1", "Pencil", 12, 0.40) }

  # Passes.
  it "makes the subtotal quantity times price" do
    expect(Shelf.subtotal(pencils)).to be_within(0.001).of(4.80)
  end

  # Fails on purpose: the red row in Test Results, with expected and got.
  it "fails on purpose" do
    stapler = Item.new("B-1", "Stapler", 1, 8.90)
    expect(Shelf.subtotal(stapler)).to eq(9.90)
  end

  # Skipped on purpose.
  it "is skipped on purpose" do
    skip "skipped on purpose"
  end

  # Pending: runs, fails as expected, and is reported as pending (it would fail if it passed).
  it "is pending on purpose" do
    pending "thousands separators aren't read yet"
    expect(Shelf.parse_quantity("1,200")).to eq(1200)
  end

  describe ".parse_quantity" do
    # A table: one example per row.
    { "25" => 25, "0" => 0, "007" => 7 }.each do |text, want|
      it "reads #{text.inspect} as #{want}" do
        expect(Shelf.parse_quantity(text)).to eq(want)
      end
    end

    it "rejects a word" do
      expect { Shelf.parse_quantity("lots") }.to raise_error(ArgumentError)
    end
  end

  describe ".discounted" do
    # Shared examples: the same example for each percentage.
    [0, 15, 100].each { |percent| it_behaves_like "a price in range", percent }
  end
end
